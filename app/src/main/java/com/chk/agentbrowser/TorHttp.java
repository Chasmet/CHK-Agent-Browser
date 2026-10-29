package com.chk.agentbrowser;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.*;
import org.json.*;

/** HTTP/1.1 inside a Tor CONNECT tunnel. Destination hosts are never resolved on the device. */
@androidx.annotation.RequiresApi(29)
final class TorHttp {
    static final String DIAGNOSTIC="https://check.torproject.org/api/ip";
    static final class Reply {int code;String url,type,body,sha256;JSONArray redirects;}
    private static final class Headers {int code;final Map<String,String> fields=new HashMap<>();}
    private TorHttp(){}
    static Reply get(String input,int port,int maxBytes)throws Exception{
        if(port<1||port>65535||maxBytes<1||maxBytes>1048576)throw new IOException("TOR_PROXY_OR_RESPONSE_LIMIT_INVALID");
        String url=TorUrlPolicy.checked(input);JSONArray history=new JSONArray();boolean onion=new URI(url).getHost().endsWith(".onion");int readTimeout=onion?90000:20000;long deadline=System.nanoTime()+(onion?180000000000L:60000000000L);
        for(int redirects=0;redirects<=5;redirects++){
            if(System.nanoTime()>deadline)throw new IOException("TOR_RESPONSE_TIMEOUT");
            URI target=new URI(url);String host=target.getHost();boolean tls=target.getScheme().equals("https");int destinationPort=target.getPort()<0?(tls?443:80):target.getPort();
            String stage="proxy_connect";
            try(Socket tunnel=new Socket()){
                tunnel.connect(new InetSocketAddress("127.0.0.1",port),20000);tunnel.setSoTimeout(readTimeout);stage="tor_connect";
                String authority=host+":"+destinationPort;
                // Tor's HTTP tunnel accepts CONNECT only, including for HTTP onion services on port 80.
                tunnel.getOutputStream().write(("CONNECT "+authority+" HTTP/1.0\r\nHost: "+authority+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));tunnel.getOutputStream().flush();
                Headers connected=headers(new DeadlineInput(tunnel.getInputStream(),tunnel,deadline,readTimeout));if(connected.code!=200)throw new IOException("TOR_CONNECT_REJECTED: "+connected.code);
                Socket transport=tunnel;
                try{
                    if(tls){stage="tls_handshake";
                        SSLSocket secure=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(tunnel,host,destinationPort,true);
                        SSLParameters parameters=secure.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");secure.setSSLParameters(parameters);secure.setSoTimeout((int)Math.max(1,Math.min(readTimeout,(deadline-System.nanoTime())/1000000)));secure.startHandshake();transport=secure;
                    }
                    String path=target.getRawPath();if(path==null||path.isEmpty())path="/";if(target.getRawQuery()!=null)path+="?"+target.getRawQuery();
                    String hostHeader=host+(destinationPort==(tls?443:80)?"":":"+destinationPort);
                    OutputStream out=transport.getOutputStream();out.write(("GET "+path+" HTTP/1.1\r\nHost: "+hostHeader+"\r\nUser-Agent: CHK-Tor-Reader/1\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
                    stage="http_response";InputStream in=new BufferedInputStream(new DeadlineInput(transport.getInputStream(),transport,deadline,readTimeout));Headers response=headers(in);for(int interim=0;response.code>=100&&response.code<200&&interim<4;interim++)response=headers(in);
                    int code=response.code;
                    if(code==301||code==302||code==303||code==307||code==308){String next=response.fields.get("location");if(next==null)throw new IOException("TOR_REDIRECT_WITHOUT_LOCATION");String destination=TorUrlPolicy.checked(new URI(url).resolve(next).toString());history.put(new JSONObject().put("from",url).put("to",destination).put("http_status",code));url=destination;continue;}
                    stage="http_body";byte[] body=body(in,response.fields,maxBytes,deadline);String encoding=response.fields.get("content-encoding");
                    if("gzip".equalsIgnoreCase(encoding)){try(InputStream compressed=new GZIPInputStream(new ByteArrayInputStream(body))){body=bounded(compressed,-1,maxBytes,deadline);}}
                    else if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new IOException("TOR_CONTENT_ENCODING_UNSUPPORTED");
                    Reply r=new Reply();r.redirects=history;r.code=code;r.url=url;r.type=response.fields.get("content-type");r.body=new String(body,StandardCharsets.UTF_8);
                    byte[] hash=MessageDigest.getInstance("SHA-256").digest(body);StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format(Locale.ROOT,"%02x",b&255));r.sha256=hex.toString();return r;
                }finally{if(transport!=tunnel)transport.close();}
            }catch(SocketTimeoutException e){throw new IOException("TOR_NETWORK_TIMEOUT: "+stage,e);}
        }
        throw new IOException("TOR_REDIRECT_LIMIT");
    }
    private static final class DeadlineInput extends FilterInputStream {
        private final Socket socket;private final long deadline;private final int maximum;
        DeadlineInput(InputStream in,Socket socket,long deadline,int maximum){super(in);this.socket=socket;this.deadline=deadline;this.maximum=maximum;}
        private void time()throws IOException{long remaining=(deadline-System.nanoTime())/1000000;if(remaining<=0)throw new SocketTimeoutException("Tor HTTP deadline");socket.setSoTimeout((int)Math.max(1,Math.min(maximum,remaining)));}
        @Override public int read()throws IOException{time();return in.read();}
        @Override public int read(byte[] b,int off,int len)throws IOException{time();return in.read(b,off,len);}
    }
    private static Headers headers(InputStream in)throws Exception{
        String status=line(in,8192);if(!status.matches("HTTP/1\\.[01] [0-9]{3}( .*)?"))throw new IOException("TOR_HTTP_STATUS_INVALID");Headers result=new Headers();result.code=Integer.parseInt(status.substring(9,12));int size=status.length();
        for(int count=0;count<100;count++){String value=line(in,8192);size+=value.length();if(size>65536)throw new IOException("TOR_HTTP_HEADERS_TOO_LARGE");if(value.isEmpty())return result;int colon=value.indexOf(':');if(colon<=0)throw new IOException("TOR_HTTP_HEADER_INVALID");String name=value.substring(0,colon).toLowerCase(Locale.ROOT),content=value.substring(colon+1).trim();if(result.fields.containsKey(name)&&(name.equals("content-length")||name.equals("transfer-encoding")))throw new IOException("TOR_HTTP_AMBIGUOUS_FRAMING");result.fields.put(name,content);}
        throw new IOException("TOR_HTTP_HEADER_LIMIT");
    }
    private static String line(InputStream in,int max)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();for(int count=0;count<max;count++){int n=in.read();if(n<0)throw new EOFException("TOR_HTTP_TRUNCATED_LINE");if(n=='\n'){byte[] value=bytes.toByteArray();int length=value.length;if(length>0&&value[length-1]=='\r')length--;return new String(value,0,length,StandardCharsets.US_ASCII);}bytes.write(n);}throw new IOException("TOR_HTTP_LINE_TOO_LONG");}
    private static byte[] body(InputStream in,Map<String,String> fields,int max,long deadline)throws Exception{
        String transfer=fields.get("transfer-encoding");if(transfer!=null){if(!transfer.equalsIgnoreCase("chunked"))throw new IOException("TOR_TRANSFER_ENCODING_UNSUPPORTED");ByteArrayOutputStream out=new ByteArrayOutputStream();
            for(int chunks=0;chunks<100000;chunks++){long count=Long.parseLong(line(in,1024).split(";",2)[0].trim(),16);if(count<0||count>max-out.size())throw new IOException("TOR_RESPONSE_TOO_LARGE");if(count==0){for(int i=0;i<100;i++)if(line(in,8192).isEmpty())return out.toByteArray();throw new IOException("TOR_HTTP_TRAILER_LIMIT");}out.write(bounded(in,count,max-out.size(),deadline));if(!line(in,2).isEmpty())throw new IOException("TOR_HTTP_CHUNK_INVALID");}
            throw new IOException("TOR_HTTP_CHUNK_LIMIT");
        }
        String length=fields.get("content-length");return bounded(in,length==null?-1:Long.parseLong(length),max,deadline);
    }
    private static byte[] bounded(InputStream in,long length,int max,long deadline)throws Exception{
        if(length>max||length< -1)throw new IOException("TOR_RESPONSE_TOO_LARGE");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];
        while(length<0||out.size()<length){if(System.nanoTime()>deadline)throw new IOException("TOR_RESPONSE_TIMEOUT");int want=length<0?buffer.length:(int)Math.min(buffer.length,length-out.size());int count=in.read(buffer,0,want);if(count<0){if(length>=0&&out.size()!=length)throw new EOFException("TOR_HTTP_TRUNCATED_BODY");break;}if(out.size()+count>max)throw new IOException("TOR_RESPONSE_TOO_LARGE");out.write(buffer,0,count);}return out.toByteArray();
    }
    static JSONObject diagnostic(int port)throws Exception{
        Reply r=get(DIAGNOSTIC,port,16384);if(r.code!=200||!DIAGNOSTIC.equals(r.url))throw new IOException("TOR_DIAGNOSTIC_HTTP_OR_REDIRECT: "+r.code);
        JSONObject json=new JSONObject(r.body);if(!json.optBoolean("IsTor",false))throw new IOException("TOR_DIAGNOSTIC_NOT_TOR");
        return new JSONObject().put("is_tor",true).put("endpoint",DIAGNOSTIC).put("response_sha256",r.sha256).put("checked_at",System.currentTimeMillis());
    }
}
