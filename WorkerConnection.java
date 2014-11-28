package mapreduce;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorkerConnection extends Thread {

    protected Socket clientSocket;
    protected final int id;
	protected BufferedReader in;
	protected OutputStream out;
	protected boolean stopped = false;
	protected Master master;
	protected ExecutorService outQueue;

    public WorkerConnection(Master master, Socket clientSocket, int id) throws IOException {
        this.clientSocket = clientSocket;
        out = clientSocket.getOutputStream();
        in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
		outQueue = Executors.newCachedThreadPool();
        this.master = master;
        this.id = id;
    }
    
    public void writeWorker(final String arg) {
    	outQueue.execute(new Runnable() {
			@Override
			public void run() {
		    	try {
		    		out.write(arg.getBytes());
		    	} catch (IOException e) {
		    		System.err.println("Error writing to Worker " + id + ": closing connection.");
		    		closeConnection();
		    	}				
			}
    	});
    }
    
    public synchronized void closeConnection() {

    	stopped = true;
    	master.remove(id);
    	try {
    		outQueue.shutdown();
    		if (!clientSocket.isClosed()) {
    			out.write("q".getBytes());
    			clientSocket.close();
    		}
    		in.close();
    		out.close();
    	} catch (IOException e) { }  //ignore exceptions since you are closing it anyways
    }
    
    public synchronized boolean isStopped() {
    	return stopped;
    }
    
    @Override
    public String toString() {
    	return "Worker " + this.id + ": " + clientSocket.toString();
    }

    /**
     * This is the loop that listens to the socket for messages from this particular Worker
     */
    public void run() {
    	String command;
    	while(!isStopped()) {
    		try {
    			if ( (command = in.readLine()) != null)
    				master.receive(command, id);
			} catch (IOException e) {
				if (isStopped()) // exception is expected when the connection is first closed
					return;
				System.err.println("Error in socket connection to Worker " + id + ": removing worker from cluster");
				this.closeConnection();
			}
    	}
    }
}