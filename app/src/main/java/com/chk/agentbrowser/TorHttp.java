package com.chk.agentbrowser;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Every socket goes to the explicitly supplied loopback Tor HTTP tunnel; there is no direct fallback. */
final class TorHttp {
    static final String DIAGNOSTIC="https://check.torproject.org/api/ip";
    static final class Reply {int code;String url,type,body,sha256;org.json.JSONArray redirects;}
    private TorHttp(){}
    static Reply get(String input,int port,int maxBytes)throws Exception{
        if(port<1||port>65535)throw new IOException("TOR_PROXY_UNAVAILABLE");
        String url=TorUrlPolicy.checked(input);org.json.JSONArray history=new org.json.JSONArray();long deadline=System.nanoTime()+60000000000L;
        for(int redirects=0;redirects<=5;redirects++){
            Proxy proxy=new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",port));
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection(proxy);
            c.setConnectTimeout(20000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
            c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("User-Agent","CHK-Tor-Reader/1");
            try{
                int code=c.getResponseCode();
                if(code==301||code==302||code==303||code==307||code==308){
                    String next=c.getHeaderField("Location");if(next==null)throw new IOException("TOR_REDIRECT_WITHOUT_LOCATION");
                    String destination=TorUrlPolicy.checked(new URI(url).resolve(next).toString());history.put(new JSONObject().put("from",url).put("to",destination).put("http_status",code));url=destination;continue;
                }
                InputStream stream=code>=400?c.getErrorStream():c.getInputStream();if(stream==null)throw new IOException("TOR_RESPONSE_EMPTY");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=stream){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(System.nanoTime()>deadline)throw new IOException("TOR_RESPONSE_TIMEOUT");if(bytes.size()+n>maxBytes)throw new IOException("TOR_RESPONSE_TOO_LARGE");bytes.write(b,0,n);}}
                Reply r=new Reply();r.redirects=history;r.code=code;r.url=url;r.type=c.getContentType();r.body=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
                byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));r.sha256=hex.toString();return r;
            }finally{c.disconnect();}
        }
        throw new IOException("TOR_REDIRECT_LIMIT");
    }
    static JSONObject diagnostic(int port)throws Exception{
        Reply r=get(DIAGNOSTIC,port,16384);
        if(r.code!=200||!DIAGNOSTIC.equals(r.url))throw new IOException("TOR_DIAGNOSTIC_HTTP_OR_REDIRECT: "+r.code);
        JSONObject json=new JSONObject(r.body);if(!json.optBoolean("IsTor",false))throw new IOException("TOR_DIAGNOSTIC_NOT_TOR");
        return new JSONObject().put("is_tor",true).put("endpoint",DIAGNOSTIC).put("response_sha256",r.sha256).put("checked_at",System.currentTimeMillis());
    }
}
