import java.util.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;


// class handling one collage commit request
public class Collage implements Runnable{
	private int collage_status;
	private boolean ck3_decision;
	private Thread t;
	//configuration params
	private String path = "server.log";
	private String filename;
	private String[] sources;
	private byte[] img;	
	//data structure storing user/sent message
	private Map<String, Msg> needVote = new HashMap<>();
	private Map<String, ProjectLib.Message> CommitDecision = new ConcurrentHashMap<>();
	private List<String> users = new ArrayList<>();
	private ProjectLib PL; // server pl instance   
	public BlockingQueue<Msg> voteReply = new LinkedBlockingQueue<>();
	public BlockingQueue<Msg> notYetAck = new LinkedBlockingQueue<>();

	// constructor used for normal commit request
	// @params: pl is the interface of ProjectLin
	// @params: filename is the collage file name
	// @params: img is the byte sequence of collage img
	// @params: sources is individual files commiting to collage
	// @params: collage_status is the status of collage, which is 1 in this case
	public Collage(ProjectLib pl, String filename, byte[] img, String[] sources,int collage_status) {
		this.collage_status = collage_status;
		this.filename = filename;
		this.img = img;
		this.sources = sources;
		this.PL = pl;
		for(String ele : sources) {
			 String user = ele.split(":")[0];
			 String source = ele.split(":")[1];
			 if(needVote.containsKey(user)) { //already has entry, append new source to the list
			 	needVote.get(user).addSource(source);
			 }else { //first new user entry, update users, needvote
			 	Msg vote = new Msg(1,filename, user, source, img);
			 	needVote.put(user, vote);
			 	users.add(user);
			 }
		}
	} 
	
	// constructor used for recover tasks from ck1
	// @params: pl is the interface of ProjectLin
	// @params: filename is the collage file name
	// @params: sources is individual files commiting to collage
	// @params: collage_status is the status of collage, which is 1 in this case
	public Collage(ProjectLib pl, String filename, String[] sources, int collage_status) {
		this.collage_status = collage_status;
		this.filename = filename;
		this.PL = pl;
		for(int i = 1;i < sources.length;i++) {
			String ele = sources[i];
			String user = ele.split(":")[0];
			String source = ele.split(":")[1];
			if(!users.contains(user)) users.add(user);

		}
	}
	// constructor used for recover tasks from ck3
	// @params: pl is the interface of ProjectLin
	// @params: filename is the collage file name
	// @params: sources is individual files commiting to collage
	// @params: collage_status is the status of collage, which is 1 in this case
	// @params: decision is the indicator of ck3 constructor
	public Collage(ProjectLib pl, String filename, String[] sources, int collage_status, String decision) {
		this.collage_status = collage_status;
		this.filename = filename;
		this.PL = pl;
		this.ck3_decision = decision.equals("T")?true:false;
		for(int i = 2;i < sources.length;i++) {
			String user = sources[i];
			users.add(user);
		}
	}	
	// constructor used for recover tasks from ck3
	// @params: pl is the interface of ProjectLin
	// @params: filename is the collage file name
	// @params: sources is individual files commiting to collage
	// @params: collage_status is the status of collage, which is 1 in this case
	// @params: failbetween is the indicator of special failure between log/disk write
	public Collage(ProjectLib pl, String filename, String[] sources, int collage_status, boolean failbetween) {
		this.collage_status = 3;
		this.filename = filename;
		this.PL = pl;
		this.ck3_decision = true;
		
		for(int i = 1;i < sources.length;i++) {
			String ele = sources[i];
			String user = ele.split(":")[0];
			if(!users.contains(user)) users.add(user);
		}
	}	



