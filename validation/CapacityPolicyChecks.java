import com.chk.agentbrowser.BrowserPolicy;
import com.chk.agentbrowser.TransferLimits;

public final class CapacityPolicyChecks {
    public static void main(String[] args)throws Exception{
        if(TransferLimits.MAX_FILE_BYTES!=6_000_000_000L)throw new AssertionError("6 GB capacity");
        for(long size:new long[]{0,2_147_483_648L,4_294_967_297L,6_000_000_000L})TransferLimits.checkSize(size);
        for(long size:new long[]{-1,6_000_000_001L,Long.MAX_VALUE}){
            try{TransferLimits.checkSize(size);throw new AssertionError("Oversize accepted");}catch(java.io.IOException expected){}
        }
        for(String url:new String[]{"http://example.com/path","https://example.com","HTTPS://example.com/","https://école.fr/","https://[2001:4860:4860::8888]/"})
            if(!BrowserPolicy.publicUrl(url))throw new AssertionError("Public HTTP/HTTPS rejected: "+url);
        for(String url:new String[]{"file:///tmp/file","javascript:alert(1)","https://user:pass@example.com/","https://127.0.0.1/","http://192.168.1.1/","https://[::1]/","https://[fd00::1]/","https://host.local/","http://10.1.1.1/"})
            if(BrowserPolicy.publicUrl(url))throw new AssertionError("Non-public URL accepted: "+url);
        System.out.println("PASS: HTTP/HTTPS and IDN policy; 64-bit file boundaries through 6 GB");
    }
}
