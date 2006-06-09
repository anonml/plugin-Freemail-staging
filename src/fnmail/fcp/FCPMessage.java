package fnmail.fcp;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Enumeration;
import java.util.Collections;

import fnmail.FNMail;

import freenet.support.io.LineReader;
import freenet.support.io.LineReadingInputStream;

public class FCPMessage {
	private String messagetype;
	private String identifier;
	public final HashMap headers;
	private File data;
	private InputStream outData;
	
	
	public FCPMessage(int id, String type) {
		this.identifier = Integer.toString(id);
		this.headers = new HashMap();
		this.messagetype = type;
		this.data = null;
		this.outData = null;
	}
	
	public FCPMessage(InputStream is) throws IOException {
		this.headers = new HashMap();
		this.outData = null;
		
		this.messagetype = null;
		LineReader r = new LineReadingInputStream(is);
		
		String line;
		while ( (line = r.readLine(200, 200)) != null) {
			/***************************************/
			//System.out.println(line);
			if (this.messagetype == null) {
				this.messagetype = line;
			} else if (line.startsWith("End")) {
				return;
			} else if (line.equals("Data")) {
				try {
					int len = Integer.decode((String)this.headers.get("DataLength")).intValue();
					this.readData(is, len);
				} catch (NumberFormatException nfe) {
				}
				return;
			} else {
				String[] parts = line.split("=");
				if (parts.length == 2)
					this.addHeader(parts[0], parts[1]);
			}
		}
	}
	
	private void addHeader(String name, String val) {
		if (name.equalsIgnoreCase("Identifier")) {
			this.identifier = val;
		} else {
			this.headers.put(name, val);
		}
	}
	
	public String getType() {
		return this.messagetype;
	}
	
	public String getId() {
		return this.identifier;
	}
	
	public File getData() {
		return this.data;
	}
	
	public void setData(InputStream d) {
		this.outData = d;
	}
	
	private void readData(InputStream is, int len) {
		try {
			this.data = File.createTempFile("fnmail-fcp", null, FNMail.getTempDir());
		} catch (Exception e) {
			this.data = null;
			return;
		}
		try {
			FileOutputStream fos = new FileOutputStream(this.data);
			
			byte[] buf = new byte[1024];
			while (len > 0) {
				int toRead = len;
				if (toRead > buf.length)
					toRead = buf.length;
				int read = is.read(buf, 0, toRead);
 				fos.write(buf, 0, read);
				len -= read;
			}
			fos.close();
		} catch (IOException ioe) {
			this.data = null;
			return;
		}
	}
	
	public boolean isCompletionMessage() {
		if (this.messagetype.equalsIgnoreCase("PutFailed"))
			return true;
		if (this.messagetype.equalsIgnoreCase("PutSuccessful"))
			return true;
		if (this.messagetype.equalsIgnoreCase("AllData"))
			return true;
		if (this.messagetype.equalsIgnoreCase("GetFailed"))
			return true;
		if (this.messagetype.equalsIgnoreCase("ProtocolError"))
			return true;
		if (this.messagetype.equalsIgnoreCase("SSKKeypair"))
			return true;
		if (this.messagetype.equalsIgnoreCase("IdentifierCollision"))
			return true;
		return false;
	}
	
	public void release() {
		if (this.data != null) {
			this.data.delete();
		}
	}
	
	public void writeto(OutputStream os) throws IOException, FCPBadFileException {
		StringBuffer buf = new StringBuffer();
		
		buf.append(this.messagetype);
		buf.append("\r\n");
		
		if (this.messagetype.equalsIgnoreCase("ClientHello")) {
			buf.append("Name=fnmail\r\n");
			buf.append("ExpectedVersion=2.0\r\n");
		}
		
		buf.append("Identifier="+this.identifier+"\r\n");
		
		for (Enumeration e = Collections.enumeration(this.headers.keySet()); e.hasMoreElements(); ) {
			String hdr = (String) e.nextElement();
			String val = (String) this.headers.get(hdr);
			
			buf.append(hdr+"="+val+"\r\n");
		}
		
		if (this.outData != null) {
			buf.append("UploadFrom=direct\r\n");
			try {
				buf.append("DataLength="+this.outData.available()+"\r\n");
			} catch (IOException ioe) {
				throw new FCPBadFileException();
			}
			buf.append("Data\r\n");
		} else {
			buf.append("EndMessage\r\n");
		}
		if (buf.length() > 0) {
			//System.out.println(buf.toString());
			os.write(buf.toString().getBytes());
		}
		if (this.outData != null) {
			byte[] bytebuf = new byte[1024];
			
			int read;
			while ( (read = this.outData.read(bytebuf)) > 0) {
				os.write(bytebuf, 0, read);
			}
			this.outData.close();
		}
	}
}
