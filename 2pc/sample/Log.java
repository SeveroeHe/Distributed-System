import java.io.*;
import java.util.*;

// class used to write log
public class Log{
	//write log to directory
	// @params: PL is the projectlib interface
	// @params: path is the log file path
	// @params: content is the log content
	public static void writeLog(ProjectLib PL, String path, String content){
		try{
			FileWriter fw = new FileWriter(path,true);
			fw.write(content+"\n");
			fw.flush();
			fw.close();
			System.out.println("log write finished");
			PL.fsync();
		}catch(Exception e) {
			e.printStackTrace();
		}
	}
	// read log from log file to entity
	// @params: path is the log file path
	public static Map<String,List<String>> readLog(String path){
		Map<String,List<String>> info = new HashMap<>();
		String strline = null;
		try{
			BufferedReader br = new BufferedReader(new FileReader(path));
			while ((strline = br.readLine()) != null) {
				String[] content = strline.split(",");
				if(info.containsKey(content[0])) {
					info.get(content[0]).add(content[1]);
				}else {
					info.put(content[0],new ArrayList<String>(Arrays.asList(content[1])));
				}
			}
			br.close();
			System.out.println("log read finished");
		}catch(Exception e) {
			e.printStackTrace();
		}
		return info;
	}

}