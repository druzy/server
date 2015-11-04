package druzy.server;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;

@SuppressWarnings("restriction")
public class RestrictedFileServer {

	//variables
	private static Hashtable<Integer,RestrictedFileServer> instances=new Hashtable<Integer,RestrictedFileServer>();
	
	private HttpServer server=null;
	private List<File> authorizedFiles=null;
	private int port=0;
	private boolean starting=false;
	
	protected RestrictedFileServer(int port){
		this.authorizedFiles=new ArrayList<File>();
		this.port=port;
		try {
			server=HttpServer.create(new InetSocketAddress(this.port),100);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if (server!=null){
			server.createContext("/",new HandlerRestrictedFileServer(this));
			server.setExecutor(Executors.newCachedThreadPool());
		}
	}
	
	public static RestrictedFileServer getInstance(int port){
		if (instances.containsKey(new Integer(port))) return instances.get(new Integer(port));
		else{
			instances.put(new Integer(port), new RestrictedFileServer(port));
			return instances.get(new Integer(port));
		}
	}
	
	public void addAuthorizedFile(File file){
		authorizedFiles.add(file);
	}
	
	public List<File> getAuthorizedFiles(){return authorizedFiles;}
	
	public boolean isAuthorized(File file){
		return authorizedFiles.contains(file);
	}
	
	public void start(){
		if (server!=null) server.start();
		setStarting(true);
	}
	
	public void stop(){
		if (server!=null) server.stop(0);
	}
	
	public static InetAddress getSiteLocalAddress(){
		InetAddress address=null;
		
			Enumeration<NetworkInterface> interfaces=null;
			try {
				interfaces = NetworkInterface.getNetworkInterfaces();
			} catch (SocketException e) {
				e.printStackTrace();
				return null;
			}
			
			while (interfaces.hasMoreElements() && address==null){
				NetworkInterface net=interfaces.nextElement();
				Enumeration<InetAddress> ienum = net.getInetAddresses();
				while (ienum.hasMoreElements() && address==null) {  // retourne l adresse IPv4 et IPv6
					InetAddress ia=ienum.nextElement();
					if (ia.isSiteLocalAddress()){
						if (ia.getHostAddress().indexOf(":")<0){
							address=ia;
						}
					}
				}
			}
			return address;
	}

	@Override
	public String toString(){
		return "http://"+getSiteLocalAddress().getHostAddress()+":"+port+"/";
		
	}

	
	public boolean isStarting() {
		return starting;
	}

	public void setStarting(boolean starting) {
		this.starting = starting;
	}
}
