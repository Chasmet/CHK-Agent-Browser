package com.chk.agentbrowser;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.widget.EditText;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class GeneralReliabilityTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private Object field(Object value,String name)throws Exception{
        Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    @Test public void jsonLongerThanElevenThousandCharactersStaysWhole()throws Exception{
        StringBuilder text=new StringBuilder();for(int i=0;i<13000;i++)text.append('é');
        String message=new JSONObject().put("text",text.toString()).toString();
        JSONObject envelope=CommandResult.envelope("test","read_page",true,message);
        assertTrue(envelope.getBoolean("ok"));
        assertEquals(text.toString(),new JSONObject(envelope.getString("result")).getString("text"));
    }
    @Test public void utf8AndJsonEscapingCannotBlockTheResultQueue()throws Exception{
        StringBuilder text=new StringBuilder();for(int i=0;i<100000;i++)text.append("📝");
        JSONObject envelope=CommandResult.envelope("test","notes_read",true,text.toString());
        assertFalse(envelope.getBoolean("ok"));
        assertTrue(envelope.toString().getBytes(StandardCharsets.UTF_8).length<CommandResult.MAX_BYTES);
        assertTrue(envelope.getString("error").contains("pagination"));
    }
    @Test public void fileNamesKeepExtensionsAndNeverReplaceExistingFiles()throws Exception{
        File directory=new File(app.getCacheDir(),"names-"+UUID.randomUUID());assertTrue(directory.mkdir());
        File first=new File(directory,"Vidéo.mp4");assertTrue(first.createNewFile());
        HashSet<String> pending=new HashSet<>();pending.add("Vidéo (1).mp4");
        assertEquals("Vidéo (2).mp4",FileNames.available(directory,"Vidéo.mp4",pending));
        StringBuilder longName=new StringBuilder();for(int i=0;i<200;i++)longName.append("📝");
        String cleaned=FileNames.clean(longName+".mp4");assertTrue(cleaned.endsWith(".mp4"));
        assertTrue(cleaned.getBytes(StandardCharsets.UTF_8).length<=180);
        assertFalse(FileNames.clean("../../photo.png").contains("/"));
        assertFalse(FileNames.clean("../photo.png").startsWith("."));
        first.delete();directory.delete();
    }
    @Test public void rendererRecoveryKeepsTheTabAndShowsARetryAction()throws Exception{
        app.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            WebView previous=(WebView)field(activity,"current");
            int count=((java.util.List<?>)field(activity,"tabs")).size();
            instrumentation.runOnMainSync(()->{
                BrowserWebState.remember(previous,"https://example.com/recover");
                activity.recoverRenderer(previous);
                assertFalse(BrowserWebState.alive(previous));
                assertEquals(View.VISIBLE,activity.findViewById(R.id.browser_error).getVisibility());
            });
            assertEquals(count,((java.util.List<?>)field(activity,"tabs")).size());
            WebView next=(WebView)field(activity,"current");assertNotSame(previous,next);
            instrumentation.runOnMainSync(()->assertEquals("https://example.com/recover",BrowserWebState.url(next)));
        }finally{instrumentation.runOnMainSync(activity::finish);}
    }
    @Test public void filePickerKeepsItsDestinationAcrossRecreation()throws Exception{
        app.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            WorkspacePanel panel=(WorkspacePanel)field(activity,"workspace");Bundle state=new Bundle();
            state.putString("workspace_folder","Documents");state.putString("workspace_import","Documents");state.putString("workspace_export","Documents/texte.txt");
            instrumentation.runOnMainSync(()->{panel.restoreState(state);panel.saveState(state);});
            assertEquals("Documents",state.getString("workspace_import"));
            assertEquals("Documents/texte.txt",state.getString("workspace_export"));
        }finally{instrumentation.runOnMainSync(activity::finish);}
    }
    @Test public void noteRecreationDuringSaveDoesNotDuplicateOrLoseTheDraft()throws Exception{
        CountDownLatch release=new CountDownLatch(1),blocked=new CountDownLatch(1);
        WorkspaceCommands.IO.execute(()->{blocked.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(blocked.await(5,TimeUnit.SECONDS));
        String name="Rotation-"+UUID.randomUUID();
        NoteEditorActivity original=(NoteEditorActivity)instrumentation.startActivitySync(new Intent(app,NoteEditorActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("title",name).putExtra("body","Avant rotation"));
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(NoteEditorActivity.class.getName(),null,false);
        NoteEditorActivity recreated=null;
        try{
            instrumentation.waitForIdleSync();instrumentation.runOnMainSync(original::recreate);
            recreated=(NoteEditorActivity)monitor.waitForActivityWithTimeout(5000);assertNotNull(recreated);
            NoteEditorActivity editor=recreated;
            instrumentation.runOnMainSync(()->((EditText)editor.findViewById(R.id.note_body)).setText("Après rotation 📝"));
            release.countDown();
            boolean saved=false;
            for(int i=0;i<60;i++){
                instrumentation.waitForIdleSync();WorkspaceStore store=new WorkspaceStore(app);
                org.json.JSONArray rows=store.notes(name,false,0);
                if(rows.length()==1&&store.note(rows.getJSONObject(0).getString("id")).getString("body").equals("Après rotation 📝")){saved=true;break;}
                Thread.sleep(100);
            }
            assertTrue("Latest draft should be persisted after rotation",saved);
            assertEquals(1,new WorkspaceStore(app).notes(name,false,0).length());
        }finally{
            release.countDown();instrumentation.removeMonitor(monitor);
            NoteEditorActivity editor=recreated;instrumentation.runOnMainSync(()->{if(editor!=null)editor.finish();original.finish();});
        }
    }
    @Test public void malformedVersionMetadataDoesNotCrashTheUpdater(){
        assertFalse(UpdateManager.newer(null,"1.0.127"));
        assertFalse(UpdateManager.newer("1.0.128",null));
        assertTrue(UpdateManager.newer("1.0.128","1.0.127"));
        assertFalse(UpdateManager.newer("1.0.126","1.0.127"));
    }
}
