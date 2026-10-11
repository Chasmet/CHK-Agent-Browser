package com.chk.agentbrowser;

import android.app.DownloadManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** User journeys on real Android views, storage, SQLite and DownloadManager. */
@RunWith(AndroidJUnit4.class)
public final class UserExperienceIntegrationTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private String unique(){return UUID.randomUUID().toString().substring(0,8);}
    private boolean has(JSONArray rows,String key,String value){
        for(int i=0;i<rows.length();i++)if(value.equals(rows.optJSONObject(i).optString(key)))return true;
        return false;
    }
    private void deleteFixture(File file){
        File[] children=file.listFiles();if(children!=null)for(File child:children)deleteFixture(child);
        file.delete();
    }
    private MainActivity main(Intent intent){
        app.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        return (MainActivity)instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private MainActivity recreate(MainActivity activity)throws Exception {
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(MainActivity.class.getName(),null,false);
        try{
            instrumentation.runOnMainSync(activity::recreate);
            MainActivity next=(MainActivity)monitor.waitForActivityWithTimeout(10000);
            assertNotNull("Activity restored by Android",next);instrumentation.waitForIdleSync();return next;
        }finally{instrumentation.removeMonitor(monitor);}
    }

    @Test public void categorySearchFindsNestedFilesAndGlobalSearchFindsFolders()throws Exception {
        WorkspaceStore store=new WorkspaceStore(app);String id=unique();
        String videoBase=WorkspaceCategoryView.destination(store,"video","");
        String audioBase=WorkspaceCategoryView.destination(store,"audio","");
        String album=videoBase+"/album-"+id,nested=album+"/chapitre-"+id;
        String sound=audioBase+"/cible-"+id+".wav",clip=nested+"/cible-"+id+".mp4";
        String legacy="ancien-"+id,oldClip=legacy+"/cible-"+id+".mp4";
        store.mkdir(album);store.mkdir(nested);store.mkdir(legacy);
        assertTrue(store.file(clip).createNewFile());assertTrue(store.file(sound).createNewFile());assertTrue(store.file(oldClip).createNewFile());
        try{
            JSONArray videos=WorkspaceCategoryView.query(app,"","video","cible-"+id,"name",0).getJSONArray("items");
            assertTrue("Category search reaches an owned subfolder",has(videos,"path",clip));
            assertTrue("Category search reaches a legacy folder",has(videos,"path",oldClip));
            assertFalse("Audio in another category is excluded",has(videos,"path",sound));
            assertTrue(has(WorkspaceCategoryView.query(app,album,"video","cible-"+id,"name",0).getJSONArray("items"),"path",clip));
            assertTrue("Global search includes matching directories",has(WorkspaceCategoryView.query(app,"","all","chapitre-"+id,"name",0).getJSONArray("items"),"path",nested));
            assertFalse("Recent remains a file-only view",has(WorkspaceCategoryView.query(app,"","recent","chapitre-"+id,"name",0).getJSONArray("items"),"path",nested));
            assertEquals(0,WorkspaceCategoryView.query(app,"","all","","name",0).getJSONArray("items").length());
            assertTrue("Source paths are untouched",store.file(clip).exists());
        }finally{deleteFixture(store.file(album));deleteFixture(store.file(legacy));store.file(sound).delete();}
    }

    @Test public void archivesAndUnknownFormatsAreReachableInDocuments()throws Exception {
        WorkspaceStore store=new WorkspaceStore(app);String id=unique();
        String zip="archive-"+id+".zip",binary="autre-"+id+".bin";
        store.writeText(zip,"zip fixture",false);store.writeText(binary,"binary fixture",false);
        try{
            JSONArray docs=WorkspaceCategoryView.query(app,"","document",id,"name",0).getJSONArray("items");
            assertTrue(has(docs,"path",zip));assertTrue(has(docs,"path",binary));
            assertFalse(has(WorkspaceCategoryView.query(app,"","video",id,"name",0).getJSONArray("items"),"path",zip));
        }finally{store.file(zip).delete();store.file(binary).delete();}
    }

    @Test public void searchSurvivesSpaceSwitchAndRecreationAndBackClearsIt()throws Exception {
        String files="fichier-"+unique(),notes="note-"+unique();
        MainActivity initial=main(new Intent(app,MainActivity.class));MainActivity restored=null;
        try{
            instrumentation.runOnMainSync(()->{
                initial.findViewById(R.id.nav_files).performClick();
                EditText field=initial.getWindow().getDecorView().findViewWithTag("workspace_search");
                field.setVisibility(View.VISIBLE);field.setText(files);
                initial.findViewById(R.id.nav_notes).performClick();
                EditText noteField=initial.getWindow().getDecorView().findViewWithTag("workspace_search");
                noteField.setVisibility(View.VISIBLE);noteField.setText(notes);
                initial.onBackPressed();
                assertEquals("",noteField.getText().toString());
                assertEquals(View.VISIBLE,initial.findViewById(R.id.workspace_container).getVisibility());
                initial.findViewById(R.id.nav_files).performClick();
                EditText returned=initial.getWindow().getDecorView().findViewWithTag("workspace_search");
                assertEquals(files,returned.getText().toString());assertEquals(View.VISIBLE,returned.getVisibility());
            });
            restored=recreate(initial);MainActivity activity=restored;
            instrumentation.runOnMainSync(()->{
                EditText query=activity.getWindow().getDecorView().findViewWithTag("workspace_search");
                assertEquals(files,query.getText().toString());assertEquals(View.VISIBLE,query.getVisibility());
                activity.onBackPressed();assertEquals("",query.getText().toString());assertEquals(View.GONE,query.getVisibility());
                assertEquals(View.VISIBLE,activity.findViewById(R.id.workspace_container).getVisibility());
                assertNotNull(activity.getWindow().getDecorView().findViewWithTag("files_category_video"));
            });
        }finally{MainActivity activity=restored;instrumentation.runOnMainSync(()->{if(activity!=null)activity.finish();initial.finish();});}
    }

    @Test public void cutNetworkLinkDoesNotAddAnotherTabAfterRecreation()throws Exception {
        SharedPreferences prefs=app.getSharedPreferences("mobile_ui",0);
        String old=prefs.getString("tabs","[]");int oldActive=prefs.getInt("active_tab",0);
        prefs.edit().putString("tabs","[]").putInt("active_tab",0).commit();
        MainActivity initial=main(new Intent(app,MainActivity.class).putExtra("cutstudio_url","https://example.com/"));
        MainActivity restored=null;AtomicInteger count=new AtomicInteger();
        try{
            instrumentation.runOnMainSync(()->{
                count.set(Integer.parseInt(((TextView)initial.findViewById(R.id.tab_count)).getText().toString()));
                assertEquals(2,count.get());assertFalse(initial.getIntent().hasExtra("cutstudio_url"));
            });
            restored=recreate(initial);MainActivity next=restored;
            instrumentation.runOnMainSync(()->assertEquals(count.get(),Integer.parseInt(((TextView)next.findViewById(R.id.tab_count)).getText().toString())));
        }finally{
            MainActivity next=restored;instrumentation.runOnMainSync(()->{if(next!=null)next.finish();initial.finish();});
            instrumentation.waitForIdleSync();prefs.edit().putString("tabs",old).putInt("active_tab",oldActive).commit();
        }
    }

    @Test public void incompleteDownloadsAreHiddenAndCannotBeImported()throws Exception {
        DownloadManager downloads=(DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE);
        assertNotNull(downloads);File root=app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        assertNotNull(root);assertTrue(root.isDirectory()||root.mkdirs());
        String id=unique(),partial="partial-"+id+".txt",complete="complete-"+id+".txt",output="import-"+id+".txt";
        DownloadManager.Request request=new DownloadManager.Request(Uri.parse("https://example.invalid/"+partial))
            .setTitle(partial).setAllowedNetworkTypes(0)
            .setDestinationInExternalFilesDir(app,Environment.DIRECTORY_DOWNLOADS,partial);
        long downloadId=downloads.enqueue(request);WorkspaceStore store=new WorkspaceStore(app);
        try{
            try(FileOutputStream out=new FileOutputStream(new File(root,partial))){out.write("incomplete".getBytes("UTF-8"));}
            try(FileOutputStream out=new FileOutputStream(new File(root,complete))){out.write("complete".getBytes("UTF-8"));}
            assertTrue(CompletedDownloads.unavailable(app).contains(new File(root,partial).getCanonicalPath()));
            JSONArray available=store.downloads();assertFalse(has(available,"name",partial));assertTrue(has(available,"name",complete));
            try{store.importDownload(partial,output);fail("Partial download must be refused");}catch(IOException expected){}
            assertFalse(store.file(output).exists());store.importDownload(complete,output);
            assertEquals("complete",store.readText(output,0).getString("text"));
        }finally{downloads.remove(downloadId);new File(root,partial).delete();new File(root,complete).delete();store.file(output).delete();}
    }

    @Test public void bulkMoveContinuesAfterCollisionAndReportsEveryItem()throws Exception {
        WorkspaceStore store=new WorkspaceStore(app);String root="batch-"+unique();
        store.mkdir(root);store.mkdir(root+"/source");store.mkdir(root+"/destination");
        String a=root+"/source/a.txt",b=root+"/source/b.txt",c=root+"/source/c.txt";
        store.writeText(a,"first",false);store.writeText(b,"keep source",false);store.writeText(c,"third",false);
        store.writeText(root+"/destination/b.txt","keep destination",false);
        try{
            JSONObject result=WorkspaceBatch.run(app,Arrays.asList(a,b,c),1,root+"/destination");
            assertEquals(2,result.getJSONArray("succeeded").length());assertEquals(1,result.getJSONArray("failed").length());
            assertEquals(b,result.getJSONArray("failed").getJSONObject(0).getString("path"));
            assertFalse(store.file(a).exists());assertFalse(store.file(c).exists());
            assertEquals("first",store.readText(root+"/destination/a.txt",0).getString("text"));
            assertEquals("third",store.readText(root+"/destination/c.txt",0).getString("text"));
            assertEquals("keep source",store.readText(b,0).getString("text"));
            assertEquals("keep destination",store.readText(root+"/destination/b.txt",0).getString("text"));
        }finally{deleteFixture(store.file(root));}
    }

    @Test public void leavingAnEmptyNoteDoesNotCreateAnUnwantedRecord()throws Exception {
        WorkspaceStore store=new WorkspaceStore(app);int before=store.notes("Sans titre",false,0).length();
        NoteEditorActivity editor=(NoteEditorActivity)instrumentation.startActivitySync(new Intent(app,NoteEditorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(editor::onBackPressed);instrumentation.waitForIdleSync();
        assertEquals(before,store.notes("Sans titre",false,0).length());
    }

    @Test public void connectionLabelDoesNotClaimSuccessWhenStoppedOrRetrying()throws Exception {
        AgentClient agent=AgentClient.get(app);SharedPreferences prefs=app.getSharedPreferences("browser_mcp_v1",0);
        boolean enabled=prefs.getBoolean("enabled",false);
        Field running=AgentClient.class.getDeclaredField("running"),contact=AgentClient.class.getDeclaredField("lastContact"),error=AgentClient.class.getDeclaredField("lastError");
        running.setAccessible(true);contact.setAccessible(true);error.setAccessible(true);
        boolean oldRunning=running.getBoolean(agent);long oldContact=contact.getLong(agent);Object oldError=error.get(agent);
        try{
            prefs.edit().putBoolean("enabled",true).commit();running.setBoolean(agent,false);
            assertTrue(agent.shortConnectionStatus().contains("arrêtée"));
            running.setBoolean(agent,true);contact.setLong(agent,SystemClock.elapsedRealtime());error.set(agent,"network failure");
            assertTrue(agent.shortConnectionStatus().contains("Reconnexion"));assertFalse(agent.shortConnectionStatus().contains("connecté"));
            error.set(agent,"");contact.setLong(agent,0);assertTrue(agent.shortConnectionStatus().contains("en cours"));
        }finally{running.setBoolean(agent,oldRunning);contact.setLong(agent,oldContact);error.set(agent,oldError);prefs.edit().putBoolean("enabled",enabled).commit();}
    }
}
