import java.io.*;

// cache reference element used for storing file status in cache||server
// has different constructors for different purpuse
public class CacheEle{
	public boolean isTemp = false;	//if file in masterRcd or tmpRcd
	public long fileVersion = -1; //fileversion
	public long filesize = 0;	
	public int accessNum = 0;
	public String filename;   // filename for storing contents
	public String dupfilename;
	public CacheEle dup;	// valid for tmpRcd elements, storing corresponding master cp in masterRcd

	//constructor for server tmp file access, used for chunk read/write
	public CacheEle(boolean isTemp, String name, String masterPath) {
		this.isTemp = isTemp;
		this.filename = name;
		this.dupfilename = masterPath;
		accessNum ++;
	}

	//used for substitute master cp by tmpfile(write action)
	//when updating read copy file in cache
	public CacheEle(String name, long fileVersion, long filesize) {
		this.isTemp = false;
		this.filename = name;
		this.fileVersion = fileVersion;
		this.filesize = filesize;
	}
	//constroctor used to create dup reference for tmp files
	public CacheEle(String keyname, long masterVersion) {
		this.filename = keyname;
		this.fileVersion = masterVersion;
	}
	//constructor for create tmp write file on proxy
	public CacheEle(String filename, long version, long size, CacheEle dup) {
		this.isTemp = true;
		this.filename = filename;
		this.fileVersion = version;
		this.filesize = size;
		this.dup = dup;
	}
}