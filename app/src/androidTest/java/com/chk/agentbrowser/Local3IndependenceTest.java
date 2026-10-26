package com.chk.agentbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Regression: Local 3 must not change Render or Local 2 configuration. */
@RunWith(AndroidJUnit4.class)
public final class Local3IndependenceTest {
    @Test public void enablingLocal3DoesNotEnableOtherChannels() {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Local3Client third=Local3Client.get(app);
        SharedPreferences local=app.getSharedPreferences("chk_local3_v1",Context.MODE_PRIVATE);
        SharedPreferences second=app.getSharedPreferences("chk_local2_v1",Context.MODE_PRIVATE);
        SharedPreferences render=app.getSharedPreferences("browser_mcp_v1",Context.MODE_PRIVATE);
        boolean old=third.enabled(), local2=second.getBoolean("enabled",false),
            renderOn=render.getBoolean("enabled",false);
        try {
            third.setEnabled(false);
            assertFalse(third.enabled());
            third.setEnabled(true);
            assertTrue(third.enabled());
            assertEquals(local2,second.getBoolean("enabled",false));
            assertEquals(renderOn,render.getBoolean("enabled",false));
            third.setEnabled(false);
            assertFalse(third.enabled());
            assertEquals(local2,second.getBoolean("enabled",false));
            assertEquals(renderOn,render.getBoolean("enabled",false));
        } finally { third.setEnabled(old); }
    }

    @Test public void invalidInvitationNeverPairsOrContactsServer() {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Local3Client third=Local3Client.get(app);
        boolean wasPaired=third.paired();
        final boolean[] called={false};
        third.pair("not-an-invitation",(ok,message)->{
            called[0]=true;
            assertFalse(ok);
            assertTrue(message.contains("Invitation invalide"));
        });
        assertTrue(called[0]);
        assertEquals(wasPaired,third.paired());
    }
    @Test public void local3EndpointIsExplicitAndHTTPSOnly() {
        assertEquals("https://mcp.example.org",Local3Client.normalizeUrl("https://mcp.example.org/mcp"));
        assertEquals("https://my-host.example.org:8443",Local3Client.normalizeUrl("https://my-host.example.org:8443/"));
        for(String bad:new String[]{"http://mcp.example.org/mcp",
            "https://user:pass@mcp.example.org/mcp",
            "https://mcp.example.org/mcp/private-token",
            "https://mcp.example.org/mcp?token=secret",
            "https://mcp.example.org/mcp#fragment"}){
            try {Local3Client.normalizeUrl(bad);fail("Accepted insecure MCP URL");}
            catch(IllegalArgumentException expected){}
        }
    }

    @Test public void upgradedLocal3AdvertisesVisionAndPagedResults() throws Exception {
        org.json.JSONArray caps=Local3Client.capabilities();
        java.util.Map<String,Boolean> found=new java.util.HashMap<>();
        for(int i=0;i<caps.length();i++){
            org.json.JSONObject c=caps.getJSONObject(i);
            assertNull(found.put(c.getString("name"),c.getBoolean("readOnly")));
        }
        assertTrue(caps.length()<=80);
        assertEquals(Boolean.TRUE,found.get("files_transfer_read"));
        assertEquals(Boolean.TRUE,found.get("browser_capture"));
        assertEquals(Boolean.TRUE,found.get("browser_inspect"));
        assertEquals(Boolean.TRUE,found.get("local3_status"));
        assertEquals(Boolean.FALSE,found.get("desktop"));
        assertEquals(Boolean.FALSE,found.get("files_move"));
    }

    @Test public void escapedResultsArePagedAndRemainReadable() throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        char[] quotes=new char[200000];java.util.Arrays.fill(quotes,'"');
        org.json.JSONObject result=new org.json.JSONObject(Local3Client.compactResult(app,new String(quotes)));
        assertTrue(result.getBoolean("result_in_file"));
        assertTrue(result.has("updated"));
        assertEquals("files_transfer_read",result.getString("read_action"));
        java.io.File stored=new WorkspaceStore(app).file(result.getString("result_file"));
        try{assertEquals(200000,stored.length());assertEquals(stored.lastModified(),result.getLong("updated"));}
        finally{stored.delete();}
        String chunk=new String(new char[12000]).replace('\0','a');
        assertEquals(chunk,Local3Client.compactResult(app,chunk));
    }

}
