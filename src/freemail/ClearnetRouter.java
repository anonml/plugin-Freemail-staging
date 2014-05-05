package freemail;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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

public class ClearnetRouter {
	private static final DateFormat dateFormat = new SimpleDateFormat("yyyy MM dd HH mm ss SSS");
	Freemail freemail;
	
	public ClearnetRouter(Freemail freemail) {
		this.freemail = freemail;
	}

	Date lastDate = null;
	
	String host; 
	String localhost; 
	int port;
	String smtpUser;
	String smtpPasswd;
	Session session;
	boolean running;
	Thread th;
	
	public void connect(String localhost, String host, int port, final String smtpUser, final String smtpPasswd)
	{
		this.host = host;
		this.port = port;
		this.smtpUser = smtpUser;
		this.smtpPasswd = smtpPasswd;
		this.localhost = localhost;
		
		Properties props = new Properties();
		props.put("mail.smtp.host", host);
		props.put("mail.smtp.localhost", localhost);
		props.put("mail.smtp.socketFactory.port", "" + port);
		props.put("mail.smtp.socketFactory.class",
				"javax.net.ssl.SSLSocketFactory");
		props.put("mail.smtp.auth", "true");
		props.put("mail.smtp.port", "" + port);
 
		session = Session.getDefaultInstance(props,
			new javax.mail.Authenticator() {
				protected PasswordAuthentication getPasswordAuthentication() {
					return new PasswordAuthentication(smtpUser,smtpPasswd);
				}
			});
 	}
	
	private boolean process(File msgFile)
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
			
			while ( (chunk = filter.readHeader()) != null ) 
			{
				headers.add(chunk);
			}
			
			if (filter.clearnetValue != null)
			{
				System.out.println("sending file '" + msgFile.getName() + "' (" + dateFormat.format(new Date(msgFile.lastModified())) + 
						") to clearnet gate '" + host + "'");
				
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
				//headers.add(filter.getClearnetHeader());
				smtpSend(headers, new BIS(br), filter.contentType);
			}
			return true;
		} catch(Exception e)
		{
			e.printStackTrace();
			return false;
		} finally
		{
			if (fis != null) try{ fis.close(); } catch(Exception ex) {}
			if (fw != null) try{ fw.close(); } catch(Exception ex) {}
		}
	}
	
	public void smtpSend(ArrayList<String> headers, final InputStream is, final String contentType) throws AddressException, MessagingException 
	{
			MimeMessage message = new MimeMessage(session);
			
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

	        message.setDataHandler(new DataHandler(source));
			
			for (String hdr : headers)
			{
				message.addHeaderLine(hdr);
			}
			System.out.println("Sending...");
			Transport.send(message);
			System.out.println("Done");
	}
	
	public static void test(FreemailCli freemail)
	{
		ClearnetRouter router = new ClearnetRouter(freemail);
		router.connect("localhost", "localhost", 1025, "fmail", "xCpPcNPZ5yL4");
		//router.processAll();
		router.startRouting();
	}
	
	public void kill()
	{
		running = false;
	}
	
	public Thread startRouting()
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
						Thread.sleep(3000);
					} catch(Exception e) {}
				}
				System.out.println("mail routing task stopped");
				th = null;
			}
		});
		th.start();
		return th;
	}
	
	public void processAll()
	{
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
			for (File f : files)
			{
				if (f.isDirectory() || f.isHidden() || f.getName().startsWith("."))
					continue;
				Date lm = new Date(f.lastModified());
				if (lastDate == null || lm.after(lastDate) )
				{
					if (!process(f))
						break;
				} 
				
				if (newLastDate == null || lm.after(newLastDate))
					newLastDate = lm;
			}
			
			if (newLastDate != null)
				prop.put("lastdate", dateFormat.format(newLastDate));
		}
	}

}
