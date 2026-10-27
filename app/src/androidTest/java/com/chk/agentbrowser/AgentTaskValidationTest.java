package com.chk.agentbrowser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Workflow controls must be verified to avoid repeating sensitive web actions. */
@RunWith(AndroidJUnit4.class)
public final class AgentTaskValidationTest {
    private static JSONObject step(String action, JSONObject args, boolean verify) throws Exception {
        JSONObject s=new JSONObject().put("id","step1").put("action",action).put("args",args);
        if(verify)s.put("verify",new JSONObject()
            .put("action","get_page_status")
            .put("args",new JSONObject())
            .put("contains",new JSONObject().put("url","example.com")));
        return s;
    }
    private static void valid(String action,JSONObject args,boolean verify)throws Exception{
        AgentTasks.validate(new JSONArray().put(step(action,args,verify)));
    }
    private static void rejected(String action,JSONObject args,boolean verify)throws Exception{
        try{valid(action,args,verify);fail("Invalid or unchecked operation should be refused: "+action);}
        catch(Exception expected){assertNotNull(expected.getMessage());}
    }
    @Test public void webFormAndReadOnlyStepsAreAllowed()throws Exception{
        valid("get_form",new JSONObject(),false);
        valid("wait_for_element",new JSONObject().put("selector","#done"),false);
        valid("get_page_status",new JSONObject(),false);
        valid("read_page",new JSONObject(),false);
        valid("resume_visible_page",new JSONObject(),false);
    }
    @Test public void durableDownloadAndItsReadOnlyStatusComposeInAWorkflow()throws Exception{
        JSONObject download=new JSONObject().put("id","cut-01").put("expected_host","example.com").put("url","https://example.com/clip.mp4");
        JSONObject step=step("download_file",download,false);
        step.put("verify",new JSONObject().put("action","desktop").put("args",new JSONObject().put("tool","browser").put("operation","download_status").put("id","cut-01"))
            .put("expect",new JSONObject().put("state","completed").put("verified",true)));
        AgentTasks.validate(new JSONArray().put(step));
        rejected("download_file",download,false);
        JSONObject writeAsProof=new JSONObject(step.toString());writeAsProof.getJSONObject("verify").getJSONObject("args").put("operation","download_file");
        try{AgentTasks.validate(new JSONArray().put(writeAsProof));fail("A verification must not enqueue another download");}catch(Exception expected){assertTrue(expected.getMessage().contains("uniquement lire"));}
    }
    @Test public void sensitiveWebOperationsNeedObservability()throws Exception{
        JSONObject click=new JSONObject().put("selector","#save")
            .put("expected_selector","#finished");
        valid("click_verified",click,true);
        rejected("click_verified",click,false);
        rejected("click_verified",new JSONObject().put("selector","#save"),true);
        valid("type",new JSONObject().put("selector","#title").put("text","Bonjour"),true);
        rejected("type",new JSONObject().put("selector","#title").put("text","Bonjour"),false);
        valid("select_option",new JSONObject().put("selector","#category").put("value","book"),true);
        valid("check",new JSONObject().put("selector","#privacy").put("checked",true),true);
        rejected("check",new JSONObject().put("selector","#privacy"),false);
        rejected("type",new JSONObject().put("selector","").put("text","xxx"),true);
    }
    @Test public void uploadRequiresFilesDestinationAndVerification()throws Exception{
        JSONObject args=new JSONObject().put("selector","#media")
            .put("expected_host","play.google.com")
            .put("files",new JSONArray().put(new JSONObject().put("path","test.apk")));
        valid("upload_file",args,true);
        rejected("upload_file",args,false);
        JSONObject noHost=new JSONObject(args.toString()).put("expected_host","");
        rejected("upload_file",noHost,true);
        JSONObject noFiles=new JSONObject(args.toString()).put("files",new JSONArray());
        rejected("upload_file",noFiles,true);
        JSONObject tooMany=new JSONObject(args.toString());
        JSONArray nine=new JSONArray();for(int i=0;i<9;i++)nine.put(new JSONObject().put("path",i+".apk"));
        tooMany.put("files",nine);rejected("upload_file",tooMany,true);
    }
    @Test public void verificationItselfCanNeverMutateThePage()throws Exception{
        JSONObject s=step("type",new JSONObject().put("selector","#title").put("text","Bon"),false);
        s.put("verify",new JSONObject().put("action","click_verified")
            .put("args",new JSONObject().put("selector","#save").put("expected_text","OK"))
            .put("contains",new JSONObject().put("text","OK")));
        try{AgentTasks.validate(new JSONArray().put(s));fail("Mutation in postcondition");}
        catch(Exception correct){assertTrue(correct.getMessage().contains("uniquement lire"));}
    }
}
