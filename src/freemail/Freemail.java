/*
 * Freemail.java
 * This file is part of Freemail
 * Copyright (C) 2006,2007,2008 Dave Baker
 * Copyright (C) 2007,2009 Matthew Toseland
 * Copyright (C) 2008 Alexander Lehmann
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;

import freemail.fcp.FCPConnection;
import freemail.fcp.FCPContext;
import freemail.imap.IMAPListener;
import freemail.smtp.SMTPListener;
import freemail.utils.Logger;
import freemail.config.ConfigClient;
import freemail.config.Configurator;
import freenet.node.fcp.GetPluginInfo;

public abstract class Freemail implements ConfigClient {
	private static final String TEMPDIRNAME = "temp";
	protected static final String DEFAULT_DATADIR = "data";
	private static final String GLOBALDATADIR = "globaldata";
	private static final String ACKDIR = "delayedacks";
	protected static final String CFGFILE = "globalconfig";
	public static final String CLEARNET_GATEWAY = "clearnet_gateway";
	public static final String CLEARNET_GATEWAY_SMTP_HOST = "smtp_gateway";
	public static final String SMTP_HOST_SSL = "smtp_gateway_ssl";
	
	private File datadir;
	private static File globaldatadir;
	private static File tempdir;
	protected static FCPConnection fcpconn = null;
	
	private Thread fcpThread;
	private ArrayList<Thread> singleAccountWatcherThreadList = new ArrayList<Thread>();
	private Thread messageSenderThread;
	private Thread smtpThread;
	private Thread ackInserterThread;
	private Thread imapThread;
	private Thread clearnetRouterThread;
	
	private final AccountManager accountManager;
	private final ArrayList<SingleAccountWatcher> singleAccountWatcherList = new ArrayList<SingleAccountWatcher>();
	private final MessageSender sender;
	private final SMTPListener smtpl;
	private final AckProcrastinator ackinserter;
	private final IMAPListener imapl;
	private final ClearnetRouter clearnetRouter;
	
	protected final Configurator configurator;
	private String clearnetGateway = null;
	private String clearnetGatewaySmtpHost = null;
	private boolean smtpSSL = true;

	
	protected Freemail(String cfgfile) throws IOException {
		configurator = new Configurator(new File(cfgfile));
		
		configurator.register(Configurator.LOG_LEVEL, new Logger(), "normal|error");
		configurator.register(CLEARNET_GATEWAY, this, clearnetGateway);
		configurator.register(CLEARNET_GATEWAY_SMTP_HOST, this, clearnetGatewaySmtpHost);
		configurator.register(SMTP_HOST_SSL, this, "true");
		
		configurator.register(Configurator.DATA_DIR, this, Freemail.DEFAULT_DATADIR);
		if (!datadir.exists() && !datadir.mkdirs()) {
			Logger.error(this,"Freemail: Couldn't create data directory. Please ensure that the user you are running Freemail as has write access to its working directory");
			throw new IOException("Couldn't create data dir");
		}
		
		configurator.register(Configurator.GLOBAL_DATA_DIR, this, GLOBALDATADIR);
		if (!globaldatadir.exists() && !globaldatadir.mkdirs()) {
			Logger.error(this,"Freemail: Couldn't create global data directory. Please ensure that the user you are running Freemail as has write access to its working directory");
			throw new IOException("Couldn't create data dir");
		}
		
		configurator.register(Configurator.TEMP_DIR, this, Freemail.TEMPDIRNAME);
		if (!tempdir.exists() && !tempdir.mkdirs()) {
			Logger.error(this,"Freemail: Couldn't create temporary directory. Please ensure that the user you are running Freemail as has write access to its working directory");
			throw new IOException("Couldn't create data dir");
		}
		
		FCPContext fcpctx = new FCPContext();
		configurator.register(Configurator.FCP_HOST, fcpctx, "localhost");
		configurator.register(Configurator.FCP_PORT, fcpctx, "9481");
		
		Freemail.fcpconn = new FCPConnection(fcpctx);
		
		accountManager = new AccountManager(datadir);
		
		sender = new MessageSender(accountManager);
		sender.setClearnetGateway(clearnetGateway);		

		File ackdir = new File(globaldatadir, ACKDIR);
		AckProcrastinator.setAckDir(ackdir);
		ackinserter = new AckProcrastinator();
		
		imapl = new IMAPListener(accountManager, configurator);
		smtpl = new SMTPListener(accountManager, sender, configurator);
		
		clearnetRouter = new ClearnetRouter(this);
	}
	
	public static File getTempDir() {
		return Freemail.tempdir;
	}
	
	public static FCPConnection getFCPConnection() {
		return Freemail.fcpconn;
	}
	
	public AccountManager getAccountManager() {
		return accountManager;
	}

	@Override
	public void setConfigProp(String key, String val) {
		if (key.equalsIgnoreCase(Configurator.DATA_DIR)) {
			datadir = new File(val);
		} else if (key.equalsIgnoreCase(Configurator.TEMP_DIR)) {
			tempdir = new File(val);
		} else if (key.equalsIgnoreCase(Configurator.GLOBAL_DATA_DIR)) {
			globaldatadir = new File(val);
		} else if (key.equals(CLEARNET_GATEWAY)) {
			clearnetGateway = val;
		} else if (key.equals(CLEARNET_GATEWAY_SMTP_HOST)) {
			clearnetGatewaySmtpHost = val;
		} else if (key.equals(SMTP_HOST_SSL)) {
			if ("true".equalsIgnoreCase(val))
			    smtpSSL = true;
		    else
		    	smtpSSL = false;
		}
	}
	
	protected void startFcp() {
		fcpThread = new Thread(fcpconn, "Freemail FCP Connection");
		fcpThread.setDaemon(true);
		fcpThread.start();
	}
	
	/**
	 * Starts {@link ClearnetRouter} threaded process. 
	 */
	protected void startClearnetRouter()
	{
		if (clearnetGatewaySmtpHost == null) return;
		
		String pts[] = clearnetGatewaySmtpHost.split(":");
		int port = 25;
		if (pts.length > 1)
		{
			try
			{
				port = new Integer(pts[1]);
			}catch(Exception e)
			{
				System.out.println("Error parsing clearnet gateway smtp server: '" + clearnetGatewaySmtpHost + "'. Using " + pts[0] + ":" + port);
			}
		}
		clearnetRouter.init(pts[0], port, null, null, smtpSSL);
		clearnetRouterThread = clearnetRouter.startRouting(3000);
	}
	
	// note that this relies on sender being initialized
	// (so startWorkers has to be called before)
	protected void startServers(boolean daemon) {
		// start the SMTP Listener
		
		smtpThread = new Thread(smtpl, "Freemail SMTP Listener");
		smtpThread.setDaemon(daemon);
		smtpThread.start();
		
		// start the IMAP listener
		imapThread = new Thread(imapl, "Freemail IMAP Listener");
		imapThread.setDaemon(daemon);
		imapThread.start();
	}
	
	protected void startWorker(FreemailAccount account, boolean daemon) {
		SingleAccountWatcher saw = new SingleAccountWatcher(account); 
		singleAccountWatcherList.add(saw);
		Thread t = new Thread(saw, "Freemail Account Watcher for "+account.getUsername());
		t.setDaemon(daemon);
		t.start();
		singleAccountWatcherThreadList.add(t);
	}
	
	protected void startWorkers(boolean daemon) {
		System.out.println("This is Freemail version "+Version.getVersionString());
		System.out.println("Freemail is released under the terms of the GNU General Public License. Freemail is provided WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. For details, see the LICENSE file included with this distribution.");
		System.out.println("");
		
		// start a SingleAccountWatcher for each account
		Iterator<FreemailAccount> i = accountManager.getAllAccounts().iterator();
		while (i.hasNext()) {
			FreemailAccount acc = i.next();
			
			startWorker(acc, daemon);
		}
		
		// start the sender thread
		messageSenderThread = new Thread(sender, "Freemail Message sender");
		messageSenderThread.setDaemon(daemon);
		messageSenderThread.start();
		
		// start the delayed ACK inserter
		ackInserterThread = new Thread(ackinserter, "Freemail Delayed ACK Inserter");
		ackInserterThread.setDaemon(daemon);
		ackInserterThread.start();
	}
	
	public void terminate() {
		long start = System.nanoTime();
		Iterator<SingleAccountWatcher> it = singleAccountWatcherList.iterator();
		while(it.hasNext()) {
			it.next().kill();
			it.remove();
		}
		long end = System.nanoTime();
		Logger.debug(this, "Spent " + (end - start) + "ns killing SingleAccountWatchers");

		start = System.nanoTime();
		sender.kill();
		ackinserter.kill();
		smtpl.kill();
		imapl.kill();
		// now kill the FCP thread - that's what all the other threads will be waiting on
		fcpconn.kill();
		clearnetRouter.kill();
		end = System.nanoTime();
		Logger.debug(this, "Spent " + (end - start) + "ns killing other threads");
		
		// now clean up all the threads
		boolean cleanedUp = false;
		while (!cleanedUp) {
			try {
				start = System.nanoTime();
				Iterator<Thread> threadIt = singleAccountWatcherThreadList.iterator();
				while(it.hasNext()) {
					threadIt.next().join();
					threadIt.remove();
				}
				end = System.nanoTime();
				Logger.debug(this, "Spent " + (end - start) + "ns joining SingleAccountWatchers");
				
				start = System.nanoTime();
				if (messageSenderThread != null) {
					messageSenderThread.join();
					messageSenderThread = null;
				}
				if (ackInserterThread != null) {
					ackInserterThread.join();
					ackInserterThread = null;
				}
				if (smtpThread != null) {
					smtpThread.join();
					smtpl.joinClientThreads();
					smtpThread = null;
				}
				if (imapThread != null) {
					imapThread.join();
					imapl.joinClientThreads();
					imapThread = null;
				}
				if (fcpThread != null) {
					fcpThread.join();
					fcpThread = null;
				}
				if (clearnetRouterThread != null) {
					clearnetRouterThread.join();
					clearnetRouterThread = null;
				}
				end = System.nanoTime();
				Logger.debug(this, "Spent " + (end - start) + "ns joining other threads");
			} catch (InterruptedException ie) {
				
			}
			cleanedUp = true;
		}
	}
}


