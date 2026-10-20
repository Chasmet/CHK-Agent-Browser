package com.chk.agentbrowser;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

@androidx.annotation.OptIn(markerClass={androidx.media3.common.util.UnstableApi.class,androidx.media3.common.util.ExperimentalApi.class})
@RunWith(AndroidJUnit4.class)
public final class CompleteUpgradeTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void movingFolderPreservesProjectAndProtectsUsedSources()throws Exception{
        WorkspaceStore store=new WorkspaceStore(context);String id="refs-"+UUID.randomUUID();
        String from=id,to=id+"-moved";store.mkdir(from);store.writeText(from+"/clip.txt","source",false);
        File record=new File(context.getFilesDir(),"studio_projects/"+id+".json");record.getParentFile().mkdirs();
        try{
            AgentFiles.save(record,new JSONObject().put("revision",7).put("name",from+"/clip.txt")
                .put("clips",new JSONArray().put(new JSONObject().put("path",from+"/clip.txt"))));
            store.move(from,to);
            JSONObject moved=AgentFiles.read(record);
            assertEquals(to+"/clip.txt",moved.getJSONArray("clips").getJSONObject(0).getString("path"));
            assertEquals("The title is not a file reference",from+"/clip.txt",moved.getString("name"));
            assertEquals(8,moved.getInt("revision"));assertFalse(store.file(from).exists());
            try{store.trash(to);fail("Used source must remain available");}catch(IOException expected){}
            record.delete();String trash=store.trash(to);assertFalse(store.file(to).exists());store.restore(trash);
            assertEquals("source",store.readText(to+"/clip.txt",0).getString("text"));
        }finally{record.delete();store.file(from+"/clip.txt").delete();store.file(to+"/clip.txt").delete();store.file(from).delete();store.file(to).delete();}
    }

    @Test public void interruptedMoveRollsBackBothMediaAndMetadata()throws Exception{
        WorkspaceStore store=new WorkspaceStore(context);String from="recovery-"+UUID.randomUUID()+".txt",to=from+"-new.txt";
        store.writeText(from,"original",false);File log=new File(context.getFilesDir(),"workspace_move_transaction.json");
        File record=new File(context.getFilesDir(),"studio_projects/"+UUID.randomUUID()+".json");record.getParentFile().mkdirs();
        JSONObject before=new JSONObject().put("path",from),after=new JSONObject().put("path",to);
        try{
            AgentFiles.save(record,after);assertTrue(store.file(from).renameTo(store.file(to)));
            AgentFiles.save(log,new JSONObject().put("from",from).put("to",to).put("prefs",new JSONObject())
                .put("records",new JSONArray().put(new JSONObject().put("file",record.getCanonicalPath()).put("before",before).put("after",after))));
            WorkspaceReferences.recover(context);
            assertTrue(store.file(from).exists());assertFalse(store.file(to).exists());assertFalse(log.exists());
            assertEquals(from,AgentFiles.read(record).getString("path"));
        }finally{record.delete();log.delete();store.file(from).delete();store.file(to).delete();}
    }

    @Test public void folderZipContainsCompleteUtf8FilesAndRefusesSelfRecursion()throws Exception{
        WorkspaceStore store=new WorkspaceStore(context);String dir="archive-"+UUID.randomUUID();store.mkdir(dir);store.writeText(dir+"/été.txt","contenu é 📝",false);String output=dir+".zip";
        try{
            WorkspaceArchive.create(context,new JSONArray().put(dir),output);
            try(ZipFile zip=new ZipFile(store.file(output))){assertNotNull(zip.getEntry(dir+"/été.txt"));}
            try{WorkspaceArchive.create(context,new JSONArray().put(dir),dir+"/self.zip");fail("Recursive archive");}catch(IOException expected){}
        }finally{store.file(output).delete();store.file(dir+"/été.txt").delete();store.file(dir).delete();}
    }

    @Test public void mixedAudioPcmAppliesVoiceDuckingAndFade()throws Exception{
        JSONObject music=new JSONObject().put("duration_ms",1000).put("volume",.8).put("duck_under_voice",true).put("fade_in_ms",100);
        JSONArray tracks=new JSONArray().put(new JSONObject().put("duration_ms",1000).put("at_ms",0).put("role","voice"));
        StudioAudio.GainProcessor gain=new StudioAudio.GainProcessor(music,tracks,0);
        gain.configure(new androidx.media3.common.audio.AudioProcessor.AudioFormat(1000,1,androidx.media3.common.C.ENCODING_PCM_16BIT));gain.flush();
        ByteBuffer input=ByteBuffer.allocateDirect(400).order(ByteOrder.nativeOrder());for(int i=0;i<200;i++)input.putShort((short)10000);input.flip();gain.queueInput(input);
        ByteBuffer output=gain.getOutput().order(ByteOrder.nativeOrder());assertEquals(0,output.getShort());output.position(200);assertEquals(2000,output.getShort());
        gain.reset();
    }

    @Test public void subtitlesRoundTripAndRespectTimeline()throws Exception{
        String srt="1\n00:00:00,100 --> 00:00:01,000\nBonjour é\n\n2\n00:00:01,100 --> 00:00:02,000\nDeux lignes\nensemble\n";
        JSONArray captions=StudioSubtitles.parse(srt);JSONObject project=new JSONObject().put("subtitles",captions);
        StudioSubtitles.validate(project,2000);assertEquals("Bonjour é",StudioSubtitles.text(project,100));assertEquals("",StudioSubtitles.text(project,1000));
        assertEquals(captions.toString(),StudioSubtitles.parse(StudioSubtitles.export(captions)).toString());
        try{StudioSubtitles.validate(project,500);fail("Out of timeline");}catch(Exception expected){}
    }

    @Test public void updaterHashMatchesPublishedDigestAlgorithm()throws Exception{
        File file=File.createTempFile("apk-check-",".bin",context.getCacheDir());
        try{try(FileOutputStream out=new FileOutputStream(file)){out.write("abc".getBytes("UTF-8"));}
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",UpdateManager.sha256(file));
            UpdateManager.Release release=new UpdateManager.Release("1.0.999","","","Notes vérifiées",1048576,"");assertTrue(release.description().contains("Notes vérifiées"));
        }finally{file.delete();}
    }
}
