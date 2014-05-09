package freemail;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import freemail.utils.EmailAddress;


/**
 * A wrapper for {@link MailHeaderFilter} that stores information from headers related to clearnet routing. 
 * @author karol presovsky
 *
 */
public class HeaderFilter_ClearnetExt extends MailHeaderFilter
{

	String clearnetValue;
	String freenetSender;
	String contentType = "application/octet-stream";
	private ArrayList<EmailAddress> clearnetContacts = new ArrayList<EmailAddress>(10);
	private boolean redirected = false;

	
	/**
	 * 
	 * @return a contact from header "X-Freenet-From" or null if there no such header
	 */
	public String getFreenetSender() {
		return freenetSender;
	}

	public HeaderFilter_ClearnetExt(BufferedReader rdr) {
		super(rdr);
		
		addCustomHeader(MessageSender.CLEARNET_TO_HEADER);
		addCustomHeader(MessageSender.FREENET_FROM_HEADER);
		addCustomHeader("Received");
	}
	
	/**
	 * returns a list of contacts from "X-Clearnet-To" header
	 * @return
	 */
	public final List<EmailAddress> getClearnetContacts()
	{
		return clearnetContacts;
	}
	
	
	private void addClernetContact(String val)
	{
			String[] entries = val.split(",");
			for (String e : entries)
			{
				EmailAddress addr = new EmailAddress(e.trim());
				if (!addr.is_freemail_address())
				{
					// check that there is not such an address already
					boolean original = true;
					for (EmailAddress stored : clearnetContacts)
					{
						if (stored.user.equals(addr.user) && stored.domain.equals(addr.domain))
						{
							original = false;
							break;
						}
					}
					if (original)
					    clearnetContacts.add(addr);
				}
			}
		
	}
	
	/**
	 * Constructs a X-Clearnet-Header from clearnet contacts
	 * @return
	 */
	public String getClearnetHeader()
	{
		if (redirected && !clearnetContacts.isEmpty())
		{
			StringBuffer out = new StringBuffer();
			out.append(MessageSender.CLEARNET_TO_HEADER).append(": ");
			for (EmailAddress a : clearnetContacts)
			{
				out.append(a.user).append("@").append(a.domain).append(",");
			}
			out.delete(out.length()-1, out.length()); // remove last comma
			String val = out.toString();
			return val;
		}
		return null;
	}
	
	public String getFreenetHeader()
	{
		if (freenetSender != null)
			return MessageSender.FREENET_FROM_HEADER + ": " + freenetSender;
		
		return null;
	}

	public int size = 0;
	
	/**
	 * Wraps original readHeader method and ads custom action 
	 */
	@Override
	public String readHeader() throws IOException 
	{
		String val = super.readHeader();
		if (val == null)
			return null; // end of headers
		
		size += val.length();
		
		if ("From".equalsIgnoreCase(bits[0]) || 
			"To".equalsIgnoreCase(bits[0]) ||	
			"CC".equalsIgnoreCase(bits[0]) ||
			"BCC".equalsIgnoreCase(bits[0]))
		{
			addClernetContact(bits[1]);
		} else if (MessageSender.CLEARNET_TO_HEADER.equalsIgnoreCase(bits[0]))
		{
			redirected = true;
			String[] parts = bits[1].split(",");
			clearnetValue = parts[0];
			for (String p:parts) addClernetContact(p);
			return readHeader();
		} else if (MessageSender.FREENET_FROM_HEADER.equalsIgnoreCase(bits[0]))
		{
			freenetSender = bits[1].trim();
			return readHeader();
		} else if ("Content-Type".equalsIgnoreCase(bits[0]))
		{
			contentType = bits[1];
		}
		
		return val;
	}
	
	

}
