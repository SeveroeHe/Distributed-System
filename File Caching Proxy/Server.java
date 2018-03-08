import java.rmi.server.UnicastRemoteObject;
import java.rmi.RemoteException;
import java.rmi.registry.*;
import java.rmi.Naming;
import java.io.*;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.nio.file.*;
import java.lang.*;

// server has two concurrent hashmap structure and two corresponding read-write lock object
// - lockRcd is used for storing file concurrent operation status
// - tmpfile is used for storing chunking read/write tmp files
// lock objects is used to explicitly syncronize lock delivery/recycle 
public class Server extends UnicastRemoteObject implements RPCOp{
	public static String fileroot;
	public final int MaxChunk = 2048000; //default chunk size, can be changed
	public static ConcurrentHashMap<String, ReentrantReadWriteLock> lockRcd= new ConcurrentHashMap<>(); 
	public static ConcurrentHashMap<String, CacheEle> tmpfile = new ConcurrentHashMap<>();
	public static Object lockMap = new Object();	//used to lock map preventing inserting same element
	public static Object locktmp = new Object();
	public Server() throws RemoteException{
		super(0);
	}
	//Handle remote open with read/write option, add read lock for operations
	// - if file is too large, create a tmp file for later chunking read
	// - else return back all file meta data and file content
	// @params: pathname is the origin pathname, need change to canonical and then server version
	// @params: version is the version of file locally(-1 if nonexists)
	// return file meta data(content)
	public FileMeta getFileMeta_read_write(String pathname, long version) throws RemoteException{
		String filepath = getCanonical(pathname);
		System.out.println("	server: open with option read,filepath: " + filepath);
		ReentrantReadWriteLock tmpLock;
		FileMeta filedata = new FileMeta();
		//check if file is in lock. if not, add it
		synchronized(lockMap) {
			if(!lockRcd.containsKey(filepath)) {//no this file in hashmap, add lock object for it
				lockRcd.put(filepath, new ReentrantReadWriteLock());
			} 
			tmpLock = lockRcd.get(filepath);
			tmpLock.readLock().lock();	//get read lock, read file info
		}
		try{
			File file = new File(filepath);
			if(!Validpath(filepath)) {
				filedata.ifpermit = false;
				return filedata;
			}
			//file did not exists, return error
			if(file.exists() == false) {
				System.out.println("	file does not exists, error");
				filedata.ifexists = false;
				return filedata;
			}
			//file is a directory, only valid for read openoption
			if(file.isDirectory() == true) {
				filedata.ifdir = true;
				System.out.println("	is a directory, error");
				return filedata;
			}
			filedata.versionNum = file.lastModified();
			if (filedata.versionNum == version) {
				filedata.ifupdated = true;
				filedata.filelen = file.length();
				return filedata;
			} else {	//file need to update, return content as well
				long size = file.length();
				//if read exceed the maxlen, need to use other ways, only return tmpcopy link
				if(size > MaxChunk) {
					String tempReadlink = openChunkRead(filepath, file.lastModified(),"r");
					filedata.filelen = size;
					filedata.servertmplink = tempReadlink;
					return filedata;
				}
				RandomAccessFile temp = new RandomAccessFile(file, "r");
				byte[] data = new byte[(int)size];  //if file is too large, read later
				temp.read(data);
				temp.close();
				filedata.filelen = size;
				filedata.data = data;
				filedata.versionNum = file.lastModified();
				return filedata;
			}
		}catch(Exception e) {
			e.printStackTrace();
			return filedata;
		}finally{
			tmpLock.readLock().unlock();
		}
	}
	//Handle remote open with create option, add write lock for operations(may need to create new file)
	// - if file is too large, create a tmp file for later chunking read
	// - else return back all file meta data and file content
	// @params: pathname is the origin pathname, need change to canonical and then server version
	// @params: version is the version of file locally(-1 if nonexists)
	// return file meta data(content)
	public FileMeta getFileMeta_create(String pathname, long version) throws RemoteException {
		System.out.println("server: open with create has been called");
		String filepath = getCanonical(pathname);

		FileMeta filedata = new FileMeta();
		ReentrantReadWriteLock tmpLock;
		synchronized(lockMap) {
			if(!lockRcd.containsKey(filepath)) {//no this file in hashmap, add lock object for it
				lockRcd.put(filepath, new ReentrantReadWriteLock());
			} 
			tmpLock = lockRcd.get(filepath);
			tmpLock.writeLock().lock();	//get write lock
		}
		try{
			File file = new File(filepath);
			//check edxistence, if nonexists, create new file with specified name
			if(file.exists() == false) {
				RandomAccessFile newBlank = new RandomAccessFile(filepath, "rw");
				newBlank.close();
				System.out.println("	: create new file, path: " + filepath);
				filedata.versionNum = (new File(filepath)).lastModified();
				return filedata;
			}
			//file is a directory, only valid for read openoption
			if(file.isDirectory() == true) {
				filedata.ifdir = true;
				System.out.println("	: is a directory,error");
				return filedata;
			}
			//indicating this is an existing file
			filedata.versionNum = file.lastModified();
			if(version == filedata.versionNum) {	//updated version, just back
				filedata.ifupdated = true;
				filedata.filelen = file.length();
				System.out.println("	: most updated");
				return filedata;
			} else {	// also copy back content
				long size = file.length();
				System.out.println("	filesize: " + String.valueOf(size)+" MaxChunk: "
					+ String.valueOf(MaxChunk));
				//if read exceed the maxlen, need to use other ways, only return tmpcopy link
				if(size > MaxChunk) {
					String tempReadlink = openChunkRead(filepath, file.lastModified(),"r");
					filedata.filelen = size;
					filedata.servertmplink = tempReadlink;
					return filedata;
				}
				RandomAccessFile temp = new RandomAccessFile(file, "r");
				byte[] data = new byte[(int)size];  //if file is too large, read later
				temp.read(data);
				temp.close();
				filedata.filelen = size;
				filedata.data = data;
				return filedata;
			}
		} catch(Exception e) {	
			e.printStackTrace();
			return filedata;
		} finally{
			tmpLock.writeLock().unlock();
		}
	}
	//Handle remote open with create_new option, add write lock for operations
	// - must not exists target file
	// @params: pathname is the origin pathname, need change to canonical and then server version
	// @params: version is the version of file locally(-1 if nonexists)
	// return file meta data(content)
	public FileMeta getFileMeta_createNew(String pathname, long version) throws RemoteException {
		System.out.println("server: open with create new has been called");
		String filepath = getCanonical(pathname);

		FileMeta filedata = new FileMeta();
		ReentrantReadWriteLock tmpLock;
		synchronized(lockMap) {
			if(!lockRcd.containsKey(filepath)) {//no this file in hashmap, add lock object for it
				lockRcd.put(filepath, new ReentrantReadWriteLock());
			} 
			tmpLock = lockRcd.get(filepath);
			tmpLock.writeLock().lock();	//get read lock, read file info
		}
		try{
			File file = new File(filepath);
			//check edxistence, if exists, return error
			if(file.exists() == true) {
				if(file.isDirectory() == true) { 
					System.out.println("	:error isdir");
					filedata.ifdir = true;
				}
				System.out.println("	:error, create new must be called with no existing file");
				return filedata;
			}
			filedata.ifexists = false;
			RandomAccessFile newBlank = new RandomAccessFile(filepath, "rw");
			newBlank.close();
			filedata.versionNum = new File(filepath).lastModified();
			return filedata;
		} catch(Exception e) {	
			e.printStackTrace();
			return filedata;
		}finally{
			tmpLock.writeLock().unlock();
		}
	}

