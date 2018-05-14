import java.util.*;
import java.io.Serializable;

// class for sending messages between server and usernodes
public class Msg implements Serializable{
	//general params
	public int op;  // 1 is for voting request
	public String filename;  //collage name
	public String user;
	public byte[] img;
	//server node params
	public List<String> sources = new ArrayList<>();
	public boolean needCommit;
	//usernode params
	public boolean voteForCollage;

	//constructor for sending msg from server to usernodes, op1
	//@params: op is the op code for msg
	//@params: filename is the task name
	//@params: user is the message target
	//@params: source is the file source
	//@params: img is the byte sequence for target collage
	public Msg(int op, String filename, String user, String source, byte[] img) {
		this.op = op;
		this.filename = filename;
		this.user = user;
		this.img = img;
		this.sources.add(source);
	}
	//constructor for sending decision from server to usernodes, op3 
	//constructor for sending back ack from user to server, op4
	//@params: op is the op code for msg
	//@params: filename is the task name
	//@params: needCommit is the boolean indicating if collage need commit
	public Msg(int op, String filename, String user, boolean needCommit) {
		this.op = op;
		this.filename = filename;
		this.user = user;
		this.needCommit = needCommit;
	}
	//constructor for usernode sending back message to server, op2
	//@params: op is the op code for msg
	//@params: user is the message target
	//@params: filename is the task name
	//@params: voteForCollage is the boolean indicating if user vote for collage
	public Msg(int op, boolean voteForCollage, String filename, String user) {
		this.op = op;
		this.voteForCollage = voteForCollage;
		this.filename = filename;
		this.user = user;
	}

	//====================== server msg helper functions ======================

	//append sources to list if already exists user
	//@params: source is the additional source for a collage
	public void addSource (String source) {
		this.sources.add(source);
	}

}