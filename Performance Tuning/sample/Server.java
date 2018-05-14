/* Sample code for basic Server */
import java.rmi.*;
import java.rmi.server.*;
import java.rmi.registry.*;
import java.util.*;
import java.util.concurrent.*;


//swrver class implementing VMOps interface
public class Server extends UnicastRemoteObject implements VMOps {
	// VM property
	public static String ip = null;
	public static int port = -1;
	public static int VMid = -1;
	public static ServerLib SL;
	public static Cloud.DatabaseOps cache;
	public static int MTDropCount = 0; //mt drop counts on master FE server
	public static boolean isbooting = true;
	// VM scaling parameters
	public static double MT_weight = 1;
	public static double Masterloop_weight = 5;
	public static double FE_weight = 3;
	public static double MT_Scaleout_Th = 2;
	public static double MT_Scalein_Th = 3;  //
	public static int gettingRequestsTimeOut = 500; // TO value for retrieving tasks from center queue
	// data structures storing VM ids and requests (central queue) on master VM
	public static int VM_fe = 0; // frontend vms
	public static int VM_mt = 0; //middle TIER vms
	public static BlockingQueue<Cloud.FrontEndOps.Request> tasks = new LinkedBlockingQueue<Cloud.FrontEndOps.Request>(); //controlled by master
	public static ConcurrentHashMap<Integer, Integer> VMLevel = new ConcurrentHashMap<>();  //storing vm id and its task
	// macro for convenience
	public static final int VM_Master = 0;
	public static final int Front_End= 1;
	public static final int Middle_Tier = 2;
	public static final int MT_master = 3;
	public Server() throws RemoteException {
		super(0);
	}

	public static void main ( String args[] ) throws Exception {
		if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
		ip = args[0];
		port = Integer.parseInt(args[1]);
		VMid = Integer.parseInt(args[2]);
		System.out.println(ip+" "+port);
		VMOps master = null;
		int VM_level = -1;
		//regist rmi for this server instance, used for looking up when scaling in
		SL = new ServerLib( ip, port );
		Server server = null;
		Registry registry = LocateRegistry.getRegistry(port);
		try{
			server = new Server();
			registry.bind("VM_master", server);
		}catch (Exception e) {	//if already exists server instance, register as slave
			server = null;
			Server slave = new Server();
			String masterPath = "//"+ip+":"+ port + "/VM_master";
			master = (VMOps) Naming.lookup(masterPath);
			try { 
				registry.bind("VM_slave_" + VMid, slave);
			} catch (Exception another) { 
				another.printStackTrace();
			}
		}
		// initialize master FE server instance, set parameters
		if(server != null) { // this is a master server, we should manage slaves
			//register master front end
			SL.register_frontend();	
			VMLevel.put(VMid, VM_Master);
			VM_level = VM_Master;
			VM_fe ++;
			//register one middle tier vm as mt_master
			VMLevel.put(SL.startVM(), MT_master);
			VM_mt++;
			//register cache
			try{
				cache = new Cache(SL); //initialize cache on master server
				registry.bind("cache", cache);
			}catch(Exception e) {
				e.printStackTrace();
			}
			//register one more front end
			for(int i = 0;i < 1;i++) {
				VMLevel.put(SL.startVM(), Front_End); //register vm and level in the map
				VM_fe++;
			}			
		} else { //if not master server, get cache remote instance
			cache = getCache(ip,port);
		}

		VM_level = VM_level < 0 ? master.getLevel(VMid): VM_level; //get vm role from global map
		switch(VM_level) {
			case VM_Master: executeMaster();
			case Front_End: executeFE(master);
			case Middle_Tier: executeMT(master);
			case MT_master: executeMTMaster(master);
		}
	}
	//Middle Tier Master Server tasks (this vm will never be shut down/scale in)
	//@params: master is the MASTER server remote instance
	public static void executeMTMaster(VMOps master) throws Exception {
		System.out.println("middle tier master working");
		int MT_curNum = 0;
		int dropNum = 0;
		//execute MT master routine
		while (true) {
			Cloud.FrontEndOps.Request r = master.pollTasks();
			if(r == null) continue;

			//scale out if necessary
			MT_curNum = master.getLevelNums(Middle_Tier);
			if( master.getTaskNum()> MT_curNum * MT_weight ) { 
			//too many tasks queue up, drop it
				System.out.println("drop happens");
				SL.drop(r);
				master.ifMTDrop(true);
			} else {
				SL.processRequest( r, cache );
				master.ifMTDrop(false);
			}
		}
	}
	//Master server tasks (this VM will never be shut down/scale in)
	//tasks:  	- scale out middle tier when MT master is booting
	//			- scale out front end if necessary
	public static void executeMaster() throws Exception {
		System.out.println("master frontend");
		int dropNum = 0;
		//drop requests when mt_master vm is still boosting
		while(isbooting) {
			if(SL.getStatusVM(2) != Cloud.CloudOps.VMStatus.Running) {
				//decide if we need to scale out middle
				if(SL.getQueueLength()>0) {
					SL.dropHead();
					dropNum++;
					if(dropNum%Masterloop_weight == 0) {
						System.out.println("scale out a middle when booting");
						VMLevel.put(SL.startVM(), Middle_Tier);
						VM_mt++;
					}
				}
				continue;
			}
			isbooting = false;
		}
		// act like normal FE worker, scale out if necessary
		while (true) {
			Cloud.FrontEndOps.Request r = SL.getNextRequest();
			tasks.add(r);
			if(SL.getQueueLength()> FE_weight*VM_fe) {
				System.out.println("master scale out FE");
				VMLevel.put(SL.startVM(), Front_End);
				VM_fe ++;
			}
		}	
	} 
	// front end server tasks, will be scaled in called by master
	//@params: master is the MASTER server remote instance
	public static void executeFE( VMOps master) throws Exception {
		SL.register_frontend();		
		System.out.println("frontend working");
		while (true) {
			Cloud.FrontEndOps.Request r = SL.getNextRequest();
			master.addTasks(r);
		}
	} 
	// Middle tier server tasks
	//@params: master is the MASTER server remote instance
	// tasks:	- scalein if timeout for consequtive three times, 
	//			- scale out from master server if drop tasks for 2 times
	public static void executeMT( VMOps master) throws Exception {
		System.out.println("middle tier working");
		int timeout = 0;
		int MT_curNum = 0;
		while (true) {
			Cloud.FrontEndOps.Request r = master.pollTasks();
			//scale in decision, based on time-out values
			if( r == null) {
				timeout++;
				if(timeout == MT_Scalein_Th) {
					System.out.println("scale in middle tier");
					SL.interruptGetNext();
					master.scaleIn(VMid, Middle_Tier);
					SL.shutDown();
					SL.endVM(VMid);
				}
				continue;
			}
			//scale out decision. If drop out two times, then scale out
			timeout = 0;

			//scale out if necessary
			MT_curNum = master.getLevelNums(Middle_Tier);
			if( master.getTaskNum()> MT_curNum * MT_weight ) {
				System.out.println("drop happens");
				SL.drop(r);
				master.ifMTDrop(true);
			} else {
				SL.processRequest( r, cache );
				master.ifMTDrop(false);
			}
		}		
	} 
	//***********************************************************************************************
	//************************* function for the VMops interface ************************************
	//***********************************************************************************************
	
