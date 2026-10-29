package com.chk.agentbrowser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Core non-destructive crop and audio regression checks. */
@RunWith(AndroidJUnit4.class)
public final class StudioAudioCropTest {
    @Test public void changingVoiceVolumeNeverMutesOtherClipsOrMusic()throws Exception{
        JSONObject one=new JSONObject().put("path","one.mp4").put("volume",1);
        JSONObject two=new JSONObject().put("path","two.mp4").put("mute",false);
        JSONObject music=new JSONObject().put("path","music.wav").put("volume",.15);
        JSONObject p=new JSONObject().put("clips",new JSONArray().put(one).put(two))
            .put("audio",new JSONArray().put(music)).put("mute_original",false);
        StudioEdits.clipVolume(p,one,1.25);
        assertEquals(1.25,one.getDouble("volume"),.0001);
        assertFalse(two.getBoolean("mute"));
        assertEquals(.15,music.getDouble("volume"),.0001);
        assertFalse(p.getBoolean("mute_original"));
    }
    @Test public void geometryIsNonDestructiveAndLimitsAreEnforced()throws Exception{
        JSONObject clip=new JSONObject().put("duration_ms",8400);
        JSONObject crop=StudioCrop.read(clip);
        JSONObject changed=StudioCrop.changed(crop,"set",new JSONObject()
            .put("units","normalized").put("rectangle",new JSONObject()
                .put("x",.1).put("y",.2).put("width",.7).put("height",.6)),1920,1080);
        assertEquals(.1,changed.getDouble("x"),.00001);
        JSONObject moved=StudioCrop.changed(changed,"move",
            new JSONObject().put("dx",1).put("dy",1),1920,1080);
        assertEquals(.3,moved.getDouble("x"),.00001);
        assertEquals(.4,moved.getDouble("y"),.00001);
        JSONObject rotated=StudioCrop.changed(moved,"rotate",
            new JSONObject().put("degrees",90),1920,1080);
        JSONObject flipped=StudioCrop.changed(rotated,"flip",
            new JSONObject().put("horizontal",true),1920,1080);
        clip.put("crop",flipped);
        assertTrue(StudioCrop.read(clip).getBoolean("flip_horizontal"));
        assertEquals(90,StudioCrop.read(clip).getDouble("degrees"),.00001);
        assertEquals(8400,clip.getLong("duration_ms"));
        try{
            StudioCrop.changed(changed,"resize",new JSONObject().put("width",2).put("height",.6),1920,1080);
            fail("Crop outside original image must be rejected");
        }catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Recadrage"));}
    }
    @Test public void pixelRectIsConvertedToNormalizedCoordinates()throws Exception{
        JSONObject crop=StudioCrop.changed(StudioCrop.read(new JSONObject()),"set",
            new JSONObject().put("units","pixels").put("rectangle",new JSONObject()
                .put("x",192).put("y",108).put("width",960).put("height",540)),1920,1080);
        assertEquals(.1,crop.getDouble("x"),.00001);
        assertEquals(.5,crop.getDouble("width"),.00001);
    }
}
