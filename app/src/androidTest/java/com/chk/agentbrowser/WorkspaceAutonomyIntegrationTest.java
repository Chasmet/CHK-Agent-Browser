package com.chk.agentbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real dispatcher, Android disk and Office/PDF packages. No personal files or live relay involved. */
@RunWith(AndroidJUnit4.class)
public final class WorkspaceAutonomyIntegrationTest {
    private Context app;private WorkspaceStore store;private String folder,id;private SharedPreferences render,local;
    private boolean oldRender,oldAuto,oldLocal;private String oldUrl;
    private static final AgentClient.ResultCallback ACTIVE=(ok,text)->{};
    @Before public void setup()throws Exception{
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();store=new WorkspaceStore(app);
        render=app.getSharedPreferences("browser_mcp_v1",0);local=app.getSharedPreferences("chk_local2_v1",0);
        oldRender=render.getBoolean("enabled",false);oldAuto=render.getBoolean("autonomous_mode",false);oldLocal=local.getBoolean("enabled",false);oldUrl=local.getString("url","");
        render.edit().putBoolean("enabled",false).putBoolean("autonomous_mode",true).commit();local.edit().putBoolean("enabled",true).putString("url","").commit();
        id="autonomy-"+UUID.randomUUID();folder="Autonomy-"+UUID.randomUUID();store.mkdir(folder);
    }
    @After public void cleanup()throws Exception{
        WorkspaceArchive.remove(store.file(folder));
        for(String kind:new String[]{"transfers","tasks"})for(String suffix:new String[]{"","-other"}){
            File f=AgentFiles.journal(app,kind,id+suffix);f.delete();new File(f.getParentFile(),id+suffix+".part").delete();
        }
        render.edit().putBoolean("enabled",oldRender).putBoolean("autonomous_mode",oldAuto).commit();local.edit().putBoolean("enabled",oldLocal).putString("url",oldUrl).commit();
    }
    private static final class Reply{boolean ok;String text;JSONObject json()throws Exception{assertTrue(text,ok);return new JSONObject(text);}}
    private Reply call(String action,JSONObject args)throws Exception{
        Reply result=new Reply();CountDownLatch latch=new CountDownLatch(1);
        AgentClient.get(app).executeLocal2("android_action",new JSONObject().put("action",action).put("args",args),(ok,text)->{result.ok=ok;result.text=text;latch.countDown();});
        assertTrue("Command timeout: "+action,latch.await(30,TimeUnit.SECONDS));return result;
    }
    private JSONObject transfer(String action,JSONObject args)throws Exception{return call("files_transfer_"+action,args.put("id",id)).json();}
    private JSONObject begin(byte[] bytes,String sha)throws Exception{return transfer("begin",new JSONObject().put("path",folder+"/binary.bin").put("size",bytes.length).put("sha256",sha));}
    private static String sha(byte[] bytes)throws Exception{return WorkspaceTransfer.hash(new ByteArrayInputStream(bytes));}
    private JSONObject chunk(byte[] bytes,int offset,int length)throws Exception{return new JSONObject().put("offset",offset).put("base64",Base64.encodeToString(Arrays.copyOfRange(bytes,offset,offset+length),Base64.NO_WRAP));}
    @Test public void resumableBinaryImportAndExportWithRepeatedBlocks()throws Exception{
        byte[] bytes=new byte[110000];new Random(42).nextBytes(bytes);begin(bytes,sha(bytes));
        assertEquals(49152,transfer("write",chunk(bytes,0,49152)).getLong("received"));
        assertEquals(49152,transfer("write",chunk(bytes,0,49152)).getLong("received"));
        File journal=AgentFiles.journal(app,"transfers",id),part=new File(journal.getParentFile(),id+".part");
        try(FileOutputStream out=new FileOutputStream(part,true)){out.write(new byte[71]);}
        assertEquals(49152,transfer("status",new JSONObject()).getLong("received"));assertEquals(49152,part.length());
        for(int offset=49152;offset<bytes.length;offset+=49152)transfer("write",chunk(bytes,offset,Math.min(49152,bytes.length-offset)));
        JSONObject committed=transfer("commit",new JSONObject());assertTrue(committed.getBoolean("verified"));assertEquals(sha(bytes),committed.getString("sha256"));
        assertTrue(transfer("commit",new JSONObject()).getBoolean("verified"));
        JSONObject info=call("files_hash",new JSONObject().put("path",folder+"/binary.bin")).json();ByteArrayOutputStream assembled=new ByteArrayOutputStream();
        for(long offset=0;offset<bytes.length;){JSONObject read=call("files_transfer_read",new JSONObject().put("path",folder+"/binary.bin").put("offset",offset).put("size",info.getLong("size")).put("updated",info.getLong("updated"))).json();byte[] block=Base64.decode(read.getString("base64"),Base64.DEFAULT);assertEquals(sha(block),read.getString("chunk_sha256"));assembled.write(block);offset+=block.length;}
        assertArrayEquals(bytes,assembled.toByteArray());assertFalse(render.getBoolean("enabled",true));
    }
    @Test public void rejectsWrongHashOutOfOrderAndConflictingReplays()throws Exception{
        byte[] bytes="bonjour".getBytes(StandardCharsets.UTF_8);begin(bytes,sha("different".getBytes(StandardCharsets.UTF_8)));
        assertFalse(call("files_transfer_write",chunk(bytes,1,2).put("id",id)).ok);
        transfer("write",chunk(bytes,0,bytes.length));
        assertFalse(call("files_transfer_write",new JSONObject().put("id",id).put("offset",0).put("base64",Base64.encodeToString(new byte[bytes.length],Base64.NO_WRAP))).ok);
        assertFalse(call("files_transfer_commit",new JSONObject().put("id",id)).ok);assertFalse(store.file(folder+"/binary.bin").exists());
        transfer("abort",new JSONObject());assertEquals("cancelled",transfer("status",new JSONObject()).getString("state"));
    }
    @Test public void missingConfirmedDataCanStillBeAborted()throws Exception{
        byte[] bytes={1,2,3};begin(bytes,sha(bytes));transfer("write",chunk(bytes,0,3));File journal=AgentFiles.journal(app,"transfers",id);new File(journal.getParentFile(),id+".part").delete();
        assertFalse(call("files_transfer_status",new JSONObject().put("id",id)).ok);assertEquals("cancelled",transfer("abort",new JSONObject()).getString("state"));
    }
    @Test public void transferNeverOverwritesOrReusesAnotherManifest()throws Exception{
        byte[] bytes={1};store.writeText(folder+"/binary.bin","original",false);
        assertFalse(call("files_transfer_begin",new JSONObject().put("id",id).put("path",folder+"/binary.bin").put("size",1).put("sha256",sha(bytes))).ok);
        store.file(folder+"/binary.bin").delete();begin(bytes,sha(bytes));
        assertFalse(call("files_transfer_begin",new JSONObject().put("id",id+"-other").put("path",folder+"/binary.bin").put("size",1).put("sha256",sha(bytes))).ok);
        assertFalse(call("files_transfer_begin",new JSONObject().put("id",id).put("path",folder+"/other.bin").put("size",1).put("sha256",sha(bytes))).ok);
    }
    @Test public void zipBackupAndRestorePreserveBytes()throws Exception{
        store.mkdir(folder+"/source");store.mkdir(folder+"/source/nested");store.writeText(folder+"/source/nested/Idée.txt","Sauvegarde é📝",false);
        call("files_zip",new JSONObject().put("path",folder+"/source").put("destination",folder+"/backup.zip")).json();
        JSONObject unzip=call("files_unzip",new JSONObject().put("path",folder+"/backup.zip").put("destination",folder+"/restored")).json();assertTrue(unzip.getBoolean("verified"));
        assertEquals(WorkspaceTransfer.hash(store.file(folder+"/source/nested/Idée.txt")),WorkspaceTransfer.hash(store.file(folder+"/restored/source/nested/Idée.txt")));
        assertFalse(call("files_unzip",new JSONObject().put("path",folder+"/backup.zip").put("destination",folder+"/restored")).ok);
        assertTrue(call("files_copy",new JSONObject().put("path",folder+"/source").put("destination",folder+"/copy")).json().getBoolean("verified"));
        assertEquals(WorkspaceTransfer.hash(store.file(folder+"/source/nested/Idée.txt")),WorkspaceTransfer.hash(store.file(folder+"/copy/nested/Idée.txt")));
    }
    @Test public void maliciousZipNeverPublishesPartialOutput()throws Exception{
        File archive=store.file(folder+"/bad.zip");
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(new FileOutputStream(archive))){zip.putNextEntry(new java.util.zip.ZipEntry("good.txt"));zip.write(1);zip.closeEntry();zip.putNextEntry(new java.util.zip.ZipEntry("../escape.txt"));zip.write(2);zip.closeEntry();}
        assertFalse(call("files_unzip",new JSONObject().put("path",folder+"/bad.zip").put("destination",folder+"/bad-output")).ok);
        assertFalse(store.file(folder+"/bad-output").exists());assertFalse(store.file(folder+"/escape.txt").exists());
    }
    @Test public void createsRealDocumentsAndExportsInteroperabilityFixtures()throws Exception{
        File fixtures=new File(app.getExternalFilesDir(null),"autonomy-fixtures");assertTrue(fixtures.isDirectory()||fixtures.mkdirs());
        for(String format:new String[]{"pdf","docx","xlsx","pptx","json","csv","txt"}){
            JSONObject args=new JSONObject().put("path",folder+"/document."+format).put("format",format).put("title","CHK autonomie").put("text","Bonjour é & <Android>\nDeuxième paragraphe");
            args.put("rows",new JSONArray().put(new JSONArray().put("Libellé").put("Valeur")).put(new JSONArray().put("é & <Android>").put(42)).put(new JSONArray().put("=1+1").put(true)));
            args.put("slides",new JSONArray().put(new JSONObject().put("title","CHK autonomie").put("text","Bonjour é & <Android>")).put(new JSONObject().put("title","Suite").put("text","Deuxième paragraphe")));
            args.put("data",new JSONObject().put("text","Bonjour é & <Android>"));
            JSONObject created=call("document_create",args).json();assertTrue(created.getBoolean("verified"));assertEquals(WorkspaceTransfer.hash(store.file(args.getString("path"))),created.getString("sha256"));
            File source=store.file(args.getString("path"));try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(new File(fixtures,"document."+format))){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            assertFalse(call("document_create",args).ok);
        }
        assertTrue(DocumentReader.readFile(app,folder+"/document.docx",1,1,0).getString("text").contains("Bonjour é & <Android>"));
        assertTrue(DocumentReader.readFile(app,folder+"/document.pdf",1,1,0).getString("text").contains("Bonjour"));
    }
    @Test public void capabilitySchemasAndVersionAreAvailableWithoutRender()throws Exception{
        Set<String> actions=new HashSet<>();int offset=0;do{JSONObject page=call("files_capabilities",new JSONObject().put("offset",offset)).json();assertEquals(4,page.getInt("workspace_version"));JSONArray rows=page.getJSONArray("actions");for(int i=0;i<rows.length();i++){JSONObject item=rows.getJSONObject(i);assertTrue(actions.add(item.getString("action")));assertEquals("object",item.getJSONObject("inputSchema").getString("type"));}offset=page.getInt("next_offset");}while(offset>=0);
        assertTrue(actions.contains("files_transfer_commit"));assertTrue(actions.contains("document_create"));assertTrue(actions.contains("video_editor_wait"));
        JSONObject status=call("workspace_status",new JSONObject()).json();assertTrue(status.getBoolean("resumable_transfers"));assertFalse(status.getJSONObject("local2").getBoolean("render_enabled"));
    }
    @Test public void syntheticPostUpdateHealthChecksRunWithoutPersonalFiles()throws Exception{
        JSONObject health=call("files_self_test",new JSONObject()).json();assertTrue(health.toString(),health.getBoolean("ok"));assertTrue(health.getBoolean("archive_sha256"));assertTrue(health.getBoolean("pptx"));assertEquals("requires_media",health.getString("video_export"));
    }
    @Test public void oversizedResponsesRemainCompleteAndReadable()throws Exception{
        StringBuilder text=new StringBuilder();for(int i=0;i<40000;i++)text.append('é');String original=new JSONObject().put("text",text).toString();
        JSONObject envelope=new JSONObject(Local2Results.prepare(app,original));assertTrue(envelope.getBoolean("result_in_file"));assertFalse(envelope.getBoolean("truncated"));File result=store.file(envelope.getString("result_file"));
        try{assertEquals(original.getBytes(StandardCharsets.UTF_8).length,result.length());assertEquals(WorkspaceTransfer.hash(result),envelope.getString("sha256"));}finally{result.delete();}
    }
    @Test public void workflowCreatesDocumentArchivesItAndVerifiesHash()throws Exception{
        JSONArray steps=new JSONArray().put(new JSONObject().put("id","document").put("action","document_create").put("args",new JSONObject().put("path",folder+"/task.txt").put("format","txt").put("text","autonomie"))
            .put("verify",new JSONObject().put("action","files_hash").put("args",new JSONObject().put("path",folder+"/task.txt")).put("expect",new JSONObject().put("sha256","$document.sha256"))))
            .put(new JSONObject().put("id","archive").put("action","files_zip").put("args",new JSONObject().put("path",folder+"/task.txt").put("destination",folder+"/task.zip"))
            .put("verify",new JSONObject().put("action","files_stat").put("args",new JSONObject().put("path",folder+"/task.zip")).put("expect",new JSONObject().put("exists",true))));
        call("files_task",new JSONObject().put("operation","submit").put("id",id).put("steps",steps)).json();
        JSONObject status=null;for(int i=0;i<100;i++){status=call("files_task",new JSONObject().put("operation","status").put("id",id)).json();if(status.getString("state").equals("completed"))break;Thread.sleep(100);}
        assertNotNull(status);assertEquals(status.toString(),"completed",status.getString("state"));assertEquals(2,status.getInt("completed"));
        call("files_task",new JSONObject().put("operation","submit").put("id",id).put("steps",steps)).json();assertTrue(store.file(folder+"/task.zip").isFile());
    }
    @Test public void cancellationBeforePublishLeavesNoDocumentOrArchive()throws Exception{
        AgentClient.ResultCallback stopped=new AgentClient.ResultCallback(){public void finish(boolean ok,String text){}public boolean isActive(){return false;}};
        try{WorkspaceDocuments.create(app,new JSONObject().put("path",folder+"/stopped.txt").put("format","txt").put("text","halt"),stopped);fail();}catch(IOException expected){}
        assertFalse(store.file(folder+"/stopped.txt").exists());store.writeText(folder+"/source.txt","safe",false);
        try{WorkspaceArchive.command(app,"files_zip",new JSONObject().put("path",folder+"/source.txt").put("destination",folder+"/stopped.zip"),stopped);fail();}catch(IOException expected){}
        assertFalse(store.file(folder+"/stopped.zip").exists());
    }
}