	// get server numbers for different role from master map
	// @params: level is the role number
	// return vm server numbers
	public int getLevelNums(int level) throws RemoteException {
		if(level == Middle_Tier) {
			return VM_mt;
		} else {
			return VM_fe;
		}
	}
	// get central queue length from master server
	// return number of tasks queued up
	public int getTaskNum() throws RemoteException {
		return tasks.size();
	}
	// scale out from server for different vm roles
	// @params: level is the vm role
	public void scaleOut(int level) throws RemoteException {
		VMLevel.put(SL.startVM(), level);
		if(level == Middle_Tier) {
			VM_mt++;
		}else {
			VM_fe++;
		}
	}
	// unregister remote instance when scaling in
	public void unexport() throws RemoteException {
		UnicastRemoteObject.unexportObject(this, true);
		System.out.println("unregist succeed");
	}

	// scale in specific vm, delete from map, list, SL service
	// @params: vmid is the vm that will be terminate
	// @params: level is the server role
	public void scaleIn(int VMid, int level) throws RemoteException {
		VMOps remove;
		//terminate VM rmi service
		try {
			remove = (VMOps) Naming.lookup("//"+ ip+":"+port + "/VM_slave_" + VMid);
			remove.unexport();
		} catch (Exception e) {
			e.printStackTrace();
		}
		//delete VM from global vm queue
		VMLevel.remove(VMid);
		if(level == Middle_Tier) {
			VM_mt -- ;
		} else {
			VM_fe --;
		}
	}
	// add tasks to central queue
	// @params: r is the request task from user
	public void addTasks(Cloud.FrontEndOps.Request r) throws RemoteException {
		tasks.add(r);
	}
	// retrieve tasks from central queue, if timeout return null
	// return client request or null if nothing in the queue
	public Cloud.FrontEndOps.Request pollTasks() throws RemoteException {
		try{
			return tasks.poll(gettingRequestsTimeOut, TimeUnit.MILLISECONDS);
		}catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
	//get vm level from map, the record may not be added to the map when querying the data structure
	// @params: vmid is the query server id
	// return role of this server
	public int getLevel(int VMid) throws RemoteException {
		int level = -1;
		while(level <0) {
			if(VMLevel.containsKey(VMid)) {
				level = VMLevel.get(VMid);
			}
		}
		return level;
	}
	// update master middle tier drop count
	// scale out if two consequtive drop happens
	// @params: dropTask is a boolean that define if the task is dropped by MT server
	public synchronized void ifMTDrop(boolean dropTask) throws RemoteException {
		if(dropTask) { //drop a task
			MTDropCount++;
			if(MTDropCount == MT_Scaleout_Th) {
				System.out.println("server decide a MT scale out");
				scaleOut(Middle_Tier);
				MTDropCount = 0;
			}
		} else {
			MTDropCount = 0;
		}
	}

	//***********************************************************************************************
	//************************* function for the general vm instance ********************************
	//***********************************************************************************************
	
	//for middle tier, get master cache instance
	// @params: ip is the ip address
	// @params: port is the port number
	// return cache instance
	public static Cloud.DatabaseOps getCache(String ip, int port) throws Exception {
		String cachePath = "//"+ip+":"+ port + "/cache";
		Cloud.DatabaseOps masterCache = (Cloud.DatabaseOps)Naming.lookup(cachePath);
		return masterCache;
	}

}

