package druzy.server;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.GZIPOutputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;


@SuppressWarnings("restriction")
public class HandlerRestrictedFileServer implements HttpHandler {
    //variables
	private static final int DEFAULT_BUFFER_SIZE = 10240; // ..bytes = 10KB.
    private static final long DEFAULT_EXPIRE_TIME = 604800000L; // ..ms = 1 week.
    private static final String MULTIPART_BOUNDARY = "MULTIPART_BYTERANGES";

    private String basePath=null;
    
    private RestrictedFileServer server=null;
    
    public HandlerRestrictedFileServer(RestrictedFileServer server){
    	this.server=server;
    }
    
	@Override
	public void handle(final HttpExchange exchange) throws IOException {
		basePath="/";
		if (exchange.getRequestMethod().equals("GET")){
			//initialisation du fichier à envoyer
			String temp=exchange.getRequestURI().toString();
			temp=temp.substring(temp.indexOf(basePath)+basePath.length());
			File file=new File(URLDecoder.decode(temp, "UTF-8"));
			if (server.isAuthorized(file)){
				
				//d'autre variables
				String fileName = file.getName();
		        long length = file.length();
		        long lastModified = file.lastModified();
		        String eTag = fileName + "_" + length + "_" + lastModified;
		        long expires = System.currentTimeMillis() + DEFAULT_EXPIRE_TIME;
	
		        //c'est partie
		        Range full = new Range(0, length - 1, length);
		        List<Range> ranges = new ArrayList<Range>();
		        String range = exchange.getRequestHeaders().getFirst("Range");
		        if (range != null) {
			        if (!range.matches("^bytes=\\d*-\\d*(,\\d*-\\d*)*$")) {
			        	System.out.println("range ne corresponds pas au standard : "+range);
			        	range=null;
			        }
			        
			        
			        String ifRange = exchange.getRequestHeaders().getFirst("If-Range");
		            if (ifRange != null && !ifRange.equals(eTag)) {
		                try {
		                    long ifRangeTime = Long.parseLong(exchange.getRequestHeaders().getFirst("If-Range")); // Throws IAE if invalid.
		                    if (ifRangeTime != -1 && ifRangeTime + 1000 < lastModified) {
		                        ranges.add(full);
		                    }
		                } catch (Exception ignore) {
		                    ranges.add(full);
		                }
		            }
		            
		            if (ranges.isEmpty()) {
		            	try{
			                for (String part : range.substring(6).split(",")) {
			                    // Assuming a file with length of 100, the following examples returns bytes at:
			                    // 50-80 (50 to 80), 40- (40 to length=100), -20 (length-20=80 to length=100).
			                    long start = sublong(part, 0, part.indexOf("-"));
			                    long end = sublong(part, part.indexOf("-") + 1, part.length());
			
			                    if (start == -1) {
			                        start = length - end;
			                        end = length - 1;
			                    } else if (end == -1 || end > length - 1) {
			                        end = length - 1;
			                    }
			
			                    // Check if Range is syntactically valid. If not, then return 416.
			                    if (start > end) {
			                        System.out.println("Range n'a pas une bonne syntaxe");
			                    }else{
				
				                    // Add range.
			                    	ranges.add(new Range(start, end, length));
			                    }
			                }
		            	}catch(Exception e){
		            		e.printStackTrace();
		            	}
		            }
		        }		
		        //prepare le retour du message
		        String contentType = Files.probeContentType(file.toPath());
		        boolean acceptsGzip = false;
		        String disposition = "inline";
		        
		        //contenttype par default
		        if (contentType == null) {
		            contentType = "application/octet-stream";
		        }
		        
		        //vérifie gzip pour les textes et position pour les images
		        if (contentType.startsWith("text")) {
		            String acceptEncoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
		            acceptsGzip = acceptEncoding != null && accepts(acceptEncoding, "gzip");
		            contentType += ";charset=UTF-8";
		        }else if (!contentType.startsWith("image")) {
		            String accept = exchange.getRequestHeaders().getFirst("Accept");
		            disposition = accept != null && accepts(accept, contentType) ? "inline" : "attachment";
		        } 
		        
		        //initialisation de la réponse
		        exchange.getResponseHeaders().add("Content-Disposition", disposition + ";filename=\"" + fileName + "\"");
		        exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
		        exchange.getResponseHeaders().add("ETag", eTag);
		        exchange.getResponseHeaders().add("Last-Modified", setDateHeader(lastModified));
		        exchange.getResponseHeaders().add("Expires", setDateHeader(expires));
		        
		        OutputStream out=exchange.getResponseBody();
		        RandomAccessFile in=new RandomAccessFile(file,"r");
		
		        if (ranges.isEmpty() || ranges.get(0) == full) {
		
		            // Return full file.
		            Range r = full;
		            exchange.getResponseHeaders().add("Content-Type", contentType);
		            exchange.getResponseHeaders().add("Content-Range", "bytes " + r.start + "-" + r.end + "/" + r.total);
		
		           
		            if (acceptsGzip) {
		
		                // The browser accepts GZIP, so GZIP the content.
		            	exchange.getResponseHeaders().add("Content-Encoding", "gzip");
		                out = new GZIPOutputStream(out, DEFAULT_BUFFER_SIZE);
		            } else {
		                // Content length is not directly predictable in case of GZIP.
		                // So only add it if there is no means of GZIP, else browser will hang.
		            	exchange.getResponseHeaders().add("Content-Length", String.valueOf(r.length));
		            }
		
		            // Copy full range.
		            sendResponse(exchange,HttpURLConnection.HTTP_OK, in, out, r.start, r.length);
		        
		        }else if (ranges.size() == 1) {
		
		            // Return single part of file.
		            Range r = ranges.get(0);
		            exchange.getResponseHeaders().add("Content-Type", contentType);
		            exchange.getResponseHeaders().add("Content-Range", "bytes " + r.start + "-" + r.end + "/" + r.total);
		            exchange.getResponseHeaders().add("Content-Length", String.valueOf(r.length));
		            
		            sendResponse(exchange,HttpURLConnection.HTTP_PARTIAL,in,out,r.start,r.length);
		
		        }else {
		
		            // Return multiple parts of file.
		        	exchange.getResponseHeaders().add("Content-Type", "multipart/byteranges; boundary=" + MULTIPART_BOUNDARY);
		
		            // Copy multi part range.
		            for (Range r : ranges) {
		                // Add multipart boundary and header fields for every range.
		            	out.write("\r\n".getBytes());
		            	out.write(("--" + MULTIPART_BOUNDARY + "\r\n").getBytes());
		            	out.write(("Content-Type: " + contentType + "\r\n").getBytes());
		                out.write(("Content-Range: bytes " + r.start + "-" + r.end + "/" + r.total+"\r\n").getBytes());
		
		                // Copy single part range of multi part range.
		                sendResponse(exchange,HttpURLConnection.HTTP_PARTIAL, in, out, r.start, r.length);
		            }
		
		            // End with multipart boundary.
		            out.write("\r\t".getBytes());
		            out.write(("--" + MULTIPART_BOUNDARY + "--"+"\r\n").getBytes());
		        }
		        
		        out.flush();
		        out.close();
		        in.close();
			}else{
				exchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND, 0);
				exchange.getResponseBody().close();
			}
		}
    }

	
	private static long sublong(String value, int beginIndex, int endIndex) {
        String substring = value.substring(beginIndex, endIndex);
        return (substring.length() > 0) ? Long.parseLong(substring) : -1;
    }
	
	private static boolean accepts(String acceptHeader, String toAccept) {
        String[] acceptValues = acceptHeader.split("\\s*(,|;)\\s*");
        Arrays.sort(acceptValues);
        return Arrays.binarySearch(acceptValues, toAccept) > -1
            || Arrays.binarySearch(acceptValues, toAccept.replaceAll("/.*$", "/*")) > -1
            || Arrays.binarySearch(acceptValues, "*/*") > -1;
    }
	
	private String setDateHeader(long date) {
	    Calendar calendar = Calendar.getInstance(Locale.US);
	    calendar.setTimeInMillis(date);
	    SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
	    dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
	    return dateFormat.format(calendar.getTime());
	}
	
	private void sendResponse(HttpExchange exchange, int code, RandomAccessFile in, OutputStream out, long start, long length) throws IOException{
		byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int read;

        exchange.sendResponseHeaders(code, length);
        
        if (in.length() == length) {
            // Write full range.
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } else {
            // Write partial range.
            in.seek(start);
            long toRead = length;

            while ((read = in.read(buffer)) > 0) {
                if ((toRead -= read) > 0) {
                    out.write(buffer, 0, read);
                } else {
                    out.write(buffer, 0, (int) toRead + read);
                    break;
                }
            }
        }
        out.flush();
	
	}
	
	/**
     * This class represents a byte range.
     */
    protected class Range {
        long start;
        long end;
        long length;
        long total;

        /**
         * Construct a byte range.
         * @param start Start of the byte range.
         * @param end End of the byte range.
         * @param total Total length of the byte source.
         */
        public Range(long start, long end, long total) {
            this.start = start;
            this.end = end;
            this.length = end - start + 1;
            this.total = total;
        }

    }

}