	// handle chunking read from server, called within a loop
	// @params: pathname: filename on server
	// @params: offset: the place start reading
	// return filecontent of this read behavior
	public FileGetData getFileRemain(String pathname, long offset) throws RemoteException{
		System.out.println("Server: chunkread has been called!");
		FileGetData retData = new FileGetData();
		try{
			RandomAccessFile tempfile = new RandomAccessFile(pathname, "r");
			tempfile.seek(offset);	//set file offsetf for read
			byte[] data = new byte[MaxChunk];
			int readlen = tempfile.read(data, 0,MaxChunk);
			retData.readlen = readlen;	//the bytes being read
			retData.data = data;	//the content being read
			retData.offset = tempfile.getFilePointer();
			return retData;
		}catch(FileNotFoundException e) {
			return retData;
		}catch(IOException e) {
			return retData;
		}
	}
	// handle chunk read from server to proxy
	// close tmpfile operation from proxy, substract tmp file access num on server
	// if file access num become zero, delete file(at that point tmp file no need to exist)
	// @params: pathname is the tmp file link on server
	public void tmpfileclose(String pathname) throws RemoteException{
		synchronized(locktmp) {
			tmpfile.get(pathname).accessNum--;
			if (tmpfile.get(pathname).accessNum == 0){	//the file should be removed
				tmpfile.remove(pathname);
				(new File(pathname)).delete();
			}
		}
	}
	// handle remote write call, open a tmp write file on server
	// @params: filepath is the original filepath, need to switch to server canonical version
	// return tmp file link to client, used for chunking write call
	public String openChunkWrite(String filepath) throws RemoteException{
		String filename = getCanonical(filepath);
		String tmplink = filename+"__"+String.valueOf(System.currentTimeMillis())+"w";
		synchronized(locktmp) {
			CacheEle addtemp = new CacheEle(true, tmplink, filename);
			tmpfile.put(tmplink, addtemp);
		}
		try{
			RandomAccessFile raf = new RandomAccessFile(tmplink, "rw");
			raf.close();
		}catch (Exception e) {
			e.printStackTrace();
		}
		return tmplink;
	}
	// handle chunking write recursively, write into FileGetData class to transfer content and file status
	// @params: filedata is the file meta info from proxy
	// @params: pathname is the tmp write file path on server
	// return file content and related file meta info
	public FileGetData chunkPushModify(FileGetData filedata, String pathname) throws RemoteException {
		System.out.println("server: chunk write has beenc alled");
		FileGetData ret = new FileGetData();
		try{
			RandomAccessFile raf = new RandomAccessFile(pathname, "rw");
			raf.seek(filedata.offset);
			raf.write(filedata.data);
			ret.offset = raf.getFilePointer();
			ret.readlen = filedata.readlen;
			raf.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return ret;
	}
	// close chunk write remote operation, push tmp file to replace master file
	// delete tmp file on server
	// @param: pathname is the tmp write link on server
	// return a file meta class indicating pushed file meta data
	public FileMeta closeChunkWrite(String pathname) throws RemoteException {
		CacheEle tmp;
		ReentrantReadWriteLock tmpLock;
		FileMeta filedata = new FileMeta();
		synchronized(locktmp) {
			tmp = tmpfile.remove(pathname);
		}
		String masterlink = tmp.dupfilename;
		synchronized(lockMap) {	//aquire write lock to change master file content
			if(!lockRcd.containsKey(masterlink)) {//no this file in hashmap, add lock object for it
				lockRcd.put(masterlink, new ReentrantReadWriteLock());
			} 
			tmpLock = lockRcd.get(masterlink);
			tmpLock.writeLock().lock();	//get write lock, change content
		}
		try{
			Files.copy(Paths.get(pathname), Paths.get(masterlink), StandardCopyOption.REPLACE_EXISTING);
			(new File(pathname)).delete();
			File master = new File(masterlink);
			filedata.versionNum = master.lastModified();
			filedata.filelen = master.length();
			System.out.println("	chunkwrite finish");
			return filedata;
		}catch(Exception e) {
			e.printStackTrace();
			return filedata;
		}finally{
			tmpLock.writeLock().unlock();
		}
	}

	// push modification to server, return new filemeta(versionnum, so force), support one total push
	// @params: newFile is the metadata from server containing content and file status info
	// @params: pathname is the origin path file pathname called by user
	// return file meta class indicating new file status info
	public FileMeta pushModify(FileMeta newFile, String pathname) throws RemoteException {
		String filepath = getCanonical(pathname);
		ReentrantReadWriteLock tmpLock;
		System.out.println("server: push modify been called, with pathname: " + filepath);
		File file = new File(filepath);
		FileMeta filedata = new FileMeta(); //return obj
		synchronized(lockMap) {
			if(!lockRcd.containsKey(filepath)) {//no this file in hashmap, add lock object for it
				lockRcd.put(filepath, new ReentrantReadWriteLock());
			} 
			tmpLock = lockRcd.get(filepath);
			tmpLock.writeLock().lock();	//get write lock, change content
		}
		try{
			if(file.exists() == true) {
				//lock ensure thread-safe
				System.out.println("	:pushed file has prev version, delete it");
				file.delete();
			}
			RandomAccessFile raf = new RandomAccessFile(filepath, "rw");
			System.out.println("	:file bytes: " + String.valueOf(newFile.data.length));
			raf.write(newFile.data);
			raf.close();
			System.out.println("	: successfully write");
			File finished = new File(filepath);
			filedata.filelen = finished.length();
			filedata.versionNum = finished.lastModified();
			System.out.println("	: filelen" + String.valueOf(filedata.filelen));
			System.out.println("	: successfully return");
			return filedata;
		}catch(IOException e) {
			e.printStackTrace();
			return filedata;
		}finally {
			tmpLock.writeLock().unlock();
		}
	}

	// handle unlink remote request form proxy client
	// acquire write lock, delete file on server
	// @params: pathname is the origin pathname called by user
	// return a file meta class 
	public FileMeta unlinkFile(String pathname) throws RemoteException {
		String filepath = getCanonical(pathname);

		FileMeta filedata = new FileMeta();
		ReentrantReadWriteLock tmpLock;
		synchronized(lockMap) {
			if(!lockRcd.containsKey(filepath)) {//no this file in hashmap, add lock object for it
				lockRcd.put(filepath, new ReentrantReadWriteLock());
			} 
			tmpLock = lockRcd.get(filepath);
			tmpLock.writeLock().lock();	//get write lock, change content
		}
		try{
			File file = new File(filepath);
			if(file.exists() == false) {
				filedata.ifexists = false;
				return filedata;
			}
			//file is a directory, only valid for read openoption
			if(file.isDirectory() == true) {
				filedata.ifdir = true;
				return filedata;
			}
			boolean result = file.delete();
			filedata.filelen = result == true? 0: -1;
			return filedata;
		}finally{
			tmpLock.writeLock().unlock();
		}
	}
	// server function, open a new tmp file for chunk read, copy all content from source to a tmp file
	// @params: filepath is the master file path on server
	// @params: master_version is the fileversion of origin file
	// @params: mode is the r/w choice for distinguish file version
	public String openChunkRead(String filepath, long master_version, String mode) {
		String tmplink = filepath+"__"+String.valueOf(master_version)+"__"+mode;
		synchronized(locktmp) {
			if(tmpfile.containsKey(tmplink)) {
				tmpfile.get(tmplink).accessNum++;
				return tmplink;
			}
			CacheEle addtemp = new CacheEle(true, tmplink, filepath);
			tmpfile.put(tmplink, addtemp);
		}
		try{
			Files.copy(Paths.get(filepath), Paths.get(tmplink), StandardCopyOption.REPLACE_EXISTING);
		}catch(Exception e) {
			e.printStackTrace();
		}
		return tmplink;
	}
	// server function to adjust root path format
	// @params: pathname is the root path
	// return finished pathname
	public static String modifyRoot(String pathname) {
		String ret = new String(pathname);
		if(ret.charAt(ret.length()-1) != '/') {ret += "/";}
		return ret;
	}
	// server function used to change filepath to a canonical path
	// @params: pathname is the origin path name
	// return canonical pathname
	public static String getCanonical(String pathname) {
		String temppath = "./" + fileroot + pathname;
		String filepath = temppath;
		try{
			filepath = (new File(temppath)).getCanonicalPath();
		}catch(Exception e) {
			e.printStackTrace();
		}
		return filepath;
	}
	// server function used to judge whether a path is a valid server path
	// @params: canipath is the file canonical path on server
	// return true if path start with root canonical path, false if not
	public static boolean Validpath(String canipath) {
		String rootcani = canipath;
		try{
		 	rootcani = (new File(fileroot)).getCanonicalPath();
		}catch(Exception e) {
			e.printStackTrace();
		}
		return canipath.startsWith(rootcani);
	}
	// need to specify <serverport> <server directory>
	public static void main(String args[]) {
		String port = args[0];
		fileroot = modifyRoot(args[1]);
		System.out.println("Server listen on port " + port +"with dft dir " + fileroot);
		try{
			LocateRegistry.createRegistry(Integer.parseInt(port)); // port , change code ?
			Server datacenter = new Server();	//give cmdline input
			Naming.rebind("//127.0.0.1:"+port+"/Server",datacenter);
		}catch(Exception e){
			e.printStackTrace();
		}
	}
}