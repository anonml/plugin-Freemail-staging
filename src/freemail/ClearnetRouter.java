package freemail;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import freemail.utils.EmailAddress;
import freemail.utils.PropsFile;

/**
 * Clearnet router implements a single threaded process that looks into inboxes of all accounts in freemail node
 * and looks for mails with X-Clearnet-To header. It sends/forwards all found messages
 * to defined smtp server. Messages are then copied to the folder "forwarded". 
 * @author karol presovsky
 *
 */
public class ClearnetRouter {
	public static final String FWD_BOX = "forwarded";
	public static final String FAILED_BOX = "failed";

	private static final DateFormat dateFormat = new SimpleDateFormat("yyyy MM dd HH mm ss SSS");
	Freemail freemail;
	
	public ClearnetRouter(Freemail freemail) {
		this.freemail = freemail;
	}

	Date lastDate = null;
	
	String host; 
	int port;
	String smtpUser;
	String smtpPasswd;
	Session session;
	boolean running;
	Thread th = null;
	
	
	/*
	 * no ssl, no authentication
	 */
	public void init(String host, int port)
	{
		init(host, port, null, null, false);
	}
	
	public void init(String host, int port, final String smtpUser, final String smtpPasswd, boolean ssl)
	{
		this.host = host;
		this.port = port;
		this.smtpUser = smtpUser;
		this.smtpPasswd = smtpPasswd;
		
		Properties props = new Properties();
		props.put("mail.smtp.host", host);
		props.put("mail.smtp.socketFactory.port", "" + port);
		if (ssl)
		{
			props.put("mail.smtp.socketFactory.class",
					"javax.net.ssl.SSLSocketFactory");
		}
		props.put("mail.smtp.auth", (smtpUser != null ? "true":"false"));
		props.put("mail.smtp.port", "" + port);
 
		javax.mail.Authenticator authenticator = null;
		if (smtpUser != null)
		{
			authenticator = new javax.mail.Authenticator() {
				protected PasswordAuthentication getPasswordAuthentication() {
					return new PasswordAuthentication(smtpUser,smtpPasswd);
				}
			};
		}
		
		session = Session.getDefaultInstance(props,authenticator);
 	}
	
	
	/**
	 * looks for X-Clearnet-To header in file. if found, sends it. 
	 * @param msgFile
	 * @return true, if successfully sent.
	 * @throws IOException 
	 * @throws MessagingException 
	 * @throws AddressException 
	 */
	private boolean process(File msgFile) throws IOException, MessagingException
	{
		ArrayList<String> headers = new ArrayList<String>();
		FileInputStream fis = null;

		FileWriter fw = null;
		
		try
		{
			fis = new FileInputStream(msgFile);
		//	System.out.println("FIS available 1:" + fis.available());
			
			BufferedReader br = new BufferedReader(new InputStreamReader(fis));
			HeaderFilter_ClearnetExt filter = new HeaderFilter_ClearnetExt(br);
			String chunk;
			
			// parse headers and store them to a list
			while ( (chunk = filter.readHeader()) != null ) 
			{
				headers.add(chunk);
			}
			
			// do forward it?
			if (filter.clearnetValue != null)
			{
				//System.out.println("sending file '" + msgFile.getName() + "' (" + dateFormat.format(new Date(msgFile.lastModified())) + 
					//	") to clearnet gate '" + host + "'");
				
				// smtp send needs input stream to read a message content from. but buffered reader pre-buffers data from input stream. 
				// we have to create a stream, that will use this buffered reader
				class BIS extends InputStream
				{
					BufferedReader br;
					
					public BIS(BufferedReader br) {
						super();
						this.br = br;
					}

					@Override
					public int read() throws IOException {
						return br.read();
					}
				};
				// send it
				smtpSend(headers, new BIS(br), filter.contentType);
				
				// successfully sent
				return true;
			}
			
			// nothing sent
			return false;
			
		} finally
		{
			if (fis != null) try{ fis.close(); } catch(Exception ex) {}
			if (fw != null) try{ fw.close(); } catch(Exception ex) {}
		}
	}

	/**
	 * Sends a message via smtp. 
	 * @param headers list of headers for message
	 * @param is message content input stream. 
	 * @param contentType 
	 * @throws AddressException
	 * @throws MessagingException
	 */
	private void smtpSend(ArrayList<String> headers, final InputStream is, final String contentType) throws AddressException, MessagingException 
	{
			MimeMessage message = new MimeMessage(session);
			
			// message content data source
	        DataSource source = new DataSource()
	        {
				@Override
				public String getContentType() {
					return contentType;
				}

				@Override
				public InputStream getInputStream() throws IOException {
					return is;
				}

				@Override
				public String getName() {
					return "message_content";
				}

				@Override
				public OutputStream getOutputStream() throws IOException {
					throw new IOException("No output stream for this datasource.");
				}
	        };

	        // set data handler for content
	        // this resets headers, so must be done before adding headers
	        message.setDataHandler(new DataHandler(source));
			
	        // feed with headers
			for (String hdr : headers)
			{
				message.addHeaderLine(hdr);
			}
			//System.out.println("Sending...");
			Transport.send(message);
			//System.out.println("Done");
	}
	
