package com.chk.agentbrowser;

import java.net.URI;
import java.util.Locale;

/** Parse only; never resolve a destination on the device before handing it to Tor. */
final class TorUrlPolicy {
    private TorUrlPolicy(){}
    static String checked(String input)throws Exception{
        if(input==null||input.length()>4096||input.contains("\\")||input.matches("(?s).*[\\x00-\\x20\\x7f].*"))throw new Exception("TOR_URL_INVALID");
        URI u=new URI(input);String scheme=u.getScheme(),host=u.getHost();
        if(scheme==null||!(scheme.equalsIgnoreCase("http")||scheme.equalsIgnoreCase("https"))||host==null||u.getRawUserInfo()!=null)throw new Exception("TOR_HTTP_URL_REQUIRED");
        host=host.toLowerCase(Locale.ROOT);if(host.endsWith("."))throw new Exception("TOR_URL_TRAILING_DOT_UNSUPPORTED");
        if(!host.matches("[a-z0-9.-]+")||host.equals("localhost")||host.endsWith(".localhost")||host.endsWith(".local")||host.matches("[0-9.]+")||!host.contains("."))throw new Exception("TOR_DESTINATION_HOST_REQUIRED");
        if(host.endsWith(".onion")){
            String[] labels=host.split("\\.");String service=labels[labels.length-2];
            if(!service.matches("[a-z2-7]{56}"))throw new Exception("TOR_V3_ONION_REQUIRED");
        }
        int port=u.getPort();if(port==0||port>65535||port< -1)throw new Exception("TOR_PORT_INVALID");
        String path=u.normalize().getRawPath();if(path==null||path.isEmpty())path="/";
        return scheme.toLowerCase(Locale.ROOT)+"://"+host+(port<0?"":":"+port)+path+(u.getRawQuery()==null?"":"?"+u.getRawQuery())+(u.getRawFragment()==null?"":"#"+u.getRawFragment());
    }
}
