package com.chk.agentbrowser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class Local2ProtocolTest {
    @Test public void http200RpcErrorMustNeverCountAsConnected() throws Exception {
        JSONObject reply = new JSONObject("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"unknown\"}}");
        try { Local2Protocol.command(reply); fail("RPC error accepted"); }
        catch (Local2Protocol.RpcFailure error) { assertEquals(-32601, error.code); }
        try { Local2Protocol.command(new JSONObject("{\"result\":{}}")); fail("Missing channel accepted"); }
        catch (java.io.IOException expected) { }
        assertNull(Local2Protocol.command(new JSONObject("{\"command\":null}")));
    }
    @Test public void invalidCommandsAndUnacknowledgedResultsAreRejected() throws Exception {
        for (String body : new String[]{"{\"command\":false}", "{\"command\":{\"id\":\"x\"}}"}) {
            try { Local2Protocol.command(new JSONObject(body)); fail("Invalid command accepted"); }
            catch (java.io.IOException expected) { }
        }
        JSONObject task = Local2Protocol.command(new JSONObject("{\"command\":{\"id\":\"x\",\"action\":\"browser_status\",\"args\":{}}}"));
        assertEquals("x", task.getString("id"));
        for (String body : new String[]{"{}", "{\"ok\":false}", "{\"ok\":\"true\"}"}) {
            try { Local2Protocol.completion(new JSONObject(body)); fail("Unacknowledged result accepted"); }
            catch (java.io.IOException expected) { }
        }
        Local2Protocol.completion(new JSONObject("{\"ok\":true}"));
    }
    @Test public void funnelCompatibilityRetainsHostAndPrivateCapability() throws Exception {
        String capability = new String(new char[64]).replace('\0', 'e');
        String address = "https://pc.example.ts.net:8443/mcp/" + capability;
        String alternate = Local2Protocol.proxyCompatibilityAddress(address);
        assertEquals("https://pc.example.ts.net:8443/mcp/mcp/" + capability, alternate);
        assertEquals(Local2Protocol.fingerprint(address), Local2Protocol.fingerprint(alternate));
        assertEquals(16, Local2Protocol.fingerprint(address).length());
        assertFalse(Local2Protocol.fingerprint(address).contains(capability));
    }
}
