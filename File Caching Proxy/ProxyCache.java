import java.io.*;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.Collections;
import java.util.LinkedHashMap;
// proxy cache class has two global data structure
//	- masterRcd is used to store most updated read file version
//		- key: filename without version & suffix
//		- value is a CacheEle class storing file version||real file name||size|| access num
//  - tmpRcd is used to store tmp write file or obsoleted read file
//		- key: real file name (filename with version and suffix)
//		- value is a CacheEle class with version||filename||size||accessnum||linked master cp info||
//  - LRU: use a linkedhashmap to ensure LRU(each valid file operation count as a file access)
// 	- NEVER EVICT OPENED FILE || tmp file will immediately be deleted if there is no access
//  - eviction victim only be find within masterRcd with zero access num
public class ProxyCache{
	private long capacity;	//max capacity of cache
	private long remain;	//cache remain
	private String rootdir;  //cache directory path
	private LinkedHashMap<String, CacheEle> masterRcd;	
	private ConcurrentHashMap<String, CacheEle> tmpRcd;	

	//@params: capacity is cache capacity
	//@param: pathname is the cache path name
	ProxyCache(long capacity, String pathname){
		this.capacity = capacity;
		this.remain = capacity;
		if(pathname.charAt(pathname.length()-1) != '/') { pathname += '/';}
		this.rootdir = pathname;
		this.masterRcd = new LinkedHashMap<>();
		this.tmpRcd = new ConcurrentHashMap<>();
	}

