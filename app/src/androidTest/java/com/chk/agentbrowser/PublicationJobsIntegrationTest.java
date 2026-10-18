package com.chk.agentbrowser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import static org.junit.Assert.*;

/** Only local files and fake HTTPS addresses; never posts to a real social account. */
@RunWith(AndroidJUnit4.class)
public final class PublicationJobsIntegrationTest {
    private Context app(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private static String uid(){return "pub"+UUID.randomUUID().toString().replace("-","").substring(0,13);}
    private static JSONObject draft(String id,String platform,String destination)throws Exception{
        return new JSONObject().put("id",id).put("platform",platform)
            .put("destination_url",destination)
            .put("title","Premier essai CHK")
            .put("caption","Texte de test jamais publié")
            .put("hashtags",new JSONArray().put("#CHK").put("musique"));
    }
    @Test public void onlyPlatformDomainsCanBeDestinations()throws Exception{
        assertTrue(PublicationJobs.validDestination("youtube","https://studio.youtube.com/"));
        assertTrue(PublicationJobs.validDestination("x","https://x.com/compose/post"));
        assertTrue(PublicationJobs.validDestination("instagram","https://www.instagram.com/"));
        assertTrue(PublicationJobs.validDestination("tiktok","https://www.tiktok.com/"));
        assertTrue(PublicationJobs.validDestination("facebook","https://www.facebook.com/"));
        assertTrue(PublicationJobs.validDestination("linkedin","https://www.linkedin.com/"));
        assertTrue(PublicationJobs.validDestination("soundcloud","https://soundcloud.com/upload"));
        assertTrue(PublicationJobs.validDestination("bluesky","https://bsky.app/"));
        assertTrue(PublicationJobs.validDestination("bandcamp","https://artist.bandcamp.com/"));
        assertFalse(PublicationJobs.validDestination("youtube","https://youtube.com.evil.invalid/"));
        assertFalse(PublicationJobs.validDestination("youtube","http://studio.youtube.com/"));
        assertFalse(PublicationJobs.validDestination("youtube","https://user@studio.youtube.com/"));
        assertFalse(PublicationJobs.validDestination("tiktok","https://studio.youtube.com/"));
    }
    @Test public void manifestKeepsMediaAndPreventsDoubleSubmission()throws Exception{
        Context context=app();String id=uid(),dir=uid(),path=dir+"/demo.mp4";
        WorkspaceStore store=new WorkspaceStore(context);
        File journal=AgentFiles.journal(context,"publications",id);
        store.mkdir(dir);
        File file=store.file(path);
        try {
            try(FileOutputStream stream=new FileOutputStream(file)){stream.write(new byte[]{1,2,3,4});}
            JSONObject data=draft(id,"youtube","https://studio.youtube.com/")
                .put("media_paths",new JSONArray().put(path));
            JSONObject prepared=PublicationJobs.command(context,"publication_prepare",data);
            assertEquals("prepared",prepared.getString("status"));
            assertEquals("video/mp4",prepared.getJSONArray("media").getJSONObject(0).getString("mime"));
            assertEquals("prepared",PublicationJobs.command(context,"publication_prepare",data).getString("status"));
            try{
                PublicationJobs.command(context,"publication_prepare",new JSONObject(data.toString())
                    .put("title","Un autre brouillon"));
                fail("An id can never silently overwrite a campaign");
            }catch(Exception expected){}
            JSONObject action=new JSONObject().put("id",id).put("expected_host","studio.youtube.com")
                .put("selector","#publish").put("expected_text","Published successfully")
                .put("mode","publish");
            try {
                PublicationJobs.begin(context,action,"https://youtube.com.evil.invalid/");
                fail("A different website must not be allowed to publish");
            }catch(Exception expected){}
            assertEquals("prepared",PublicationJobs.command(context,"publication_status",
                new JSONObject().put("id",id)).getString("status"));
            PublicationJobs.begin(context,action,"https://studio.youtube.com/channel/123");
            assertEquals("click_pending",PublicationJobs.command(context,"publication_status",
                new JSONObject().put("id",id)).getString("status"));
            PublicationJobs.finish(context,id,false,"Confirmation non observée",
                "https://studio.youtube.com/channel/123");
            assertEquals("uncertain",PublicationJobs.command(context,"publication_status",
                new JSONObject().put("id",id)).getString("status"));
            try{PublicationJobs.begin(context,action,"https://studio.youtube.com/");
                fail("An uncertain result must never repeat the click");
            }catch(Exception expected){}
        }finally{journal.delete();file.delete();store.file(dir).delete();}
    }
    @Test public void accountGuardIsRequiredForNamedAccounts()throws Exception{
        Context context=app();String id=uid();
        File journal=AgentFiles.journal(context,"publications",id);
        try{
            PublicationJobs.command(context,"publication_prepare",
                draft(id,"x","https://x.com/compose/post").put("account","CHK"));
            JSONObject args=new JSONObject().put("id",id).put("expected_host","x.com")
                .put("selector","#post").put("expected_text","Your post was sent");
            try{
                PublicationJobs.begin(context,args,"https://x.com/compose/post");
                fail("The agent must verify the account before posting");
            }catch(Exception expected){}
            assertEquals("prepared",PublicationJobs.command(context,"publication_status",
                new JSONObject().put("id",id)).getString("status"));
            args.put("account_selector","#signed-in-identity");
            PublicationJobs.begin(context,args,"https://x.com/compose/post");
            PublicationJobs.finish(context,id,false,"Compte de test : aucun clic","https://x.com/");
            assertEquals("uncertain",PublicationJobs.command(context,"publication_status",
                new JSONObject().put("id",id)).getString("status"));
            assertTrue(BrowserScripts.accountMatches("\"#signed-in-identity\"",
                "\"CHK\"").contains("ACCOUNT_MISMATCH"));
        }finally{journal.delete();}
    }
    @Test public void paidPromotionNeverExecutesAPublishClick()throws Exception{
        Context context=app();String id=uid();
        File journal=AgentFiles.journal(context,"publications",id);
        try{
            JSONObject plan=draft(id,"instagram","https://www.instagram.com/")
                .put("promotion_mode","paid");
            JSONObject result=PublicationJobs.command(context,"publication_prepare",plan);
            assertEquals("paid",result.getString("promotion_mode"));
            try{
                PublicationJobs.begin(context,new JSONObject().put("id",id)
                    .put("expected_host","www.instagram.com").put("selector","#confirm")
                    .put("expected_text","Campaign created"),
                    "https://www.instagram.com/");
                fail("Paid campaigns must not be purchased by generic automation");
            }catch(Exception expected){}
            assertEquals("prepared",PublicationJobs.command(context,"publication_status",
                new JSONObject().put("id",id)).getString("status"));
        }finally{journal.delete();}
    }
    @Test public void scheduleDatesRequireRealFutureTimeAndExplicitZone()throws Exception{
        Context context=app();String id=uid();
        File journal=AgentFiles.journal(context,"publications",id);
        try{
            JSONObject draft=draft(id,"youtube","https://studio.youtube.com/");
            for(String value:new String[]{"2020-10-18T15:00:00+02:00",
                    "2099-02-30T19:00:00+01:00","2099-11-01T19:00:00"}){
                draft.put("schedule_at",value);
                try{PublicationJobs.command(context,"publication_prepare",draft);
                    fail("Invalid or past date must never be scheduled: "+value);
                }catch(Exception expected){}
            }
            draft.put("schedule_at","2099-11-01T19:00:00+01:00");
            assertEquals("prepared",PublicationJobs.command(context,
                "publication_prepare",draft).getString("status"));
        }finally{journal.delete();}
    }
    @Test public void taskPlannerAcceptsPublicationActionsAndRequiresPostcondition()throws Exception{
        JSONArray tasks=new JSONArray().put(new JSONObject().put("id","prepare")
            .put("action","publication_prepare").put("args",new JSONObject()))
            .put(new JSONObject().put("id","confirm")
                .put("action","publication_submit").put("args",new JSONObject()
                    .put("id","sample").put("expected_host","studio.youtube.com")
                    .put("selector","#submit").put("expected_text","Published successfully")
                    .put("mode","publish")));
        AgentTasks.validate(tasks);
        tasks.getJSONObject(1).getJSONObject("args").remove("expected_text");
        try{AgentTasks.validate(tasks);fail("Unverifiable publication must be refused");}
        catch(Exception expected){}
    }
}
