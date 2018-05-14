/* Skeleton code for UserNode */
import java.util.*;
import java.io.*;

//user node class
public class UserNode implements ProjectLib.MessageHandling {
	public final String myId;
	public static ProjectLib PL;
	private static String path;
	private static String userid;
	//data structure monitoring resources usage
	private static Map<String, List<String>> requests = new HashMap<>();
	private static List<String> lockedRss = new ArrayList<>();
	private static Set<String> deletedRss = new HashSet<>();
	// constructor for user node class
	// @params: id is the user node id
	public UserNode( String id ) {
		this.myId = id;
		this.path = "user_"+id+".log";
		this.userid = id;
	}

	// executing vote request from server
	// send back decision made by usernode to server
	// 2 indicating this message is client replying server's vote request
	// @params: msg is a message class send from server
	public void needVote(Msg msg) {
		String voteFile = msg.filename;
		boolean voteForCollage = true;
		boolean dltOrLocked = false;
		List<String> RawSources = msg.sources;
		String[] sources = new String[RawSources.size()];
		sources = RawSources.toArray(sources);
		//check if resources is locked or deleted
		for(String ele: sources) {
			if(lockedRss.contains(ele) || deletedRss.contains(ele)) {
				dltOrLocked = true;
				break;
			}
		}
		//check if this is a dumplicated request
		if(requests.containsKey(voteFile)) {
			voteForCollage = false;
		} //check if user like this collage
		else if(PL.askUser(msg.img, sources)==false) {
			voteForCollage = false;
		}// check if resources has been deleted or locked
		else if(dltOrLocked == true) {
			voteForCollage = false;
		}

		// respond to server
		//form serialized message
		Msg sendBack = new Msg(2, voteForCollage, voteFile, myId);
		byte[] content = ProcessMsg.serialize(sendBack);
		ProjectLib.Message msgSend = new ProjectLib.Message("Server",content);
		//adjust resources allocation
		if(voteForCollage == true) { 
			//if vote for true, append resources onto locked set
			requests.put(voteFile, RawSources);
			for(String ele: RawSources) {
				if(!lockedRss.contains(ele)) lockedRss.add(ele);
			}
		}
		//write to log
		String voteDecision = voteForCollage?"T":"F";
		String logContent = voteFile+",2_"+voteDecision+"_"+String.join("_",RawSources);
		Log.writeLog(PL,path,logContent);
		//send vote to server
		PL.sendMessage(msgSend);
	}

	//dealing with commit decision from server
	//return ack to server
	//@params: msg is the message class sent from server
	public void needAck(Msg msg) {
		String filename = msg.filename;
		Msg Ack = new Msg(4, filename, myId, msg.needCommit);
		byte[] content = ProcessMsg.serialize(Ack);
		ProjectLib.Message msgSend = new ProjectLib.Message("Server",content);
		//write to log when receive commit decision from server
		String logContent = filename+",4_";
		
		//free/delete resources
		if(msg.needCommit) {
			//need commit file
			logContent += "T_";
			if(requests.containsKey(filename)) {
				List<String> lockedResources = requests.get(filename);
				//write to log
				logContent += String.join("_",lockedResources);
				Log.writeLog(PL,path,logContent);
				//free/delete resources
				for(String source: lockedResources) {
					if(lockedRss.contains(source)) {
						lockedRss.remove(source);
					}
					deleteFile(source);
					System.out.println("	In user "+ myId+" file "+source+" has been delete");
					deletedRss.add(source);
				}
				requests.remove(filename);
			}
		}else {	//no need to commit, just release locked resources
			//check if previously lock resources. release them if so.
			logContent += "F_";
			if(requests.containsKey(filename)) {
				List<String> lockedResources = requests.get(filename);
				//write to log
				logContent += String.join("_",lockedResources);
				Log.writeLog(PL,path,logContent);
				//free resources
				for(String source: lockedResources) {
					if(lockedRss.contains(source)) lockedRss.remove(source);
				}
				requests.remove(filename);
			}
		}
		PL.sendMessage(msgSend);
		System.out.println("send msg to server");
	}

	//called by project lib class. Each incoming request will be redirected to this func
	//distinguish info type and hand out to specific functions
	//@params: msg is the message sent from server
	public boolean deliverMessage( ProjectLib.Message msg ) {
		Msg msgFromServer = ProcessMsg.deserialize(msg.body);
		if(msgFromServer.op == 1) {
			System.out.println( myId + ": Got message from " + msg.addr +" to vote");
			needVote(msgFromServer);
		}else if(msgFromServer.op == 3) {
			System.out.println( myId + ": Got message from " + msg.addr +" to ack");
			needAck(msgFromServer);
		}

		return true;
	}	
	//delete real file within local file storage
	//@params: filename is the file need to be deleted
	public static void deleteFile(String filename) {
		File file = new File(filename);
		file.delete();
	}
	//recover from log if node fail happens
	public static void recovery() {
		Map<String,List<String>> logInfo = Log.readLog(path);
		for(String task: logInfo.keySet()) {
			List<String> rcds = logInfo.get(task);
			int rcdLen = rcds.size();
			if(rcdLen == 1) {
				System.out.println("recover CK2 for "+task+" from user "+userid);
				String[] info = rcds.get(0).split("_");
				if(info[0].equals("2")) {
					if(info[1].equals("T")) {
						//need to lock resources
						List<String> needLocked = new ArrayList<>();
						for(int i = 2;i<info.length;i++) {
							needLocked.add(info[i]);
						}
						System.out.println("user recovery executing");
						System.out.println(needLocked);
						//lock necessary resources
						requests.put(task,needLocked);
						for(String ele: needLocked) {
							if(!lockedRss.contains(ele)) lockedRss.add(ele);
						}
					}
				}
			}else if(rcdLen == 2) {
				System.out.println("recover CK4 for "+task+" from user "+userid);
				String[] ck2 = rcds.get(0).split("_");
				String[] ck4 = rcds.get(1).split("_");
				//if ck2 vote for F, do nothing
				//if ck2 vote for T and ck4 get T, dlt
				//if ck2 vote for T and ck4 get F, release ## do nothing
				if(ck2[0].equals("2") && ck2[1].equals("T")) {
					if(ck4[0].equals("4") && ck4[1].equals("T")) {
						//check for dlt
						System.out.println("user "+userid+": both T, dlt files");
						for( int i = 2;i < ck4.length;i++) {
							deleteFile(ck4[i]);
							deletedRss.add(ck4[i]);
						}
						System.out.println(deletedRss);
					}
				}
			}
		}
	}
	
	public static void main ( String args[] ) throws Exception {
		if (args.length != 2) throw new Exception("Need 2 args: <port> <id>");
		UserNode UN = new UserNode(args[1]);
		// reconstruct node working structure base on log
		File userlog = new File(path);
		if(userlog.exists()) recovery();
		PL = new ProjectLib( Integer.parseInt(args[0]), args[1], UN );
		
	}
}

