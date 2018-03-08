import java.rmi.Remote;
import java.rmi.RemoteException;
import java.io.*;

//RPC interface for server
public interface RPCOp extends Remote{
	public FileMeta getFileMeta_read_write(String pathname, long version) throws RemoteException;
	public FileMeta getFileMeta_create(String pathname, long version) throws RemoteException;
	public FileMeta getFileMeta_createNew(String pathname, long version) throws RemoteException;

	public FileMeta pushModify(FileMeta newFile, String pathname) throws RemoteException;
	public void tmpfileclose(String pathname) throws RemoteException;

	public String openChunkWrite(String filepath) throws RemoteException;
	public FileGetData chunkPushModify(FileGetData filedata, String pathname) throws RemoteException;
	public FileMeta closeChunkWrite(String pathname) throws RemoteException;

	public FileGetData getFileRemain(String pathname, long offset) throws RemoteException;
	public FileMeta unlinkFile(String pathname) throws RemoteException;
}
