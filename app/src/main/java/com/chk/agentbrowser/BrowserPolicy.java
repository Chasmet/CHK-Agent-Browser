package com.chk.agentbrowser;

import java.net.URI;
import java.util.Locale;

/** Same HTTP/HTTPS policy for visible tabs, retained tabs and MCP commands. */
public final class BrowserPolicy {
    public static final int MAX_TABS=24;
    private BrowserPolicy(){}
    public static boolean webUrl(String value){
        try{
            if(value==null||value.length()>2000)return false;
            URI u=new URI(value);String s=u.getScheme();java.net.URL url=new java.net.URL(value);int port=url.getPort();
            return ("http".equalsIgnoreCase(s)||"https".equalsIgnoreCase(s))
                &&!host(value).isEmpty()&&!onion(value)&&url.getUserInfo()==null&&u.getRawUserInfo()==null&&port!=0&&port<=65535;
        }catch(Exception e){return false;}
    }
    public static boolean onion(String value){try{String h=host(value).toLowerCase(Locale.ROOT);while(h.endsWith("."))h=h.substring(0,h.length()-1);return h.equals("onion")||h.endsWith(".onion");}catch(Exception e){return false;}}
    public static boolean publicUrl(String value){
        if(!webUrl(value))return false;
        String h;try{h=host(value).toLowerCase(Locale.ROOT);}catch(Exception e){return false;}
        if(h.startsWith("[")&&h.endsWith("]"))h=h.substring(1,h.length()-1);
        if(h.equals("localhost")||h.endsWith(".local")||h.endsWith(".internal")
            ||h.equals("0.0.0.0")||h.equals("::")||h.equals("::1")
            ||h.startsWith("127.")||h.startsWith("10.")||h.startsWith("192.168.")||h.startsWith("169.254.")
            ||h.startsWith("fc")&&h.contains(":")||h.startsWith("fd")&&h.contains(":")
            ||h.startsWith("fe80:")||h.startsWith("::ffff:"))return false;
        if(h.startsWith("172.")){try{int n=Integer.parseInt(h.split("\\.")[1]);if(n>=16&&n<=31)return false;}catch(Exception ignored){}}
        return true;
    }
    private static String host(String value)throws Exception{
        String h=new java.net.URL(value).getHost();
        return h.contains(":")?h:java.net.IDN.toASCII(h);
    }
}
