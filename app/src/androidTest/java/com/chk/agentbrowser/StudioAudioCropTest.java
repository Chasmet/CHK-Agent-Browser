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
    @Test public void ordinaryBrowserRejectsOnionBeforeNetworking()throws Exception{
        assertTrue(BrowserPolicy.webUrl("https://example.org/path"));
        for(String url:new String[]{"http://example.onion/","http://EXAMPLE.ONION./","https://sub.example.onion:443/a"}){
            assertTrue(BrowserPolicy.onionUrl(url));assertFalse(BrowserPolicy.webUrl(url));assertEquals(403,TorNetworkGuard.blockedOnion(url).getStatusCode());
        }
        assertNull(TorNetworkGuard.blockedOnion("https://example.org/?host=example.onion"));
        JSONObject tor=TorMcp.status(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext());
        assertFalse(tor.getBoolean("tor_verified"));assertTrue(tor.isNull("tor_connected"));assertFalse(tor.getBoolean("can_disconnect_external_tor"));
    }
    @Test public void allCropRatiosAreSpatialAndKeepDuration()throws Exception{
        JSONObject source=new JSONObject().put("duration_ms",9000);
        for(String ratio:new String[]{"9:16","16:9","1:1","4:5"}){
            JSONObject c=StudioCrop.changed(StudioCrop.read(source),"set",new JSONObject().put("ratio",ratio).put("rectangle",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)),1080,1920);
            String[] parts=ratio.split(":");double wanted=Double.parseDouble(parts[0])/Double.parseDouble(parts[1]);assertEquals(wanted,c.getDouble("width")*1080/(c.getDouble("height")*1920),.00001);assertEquals(9000,source.getLong("duration_ms"));
        }
    }
}
