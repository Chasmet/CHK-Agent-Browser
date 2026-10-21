package com.chk.agentbrowser;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local smoke checks after each APK version. Uses synthetic files only, never personal media. */
final class WorkspaceHealth {
    private static final AtomicBoolean scheduled=new AtomicBoolean();
    private WorkspaceHealth(){}
    static void ensure(Context c){
        Context app=c.getApplicationContext();
        int version=version(app);
        if(app.getSharedPreferences("workspace_health",0).getInt("checked_version",-1)==version||!scheduled.compareAndSet(false,true))return;
        WorkspaceCommands.IO.execute(()->{try{run(app);}finally{scheduled.set(false);}});
    }
    private static int version(Context c){try{return c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionCode;}catch(Exception e){return -1;}}
    static JSONObject cached(Context c){try{return new JSONObject(c.getSharedPreferences("workspace_health",0).getString("report","{}"));}catch(Exception ignored){return new JSONObject();}}
    static String summary(Context c){JSONObject result=cached(c);return result.has("ok")?(result.optBoolean("ok")?"contrôles locaux réussis":"échec des contrôles locaux")+" · APK "+result.optInt("apk_version"):"contrôles locaux à effectuer";}
    static JSONObject run(Context c){
        String folder="CHK-SelfTest-"+UUID.randomUUID();JSONObject result=new JSONObject();WorkspaceStore store=null;
        AgentClient.ResultCallback active=(ok,text)->{};
        try{
            store=new WorkspaceStore(c);store.mkdir(folder);store.writeText(folder+"/source.txt","CHK diagnostic é",false);
            String expected=WorkspaceTransfer.hash(store.file(folder+"/source.txt"));
            result.put("files",store.readText(folder+"/source.txt",0).getString("text").equals("CHK diagnostic é"));
            WorkspaceArchive.command(c,"files_zip",new JSONObject().put("path",folder+"/source.txt").put("destination",folder+"/backup.zip"),active);
            WorkspaceArchive.command(c,"files_unzip",new JSONObject().put("path",folder+"/backup.zip").put("destination",folder+"/restore"),active);
            result.put("archive_sha256",expected.equals(WorkspaceTransfer.hash(store.file(folder+"/restore/source.txt"))));
            for(String format:new String[]{"pdf","docx","xlsx","pptx"}){
                JSONObject args=new JSONObject().put("path",folder+"/test."+format).put("format",format).put("text","CHK diagnostic")
                    .put("rows",new org.json.JSONArray().put(new org.json.JSONArray().put("CHK").put(1)))
                    .put("slides",new org.json.JSONArray().put(new JSONObject().put("text","CHK diagnostic")));
                result.put(format,WorkspaceDocuments.create(c,args,active).getBoolean("verified"));
            }
            result.put("ok",result.getBoolean("files")&&result.getBoolean("archive_sha256"));
        }catch(Exception e){try{result.put("ok",false).put("error","Contrôle local interrompu : "+e.getClass().getSimpleName());}catch(Exception ignored){}}
        finally{if(store!=null)try{WorkspaceArchive.remove(store.file(folder));}catch(Exception ignored){}}
        try{result.put("apk_version",version(c)).put("checked_at",System.currentTimeMillis()).put("scope","synthetic_files_and_document_packages")
            .put("browser","requires_active_page").put("external_storage","requires_owner_grant").put("video_export","requires_media");}catch(Exception ignored){}
        c.getSharedPreferences("workspace_health",0).edit().putInt("checked_version",version(c)).putString("report",result.toString()).apply();
        return result;
    }
}
