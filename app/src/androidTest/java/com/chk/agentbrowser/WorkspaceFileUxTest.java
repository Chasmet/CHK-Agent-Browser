package com.chk.agentbrowser;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** File manager gestures and audio controls, exercised on an Android activity. */
@RunWith(AndroidJUnit4.class)
public final class WorkspaceFileUxTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private final Context context=instrument.getTargetContext();

    @Test public void longPressSelectsWhileOverflowKeepsFileOptions() throws Exception {
        context.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        MainActivity activity=(MainActivity)instrument.startActivitySync(
            new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            JSONArray data=new JSONArray().put(new JSONObject()
                .put("name","Exemple.mp3").put("path","audio/Exemple.mp3")
                .put("size",17280).put("updated",1700000000000L)
                .put("mime_type","audio/mpeg").put("directory",false));
            instrument.runOnMainSync(()->{
                LinkedHashSet<String> selected=new LinkedHashSet<>();
                AtomicInteger longPresses=new AtomicInteger(),menus=new AtomicInteger();
                WorkspaceFileAdapter adapter=new WorkspaceFileAdapter(activity,selected,
                    new WorkspaceFileAdapter.Actions(){
                        public void openFolder(String path){}
                        public void openFile(String path){}
                        public void menu(String path){menus.incrementAndGet();}
                        public void startSelection(String path){selected.add(path);longPresses.incrementAndGet();}
                        public void selectedChanged(){}
                    });
                adapter.submit(data,false,false,1);
                WorkspaceFileAdapter.Holder holder=adapter.onCreateViewHolder(
                    new FrameLayout(activity),0);
                adapter.onBindViewHolder(holder,0);
                assertTrue(holder.detail.getText().toString().contains("2023"));
                assertEquals(View.VISIBLE,holder.more.getVisibility());
                assertTrue(holder.card.performLongClick());
                assertEquals(1,longPresses.get());
                assertTrue(selected.contains("audio/Exemple.mp3"));
                assertEquals(0,menus.get());
                holder.more.performClick();
                assertEquals(1,menus.get());
                adapter.selectionMode(true);
                adapter.onBindViewHolder(holder,0);
                assertEquals(View.GONE,holder.more.getVisibility());
            });
        }finally{instrument.runOnMainSync(activity::finish);}
    }

    @Test public void audioBarStaysMountedAndCanStopWithoutDeletingFile() throws Exception {
        context.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        MainActivity activity=(MainActivity)instrument.startActivitySync(
            new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        WorkspaceStore store=new WorkspaceStore(context);
        String name="mini-player-"+UUID.randomUUID()+".wav";
        File wave=store.file(name);
        writeSilenceWave(wave);
        final WorkspaceAudioBar[] bar=new WorkspaceAudioBar[1];
        try{
            instrument.runOnMainSync(()->{
                bar[0]=new WorkspaceAudioBar(activity,()->{});
                bar[0].open(name);
                assertTrue(bar[0].visible());
                assertEquals(name,bar[0].path());
                // A folder refresh only refreshes list rows, never this playback view.
                assertTrue(storeExists(wave));
                bar[0].stop();
                assertFalse(bar[0].visible());
                assertEquals("",bar[0].path());
                bar[0].destroy();
            });
            assertTrue(wave.isFile());
        }finally{
            instrument.runOnMainSync(()->{if(bar[0]!=null)bar[0].destroy();activity.finish();});
            wave.delete();
        }
    }
    @Test public void renameOnlyChangesStemAndNeverLosesMediaExtension() throws Exception {
        assertEquals(".mp4",WorkspaceRenameDialog.protectedExtension("SKANO_30s.mp4",false));
        assertEquals("SKANO_30s",WorkspaceRenameDialog.editableStem("SKANO_30s.mp4",false));
        assertEquals("Version finale.mp4",
            WorkspaceRenameDialog.resultName("SKANO_30s.mp4",false,"Version finale"));
        assertEquals("Version finale.mp4",
            WorkspaceRenameDialog.resultName("SKANO_30s.mp4",false,"Version finale.mp4"));
        assertEquals("clip.tar.gz",
            WorkspaceRenameDialog.resultName("archive.tar.gz",false,"clip"));
        assertEquals("Dossier.2026",
            WorkspaceRenameDialog.resultName("Dossier.2026",true,"Dossier.2026"));
        assertEquals("archive",
            WorkspaceRenameDialog.editableStem("archive",false));
        try{
            WorkspaceRenameDialog.resultName("SKANO_30s.mp4",false,"");
            fail("Empty name is forbidden");
        }catch(java.io.IOException expected){}
        try{
            WorkspaceRenameDialog.resultName("SKANO_30s.mp4",false,"../../private");
            fail("Path traversal is forbidden");
        }catch(java.io.IOException expected){}
        WorkspaceStore store=new WorkspaceStore(context);
        String id=UUID.randomUUID().toString();
        String old="rename-"+id+".mp4",renamed="renamed-"+id+".mp4";
        File source=store.file(old),target=store.file(renamed);
        try{
            assertTrue(source.createNewFile());
            store.move(old,WorkspaceRenameDialog.resultName(old,false,"renamed-"+id));
            assertFalse(source.exists());
            assertTrue(target.isFile());
            assertTrue(store.mime(renamed).startsWith("video/"));
        }finally{source.delete();target.delete();}
    }

    @Test public void renameDialogDisplaysCompleteEditableStemAndReadOnlyExtension()
        throws Exception {
        context.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        MainActivity activity=(MainActivity)instrument.startActivitySync(
            new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final android.app.Dialog[] opened=new android.app.Dialog[1];
        final String[] value={null};
        try{
            instrument.runOnMainSync(()->{
                opened[0]=WorkspaceRenameDialog.rename(activity,
                    "SKANO_PREMIER_OMBRE_SUR_LA_VILLE_30s.mp4",false,
                    newName->value[0]=newName);
                android.widget.EditText input=opened[0].findViewWithTag("workspace_rename_name");
                android.widget.TextView extension=opened[0].findViewWithTag("workspace_rename_extension");
                View save=opened[0].findViewWithTag("workspace_rename_save");
                assertNotNull(input);assertNotNull(extension);assertNotNull(save);
                assertEquals("SKANO_PREMIER_OMBRE_SUR_LA_VILLE_30s",
                    input.getText().toString());
                assertEquals("Extension protégée : .mp4",extension.getText().toString());
                assertEquals(0xff172537,input.getCurrentTextColor());
                assertTrue("Long filename must wrap",input.getMaxLines()>=3);
                assertEquals(0,input.getSelectionStart());
                assertEquals(input.length(),input.getSelectionEnd());
                input.setText("SKANO_RENOMMÉ");
                save.performClick();
                assertEquals("SKANO_RENOMMÉ.mp4",value[0]);
                assertFalse(opened[0].isShowing());
            });
        }finally{
            instrument.runOnMainSync(()->{
                if(opened[0]!=null&&opened[0].isShowing())opened[0].dismiss();
                activity.finish();
            });
        }
    }

    @Test public void thumbnailGridUsesReadableCellsAndVisibleMenu()throws Exception{
        context.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        MainActivity activity=(MainActivity)instrument.startActivitySync(
            new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            JSONArray data=new JSONArray().put(new JSONObject()
                .put("name","SKANO_PREMIER_OMBRE_SUR_LA_VILLE_30s.mp4")
                .put("path","test/SKANO_PREMIER_OMBRE_SUR_LA_VILLE_30s.mp4")
                .put("size",12345).put("mime_type","video/mp4").put("directory",false));
            instrument.runOnMainSync(()->{
                WorkspaceFileAdapter adapter=new WorkspaceFileAdapter(activity,
                    new LinkedHashSet<>(),new WorkspaceFileAdapter.Actions(){
                        public void openFolder(String path){}
                        public void openFile(String path){}
                        public void menu(String path){}
                        public void startSelection(String path){}
                        public void selectedChanged(){}
                    });
                adapter.submit(data,true,false,1);
                WorkspaceFileAdapter.Holder holder=adapter.onCreateViewHolder(
                    new FrameLayout(activity),1);
                adapter.onBindViewHolder(holder,0);
                assertEquals(4,holder.title.getMaxLines());
                assertTrue(holder.title.getPaddingLeft()<=MobileUi.dp(activity,4));
                assertEquals(0,holder.more.getPaddingLeft());
                assertEquals(View.VISIBLE,holder.more.getVisibility());
                assertTrue(holder.card.getLayoutParams().height>=MobileUi.dp(activity,220));
            });
        }finally{instrument.runOnMainSync(activity::finish);}
    }

    private static boolean storeExists(File file){return file.isFile();}
    private static void writeSilenceWave(File output)throws Exception{
        int sampleRate=8000,bytes=sampleRate*2;
        byte[] header=new byte[44+bytes];
        putText(header,0,"RIFF");putInt(header,4,36+bytes);
        putText(header,8,"WAVE");putText(header,12,"fmt ");
        putInt(header,16,16);putShort(header,20,1);putShort(header,22,1);
        putInt(header,24,sampleRate);putInt(header,28,sampleRate*2);
        putShort(header,32,2);putShort(header,34,16);
        putText(header,36,"data");putInt(header,40,bytes);
        try(FileOutputStream stream=new FileOutputStream(output)){stream.write(header);}
    }
    private static void putText(byte[] b,int at,String s){
        for(int i=0;i<s.length();i++)b[at+i]=(byte)s.charAt(i);
    }
    private static void putInt(byte[] b,int at,int n){
        for(int i=0;i<4;i++)b[at+i]=(byte)(n>>(8*i));
    }
    private static void putShort(byte[] b,int at,int n){
        for(int i=0;i<2;i++)b[at+i]=(byte)(n>>(8*i));
    }
}
