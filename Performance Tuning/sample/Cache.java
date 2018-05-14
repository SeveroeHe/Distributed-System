import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


// cache class implements cloud database interface
public class Cache extends UnicastRemoteObject implements Cloud.DatabaseOps{
	private Map<String, String> map; //container restoring get request
	private ServerLib SL;
	private Cloud.DatabaseOps database;

	// constructor, initialize map and cloud database reference
	public Cache(ServerLib SL) throws RemoteException {
		super();
		map = new ConcurrentHashMap<>();
		database = SL.getDB();
	}

	// get elements from db, store in cache
	// @params: key is the tasks key used to retrieve value
	// return value
	@Override
	public String get(String key)  throws RemoteException {
		if(map.containsKey(key)) {
			return map.get(key);
		}
		String value = database.get(key);
		map.put(key, value);
		return value;
	}

	//pass a set request to database
	@Override
	public boolean set(String arg0, String arg1, String arg2) throws RemoteException {
		return database.set(arg0,arg1,arg2);
	}
	//transfer a real transaction to database
	@Override
	public boolean transaction(String arg0, float arg1, int arg2) throws RemoteException {
		return database.transaction(arg0, arg1, arg2);
	}
}