package com.chk.agentbrowser;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.util.UUID;

/** Real Android storage/SQLite/provider checks, isolated by unique paths and note IDs. */
@RunWith(AndroidJUnit4.class)
public final class WorkspaceIntegrationTest {
    private android.app.Instrumentation getInstrumentation(){return InstrumentationRegistry.getInstrumentation();}
    @Test public void testFileLifecycleAndProvider()throws Exception{
        android.content.Context c=getInstrumentation().getTargetContext();WorkspaceStore store=new WorkspaceStore(c);String dir="test-"+UUID.randomUUID();store.mkdir(dir);
        String path=dir+"/Idée.txt";store.writeText(path,"Bonjour é📝",false);
        assertEquals("Bonjour é📝",store.readText(path,0).getString("text"));
        try{store.writeText(path,"Overwrite",false);fail("Must refuse overwrite");}catch(IOException expected){}
        assertEquals(1,store.list(dir,"",0).getJSONArray("items").length());
        InputStream in=c.getContentResolver().openInputStream(store.uri(path));assertNotNull(in);in.close();
        store.move(path,dir+"/Renommé.txt");assertFalse(store.file(path).exists());
        String trash=store.trash(dir+"/Renommé.txt");assertFalse(store.file(dir+"/Renommé.txt").exists());store.restore(trash);
        assertTrue(store.file(dir+"/Renommé.txt").exists());
        WorkspaceStore reopened=new WorkspaceStore(c);assertEquals("Bonjour é📝",reopened.readText(dir+"/Renommé.txt",0).getString("text"));
        store.file(dir+"/Renommé.txt").delete();store.file(dir).delete();
    }
    @Test public void testNoteRevisionConflictAndTrash()throws Exception{
        WorkspaceStore s=new WorkspaceStore(getInstrumentation().getTargetContext());JSONObject first=s.saveNote("","Mon idée","Brouillon","https://example.com",true,0);String id=first.getString("id");
        JSONObject next=s.saveNote(id,"Mon idée","Version récente","https://example.com",true,1);assertEquals(2,next.getInt("revision"));
        try{s.saveNote(id,"Mon idée","Version ancienne","",false,1);fail("Must refuse stale revision");}catch(IOException expected){}
        assertEquals("Version récente",s.note(id).getString("body"));s.deleteNote(id,true,2);assertTrue(s.note(id).getBoolean("deleted"));s.deleteNote(id,false,3);assertFalse(s.note(id).getBoolean("deleted"));
    }
    @Test public void testTraversalCannotReachPreferences()throws Exception{
        WorkspaceStore s=new WorkspaceStore(getInstrumentation().getTargetContext());for(String path:new String[]{"../shared_prefs/browser_mcp_v1.xml","/sdcard/file","foo/../../bar","foo/.hidden"}){try{s.file(path);fail(path);}catch(IOException expected){}}
    }
    @Test public void testUriImportAndBinaryChunks()throws Exception{
        android.content.Context c=getInstrumentation().getTargetContext();WorkspaceStore s=new WorkspaceStore(c);File input=new File(c.getCacheDir(),"import-test.bin");byte[] data=new byte[18000];for(int i=0;i<data.length;i++)data[i]=(byte)(i%251);try(FileOutputStream out=new FileOutputStream(input)){out.write(data);}String path="test-"+UUID.randomUUID()+".bin";s.importUri(Uri.fromFile(input),path);
        JSONObject chunk=s.readBytes(path,8192);assertEquals(16384,chunk.getLong("next_offset"));assertEquals(8192,android.util.Base64.decode(chunk.getString("base64"),0).length);assertEquals(-1,s.readBytes(path,16384).getLong("next_offset"));input.delete();s.file(path).delete();
    }
    @Test public void testEditorAutosaveKeepsLatestDraft()throws Exception{
        android.content.Context c=getInstrumentation().getTargetContext();String name="Editor-"+UUID.randomUUID();
        android.content.Intent intent=new android.content.Intent(c,NoteEditorActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("title",name).putExtra("body","Initial").putExtra("source","https://example.com/source");
        NoteEditorActivity editor=(NoteEditorActivity)getInstrumentation().startActivitySync(intent);
        getInstrumentation().runOnMainSync(()->{((android.widget.EditText)editor.findViewById(R.id.note_body)).setText("Version finale é📝");editor.onBackPressed();});
        JSONObject saved=null;for(int attempt=0;attempt<40;attempt++){getInstrumentation().waitForIdleSync();WorkspaceStore store=new WorkspaceStore(c);org.json.JSONArray rows=store.notes(name,false,0);if(rows.length()>0){saved=store.note(rows.getJSONObject(0).getString("id"));if(saved.getString("body").equals("Version finale é📝"))break;}Thread.sleep(150);}
        assertNotNull(saved);assertEquals("Version finale é📝",saved.getString("body"));assertEquals("https://example.com/source",saved.getString("source_url"));
    }
    @Test public void testMobileNavigationRetainsBrowser()throws Exception{
        android.content.Context c=getInstrumentation().getTargetContext();c.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();android.content.Intent i=new android.content.Intent(c,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity a=(MainActivity)getInstrumentation().startActivitySync(i);getInstrumentation().runOnMainSync(()->{
            assertNotNull(a.findViewById(R.id.nav_files));assertTrue(a.findViewById(R.id.address_bar).getVisibility()==android.view.View.VISIBLE);
            a.findViewById(R.id.nav_files).performClick();assertEquals(android.view.View.VISIBLE,a.findViewById(R.id.workspace_container).getVisibility());assertEquals(android.view.View.GONE,a.findViewById(R.id.address_bar).getVisibility());
            a.findViewById(R.id.nav_browser).performClick();assertEquals(android.view.View.VISIBLE,a.findViewById(R.id.web_container).getVisibility());a.finish();
        });
    }
}
