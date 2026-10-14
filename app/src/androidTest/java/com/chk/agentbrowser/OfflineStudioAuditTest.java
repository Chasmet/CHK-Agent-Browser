package com.chk.agentbrowser;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import static org.junit.Assert.*;

/** Local-only tests: no network, authentication or external AI service. */
@RunWith(AndroidJUnit4.class)
public final class OfflineStudioAuditTest {
    private static void u16(OutputStream output,int v)throws Exception{
        output.write(v&255);output.write((v>>8)&255);
    }
    private static void u32(OutputStream output,int v)throws Exception{
        u16(output,v&65535);u16(output,(v>>>16)&65535);
    }
    @Test public void detectsRhythmFromPcmWithoutNetwork()throws Exception{
        File f=File.createTempFile("chk-beat-", ".wav",
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        try{
            int rate=16000, seconds=12, samples=rate*seconds;
            try(OutputStream out=new FileOutputStream(f)){
                out.write(new byte[]{'R','I','F','F'});
                u32(out,36+samples*2);
                out.write(new byte[]{'W','A','V','E','f','m','t',' '});
                u32(out,16);u16(out,1);u16(out,1);u32(out,rate);
                u32(out,rate*2);u16(out,2);u16(out,16);
                out.write(new byte[]{'d','a','t','a'});
                u32(out,samples*2);
                // Short pulses every half second => 120 beats per minute.
                for(int i=0;i<samples;i++){
                    int phase=i%(rate/2);
                    int signal=phase<rate/20?14000:0;
                    u16(out,signal);
                }
            }
            JSONObject result=OfflineStudioAudit.analyzePcmWave(f,12);
            assertTrue(result.getBoolean("available"));
            assertEquals(120,result.getInt("bpm_estimated"));
            assertEquals("local PCM onset autocorrelation",result.getString("method"));
        }finally{f.delete();}
    }

    @Test public void distinguishesTimelineReuseFromTrueDuplicateFile()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkspaceStore store=new WorkspaceStore(context);
        String path="audit-"+UUID.randomUUID()+".mp4";
        File temp=new File(context.getCacheDir(),path);
        try{
            try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext()
                    .getAssets().open("editor-fixture.mp4");
                OutputStream out=new FileOutputStream(temp)){
                byte[] buffer=new byte[16384];int count;
                while((count=in.read(buffer))>0)out.write(buffer,0,count);
            }
            store.importUri(Uri.fromFile(temp),path);
            JSONArray clips=new JSONArray()
                .put(new JSONObject().put("path",path).put("start_ms",0).put("duration_ms",1000))
                .put(new JSONObject().put("path",path).put("start_ms",1000).put("duration_ms",1000));
            JSONObject project=new JSONObject().put("clips",clips).put("audio",new JSONArray());
            JSONObject report=OfflineStudioAudit.analyze(context,project);
            assertTrue(report.getBoolean("network_used")==false);
            assertFalse(report.getBoolean("changes_applied"));
            assertEquals(1,report.getInt("source_reuses"));
            assertEquals(0,report.getInt("identical_separate_files"));
            assertEquals(2,report.getInt("clip_count"));
        }finally{store.file(path).delete();temp.delete();}
    }

    @Test public void rejectsColorGradeBeyondSafeGpuRange()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkspaceStore store=new WorkspaceStore(context);
        String path="grade-"+UUID.randomUUID()+".mp4";
        File temp=new File(context.getCacheDir(),path);
        try{
            try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext()
                    .getAssets().open("editor-fixture.mp4");
                OutputStream out=new FileOutputStream(temp)){
                byte[] buffer=new byte[8192];int count;
                while((count=in.read(buffer))>0)out.write(buffer,0,count);
            }
            store.importUri(Uri.fromFile(temp),path);
            JSONObject clip=new JSONObject().put("path",path).put("duration_ms",1000)
                .put("grade_exposure",.12).put("grade_exposure_end",.35);
            JSONObject project=new JSONObject().put("name","audit grade")
                .put("output","grade-output-"+UUID.randomUUID()+".mp4")
                .put("clips",new JSONArray().put(clip)).put("audio",new JSONArray());
            VideoEditorEngine.get().validate(context,project);
            clip.put("grade_exposure",1.5);
            try{
                VideoEditorEngine.get().validate(context,project);
                fail("Unsafe exposure must be rejected");
            }catch(IllegalArgumentException expected){
                assertTrue(expected.getMessage().contains("Étalonnage"));
            }
        }finally{store.file(path).delete();temp.delete();}
    }
}
