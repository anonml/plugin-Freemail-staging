/*
 * MessageSender.java
 * This file is part of Freemail
 * Copyright (C) 2006,2008 Dave Baker
 * Copyright (C) 2007 Alexander Lehmann
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package freemail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Vector;
import java.util.Enumeration;

import freemail.utils.EmailAddress;
import freemail.fcp.ConnectionTerminatedException;
import freemail.utils.Logger;

public class MessageSender implements Runnable {
	/**
	 * Whether the thread this service runs in should stop.
	 */
	protected volatile boolean stopping = false;

	public static final String OUTBOX_DIR = "outbox";
	private static final int MIN_RUN_TIME = 60000;
	private static final int MAX_TRIES = 10;
	private final AccountManager accountManager;
	private Thread senderthread = null;
	private static final String ATTR_SEP_CHAR = "_";
	public static final String CLEARNET_TO_HEADER = "X-Clearnet-To";
	public static final String FREENET_FROM_HEADER = "X-Freenet-From";
	private String clearnetGateway = null;
	
	
	public void setClearnetGateway(String clearnetGateway) {
		this.clearnetGateway = clearnetGateway;
		Logger.normal(MessageSender.class, "clearnet gateway set to \"" + this.clearnetGateway + "\"" );
	}
		
	public String getClearnetGateway() {
		return clearnetGateway;
	}

	public MessageSender(AccountManager accMgr) {
		accountManager = accMgr;
	}
	
	public void sendMessage(FreemailAccount fromAccount, Vector<EmailAddress> to, File msg) throws IOException {
		File outbox = new File(fromAccount.getAccountDir(), OUTBOX_DIR);
		
		Enumeration<EmailAddress> e = to.elements();
		while (e.hasMoreElements()) {
			EmailAddress email = e.nextElement();
			String rcpt = email.user + "@" + email.domain;

			// redirection to a "gate way" node, if one is set up
			// and recipient is not a freemail address 
			if ((clearnetGateway != null) &&
				    (!email.is_freemail_address()))
			{
			     this.copyToOutbox(msg, outbox, clearnetGateway, rcpt, 
			    		 fromAccount.getUsername()+"@"+AccountManager.getFreemailDomain(fromAccount.getProps()));
			} else
			{
				this.copyToOutbox(msg, outbox, rcpt, null, null); 
			}
		}
		this.senderthread.interrupt();
	}
	
	private void copyToOutbox(File src, File outbox, String to, String clearnetTo, String freenetFrom) throws IOException {
		File tempfile = File.createTempFile("fmail-msg-tmp", null, Freemail.getTempDir());
		
		FileOutputStream fos = new FileOutputStream(tempfile);
		FileInputStream fis = new FileInputStream(src);
		
		if (clearnetTo != null)
		{
			// add a header to message. this is assumed to be an internet email address
			byte [] prefix = (CLEARNET_TO_HEADER + ": " + clearnetTo + "\r\n").getBytes();
			fos.write(prefix);
			Logger.normal(MessageSender.class, clearnetTo + " redirected to " + to);
		}
		
		if (freenetFrom != null)
		{
			byte [] prefix = (FREENET_FROM_HEADER + ": " + freenetFrom + "\r\n").getBytes();
			fos.write(prefix);
		}
		
		byte[] buf = new byte[1024];
		int read;
		while ( (read = fis.read(buf)) > 0) {
			fos.write(buf, 0, read);
		}
		fis.close();
		fos.close();
		
		this.moveToOutbox(tempfile, 0, to, outbox);
	}
	
	// save a file to the outbox handling name collisions and atomicity
	private void moveToOutbox(File f, int tries, String to, File outbox) {
		File destfile;
		int prefix = 1;
		synchronized (this.senderthread) {
			do {
				String filename = prefix + ATTR_SEP_CHAR + tries + ATTR_SEP_CHAR + to;
				destfile = new File(outbox, filename);
				prefix++;
			} while (destfile.exists());
			
			f.renameTo(destfile);
		}
	}
	
	@Override
	public void run() {
		this.senderthread = Thread.currentThread();
		while (!stopping) {
			long start = System.currentTimeMillis();
			
			// iterate through users
			Iterator<FreemailAccount> i = accountManager.getAllAccounts().iterator();
			while (i.hasNext()) {
				if(stopping) break;
				FreemailAccount acc = i.next();
				
				File outbox = new File(acc.getAccountDir(), OUTBOX_DIR);
				if (!outbox.exists()) outbox.mkdir();
				
				try {
					this.sendDir(acc, outbox);
				} catch (ConnectionTerminatedException cte) {
					return;
				} catch (InterruptedException e) {
					Logger.debug(this, "Sender thread interrupted, stopping");
					kill();
					return;
				}
			}

			long runtime = System.currentTimeMillis() - start;
			
			if (MIN_RUN_TIME - runtime > 0 && !stopping) {
				try {
					Thread.sleep(MIN_RUN_TIME - runtime);
				} catch (InterruptedException ie) {
				}
			}
		}
	}

	/**
	 * Terminate the run method
	 */
	public void kill() {
		stopping = true;
		if (senderthread != null) senderthread.interrupt();
	}
	
	private void sendDir(FreemailAccount fromAccount, File dir) throws ConnectionTerminatedException,
	                                                                   InterruptedException {
		if (dir == null) return;
		File[] files = dir.listFiles();
		for (int i = 0; i < files.length; i++) {
			if (files[i].getName().startsWith("."))
				continue;
			
			this.sendSingle(fromAccount, files[i]);
		}
	}
	
	private void sendSingle(FreemailAccount fromAccount, File msg) throws ConnectionTerminatedException,
	                                                                      InterruptedException {
		String parts[] = msg.getName().split(ATTR_SEP_CHAR, 3);
		EmailAddress addr;
		int tries;
		if (parts.length < 3) {
			Logger.error(this,"Warning invalid file in outbox - deleting.");
			msg.delete();
			return;
		} else {
			tries = Integer.parseInt(parts[1]);
			addr = new EmailAddress(parts[2]);
		}
		
		if (addr.domain == null || addr.domain.length() == 0) {
			msg.delete();
			return;
		}
		
		if (this.sendSecure(fromAccount, addr, msg)) {
			msg.delete();
		} else {
			tries++;
			if (tries > MAX_TRIES) {
				if (Postman.bounceMessage(msg, fromAccount.getMessageBank(),
						"Tried too many times to deliver this message, but it doesn't apear that this address even exists. "
								+"If you're sure that it does, check your Freenet connection.")) {
					msg.delete();
				}
			} else {
				this.moveToOutbox(msg, tries, parts[2], msg.getParentFile());
			}
		}
	}
	
	private boolean sendSecure(FreemailAccount fromAccount, EmailAddress addr, File msg) throws ConnectionTerminatedException,
	                                                                                            InterruptedException {
		Logger.normal(this,"sending secure");
		OutboundContact ct;
		try {
			ct = new OutboundContact(fromAccount, addr);
		} catch (BadFreemailAddressException bfae) {
			// bounce
			return Postman.bounceMessage(msg, fromAccount.getMessageBank(), "The address that this message was destined for ("+addr+") is not a valid Freemail address.");
		} catch (OutboundContactFatalException obfe) {
			// bounce
			return Postman.bounceMessage(msg, fromAccount.getMessageBank(), obfe.getMessage());
		} catch (IOException ioe) {
			// couldn't get the mailsite - try again if you're not ready
			//to give up yet
			return false; 
		}
		
		if (clearnetGateway != null)
		{
			ct.setClearnetToHeader(CLEARNET_TO_HEADER);
			ct.setFreenetFromHeader(FREENET_FROM_HEADER);
		}
		
		return ct.sendMessage(msg);
	}
}
