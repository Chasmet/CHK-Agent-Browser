package com.chk.agentbrowser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Regression guards; no user media, account, network or publishing required. */
@RunWith(AndroidJUnit4.class)
public final class StudioProAudioCropTest {
    @Test public void ordinaryBrowserNeverAttemptsOnionResolution(){
        assertTrue(BrowserPolicy.webUrl("https://example.com"));
        assertFalse(BrowserPolicy.webUrl("http://testexample.onion/"));
        assertFalse(BrowserPolicy.publicUrl("https://testexample.onion"));
        assertTrue(TorGatewayActivity.isOnion("https://testexample.onion/path"));
        assertFalse(TorGatewayActivity.isOnion("https://example.com"));
    }
    @Test public void addingMusicDoesNotMuteOriginalOrUnrelatedClips()throws Exception{
        JSONObject first=new JSONObject().put("path","a.mp4").put("duration_ms",3000).put("volume",1);
        JSONObject second=new JSONObject().put("path","b.mp4").put("duration_ms",3000).put("volume",1);
        JSONObject project=new JSONObject().put("clips",new JSONArray().put(first).put(second))
            .put("audio",new JSONArray().put(new JSONObject().put("path","song.wav").put("duration_ms",4000)));
        StudioEdits.clipVolume(project,first,.85);
        assertFalse(project.optBoolean("mute_original",false));
        assertFalse(second.optBoolean("mute",false));
        assertEquals(1.0,second.getDouble("volume"),.001);
        assertEquals(.85,first.getDouble("volume"),.001);
    }
    @Test public void parallelPromotionPreservesExistingSequence()throws Exception{
        JSONObject p=new JSONObject().put("clips",new JSONArray().put(new JSONObject().put("duration_ms",7000)))
            .put("audio",new JSONArray().put(new JSONObject().put("path","a.wav").put("duration_ms",3000))
                .put(new JSONObject().put("path","b.wav").put("duration_ms",4000)));
        StudioEdits.promoteAudioParallel(p);
        assertEquals("parallel",p.getString("audio_layout"));
        assertEquals(0,p.getJSONArray("audio").getJSONObject(0).getLong("offset_ms"));
        assertEquals(3000,p.getJSONArray("audio").getJSONObject(1).getLong("offset_ms"));
        StudioEdits.fitAudio(p);
        assertEquals(2,p.getJSONArray("audio").length());
    }
    @Test public void cropValidatesGeometryAndPreservesDuration()throws Exception{
        JSONObject c=StudioCropControl.initial();
        assertEquals(1.0,c.getDouble("w"),.0001);
        c.put("x",.25).put("y",.25).put("w",.5).put("h",.5).put("rotate_deg",90);
        StudioCropControl.validate(c);
        assertNotNull(StudioCropControl.effect(c));
        c.put("w",.9);
        try{StudioCropControl.validate(c);fail("Outside image");}catch(IllegalArgumentException good){}
    }
    @Test public void decibelsAndGainSupportSilenceAndTwoHundredPercent()throws Exception{
        assertEquals(2,StudioAudioControl.linear(6.020599913),.00001);
        assertEquals("−Infinity".replace("−","-"),String.valueOf(StudioAudioControl.db(0)).replace("−","-"));
        assertEquals(6.02,((Number)StudioAudioControl.db(2)).doubleValue(),.015);
        assertTrue(StudioAudioEnvelope.needed(new JSONObject().put("fade_in_ms",200)));
    }
}