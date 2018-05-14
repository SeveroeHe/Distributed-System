/* Skeleton code for Server */
import java.util.*;
import java.io.*;

// main server class 
public class Server implements ProjectLib.CommitServing {
	private static ProjectLib PL;
	private static Map<String, Collage> jobs= new HashMap<>();
	private static String path = "server.log";

	// called when there comes a commit request. 
	// create new task and append it to global list
	//@params: filename is the target collage name
	//@params: img is the byte data of target
	//@params: sources is the collage sources
	public void startCommit( String filename, byte[] img, String[] sources ) {
		System.out.println( "Server: Got request to commit "+filename );
		Collage ele = new Collage(PL, filename, img, sources,1);
		jobs.put(filename, ele);
		//write log
		String logContent = filename+",1_"+String.join("_", sources);
		Log.writeLog(PL,path,logContent);
		//run process
		ele.start();
	}
	
	public static void main ( String args[] ) throws Exception {
		if (args.length != 1) throw new Exception("Need 1 arg: <port>");
		Server srv = new Server();

		PL = new ProjectLib( Integer.parseInt(args[0]), srv );
		
		//recovery from logs
		// if task has three rcd, a finished file,do nothing
		File serverlog = new File(path);
		if(serverlog.exists()) {
			System.out.println("recovery from server");
			Map<String,List<String>> logInfo = Log.readLog(path);
			for(String task: logInfo.keySet()) {
				List<String> rcds = logInfo.get(task);
				int rcdLen = rcds.size();
				if(rcdLen == 1) {	//need to deliver false commit decision to users
					File file = new File(task);
					if(file.exists()) {	//write disk -> failure -> write log
						System.out.println("fail between write disk and write log for task "+task);
						String[] info = rcds.get(0).split("_");
						Collage ele = new Collage(PL,task,info,9,true);
						jobs.put(task,ele);
						ele.start();
					} else {
						System.out.println("recovery from ck1 for task "+task);
						String[] info = rcds.get(0).split("_");
						Collage ele = new Collage(PL,task,info,2);
						jobs.put(task,ele);
						ele.start();
					}
				}else if(rcdLen == 2) {	//need to deliver commit decisions to users (real recorded decision)
					System.out.println("recovery from ck3 for task "+task);
					String[] info = rcds.get(1).split("_");
					Collage ele = new Collage(PL,task,info,3,info[1]);
					jobs.put(task,ele);
					// ele.run();
					ele.start();
				}
			}
		}

		// main loop
		while (true) {
			ProjectLib.Message msg = PL.getMessage();
			Msg userReply = ProcessMsg.deserialize(msg.body);
			String filename = userReply.filename;
			System.out.println( "Server: Got message from " + msg.addr +" op code: "+String.valueOf(userReply.op));
			//if task has alraedy be removed, continue
			if(!jobs.containsKey(filename)) {
				continue;
			}
			//if task is running, and op code is 2, append vote response to class queue
			if(userReply.op == 2) {
				jobs.get(filename).voteReply.add(userReply);
			}else if(userReply.op == 4) {
				//indicating an ack from usernode
				Collage task = jobs.get(filename);
				boolean taskStatus = task.cleaningAck(userReply);
				if(taskStatus) {
					//indicating ack is finished, this task is done. write to log
					String logContent = filename+",5_finish";
					Log.writeLog(PL,path,logContent);
					jobs.remove(filename);
				}
			}
		}
	}
}