	// evicting a master file, always in master copy
	// free cache space, delete file in memory
	// @param: versionID is the pending file's version
	// @param: cachepath is the file path in cache version
	public void evictFile(long versionID, String cachepath) {
		if( versionID == -1) return;
		if(masterRcd.containsKey(cachepath)) {	//modify cache ref only link exists
			CacheEle rmv_master =  masterRcd.remove(cachepath);
			remain += rmv_master.filesize;
			System.out.println("	cache: file evict, with new remain"+ String.valueOf(remain));
		}
		(new File(cachepath)).delete();	//if noexists, return false
	}
	// check file version in cache (always master copy). if nonexist, return -1
	// @param: pathname, the CANONICAL cache pathname
	// return: -1 if cannot find the path, versionID if find the object
	public long checkFileVersion(String cachepath) {
		long versionSeries = -1;
		for(String key: masterRcd.keySet()){
			if(key.equals(cachepath)) {
				versionSeries = masterRcd.get(key).fileVersion;
				break;
			}
		}
		return versionSeries;
	}
	// check cache capacity 
	// @params: size is the expected size for cache operation
	// return true if has space, false if no space left
	public boolean checkCapacity(long size) {
		return remain > size? true:false;
	} 
	// check cache capacity 
	// @params: keyPath is the pathname for key stored in masterRcd
	// return true if has space, false if no space left
	public boolean checkCapacity(String keyPath) {
		long size = masterRcd.get(keyPath).filesize;
		return remain > size? true:false;
	} 
	//evict closed master copy when need to update cache returned froms server
	// @params: keyPath is the pathname for key stored in amsterRcd
	// return true if successfully evict, false if fail
	public boolean evictIfClose(String keyPath) {
		if(masterRcd.get(keyPath) != null && masterRcd.get(keyPath).accessNum == 0) {
			CacheEle rmv = masterRcd.remove(keyPath);
			remain += rmv.filesize;
			(new File(rmv.filename)).delete();
			return true;
		}
		return false;
	}
	// evict tmp write file from cache, substract master access if exists master
	// @params: cachepath is the tmp write file name
	// return corresponding master's file size
	public long evictTmpFile(String cachepath) {
		long evictSize = 0;
		CacheEle rmv = tmpRcd.remove(cachepath);
		remain += rmv.filesize;
		String mastercp = rmv.dup.filename;
		if(masterRcd.containsKey(mastercp)) {
			CacheEle master = masterRcd.get(mastercp);
			if(master.fileVersion == rmv.dup.fileVersion) {	//find right master
				master.accessNum--;
			}
			return master.filesize;
		}
		return evictSize;
	}
	// evict cache ele to leave at least 'size' space
	// only master copy can be delete
	// @params: masterpath is the file path that need to be create a temp file
	// return true if evict succeed, false if no intended space left
	public boolean evictGetSpace(String masterpath) {
		long size = masterRcd.get(masterpath).filesize;
		ArrayList<String> masterKeySet = new ArrayList<>(masterRcd.keySet());
		for(String key: masterKeySet) {	//if not itself and not open, evictable
			if(key.equals(masterpath) == false && masterRcd.get(key).accessNum == 0) {
				CacheEle tobeDlt = masterRcd.remove(key);
				remain += tobeDlt.filesize;
				(new File(tobeDlt.filename)).delete();
				System.out.println("	evict master file: " + key);
				if(remain >= size) return true;
			}
		}
		return false;
	}
	// evict cache ele to leave at least 'size' space
	// only master copy can be delete
	// @params: size is the expected file size to execute cache operation
	// return true if evict succeed, false if no intended space left
	public boolean evictGetSpace(long size) {
		ArrayList<String> masterKeySet = new ArrayList<>(masterRcd.keySet());
		for(String key: masterKeySet) {
			if(masterRcd.get(key).accessNum == 0) {	
			//if access num is not zero, someone is access file, cannot evict
				CacheEle tobeDlt = masterRcd.remove(key);
				remain += tobeDlt.filesize;
				(new File(tobeDlt.filename)).delete();
				System.out.println("	evict master file: " + key);
				if(remain >= size) return true;
			}
		}
		return false;
	}
	// read mostupdated fileversion, increate master cp's accessnum by 1
	// @params: originPath is the key in masterRcd, which is the file name for read
	// return real file name for creating RAF object
	public String readMostUpdated(String originPath) {
		CacheEle master = masterRcd.remove(originPath);
		master.accessNum++;
		masterRcd.put(originPath, master);
		return master.filename;
	}
	//check if cuncurrent open happens
	// @params: cachepath is the canonical pathname
	// @params: versionID is the file version number
	//return real file name for cache operation if no need to update
	// or null if need to update
	public String needUpdate(long versionID, String cachepath) {
		if(masterRcd.containsKey(cachepath)) {	
			if(versionID == masterRcd.get(cachepath).fileVersion) {
				System.out.println("	already got most updated during session, add access");
				CacheEle master = masterRcd.remove(cachepath);
				master.accessNum++;
				masterRcd.put(cachepath, master);
				return master.filename;
			}
		}
		return null;
	}
	// create new most updated file version for read
	// move prev version to tmp list, cp file status tp new version
	// add dup class in prev version CacheEle class for linking new/prev file version
	// @params:  versionID is the new file's version ID
	// return filelink(used to copy new content onto)
	public String addMasterFile(String cachepath, long versionID,long filesize) {
		CacheEle master = masterRcd.remove(cachepath);
		//create new master ele
		CacheEle newMaster = new CacheEle(cachepath+"__"
				+String.valueOf(versionID)+"__r", versionID, filesize);
		remain -= filesize;
		//add old ele nto tmp file rcd
		if(master != null) {
			newMaster.accessNum = master.accessNum;   
			CacheEle dup = new CacheEle(cachepath, versionID);
			master.dup = dup;
			tmpRcd.put(master.filename, master);	//add to tmp list		
		}
		masterRcd.put(cachepath, newMaster);
		System.out.println("	master cp has been created, filename:"
			+newMaster.filename+" cache remain: " + String.valueOf(remain));
		return newMaster.filename;
	}
	// create new write reference, add dup,add reference to tmp list(access = 1)
	// update master accessnum (++)
	// @params: masterpath: the key in master list
	// @params: tmpFilepATH: INTENDED file path for temp write file
	// @params: if add access on master before
	// return master file content link
	public String addtmpFile(String masterpath, String tmpFilePath,String mode) {
		CacheEle master = masterRcd.remove(masterpath);
		long version = Long.parseLong((tmpFilePath.split("__"))[1]);
		master.accessNum++;		
		masterRcd.put(masterpath, master);
		//create new tmp write file reference
		CacheEle dup = new CacheEle(masterpath, master.fileVersion);
		CacheEle tmp = new CacheEle(tmpFilePath, version, master.filesize, dup);		
		remain -= master.filesize;
		tmp.accessNum++;
		tmpRcd.put(tmpFilePath, tmp);
		System.out.println("	Cache: new tempfile is created, with name |"
			+ tmpFilePath+"| size: " + String.valueOf(master.filesize)
			+" dup_mster_cp: "+ masterpath);
		return master.filename;
	}
	// delete origin master(if exists), change filename, fileversion, cp over accessnum
	// remove mastercp from reference, create new master, copy status(accessnum)
	// @params: filename is the real file pathname for new file
	// @params: cachepath is the file path in cache hashmap
	// @params: newVersion is the version number of new file
	public void replaceMaster(String filename, String cachepath, long newVersion) {
		long newsize = (new File(filename)).length();
		int access = 0;
		if(masterRcd.containsKey(cachepath)) {
			CacheEle rmv =  masterRcd.remove(cachepath);
			remain += rmv.filesize;
			access = rmv.accessNum;
			(new File(rmv.filename)).delete();
		} 
		CacheEle newMaster = new CacheEle(filename, newVersion, newsize);
		newMaster.accessNum = access;
		masterRcd.put(cachepath, newMaster);
		remain -= newsize;
	}
	// substract access num when read finished
	// if file is in tmp link, also substract its corresponding access num
	// and delete file if its access number is zero
	// @params: filepath is the real file name
	// @params: keyPath is the file name as key in masterRcd list
	public void closeTmpRead(String filepath, String keyPath) {
		CacheEle master = masterRcd.remove(keyPath);	//may be null
		if(master != null) {
			master.accessNum--;
			masterRcd.put(keyPath, master);			
		}
		if(tmpRcd.containsKey(filepath)) {
			CacheEle tmp = tmpRcd.remove(filepath);
			tmp.accessNum--;
			if(tmp.accessNum == 0) {
				(new File(filepath)).delete();
			}else {
				tmpRcd.put(filepath, tmp);
			}
		}
	}
	//print cache for debug purpose
	public void printCache() {
		System.out.println("	Cache: here are the elements");
		for(String key: masterRcd.keySet()) {
			CacheEle rpt = masterRcd.get(key);
			System.out.println("		key: " + key+"  version: " 
				+ String.valueOf(rpt.fileVersion)
				+" "+rpt.accessNum);
		}
		System.out.println("	 TMP FILE report");
		for(String key: tmpRcd.keySet()) {
			CacheEle rpt = tmpRcd.get(key);
			System.out.println("		key: " + key+"  version: " 
				+ String.valueOf(rpt.fileVersion)+" "
				+ rpt.dupfilename+" "+rpt.accessNum);
		}
		System.out.println("	Cache report done.");
	}
	// flat file within cache dir, replace "/" to be "|$|"
	// translate file path to be cache path, in CANONICAL VERISON
	// @params: pathname is the origin pathname
	// return CANONICAL cache path
	public String translateFileName(String pathname) {
		String cachePath = pathname.replace("/","|$|");
		try{
			cachePath =(new File(rootdir+cachePath)).getCanonicalPath();
			return cachePath;
		}catch(Exception e) {
			e.printStackTrace();
		} 
		return cachePath;
	}
	// translate cachepath to origin name 
	// @params: cachepath is the filepath in cache version
	// return original path
	public String returnOrigName(String cachepath) {
		String[] temp_node = cachepath.split("/");
		String [] temp = temp_node[temp_node.length-1].split("__",2);
		String orig = temp[0].replace("|$|","/");
		return orig;
	}
	// get file status
	// @params: istemp, true if in tmpRcd, false if in masterRcd
	// @params: key is used for search in hashmap
	public CacheEle getStatus(boolean istemp, String key) {
		if(istemp) return tmpRcd.get(key);
		return masterRcd.get(key);
	}
}