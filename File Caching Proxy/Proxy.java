
import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.*;
import java.nio.file.*;
import java.lang.*;

class Proxy {
	public static RPCOp server;	//a static rpcserver interface
	public static ProxyCache cache; 
	public static Object cacheLock = new Object();	// used to lock cache when modifying cache records
	// each user has their own filehandler class
	// each user has three session storage hashmap storing fd || raf object || file name ||op permission
	private static class FileHandler implements FileHandling{
		private int fdGlobal = 10000;	//global fd, session used, no conflinct with other user.
		public final int MaxChunk = 2048000; //default chunk size
		private Map<Integer , RandomAccessFile> fdStorage= new HashMap<>();
		private Map<Integer, String> pmStorage = new HashMap<>();
		private Map<Integer, String> fnameStorage = new HashMap<>();	//fd, tmp file name

		// open function, dealing with file operation read||write||create||create_new
		// @params path: origin path input from user
		// @params: open option is one of ||READ||WRITE||CREATE||CREATE_NEW||
		public int open( String path, OpenOption o ){
			int ret = 0;
			long fileVersion;
			try{
			String cachepath = cache.translateFileName(path);	//canonical pathname for key 
			String tmpfilePath;
			synchronized(cacheLock) {fileVersion = cache.checkFileVersion(cachepath);}
			System.out.println("open has been called ! path: |"+ path +"|,option: " + o.toString()+"version: "+String.valueOf(fileVersion));
			if(path == null) return Errors.EINVAL;
			switch(o) {
				case READ:
					FileMeta filedata = server.getFileMeta_read_write(path, fileVersion);
					if(filedata.ifpermit == false) {
						System.out.println("	operation not permit");
						return Errors.EPERM;	
					}
					if(filedata.ifexists == false) {
						if(fileVersion != -1) {	//if this file in cache but already been deleted on server, delete from cache
							System.out.println("proxy: file delete on server, so need to delete locally");
							synchronized(cacheLock){
								cache.evictFile(fileVersion,cachepath);	
							}
						}
						return Errors.ENOENT;
					}
					if(filedata.ifdir == true) {
						fdStorage.put(fdGlobal, null);		//store assigned fd into hashmap
						pmStorage.put(fdGlobal, "r");
						ret = fdGlobal++;
						return ret;
					}
					if(filedata.ifupdated == true) { //copy in cache is most updated, just read it, create a copy
						System.out.println("proxy: alrady got most updated file version");
						synchronized(cacheLock) {	//check if read tmp already exists
							if(cache.checkFileVersion(cachepath) == -1) {	//file been delete between interval
								return Errors.ENOENT;
							}
							//just read most updated version from mastercp
							tmpfilePath = cache.readMostUpdated(cachepath);	//a name indicating version
						}
						try{
							RandomAccessFile fileObj = new RandomAccessFile(tmpfilePath, "r");
							fdStorage.put(fdGlobal, fileObj);		//store assigned fd into hashmap
							pmStorage.put(fdGlobal, "r");
							fnameStorage.put(fdGlobal, tmpfilePath);
							ret = fdGlobal++;
							return ret;
						}catch(FileNotFoundException e) {
							return Errors.ENOENT;
						}
					} else {	// need to update cache
						System.out.println("proxy: file received, need to update cache, info: size"+String.valueOf(filedata.filelen));
						synchronized(cacheLock) {
							long filesize = filedata.filelen;
							fileVersion = filedata.versionNum;
							tmpfilePath = cache.needUpdate(fileVersion, cachepath);
							if(tmpfilePath == null) {	//need to update cache
								//check capacity, evict to get space
								cache.evictIfClose(cachepath);
								//if prev file is zero access, delete
								boolean hasSpace = cache.checkCapacity(filesize);
								if(!hasSpace) {	//if no valid space, try to evict cache element
									boolean evictSuccess = cache.evictGetSpace(filesize);
									if(!evictSuccess) return Errors.ENOMEM;
								}// now has space, create new file status
								tmpfilePath = cache.addMasterFile(cachepath, fileVersion, filesize);
								tmpfilePath = cache.readMostUpdated(cachepath);	//a name indicating version
								updateCacheRet(filedata, tmpfilePath);//copy file content
							}
						}
						try{
							RandomAccessFile fileObj = new RandomAccessFile(tmpfilePath, "r");
							fdStorage.put(fdGlobal, fileObj);		//store assigned fd into hashmap
							pmStorage.put(fdGlobal, "r");
							fnameStorage.put(fdGlobal, tmpfilePath);
							ret = fdGlobal++;
						}catch(FileNotFoundException e) {
							e.printStackTrace();
						}
						return ret;
					}
				case WRITE: 	//OPEN EXISTING FILE FOR READ/WRITE must exist
					FileMeta filedata_wr = server.getFileMeta_read_write(path, fileVersion);
					String masterFileName = null;
					if(filedata_wr.ifexists == false) {
						if(fileVersion != -1) {	//if this file in cache but already been deleted on server, delete from cache
							System.out.println("proxy: file delete on server, so need to delete locally");
							cache.evictFile(fileVersion,cachepath);	
						}
						System.out.println("proxy: open with write must happen on existing file");
						return Errors.ENOENT;
					}
					if(filedata_wr.ifdir == true) {
						System.out.println("	write file path is a directory");
						return Errors.EISDIR;
					}
					if(filedata_wr.ifupdated == true) { //copy in cache is most updated, just read it, get a copy
						System.out.println("proxy: alrady got most updated file version");
						//copy cache file, create new file, must create new for write option
						synchronized(cacheLock) {	//check if read tmp already exists
							if(cache.checkFileVersion(cachepath) == -1) {	//file been delete between interval
								return Errors.ENOENT;
							}
							//file exists, check if has space to create tmp write file
							if(!cache.checkCapacity(cachepath)) {	//has space, create reference
								boolean evictSuccess = cache.evictGetSpace(cachepath);
								if(!evictSuccess) {return Errors.ENOMEM;}							
							} 
							//create tmp copy for write, take space, add reference
							tmpfilePath = getTmpFilePath(cachepath, "w");
							masterFileName = cache.addtmpFile(cachepath, tmpfilePath,"w");
							createTmpFile(masterFileName, tmpfilePath);
						}
						try{
							RandomAccessFile fileObj = new RandomAccessFile(tmpfilePath, "rw");
							fdStorage.put(fdGlobal, fileObj);		//store assigned fd into hashmap
							pmStorage.put(fdGlobal, "rw");
							fnameStorage.put(fdGlobal, tmpfilePath);
							ret = fdGlobal++;
							return ret;
						}catch(FileNotFoundException e) {
							return Errors.ENOENT;
						}
					} else {	// need to update cache, then copy cache content to tmpfile
						//update file represented by cachepath, could have multiple write copy
						System.out.println("proxy(write): file received, need to update cache, info: size"+String.valueOf(filedata_wr.filelen));
						synchronized(cacheLock) {
							long filesize = filedata_wr.filelen;
							fileVersion = filedata_wr.versionNum;
							tmpfilePath = cache.needUpdate(fileVersion, cachepath);	//null if need update
							if(tmpfilePath == null) {	//need to update cache
								//check capacity, evict to get space
								cache.evictIfClose(cachepath);
								//***( do we need to evict old closed version first?)
								boolean hasSpace = cache.checkCapacity(filesize);
								if(!hasSpace) {	//if no valid space, try to evict cache element
									boolean evictSuccess = cache.evictGetSpace(filesize);
									if(!evictSuccess) return Errors.ENOMEM;
								}// now has space, update new master file's content
								tmpfilePath = cache.addMasterFile(cachepath, fileVersion, filesize);
								updateCacheRet(filedata_wr, tmpfilePath);//copy file content
							}// create write tmp file
							tmpfilePath = getTmpFilePath(cachepath, "w");
							masterFileName = cache.addtmpFile(cachepath, tmpfilePath,"w");
							createTmpFile(masterFileName, tmpfilePath);							
						}
						try{
							RandomAccessFile fileObj = new RandomAccessFile(tmpfilePath, "rw");
							fdStorage.put(fdGlobal, fileObj);		//store assigned fd into hashmap
							pmStorage.put(fdGlobal, "rw");
							fnameStorage.put(fdGlobal, tmpfilePath);
							ret = fdGlobal++;
						}catch(FileNotFoundException e) {
							e.printStackTrace();
						}
						return ret;
					}
				case CREATE:  //raf with rw
					System.out.println("proxy: open with create has been called");
					FileMeta filedata_c = server.getFileMeta_create(path, fileVersion);
					if(filedata_c.ifdir == true) {
						System.out.println("	write file path is a directory");
						return Errors.EISDIR;
					}
					if(filedata_c.ifupdated == true) { //copy in cache is most updated, just read it, make a copy
						System.out.println("proxy: alrady got most updated file version");
						synchronized(cacheLock) {	//check if read tmp already exists
							if(cache.checkFileVersion(cachepath) == -1) {	//file been delete between interval
								return Errors.ENOENT;
							}
							//if temp file does not exists, check if cache has capacity to insert
							if(!cache.checkCapacity(cachepath)) {	//has space, create reference
								boolean evictSuccess = cache.evictGetSpace(cachepath);
								if(!evictSuccess) {return Errors.ENOMEM;}							
							} 
							//create tmp copy for write, take space, add reference
							tmpfilePath = getTmpFilePath(cachepath, "w");
							masterFileName = cache.addtmpFile(cachepath, tmpfilePath,"w");
							createTmpFile(masterFileName, tmpfilePath);
						}
						try{
							RandomAccessFile fileObj = new RandomAccessFile(tmpfilePath, "rw");
							fdStorage.put(fdGlobal, fileObj);		//store assigned fd into hashmap
							pmStorage.put(fdGlobal, "rw");
							fnameStorage.put(fdGlobal, tmpfilePath);
							ret = fdGlobal++;
							return ret;
						}catch(FileNotFoundException e) {
							return Errors.ENOENT;
						}
					} else {	// need to update cache, then copy cache content to tmpfile
						System.out.println("proxy(create): file received, need to update cache, info: size"+String.valueOf(filedata_c.filelen));
						synchronized(cacheLock) {
							long filesize = filedata_c.filelen;
							fileVersion = filedata_c.versionNum;
							tmpfilePath = cache.needUpdate(fileVersion, cachepath);	//null if need update
							if(tmpfilePath == null) {	//need to update cache
								//check capacity, evict to get space
								cache.evictIfClose(cachepath);
								//***( do we need to evict old closed version first?)
								boolean hasSpace = cache.checkCapacity(filesize);
								if(!hasSpace) {	//if no valid space, try to evict cache element
									boolean evictSuccess = cache.evictGetSpace(filesize);
									if(!evictSuccess) return Errors.ENOMEM;
								}// now has space, update new master file's content
								tmpfilePath = cache.addMasterFile(cachepath, fileVersion, filesize);
								updateCacheRet(filedata_c, tmpfilePath);//copy file content
							}// create write tmp file
							tmpfilePath = getTmpFilePath(cachepath, "w");
							masterFileName = cache.addtmpFile(cachepath, tmpfilePath,"w");
							createTmpFile(masterFileName, tmpfilePath);							
						}
						try{
							RandomAccessFile fileObj = new RandomAccessFile(tmpfilePath, "rw");
							fdStorage.put(fdGlobal, fileObj);		//store assigned fd into hashmap
							pmStorage.put(fdGlobal, "rw");
							fnameStorage.put(fdGlobal, tmpfilePath);
							ret = fdGlobal++;
						}catch(FileNotFoundException e) {
							e.printStackTrace();
						}
						return ret;
					}						
				case CREATE_NEW:  //file must not exists
					System.out.println("proxy: open with create new has been called");
					FileMeta filedata_cn = server.getFileMeta_createNew(path, fileVersion);
					if(filedata_cn.ifexists == true) {
						if(filedata_cn.ifdir == true) {
							System.out.println("	write file path is a directory");
							return Errors.EISDIR;							
						}
						return Errors.EEXIST;	//regular file aready exists
					}
						//create write copy, first need check if there are valid space in cache
						System.out.println("proxy: file received, need to update cache, info: size"+String.valueOf(filedata_cn.filelen));
						synchronized(cacheLock) {
							long filesize = filedata_cn.filelen;
							fileVersion = filedata_cn.versionNum;
							tmpfilePath = cache.needUpdate(fileVersion, cachepath);
							if(tmpfilePath == null) {	//need to update cache(must have space for adding 0
								masterFileName = cache.addMasterFile(cachepath, fileVersion, filesize);
								updateCacheRet(filedata_cn, masterFileName);//copy file content
							}
							tmpfilePath = getTmpFilePath(cachepath, "w");
							masterFileName = cache.addtmpFile(cachepath, tmpfilePath,"w");
							createTmpFile(masterFileName, tmpfilePath);	
						}							
						try{
							RandomAccessFile fileObj = new RandomAccessFile(tmpfilePath, "rw");
							fdStorage.put(fdGlobal, fileObj);		//store assigned fd into hashmap
							pmStorage.put(fdGlobal, "rw");
							fnameStorage.put(fdGlobal, tmpfilePath);
							ret = fdGlobal++;
							return ret;
						}catch(FileNotFoundException e) {
							e.printStackTrace();
							return Errors.ENOENT;
						}
				default: return Errors.EINVAL;
			}
			}catch(RemoteException e) {
				e.printStackTrace();
			}
			return ret;
		}
		// close function
		// @params: fd is the file descriptor
		// return operation status
		public int close( int fd ) {
			System.out.println("close has been called :" + String.valueOf(fd));
			if(fdStorage.containsKey(fd)) {		// the fd exists
				FileMeta retObj = new FileMeta();
				RandomAccessFile temp = fdStorage.remove(fd);
				String permission = pmStorage.remove(fd);
				String cachetmpPath = fnameStorage.remove(fd);
				String pathname = cache.returnOrigName(cachetmpPath);
				String cacheMasterPath = cachetmpPath.split("__")[0];
				System.out.println("	element info: "+ String.valueOf(fd) + " " +permission+" "+cachetmpPath);
				try{
					if(permission.equals("rw")) {	//indicating a write action
						//push tmp file to server, update cache
						System.out.println("proxy: close with rw has been called");
						long size = temp.length();
						temp.seek(0);
						if(size > MaxChunk) {
							String tmplink = server.openChunkWrite(pathname);
							long reallen = 0;	//bytes sending to server
							while(reallen < size) {
								FileGetData tmp = new FileGetData();
								byte[] tmpdata = new byte[MaxChunk];
								temp.seek(reallen);
								int readlen = temp.read(tmpdata,0, MaxChunk);
								tmp.data = tmpdata;
								tmp.readlen = (long) readlen;
								tmp.offset = reallen;
								tmp = server.chunkPushModify(tmp, tmplink);
								System.out.println("file offset locally: "+reallen+" on server: "+tmp.offset);
								reallen += (long) readlen;
							}
							retObj = server.closeChunkWrite(tmplink);	//close server tmp file,push new version
						} else {
							byte[] data = new byte[(int)size];  
							FileMeta sendObj = new FileMeta();
							int readlen = temp.read(data);
							sendObj.data =data;
							sendObj.filelen = size;
							System.out.println("	CLOSE: SEND to server, filelen: " +String.valueOf(size)+" readlen: "+String.valueOf(readlen));
							retObj = server.pushModify(sendObj, pathname);
						}
						//update cache records
						// delete local version if the version is closed
						synchronized(cacheLock) {
							CacheEle tmp = cache.getStatus(true, cachetmpPath);
							boolean evictSuccess = false;
							File tmpfile = new File(cachetmpPath);
							long tmpsize = tmpfile.length();

							long mastersize = cache.evictTmpFile(cachetmpPath);	//delete from list, free space, get size
							long needsize = tmpsize - mastersize;	//evict master (-) append new file(+)
							boolean hasSpace = cache.checkCapacity((needsize>0?needsize:0));
							if(hasSpace) {
								evictSuccess = true;
							} else {
								evictSuccess = cache.evictGetSpace(needsize);
							}
							if(evictSuccess) {
								String newMasterName = cacheMasterPath+"__"+String.valueOf(retObj.versionNum)+"__"+"r";
								createTmpFile(cachetmpPath, newMasterName);
								cache.replaceMaster(newMasterName, cacheMasterPath,retObj.versionNum);
							}
						}
						(new File(cachetmpPath)).delete();	//delete closed tmp write file
						temp.close();	
					} else if(permission.equals("r")){
						System.out.println("	close with read: file key"+cacheMasterPath);
						synchronized(cacheLock) {
							cache.closeTmpRead(cachetmpPath,cacheMasterPath);
						}
					}
					return 0;
				} catch(IOException e){
					return Errors.EBUSY;
				} 
			}
			return Errors.EBADF;
		}
		//write function, contains no rpc call
		public long write( int fd, byte[] buf ) {
			System.out.println("Write has been called , fd: " + String.valueOf(fd));
			if(fdStorage.containsKey(fd)) {		// the fd exists
				System.out.println("	key has been matched, with now globalfd "+ String.valueOf(fdGlobal));
				RandomAccessFile temp = fdStorage.get(fd);
				String permission = pmStorage.get(fd);
				System.out.println("	element info: "+ String.valueOf(fd) + " " +permission);
				if(permission.equals("r")) {
					System.out.println("	permission denied, r with rw");
					return Errors.EBADF;
				}
				try{	//nornally, might have IOException
					if(temp == null) {	//indicate a directory
						System.out.println("	is directory");
						return Errors.EISDIR;
					}
					temp.write(buf);		//will shortcount happen?
					System.out.println("	write "+ String.valueOf(buf.length)+" bytes");
					return buf.length;
				} catch(IOException e){		//what if write on a file with mode 'r'
					System.out.println("	io error");
					return Errors.EBUSY;	//indicate IO exception
				} 
			} 
			System.out.println("	bad file descriptor");
			return Errors.EBADF;
		}
		//read call, contains no rpc call
		public long read( int fd, byte[] buf ) {
			if(fdStorage.containsKey(fd)) {		// the fd exists
				RandomAccessFile temp = fdStorage.get(fd);
				try{	//nornally, might have IOException
					if(temp == null) {	//indicate a directory
						System.out.println("	is a directory");
						return Errors.EISDIR;
					}
					long ret = temp.read(buf);		//will shortcount happen?
					if ( ret == -1) ret = 0;
					return ret;
				} catch(IOException e){
					return Errors.EBUSY;	//indicate IO exception
				} 
			} 
			return Errors.EBADF;
		}
		//lseek call, contains no rpc call
		public long lseek( int fd, long pos, LseekOption o ) {
			System.out.println("lseek has been called , fd,option: " + String.valueOf(fd)+" "+String.valueOf(o));
			if(fdStorage.containsKey(fd)) {		// the fd exists
				System.out.println("	key has been matched, with now globalfd "+ String.valueOf(fdGlobal));
				RandomAccessFile temp = fdStorage.get(fd);
				try{	//nornally, might have IOException
					if(temp == null) {	//indicate a directory
						return Errors.EISDIR;
					}
					long offHead = temp.getFilePointer();
					if(String.valueOf(o).equals("FROM_CURRENT")) {
						temp.seek(pos+offHead);
						return pos+offHead;
					} else if(String.valueOf(o).equals("FROM_START")) {
						temp.seek(pos);
						return pos;		// from start flag, set to current position
					} else if(String.valueOf(o).equals("FROM_END")) {
						temp.seek(temp.length()+pos);
						return temp.length()+pos;
					} else{
						return Errors.EINVAL;	//bad params
					}
				} catch(IOException e){
					return Errors.EBUSY;	//indicate IO exception
				} 
			} 
			return Errors.EBADF;
		}
		// unlink a path, errors may be nonexists and isdir
		// @params: path is the pathname need to be unlinked
		public int unlink( String path ) {	//check bad path/not exist 
			System.out.println("unlink has been called , path: " + path);
			FileMeta filedata = new FileMeta();
			try{
				filedata = server.unlinkFile(path);
				if(filedata.ifexists == false) {
					return Errors.ENOENT;
				}
				if(filedata.ifdir == true) {
					return Errors.EISDIR;
				}
				synchronized(cacheLock) {
					cache.evictIfClose(cache.translateFileName(path));
				}
			}catch(RemoteException e){
				e.printStackTrace();
			}		
			return (int)filedata.filelen;
		}
		// copy a master file to a temp file
		// @params: cachepath is valid CANONICAL cache path
		// @params: tmpfilepath is the target file path
		public void createTmpFile(String cachepath, String tmpfilePath) {
			Path src = Paths.get(cachepath);	//file path stored in cache
			Path des = Paths.get(tmpfilePath);
			try{
				Files.copy(src, des, StandardCopyOption.REPLACE_EXISTING);
			}catch(IOException e) {
				e.printStackTrace();
			}
		}
		// assign a new tmp file name, based on current timestamp
		// @params: cachepath is the filepath
		// @params: mode is the operation mode r/w
		// return new tmp file name
		public String getTmpFilePath(String cachepath, String mode) {
			return cachepath+"__"+String.valueOf(System.currentTimeMillis()) + "__" +mode;	//file path
		} 
		// craete local master copy of the file, or receiving remaining byte from server
		// write into a opened raf file class
		// @params: filedata is the fileMeta class received from server
		// @params: cachepath is the target file path
		public void updateCacheRet(FileMeta filedata, String cachepath) {
			System.out.println("proxy: need to add file to cache");
			long filelen = filedata.filelen;	//the true length of the file
			long reallen = filedata.data.length;	//the length of data sent from server
			try{
				RandomAccessFile fileObjnew = new RandomAccessFile(cachepath, "rw");
				if(filelen <= MaxChunk) {	//everything is inside the data byte array
					fileObjnew.write(filedata.data);
					fileObjnew.close();
				} else {
					String servertmplink = filedata.servertmplink;
					while(reallen < filelen) {
						FileGetData temp = server.getFileRemain(servertmplink, reallen);
						fileObjnew.write(temp.data);
						reallen += temp.readlen;
					}
					fileObjnew.close();
					server.tmpfileclose(servertmplink);
				}
			}catch (Exception e) {
				e.printStackTrace();
			}
		}
		// indicating a client session has been closed
		public void clientdone() {
			System.out.println("client done!");
			cache.printCache();
			return;
		}
	}
	
	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient() {
			System.out.println("	new client has been set up");
			return new FileHandler();
		}
	}
	// calling with commandline input:
	// <serverip> <serverport number> <cache directory> <cachesize>
	public static void main(String[] args) throws IOException {
		String serverip = args[0];
		String serverport = args[1];
		String cachedir = args[2];
		String cachesize = args[3];
		System.out.println("proxy ready, initialize cache dir: "+cachedir+" cachesize: "+cachesize);
		//initialize cache
		cache = new ProxyCache(Long.parseLong(cachesize), cachedir);	// change dir path (may need to add /)
		try{
			server = (RPCOp)Naming.lookup("//"+serverip+":"+serverport+"/Server");//create interface for each proxy
		}catch(Exception e) {
			e.printStackTrace();
		}
		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}

