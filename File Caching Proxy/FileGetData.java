import java.io.Serializable;

// file data transferring class, used for chunk read/write
// data is the read/write content
// offset is used for locating content position
// readlen is used for monitoring bytes transferring status
public class FileGetData implements Serializable{
	public long offset;
	public long readlen;	
	public byte[] data;

	public FileGetData() {
		offset = 0;
		readlen = 0;
		data = new byte[0];
	}
}