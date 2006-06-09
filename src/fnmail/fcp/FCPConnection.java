package fnmail.fcp;

import java.io.OutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;

public class FCPConnection implements Runnable {
	private final FCPContext fcpctx;
	private OutputStream os;
	private InputStream is;
	private Socket conn;
	private int nextMsgId;
	private final HashMap clients;

	public FCPConnection(FCPContext ctx) {
		this.fcpctx = ctx;
		this.clients = new HashMap();
		
		this.tryConnect();
	}
	
	private void tryConnect() {
		if (this.conn != null) return;
		
		try {
			this.nextMsgId = 1;
			this.conn = this.fcpctx.getConn();
			this.is = this.conn.getInputStream();
			this.os = this.conn.getOutputStream();
			
			FCPMessage hello = new FCPMessage(this.nextMsgId, "ClientHello");
			this.nextMsgId++;
			hello.writeto(this.os);
			FCPMessage reply = this.getMessage();
			if (reply.getType() == null) {
				System.out.println("Connection closed");
				this.conn = null;
				return;
			}
			if (!reply.getType().equals("NodeHello")) {
				System.out.println("Warning - got '"+reply.getType()+"' from node, expecting 'NodeHello'");
			}
		} catch (IOException ioe) {
			this.conn = null;
			this.is = null;
			this.os = null;
			return;
		} catch (FCPBadFileException bfe) {
			// won't be thrown from a hello, so should really
			// never get here!
		}
	}
	
	public void run() {
		while (true) {
			try {
				this.tryConnect();
				if (this.conn == null) throw new IOException();
				
				FCPMessage msg = this.getMessage();
				if (msg.getType() == null) throw new IOException("Connection closed");
				this.dispatch(msg);
			} catch (IOException ioe) {
				this.conn = null;
				this.os = null;
				this.is = null;
				// tell all our clients it's all over
				Iterator i = this.clients.values().iterator();
				while (i.hasNext()) {
					FCPClient cli = (FCPClient)i.next();
					cli.requestFinished(new FCPMessage(1, "ConnectionClosed"));
				}
				this.clients.clear();
				// wait a bit
				try {
					Thread.sleep(10000);
				} catch (InterruptedException ie) {
				}
			}
		}
	}

	protected void finalize() throws Throwable {
		try {
			this.conn.close();
		} catch (Exception e) {
		}
		super.finalize();
	}
	
	public synchronized void doRequest(FCPClient cli, FCPMessage msg) throws NoNodeConnectionException, FCPBadFileException {
		if (this.os == null) throw new NoNodeConnectionException("No Connection");
		this.clients.put(msg.getId(), cli);
		try {
			msg.writeto(this.os);
		} catch (IOException ioe) {
			throw new NoNodeConnectionException(ioe.getMessage());
		}
	}
	
	private void dispatch(FCPMessage msg) {
		FCPClient cli = (FCPClient)this.clients.get(msg.getId());
		if (cli == null) {
			// normally we'd leave it up to the client
			// to delete any data, but it looks like
			// we'll have to do it
			msg.release();
			return;
		}
		if (msg.isCompletionMessage()) {
			this.clients.remove(msg.getId());
			cli.requestFinished(msg);
		} else {
			cli.requestStatus(msg);
		}
	}
	
	public synchronized FCPMessage getMessage(String type) {
		FCPMessage m = new FCPMessage(this.nextMsgId, type);
		this.nextMsgId++;
		return m;
	}
	
	private FCPMessage getMessage() throws IOException {
		return new FCPMessage(this.is);
	}
}
