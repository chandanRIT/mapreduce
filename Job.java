package mapreduce;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Job<K extends Serializable, V> {

	protected Worker worker;
	protected Mapper<K, V> mr;
	protected HashMap<K, List<V>> mapOutput;
	protected HashMap<K, V> finalOut;
	protected String[] files;
	
	public Job(Worker worker, Mapper<K, V> mr, String...strings) {
		this.worker = worker;
		this.mr = mr;
		this.mr.setJob(this);
		files = strings;
		mapOutput = new HashMap<>();
		finalOut = new HashMap<>();
	}
	
	public void begin() {
		
		// since this is reading from a file don't do it in parallel,
		// speed limit is reading from disk
		for (final String filename : files) 
			// convenience function provided if user doesn't want to call 'emit'
			emit(mr.map(new File(filename)));

		// now the output map has been populated, so it needs to be shuffled and sorted 
		// first notify Master of the keys you have at this node, and their sizes
		sendAllKeysToMaster();
	}
	
	public void reduce() {
		ArrayList<Thread> thrs = new ArrayList<>();
		for(final K key: mapOutput.keySet()) {
			thrs.add(new Thread(new Runnable() {
				public void run() {
					finalOut.put(key, mr.reduce(key, mapOutput.get(key)));
				}
			}));
			thrs.get(thrs.size()-1).start();
		}
		// wait for all the threads to finish
		for(Thread t: thrs){
			try {
				t.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		// TODO this is testing code to be removed,
		for( K key : finalOut.keySet() ) {
			System.out.println("key: " + key + " result: " + finalOut.get(key));
		}
		
		//finalOut holds the results of this MR job, send it to Master
		//worker.writeMaster(Utils.W2M_RESULTS);
		sendResults();
	}
	
	public void emit(K key, V value) {
		if (mapOutput.containsKey(key)) 
			mapOutput.get(key).add(value);
		else {
			List<V> l = new ArrayList<>();
			l.add(value);
			mapOutput.put(key, l);
		}
	}
	
	public void emit(HashMap<K, V> tmp) {
		if (tmp == null)
			return;
		for(K key: tmp.keySet()) 
			emit(key, tmp.get(key));
	}
	
	public void receiveKVAndAggregate (Object k, Object v){
		//wMinusOneCount++;
		K key = (K)k; 
		List<V> valList = (List<V>) v;
		if(mapOutput.containsKey(key)){
			mapOutput.get(key).addAll(valList);
		} else {
			mapOutput.put(key, valList);
		}
		//System.out.println("Recieved from Worker: ");
		//System.out.println("Key: " + key);
		//System.out.println("Key's: type" + key.getClass().getName());
		System.out.println("List<Value>: " + valList); 
	}
	
	public void receiveKeyAssignments() {
		// need key, ip address, and port from Master to fwd your values there
		// K key = mr.parse("F");
		// TODO
		
		// Then send all the P2P traffic...
		// P2P traffic should remove values from output as it is sent and add to it as it
		// is received,
		
		//then once P2P is fished call this.reduce()
		// so output has all the key - list of Values
		try {
			ObjectInputStream objInStream = new ObjectInputStream(worker.in);
			List<Object[]> keyTransferMsgs = (List<Object[]>) objInStream.readObject();
			System.out.println("Received TMsgs: " + keyTransferMsgs);
			//objInStream.close();
			//List<Object[]> msgList = (List<Object[]>)Utils.gson.fromJson(br.readLine(), List.class);
			for (Object[] o : keyTransferMsgs){
				K k = (K)o[0];
				String peerAddress = (String) o[1]; 
				Integer peerPort = (Integer) o[2]; //testing on same machine
				List<V> v = mapOutput.get(k);
				//System.out.println("key" + k);
				//System.out.println("peeraddes" + peerAddress);
				//System.out.println("pp" + peerPort);
				mapOutput.remove(k); //so that only keys assigned to this worker are left in mapOutput
				worker.wP2P.send(k, v, peerAddress, peerPort); //sends key and its value list as object[]
			}
			//A worker sends this message, so that master can keep track of workers who are ready for reduce
			worker.writeMaster(Utils.W2M_KEYSHUFFLED);
		} catch (IOException | ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public void sendAllKeysToMaster() {
		byte[] data;
		for (K key: mapOutput.keySet()) {
			worker.writeMaster(Utils.W2M_KEY);
			data = Utils.concat(mr.getBytes(key), 
					Utils.intToByteArray(mapOutput.get(key).size()));
			worker.writeMaster(data);
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		worker.writeMaster(Utils.W2M_KEY_COMPLETE);
		System.out.println("Keys transferred to Master");
	}
	
	public void sendResults() {
		// TODO send results to Master
		for (Map.Entry<K, V> e : finalOut.entrySet()){
			worker.writeMaster(Utils.W2M_RESULTS);
			System.out.println("Sent results to master");
			worker.writeObjToMaster(new Object[]{e.getKey(), e.getValue()});
		}
		worker.writeMaster(Utils.W2M_JOBDONE);
	}
	
	public void stopExecution() {
		
	}
}
