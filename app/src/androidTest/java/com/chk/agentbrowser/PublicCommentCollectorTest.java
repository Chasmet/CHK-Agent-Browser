package com.chk.agentbrowser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class PublicCommentCollectorTest {
    private static JSONObject request(String url) throws Exception {
        return new JSONObject().put("id","sondage2027")
            .put("urls",new JSONArray().put(url)).put("max_scrolls",5)
            .put("max_comments",200);
    }
    @Test public void publicPostsAreAllowed() throws Exception {
        PublicCommentCollector.validate(request("https://x.com/example/status/2109284642817802649"));
        PublicCommentCollector.validate(request("https://www.tiktok.com/@example/video/7280000000000000000"));
    }
    @Test public void profilesAndExternalRedirectsAreRejected() throws Exception {
        String[] urls={"https://x.com/example","https://www.tiktok.com/@example",
            "https://play.google.com/store/apps/details?id=com.zhiliaoapp.musically",
            "http://x.com/example/status/123","https://x.com.evil.example/u/status/123",
            "https://x.com/127.0.0.1","https://127.0.0.1/u/status/123"};
        for(String url:urls){
            try { PublicCommentCollector.validate(request(url));fail("URL interdite : "+url); }
            catch(Exception expected){assertNotNull(expected.getMessage());}
        }
    }
    @Test public void collectorsAreBounded() throws Exception {
        JSONArray urls=new JSONArray();for(int i=0;i<9;i++)urls.put("https://x.com/test/status/"+(123+i));
        try {PublicCommentCollector.validate(new JSONObject().put("id","sondage2027").put("urls",urls));fail();}
        catch(Exception expected){}
        try {PublicCommentCollector.validate(request("https://x.com/test/status/123").put("max_comments",501));fail();}
        catch(Exception expected){}
        try {PublicCommentCollector.validate(request("https://x.com/test/status/123").put("max_scrolls",100));fail();}
        catch(Exception expected){}
    }
    @Test public void privateHandlesAndLinksAreRedacted() {
        String s=PublicCommentCollector.sanitize("Je suis contre @Utilisateur123, voir https://example.org/a\n");
        assertFalse(s.contains("Utilisateur123"));assertFalse(s.contains("https://"));
        assertTrue(s.contains("[mention]"));
    }
    @Test public void taskRunnerAcceptsReadingAndBatchCollection()throws Exception {
        JSONArray steps=new JSONArray();
        steps.put(new JSONObject().put("id","scroll1").put("action","scroll")
            .put("args",new JSONObject().put("direction","down")));
        steps.put(new JSONObject().put("id","collect1").put("action","collect_public_comments")
            .put("args",request("https://x.com/example/status/123")));
        AgentTasks.validate(steps);
    }
}