	//send prepare votes msg to all the users
	public void Prepare() {
		for(String user: users) {
			byte[] content = ProcessMsg.serialize(needVote.get(user));
			ProjectLib.Message msgSend = new ProjectLib.Message(user,content);
			PL.sendMessage(msgSend);
			needVote.remove(user);
		}
	}
	// collecting requests from users, return decision to thread
	public boolean processVote() {
		long start = System.currentTimeMillis();
		long end = start;
		int replyCnt = 0;
		int voteTotal = users.size();
		boolean needCommit = true;
		System.out.println("process vote for "+ filename);
		while(end - start < 3000 && replyCnt < voteTotal) {
			// System.out.println("within loop");
			if(!voteReply.isEmpty()) {
				//if queue is not empty, get reply from queue
				Msg ele = voteReply.poll();
				if(ele.voteForCollage == true) {
					replyCnt ++;
					continue;
				} else { // if one user disagree, total answer should be false
					needCommit = false;
					break;
				}
			}
			//if is empty, update end time, continue loop
			end =  System.currentTimeMillis();
		}
		//check if there exists timeout
		if( needCommit && replyCnt < voteTotal) needCommit = false;
		System.out.println("commit finished, result "+ needCommit);
		return needCommit;
	}

	//deliver decision based on vote collection to users
	//@params: commit is true if need commit, false if need abort
	public void deliverDecision(boolean commit) {
		for(String user: users) {
			Msg decision = new Msg(3, filename, user, commit);
			byte[] content = ProcessMsg.serialize(decision);
			ProjectLib.Message msgSend = new ProjectLib.Message(user,content);
			PL.sendMessage(msgSend);	
			CommitDecision.put(user, msgSend);
		}
	}
	//receiving ack from users, recend decision each 3 seconds if no ack received
	public void handleAck() {
		long start = System.currentTimeMillis();
		long end = start;	
		int userCnt = users.size();
		System.out.println("handling ack in Server");
		while(CommitDecision.size() != 0){
			if(end - start >= 3000) {
				for(String user: CommitDecision.keySet()) {
					System.out.println("resend ack to "+ user+ " for task "+ filename);
					PL.sendMessage(CommitDecision.get(user));
				}
				start = end;
			}else {
				Msg tmp = notYetAck.poll();
				if(tmp != null && CommitDecision.containsKey(tmp.user)) {
					CommitDecision.remove(tmp.user);
					System.out.println(tmp.user+" 's ack has been received");
				}
				end = System.currentTimeMillis();
			}
		}

	}
	// check if last ack has beenr eceived or not. if yes, delete task from global list
	// @params: ack is a message unit used to transfer between client and server
	public boolean cleaningAck(Msg ack) {
		notYetAck.offer(ack);
		String lastUser = ack.user;
		if(CommitDecision.size() == 1 && CommitDecision.containsKey(lastUser)) {
			System.out.println("last ack received");
			return true;
		}
		return false;
	}


	//write file to local storage (server directory)
	public void writeToDir() {
		try{
			String dirPath = Paths.get(".").toAbsolutePath().normalize().toString()+"/";
			String path = dirPath + filename;  //get server dir path
			System.out.println("output file path is: " + path);
			FileOutputStream out = new FileOutputStream(path);
            out.write(img);
            out.close();
            PL.fsync();
		}catch(Exception e) {
			e.printStackTrace();
		}
	}


	@Override
	public void run() {
		//distribute commit message
		if(collage_status == 1) {	//indicating normal status
			System.out.println("task class running");
			Prepare();
			// System.out.println("sending votes to user finished");
			boolean commit = processVote();
			if(commit) { //write to disk, record in log
				writeToDir();
			}
			//write log for ck3
			String commitSignal = commit?"T":"F";
			String logContent = filename+",3_"+commitSignal+"_"+String.join("_",users);
			Log.writeLog(PL,path, logContent);
			//routing ck5
			deliverDecision(commit);
			handleAck();
		} else if(collage_status == 2) {	//recover from ck1, start from sending false commit
			System.out.println("recover from ck1,distribute False commit");
			//write log 
			String logContent = filename+",3_F_"+String.join("_",users);
			Log.writeLog(PL,path, logContent);
			//deliver commit decisions
			deliverDecision(false);
			handleAck();
		} else if(collage_status == 3) {	//recovery from ck3, sending real status
			System.out.println("recover from ck3,distribute real decision,expecting acks");
			//deliver commit decisions
			deliverDecision(ck3_decision);
			handleAck();
		}
	}

	//called when start a new task thread
	public void start() {
		if(t == null) {
			t = new Thread(this);
			t.start();
		}
	}


}