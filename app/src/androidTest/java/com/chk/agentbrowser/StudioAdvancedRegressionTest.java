package com.chk.agentbrowser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class StudioAdvancedRegressionTest {
    @Test public void changingOneClipVolumeNeverMutesAnotherOrOriginalAudio()throws Exception{
        JSONObject first=new JSONObject().put("path","voice-video.mp4").put("volume",1.0);
        JSONObject second=new JSONObject().put("path","second.mp4").put("volume",0.8);
        JSONObject music=new JSONObject().put("path","music.wav").put("volume",0.15);
        JSONObject project=new JSONObject().put("clips",new JSONArray().put(first).put(second))
            .put("audio",new JSONArray().put(music)).put("mute_original",false);
        StudioEdits.clipVolume(project,first,1.5);
        assertEquals(1.5,first.getDouble("volume"),0.000001);
        assertEquals(0.8,second.getDouble("volume"),0.000001);
        assertFalse(second.optBoolean("mute",false));
        assertFalse(project.getBoolean("mute_original"));
        assertEquals(0.15,music.getDouble("volume"),0.000001);
    }
    @Test public void decibelConversionIsPreciseAndSilenceIsMinusInfinity(){
        assertEquals(0,StudioAdvancedCommands.dbToLinear(Double.NEGATIVE_INFINITY),0);
        assertTrue(Double.isInfinite(StudioAdvancedCommands.linearToDb(0)));
        assertEquals(1,StudioAdvancedCommands.dbToLinear(0),0.0000001);
        assertEquals(2,StudioAdvancedCommands.dbToLinear(20*Math.log10(2)),0.0000001);
        assertEquals(20*Math.log10(0.15),StudioAdvancedCommands.linearToDb(.15),0.0000001);
    }
}
