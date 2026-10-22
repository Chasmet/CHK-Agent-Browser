package com.chk.agentbrowser;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

@RunWith(AndroidJUnit4.class)
public final class UniversalFilesIntegrationTest {
    private Context app;private WorkspaceStore store;private String folder;private final AgentClient.ResultCallback active=new AgentClient.ResultCallback(){public void finish(boolean ok,String value){}};
    @Before public void setup()throws Exception{app=InstrumentationRegistry.getInstrumentation().getTargetContext();store=new WorkspaceStore(app);folder="universal-"+UUID.randomUUID();store.mkdir(folder);}
    @After public void cleanup()throws Exception{remove(store.file(folder));}
    private void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
    private String put(String name,byte[] data)throws Exception{String path=folder+"/"+name;try(OutputStream out=new FileOutputStream(store.file(path))){out.write(data);}return path;}
    private String text(String name,String text)throws Exception{return put(name,text.getBytes("UTF-8"));}
    private JSONObject invoke(String action,JSONObject args)throws Exception{CountDownLatch done=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean();AtomicReference<String> value=new AtomicReference<>();WorkspaceCommands.run(app,action,args,new AgentClient.ResultCallback(){public void finish(boolean ok,String body){success.set(ok);value.set(body);done.countDown();}});assertTrue("Timeout "+action,done.await(30,TimeUnit.SECONDS));assertTrue(value.get(),success.get());return new JSONObject(value.get());}
    @Test public void metadataOpensInsideChkAndCopiesAll()throws Exception{
        String meta="La ville s’endort, les souvenirs restent. #RapFrancais #NoirEtBlanc";String path=text("META_A_COPIER.txt",meta);
        FileViewerActivity activity=(FileViewerActivity)InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(app,FileViewerActivity.class).putExtra("path",path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{AtomicBoolean visible=new AtomicBoolean();for(int i=0;i<50;i++){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{android.view.View found=activity.getWindow().getDecorView().findViewWithTag("file_content");visible.set(found instanceof TextView&&((TextView)found).getText().toString().equals(meta));});if(visible.get())break;Thread.sleep(100);}assertTrue("TXT must be visible without another app",visible.get());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->activity.getWindow().getDecorView().findViewWithTag("Copier tout").performClick());Thread.sleep(300);AtomicReference<String> clipboard=new AtomicReference<>();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{ClipboardManager manager=(ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);clipboard.set(manager.getPrimaryClip().getItemAt(0).getText().toString());});assertEquals(meta,clipboard.get());assertEquals(meta,FileContent.full(app,path,1000));
        }finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);}
    }
    @Test public void detectsRealImageAndBinaryDespiteExtensions()throws Exception{
        String path=folder+"/photo.txt";Bitmap bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.BLUE);try(OutputStream out=new FileOutputStream(store.file(path))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
        JSONObject info=FileFormats.detect(app,path);assertEquals("png",info.getString("format"));assertEquals("image",info.getString("kind"));assertFalse(info.getBoolean("editable"));
        String binary=put("fake.txt",new byte[]{0,1,2,3});assertEquals("binary",FileFormats.detect(app,binary).getString("kind"));JSONObject hex=invoke("files_media_inspect",new JSONObject().put("path",binary));assertTrue(hex.getString("text").contains("00 01 02 03"));
        String script=text("index.html","<script>throw new Error('must not execute')</script>");assertFalse(FileFormats.detect(app,script).getBoolean("executes_content"));
    }
    @Test public void unicodePagesReconstructLargeTextWithoutTruncation()throws Exception{
        StringBuilder source=new StringBuilder();for(int i=0;i<9000;i++)source.append("Métadonnées 🥁 numéro ").append(i).append('\n');String path=text("large.txt",source.toString());assertTrue(store.file(path).length()>65536);StringBuilder found=new StringBuilder();int offset=0;
        do{JSONObject page=store.readText(path,offset);found.append(page.getString("text"));offset=page.getInt("next_offset");}while(offset>=0);assertEquals(source.toString(),found.toString());
        String utf16=put("utf16.txt",("\ufeffÉté 音楽 🥁").getBytes("UTF-16LE"));assertEquals("UTF-16LE",FileFormats.detect(app,utf16).getString("encoding"));assertEquals("Été 音楽 🥁",FileContent.full(app,utf16,1000));
        assertEquals("",FileContent.page(app,text("empty.txt",""),0).getString("text"));
    }
    @Test public void validatesJsonAndShowsQuotedCsv()throws Exception{
        String valid=text("data.json","{\"titre\":\"Été\"}");assertTrue(UniversalFiles.preview(app,valid,new JSONObject()).getBoolean("json_valid"));
        String bad=text("bad.json","{broken");assertFalse(UniversalFiles.preview(app,bad,new JSONObject()).getBoolean("json_valid"));
        JSONArray rows=UniversalFiles.table("nom,phrase\nCHK,\"bonjour, monde\"\n",',');assertEquals("bonjour, monde",rows.getJSONArray(1).getString(1));
        String csv=text("rows.csv","nom,valeur\nCHK,12\n");JSONObject converted=UniversalFiles.convert(app,new JSONObject().put("path",csv).put("destination",folder+"/rows.xlsx").put("format","xlsx"),active);assertTrue(converted.getBoolean("verified"));assertEquals("xlsx",FileFormats.detect(app,folder+"/rows.xlsx").getString("format"));
    }
    @Test public void guardedEditsKeepRecoverableOriginal()throws Exception{
        String path=text("meta.txt","ancienne version"),hash=WorkspaceTransfer.hash(store.file(path));JSONObject saved=store.editText(path,"nouvelle version",hash);assertTrue(saved.getBoolean("verified"));assertTrue(saved.has("backup_trash_id"));
        try{store.editText(path,"écrasement",hash);fail("Stale hash must fail");}catch(IOException expected){}assertEquals("nouvelle version",FileContent.full(app,path,1000));
        String large=new String(new char[90000]).replace('\0','x');assertTrue(store.writeText(folder+"/large-edit.txt",large,false).getBoolean("verified"));
    }
    @Test public void zipListsAndSelectivelyExtractsWithoutTraversal()throws Exception{
        String path=folder+"/items.zip";try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(store.file(path)))){for(String name:new String[]{"one.txt","nested/two.txt"}){zip.putNextEntry(new ZipEntry(name));zip.write(name.getBytes("UTF-8"));zip.closeEntry();}}
        assertEquals(2,UniversalFiles.archiveList(app,path,0).getInt("total"));assertEquals("one.txt",UniversalFiles.archiveText(app,path,"one.txt").getString("text"));
        UniversalFiles.extract(app,path,folder+"/selected",new JSONArray().put("nested/two.txt"),active);assertTrue(store.file(folder+"/selected/nested/two.txt").isFile());assertFalse(store.file(folder+"/selected/one.txt").exists());
        String bad=folder+"/bad.zip";try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(store.file(bad)))){zip.putNextEntry(new ZipEntry("../escape.txt"));zip.write(1);zip.closeEntry();}
        try{UniversalFiles.extract(app,bad,folder+"/unsafe",null,active);fail("Traversal must fail");}catch(IOException expected){}assertFalse(store.file(folder+"/unsafe").exists());
    }
    @Test public void sqliteReadOnlyAndImageConversionAreVerified()throws Exception{
        String path=folder+"/table.db";try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(store.file(path),null)){db.execSQL("CREATE TABLE metas(titre TEXT)");db.execSQL("INSERT INTO metas VALUES ('Dans la brume')");}String before=WorkspaceTransfer.hash(store.file(path));JSONObject db=UniversalFiles.database(app,path,new JSONObject());assertEquals("Dans la brume",db.getJSONArray("rows").getJSONArray(0).getString(0));assertEquals(before,WorkspaceTransfer.hash(store.file(path)));
        String image=folder+"/input.png";Bitmap bitmap=Bitmap.createBitmap(40,30,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.RED);try(OutputStream out=new FileOutputStream(store.file(image))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
        JSONObject result=UniversalFiles.convert(app,new JSONObject().put("path",image).put("destination",folder+"/output.jpg").put("format","jpg"),active);assertTrue(result.getBoolean("verified"));assertEquals("jpeg",FileFormats.detect(app,folder+"/output.jpg").getString("format"));
    }
    @Test public void searchAndMcpExposeActualCapabilities()throws Exception{
        String path=text("meta.txt","Les souvenirs restent");JSONObject search=invoke("files_search_content",new JSONObject().put("folder",folder).put("query","souvenirs"));assertTrue(search.getBoolean("complete"));assertEquals(path,search.getJSONArray("matches").getJSONObject(0).getString("path"));
        assertEquals("txt",invoke("files_detect_format",new JSONObject().put("path",path)).getString("format"));assertEquals("Les souvenirs restent",invoke("files_copy_content",new JSONObject().put("path",path)).getString("text"));assertTrue(invoke("files_verify",new JSONObject().put("path",path).put("expected_sha256",WorkspaceTransfer.hash(store.file(path)))).getBoolean("verified"));
    }
}
