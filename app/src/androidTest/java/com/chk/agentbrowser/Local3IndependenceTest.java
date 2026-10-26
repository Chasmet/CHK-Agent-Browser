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
    @Test public void legacyServerManifestMatchesWithoutChangingPolicies()throws Exception{
        org.json.JSONArray expected;
        try(java.io.InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets()
            .open("local3-server-1.0.4-capabilities.json");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            expected=new org.json.JSONArray(new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
        }
        java.util.Map<String,Boolean> policy=new java.util.HashMap<>();
        for(int i=0;i<expected.length();i++){org.json.JSONObject c=expected.getJSONObject(i);policy.put(c.getString("name"),c.getBoolean("readOnly"));}
        org.json.JSONArray actual=Local3Client.compatibleCapabilities();
        assertEquals(expected.length(),actual.length());
        for(int i=0;i<actual.length();i++){
            org.json.JSONObject c=actual.getJSONObject(i);
            assertEquals(policy.remove(c.getString("name")),Boolean.valueOf(c.getBoolean("readOnly")));
        }
        assertTrue(policy.isEmpty());assertEquals(2,BrowserAutomation.VERSION);
    }
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
        assertTrue(caps.length()>80);
        assertEquals(Boolean.FALSE,found.get("browser_upload_file"));
        assertEquals(Boolean.TRUE,found.get("workflow_status"));
        assertEquals(Boolean.FALSE,found.get("workflow_run"));
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


    @Test public void local3PermissionRevocationIsIndependentAndPersistent(){
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Local3Client third=Local3Client.get(app);
        boolean before=third.persistentPermissions(),autonomous=AgentClient.get(app).isAutonomous();
        boolean second=Local2Client.get(app).enabled(),render=AgentClient.get(app).isEnabled();
        try{
            third.setPersistentPermissions(false);
            assertFalse(third.autonomousAllowed());
            assertFalse(app.getSharedPreferences("chk_local3_v1",Context.MODE_PRIVATE).getBoolean("persistent_permissions",true));
            assertEquals(autonomous,AgentClient.get(app).isAutonomous());
            assertEquals(second,Local2Client.get(app).enabled());assertEquals(render,AgentClient.get(app).isEnabled());
            third.setPersistentPermissions(true);assertEquals(autonomous,third.autonomousAllowed());
        }finally{third.setPersistentPermissions(before);}
    }
    @Test public void local3DirectToolsReuseExistingHandlersWithoutChangingArguments()throws Exception{
        org.json.JSONObject original=new org.json.JSONObject().put("id","cut-02").put("expected_host","grok.com");
        org.json.JSONObject upload=Local3Commands.translate("browser_upload_file",original);
        assertEquals("desktop",upload.getString("action"));
        assertEquals("browser",upload.getJSONObject("args").getString("tool"));
        assertEquals("upload_file",upload.getJSONObject("args").getString("operation"));
        assertEquals("cut-02",upload.getJSONObject("args").getString("id"));
        assertFalse(original.has("tool"));
        for(String[] pair:new String[][]{{"workflow_run","submit"},{"workflow_status","status"},{"workflow_resume","resume"},{"workflow_cancel","cancel"}}){
            org.json.JSONObject args=Local3Commands.translate(pair[0],original).getJSONObject("args");
            assertEquals("tasks",args.getString("tool"));assertEquals(pair[1],args.getString("operation"));
        }
        assertNull(Local3Commands.translate("unknown",original));
    }
    @Test public void workflowsBeyondFiftyStepsRemainValidAndTheirProgressIsPaged()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        org.json.JSONArray steps=new org.json.JSONArray();
        for(int i=0;i<65;i++)steps.put(new org.json.JSONObject().put("id","read-"+i).put("action","workspace_status").put("args",new org.json.JSONObject()).put("state","done"));
        AgentTasks.validate(steps);
        String id="local3-many-"+java.util.UUID.randomUUID();java.io.File file=AgentFiles.journal(app,"tasks",id);
        try{
            AgentFiles.save(file,new org.json.JSONObject().put("id",id).put("state","completed").put("steps",steps));
            org.json.JSONObject page=AgentTasks.command(app,new org.json.JSONObject().put("id",id).put("operation","status").put("offset",60),true,true);
            assertEquals(65,page.getInt("total"));assertEquals(65,page.getInt("completed"));
            assertEquals(5,page.getJSONArray("steps").length());assertEquals(-1,page.getInt("next_offset"));
        }finally{file.delete();}
    }
}
