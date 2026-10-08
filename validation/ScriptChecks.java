import com.chk.agentbrowser.BrowserScripts;
import com.chk.agentbrowser.RelayPolicy;
public final class ScriptChecks {
    public static void main(String[] args) throws Exception {
        if(RelayPolicy.pollDelay(0,false)!=3000L || RelayPolicy.pollDelay(0,true)!=10000L)
            throw new AssertionError("Normal polling");
        for(int failures=1;failures<100;failures++){
            long delay=RelayPolicy.pollDelay(failures,false);
            if(delay<3000||delay>30000)throw new AssertionError("Retry bounds");
        }
        if(RelayPolicy.pollDelay(Integer.MAX_VALUE,false)>30000)
            throw new AssertionError("Retry overflow");
        if(!RelayPolicy.isTerminalResultStatus(404)||!RelayPolicy.isTerminalResultStatus(410)
            ||RelayPolicy.isTerminalResultStatus(503)||RelayPolicy.isTerminalResultStatus(401))
            throw new AssertionError("Expired result must not block polling; transient errors retry");
        java.nio.file.Files.write(java.nio.file.Paths.get(args[0],"read.js"),BrowserScripts.readPage().getBytes("UTF-8"));
        java.nio.file.Files.write(java.nio.file.Paths.get(args[0],"type.js"),
            BrowserScripts.type("\"#target\"","\"hello\\nworld\"").getBytes("UTF-8"));
    }
}
