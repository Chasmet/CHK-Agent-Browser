package com.chk.agentbrowser;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class StudioSubtitlesTest {
    @Test public void recognizerWordTimesAndSourceOffsetsBecomeTimelineCues()throws Exception{
        JSONArray words=new JSONArray();MediaTranscriber.collectWords(words,"{\"result\":[{\"word\":\"bonjour\",\"start\":0.30,\"end\":0.60,\"conf\":0.95},{\"word\":\"Cheikh\",\"start\":0.65,\"end\":1.0},{\"word\":\"suite\",\"start\":2.0,\"end\":2.3}]}",5000);
        JSONArray cues=new JSONArray();StudioSubtitles.append(cues,words,5000,3000,10000,1,20000);
        assertEquals(2,cues.length());assertEquals("bonjour Cheikh",cues.getJSONObject(0).getString("text"));
        assertEquals(10300,cues.getJSONObject(0).getLong("start_ms"));assertEquals(11000,cues.getJSONObject(0).getLong("end_ms"));assertEquals(12000,cues.getJSONObject(1).getLong("start_ms"));
        JSONObject project=new JSONObject().put("subtitles",cues);assertEquals("",StudioSubtitles.at(project,10299));assertEquals("bonjour Cheikh",StudioSubtitles.at(project,10300));assertEquals("",StudioSubtitles.at(project,11000));
    }
    @Test public void sourceTrimAndSpeedAreConvertedToProjectTime()throws Exception{
        JSONArray w=new JSONArray().put(new JSONObject().put("text","rap").put("start_ms",4200).put("end_ms",4800));JSONArray cues=new JSONArray();
        StudioSubtitles.append(cues,w,4000,1000,3000,2,5000);assertEquals(3100,cues.getJSONObject(0).getLong("start_ms"));assertEquals(3400,cues.getJSONObject(0).getLong("end_ms"));
    }
    @Test public void invalidAndOverlappingSubtitleEditsAreRejected()throws Exception{
        JSONObject p=new JSONObject().put("subtitles",new JSONArray().put(new JSONObject().put("start_ms",100).put("end_ms",500).put("text","premier")).put(new JSONObject().put("start_ms",490).put("end_ms",800).put("text","second")));
        try{StudioSubtitles.validate(p,1000);fail("Overlap must be rejected");}catch(java.io.IOException expected){}
        p.getJSONArray("subtitles").getJSONObject(1).put("start_ms",500);StudioSubtitles.validate(p,1000);
        p.getJSONArray("subtitles").getJSONObject(1).put("text","");try{StudioSubtitles.validate(p,1000);fail("Empty cue must be rejected");}catch(java.io.IOException expected){}
    }
}
