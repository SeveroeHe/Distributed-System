
import java.io.*;

//wrapper class used to serialize/deserialize message class
public class ProcessMsg implements Serializable{
	//serialize a class object when tryig to send msg
    //@params: obj is a message object need to serialize
    // return byte array
	public static byte[] serialize(Object obj) {
        try {
            ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            ObjectOutputStream objectOut = new ObjectOutputStream(byteOut);
            objectOut.writeObject(obj);
            return byteOut.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    //deserailize object when receiving it
    //@params: bytesObj is the byte string need to be deserialized
    // return a normal message class
    public static Msg deserialize(byte[] bytesObj)  {
    	try {
    		ByteArrayInputStream byteIn = new ByteArrayInputStream(bytesObj);
    		ObjectInputStream objectIn = new ObjectInputStream(byteIn);
    		return (Msg)objectIn.readObject();
    	} catch (Exception e) {
    		e.printStackTrace();
            return null;
    	}
    }
}