import java.io.Serializable;

// used for transferring file metadata/data, not used for chunking read/write
// - servertmplink is used for sending back tmp file link on server to cache,
//	 user use this link for chunking read/write 
// - other if statement is used for transferring error message
public class FileMeta implements Serializable{
	public long versionNum = -1;
	public long filelen;	//make way for content transfer
	public byte[] data;
	public boolean ifdir;
	public boolean ifexists;
	public boolean ifupdated;
	public String servertmplink;
	public boolean ifpermit;
	public boolean ifexceed;

	public FileMeta() {
		filelen = 0;
		data = new byte[0];
		ifdir = false;
		ifexists = true;
		ifupdated = false;
		ifexceed = false;
		ifpermit = true;
	}
}