	public static void test(FreemailCli freemail)
	{
		ClearnetRouter router = new ClearnetRouter(freemail);
		router.init("localhost", 1025);
		//router.processAll();
		router.startRouting(3000);
	}
	
	/**
	 * Setting break condition for threaded process and thus effectively stops it. 
	 */
	public void kill()
	{
		running = false;
	}
	
	/**
	 * Starts a threaded process that checks inbox. Stop process by calling {@link ClearnetRouter#kill()}
	 *
	 * @param periodInMilis a time period to wait until next check
	 * @return a started thread
	 */
	public Thread startRouting(final int periodInMilis)
	{
		if (th != null)
		{
			System.out.println("Clearnet router thread already started.");
			return th;
		}
		
		th = new Thread(new Runnable()
		{
			@Override
			public void run() {
				System.out.println("mail routing task started");
				running = true;
				while(running)
				{
					processAll();
					try
					{
						Thread.sleep(periodInMilis);
					} catch(Exception e) {}
				}
				System.out.println("mail routing task stopped");
				th = null;
			}
		});
		th.start();
		return th;
	}
	
	
	/**
	 * checks all inboxes in all accounts.
	 */
	public void processAll()
	{
		// a loop for all accounts
		for ( Object ob :  freemail.getAccountManager().getAllAccounts())
		{
			if (!(ob instanceof FreemailAccount)) {
				System.out.println("ups! account of unexpected type: " + ob.getClass().getCanonicalName());
				continue;
			}
			FreemailAccount ac = (FreemailAccount)ob;
		
			File inbox = new File(ac.getAccountDir(), "inbox");
			if ((!inbox.exists()) || (!inbox.isDirectory()))
				continue;
		
			// sets a value for EHLO command to identify sender
			session.getProperties().setProperty("mail.smtp.localhost", AccountManager.getFreemailDomain(ac.getProps()));

			File fwdbox = new File(ac.getAccountDir(), FWD_BOX);
			if (!fwdbox.exists())
			{
				fwdbox.mkdir();
			}
			
			if (!fwdbox.exists() || fwdbox.isFile())
			{
				System.out.println("Can't create dir 'forwarded' for account " + ac.getUsername() + 
						". Skipping forwarding to clearnet smtp gateway for this account.");
				continue;
			}
			
			// read a stored date of last received mail, to skip older messages 
			File cfg = new File(ac.getAccountDir(), "clearnet_router_cfg");
			PropsFile prop = PropsFile.createPropsFile(cfg);
			String lastDateStr = prop.get("lastdate");
			if (lastDateStr != null) {
				try {
					lastDate = dateFormat.parse(lastDateStr);
				} catch(Exception e)
				{
					e.printStackTrace();
				}
			}
			
			File[] files = inbox.listFiles();
			Date newLastDate = lastDate;
			
			// run through inbox messages
			for (File f : files)
			{
				// skip non-messages
				if (f.isDirectory() || f.isHidden() || f.getName().startsWith("."))
					continue;
				
				Date lm = new Date(f.lastModified());
				if (lastDate == null || lm.after(lastDate) )
				{
					File fwdmsg = new File(fwdbox, f.getName());
					if (fwdmsg.exists()) continue; // already forwarded
					try
					{
						// check for clearnet header and send if found
						if (process(f))
						{
							// copy to forwarded box
							try
							{
						        Files.copy( f.toPath(), fwdmsg.toPath());
							} catch(Exception e)
							{
								// break processing messages if one fails
								e.printStackTrace();
							}
						}
					} catch (Exception e)
					{
						e.printStackTrace();
						// save failed for manual resolution
						saveFailed(f, ac.getAccountDir());
					}
				} 
				
				// backup date of last received mail
				if (newLastDate == null || lm.after(newLastDate))
					newLastDate = lm;
			}

			// save date of last received mail
			if (newLastDate != null)
				prop.put("lastdate", dateFormat.format(newLastDate));
		}
	}
	
	/**
	 * saves message file from account folder to "failed" sub-folder. Creates one if does not exist. 
	 * @param f
	 * @param accountDir
	 */
	private void saveFailed(File f, File accountDir)
	{
		File failedbox = new File(accountDir, FAILED_BOX);
		if (!failedbox.exists())
		{
			if (!failedbox.mkdir())
			{
				System.out.println("Error: Could not create failed box " + failedbox.getAbsolutePath());
			}
		}
		if (failedbox.exists())
		{
			File failedmsg = new File(failedbox, f.getName());
			if (failedmsg.exists())
			{
				try
				{
				    Files.copy(f.toPath(), failedmsg.toPath());
				    System.out.println("Failed message stored to '" + failedmsg.getAbsolutePath() + "\"");
				} catch(IOException ioe)
				{
					ioe.printStackTrace();
				}
			}
		}
		
	}

}
