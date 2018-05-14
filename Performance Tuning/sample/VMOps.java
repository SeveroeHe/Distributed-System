import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.*;
import java.util.concurrent.*;

// master server interface 
public interface VMOps extends Remote {
	public int getLevelNums(int level) throws RemoteException;
	public int getLevel(int VMid) throws RemoteException;
	public int getTaskNum() throws RemoteException;
	public void addTasks(Cloud.FrontEndOps.Request r) throws RemoteException;
	public Cloud.FrontEndOps.Request pollTasks() throws RemoteException;
	public void scaleIn(int VMid, int level) throws RemoteException;
	public void scaleOut(int level) throws RemoteException;	
	public void unexport() throws RemoteException;
	public void ifMTDrop(boolean dropTask) throws RemoteException;
}
