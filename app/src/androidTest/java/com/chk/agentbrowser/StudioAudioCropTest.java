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
    @Test public void fourTouchHandlesKeepOppositeCornerAndSourceBounds()throws Exception{
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            android.content.Context app=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
            for(int corner=0;corner<4;corner++){
                StudioCropView view=new StudioCropView(app);android.widget.FrameLayout parent=new android.widget.FrameLayout(app);parent.addView(view);view.layout(0,0,400,600);
                android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(400,600,android.graphics.Bitmap.Config.ARGB_8888);image.eraseColor(android.graphics.Color.RED);view.source(image);view.setCrop(.1,.1,.8,.8);
                android.graphics.Bitmap screen=android.graphics.Bitmap.createBitmap(400,600,android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(screen));
                float x=corner%2==0?40:360,y=corner/2==0?60:540;long now=android.os.SystemClock.uptimeMillis();
                android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0),move=android.view.MotionEvent.obtain(now,now+20,android.view.MotionEvent.ACTION_MOVE,x+(corner%2==0?15:-15),y+(corner/2==0?20:-20),0),up=android.view.MotionEvent.obtain(now,now+40,android.view.MotionEvent.ACTION_UP,x,y,0);
                assertTrue(view.onTouchEvent(down));assertTrue(view.onTouchEvent(move));assertTrue(view.onTouchEvent(up));down.recycle();move.recycle();up.recycle();
                assertTrue(view.left()>=0&&view.top()>=0);assertTrue(view.left()+view.widthNorm()<=1.00001);assertTrue(view.top()+view.heightNorm()<=1.00001);assertTrue(view.widthNorm()<.8);assertTrue(view.heightNorm()<.8);
                if(corner%2==0)assertEquals(.9,view.left()+view.widthNorm(),.0001);else assertEquals(.1,view.left(),.0001);
                if(corner/2==0)assertEquals(.9,view.top()+view.heightNorm(),.0001);else assertEquals(.1,view.top(),.0001);
                for(float ratio:new float[]{9f/16,16f/9,1,4f/5}){view.setRatio(ratio);assertEquals(ratio,view.widthNorm()*400/(view.heightNorm()*600),.0001);}
                screen.recycle();image.recycle();
            }
        });
    }
}
