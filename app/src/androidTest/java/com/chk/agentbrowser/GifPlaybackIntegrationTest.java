package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

/**
 * This fixture is a real, independently generated animated GIF:
 * 8x8 red frame (0..199ms) and green frame (200..399ms), infinite loop.
 * Never mock a successful decoder or use a still GIF in place of animation.
 */
@RunWith(AndroidJUnit4.class)
public final class GifPlaybackIntegrationTest {
    private static final String GIF =
        "R0lGODlhCAAIAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh"
       +"+QQIFAAAACwAAAAACAAIAAAIDwABCBxIsKDBgwgTKkwYEAAh+QQIFAAAACw"
       +"AAAAACAAIAIEA/wAAAAAAAAAAAAAIDwABCBxIsKDBgwgTKkwYEAA7";
    private File makeGif(Context c)throws Exception {
        File file=new WorkspaceStore(c).file("gif-test-"+UUID.randomUUID()+".gif");
        // The encoded bytes are in the test source, no remote media download.
        String base64=GIF.replace(" ","");
        try(FileOutputStream out=new FileOutputStream(file)){
            out.write(Base64.decode(base64,Base64.DEFAULT));
        }
        return file;
    }
    @Test public void realAnimatedFramesAndMetadataAreAvailable()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File f=makeGif(c);
        try {
            assertTrue(GifPlaybackView.isGif(f));
            assertTrue(StudioImages.isImage(f));
            JSONObject info=StudioImages.info(f);
            assertTrue(info.getBoolean("animated_gif"));
            assertEquals("image/gif",info.getString("mime_type"));
            assertTrue(info.getLong("loop_duration_ms")>=200);
            Bitmap a=GifPlaybackView.frame(f,0,32);
            Bitmap b=GifPlaybackView.frame(f,250,32);
            try {
                int ca=a.getPixel(4,4),cb=b.getPixel(4,4);
                assertTrue("First frame must be red",android.graphics.Color.red(ca)>200);
                assertTrue("Second frame must be green",android.graphics.Color.green(cb)>200);
                assertNotEquals("Playback must not freeze on frame zero",ca,cb);
            } finally {a.recycle();b.recycle();}
            assertNotNull(GifPlaybackView.decode(f,256));
        }finally{f.delete();}
    }
    @Test public void animatedGifProducesRealMp4ForStudio()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File gif=makeGif(c);
        try {
            File mp4=GifVideoClip.ensure(c,gif,1200);
            assertTrue(mp4.isFile()&&mp4.length()>1000);
            MediaMetadataRetriever retriever=new MediaMetadataRetriever();
            try {
                retriever.setDataSource(mp4.getAbsolutePath());
                assertEquals("yes",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
                Bitmap first=retriever.getFrameAtTime(100000,MediaMetadataRetriever.OPTION_CLOSEST);
                Bitmap second=retriever.getFrameAtTime(300000,MediaMetadataRetriever.OPTION_CLOSEST);
                assertNotNull("First MP4 frame is decoded",first);
                assertNotNull("Second MP4 frame is decoded",second);
                try {
                    int x=first.getWidth()/2,y=first.getHeight()/2;
                    int a=first.getPixel(x,y),b=second.getPixel(x,y);
                    assertTrue("Red phase preserved",android.graphics.Color.red(a)>android.graphics.Color.green(a));
                    assertTrue("Green phase preserved",android.graphics.Color.green(b)>android.graphics.Color.red(b));
                } finally {first.recycle();second.recycle();}
            }finally{retriever.release();}
            // Second use is idempotent; no extra files or source changes.
            assertEquals(mp4.getAbsolutePath(),GifVideoClip.ensure(c,gif,1200).getAbsolutePath());
            mp4.delete();
        }finally{gif.delete();}
    }
}
