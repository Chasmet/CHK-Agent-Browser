package com.chk.agentbrowser;

import android.content.Context;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** Production tools travel over Local 3's existing desktop carrier; no PC endpoint is added. */
final class ProductionMcp {
    static final java.util.List<String> OPERATIONS=Arrays.asList("capabilities","health","project_create","project_status","asset_add",
        "record_start","record_stop","record_status","narration_voices","narrate","narration_status","narration_resume","narration_cancel",
        "assemble","export","export_status","verify","publication_prepare","publication_status");
    private ProductionMcp(){}
    static boolean readOnly(JSONObject args){return Arrays.asList("capabilities","health","project_status","record_status",
        "narration_voices","narration_status","export_status","verify","publication_status").contains(args.optString("operation"));}
    static JSONObject capabilities(Context app)throws Exception {
        return new JSONObject().put("protocol",1).put("transport","desktop / tool=production")
            .put("installed_version",app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionName)
            .put("operations",new JSONArray(OPERATIONS)).put("project_root","Projects/<request_id>")
            .put("capture_engine","Android private VirtualDisplay + actual WebView + MediaRecorder H264")
            .put("capture_audio",false).put("capture_max_ms",600000).put("recording_requires_api",26)
            .put("narration_provider","installed Android TextToSpeech engine; French voices and network requirement reported by narration_voices")
            .put("subtitle_timing","actual synthesized utterance durations; word alignment can be regenerated with existing Vosk tool")
            .put("montage_export",1080).put("publication_transport","existing publication_prepare/submit with observed platform confirmation")
            .put("cut_metadata_full_json",true).put("pc_server_changed",false)
            .put("schemas",new JSONObject()
                .put("project_create","request_id:string, name:string -> project_path")
                .put("project_status","project_path:string")
                .put("asset_add","project_path:string, source_path:string (existing CHK file; copied and hashed)")
                .put("record_start","project_path, request_id, tab_id?:string, max_duration_ms?:1000..600000, width?:int, height?:int")
                .put("record_stop / record_status","project_path, request_id; stop is asynchronous, poll until completed and valid=true")
                .put("narration_voices","no arguments; installed French voice ids, quality and network requirement")
                .put("narrate","project_path, request_id, text:string OR segments:[{text, offset_ms?:int, pause_ms?:int, rate?:0.5..2, pitch?:0.5..2}], voice?:id, rate?:0.5..2, pitch?:0.5..2, allow_network?:boolean, trim_silence?:boolean")
                .put("narration_status / narration_resume / narration_cancel","project_path, request_id; resume reuses the saved request and confirmed segments")
                .put("assemble","project_path, request_id, clips?:[{path,start_ms?,duration_ms?}], narration_id?:string, narration_offset_ms?:int, title?:string, credits?:string, aspect_ratio?:9:16|16:9|1:1, music_path?:string, music_volume?:0..2, fade_ms?:int, camera_motion?:boolean, mute_original?:boolean")
                .put("export","project_path, request_id; adds the saved montage to Studio's durable export queue")
                .put("export_status / verify","project_path; verify decodes sampled output frames and measures actual audio")
                .put("publication_prepare","project_path, request_id, platform, destination_url, account, title, caption, hashtags?:[string], schedule_at?:ISO with timezone")
                .put("publication_status","project_path, request_id")
                .put("workflow","desktop tool=tasks operation=submit, id, auto_resume=true, steps:[{id,action,args,verify:{action,args,expect|contains,timeout_ms?,poll_ms?}}]"));
    }
    static void execute(Context app,WebView web,JSONObject args,AgentClient.ResultCallback callback){
        String op=args.optString("operation","capabilities");
        if(!OPERATIONS.contains(op)){callback.finish(false,"Opération de production inconnue");return;}
        try{
            if(op.equals("record_start")){
                WebView target=web!=null&&args.optString("tab_id").isEmpty()?web:AgentControl.resolveWeb(app,args,callback);
                BrowserRecording.start(app,target,args,callback);return;
            }
            if(op.equals("record_stop")){BrowserRecording.stop(app,args,callback);return;}
            if(op.equals("narration_voices")){ProductionNarration.voices(app,callback);return;}
            if(op.equals("narrate")){ProductionNarration.start(app,args,callback);return;}
            if(op.equals("narration_resume")){ProductionNarration.resume(app,args,callback);return;}
            if(op.equals("narration_cancel")){ProductionNarration.cancel(app,args,callback);return;}
            if(op.equals("export")){
                String root=ProductionProject.root(args);JSONObject production=ProductionProject.read(app,root);
                String path=production.getJSONObject("montage").getString("montage_path");
                JSONObject request=new JSONObject().put("request_id","production-"+AgentVision.digest(root+"/"+ProductionProject.id(args)).substring(0,40))
                    .put("projects",new JSONArray().put(new JSONObject().put("path",path)));
                StudioAutomation.command(app,"video_editor_queue_add",request,(ok,text)->callback.finish(ok,text));return;
            }
            java.util.concurrent.ExecutorService executor=op.equals("assemble")||op.equals("verify")?VideoEditorEngine.DISK:WorkspaceCommands.IO;
            executor.execute(()->{
                if(!callback.isActive())return;
                try{callback.finish(true,dispatch(app,op,args,callback).toString());}
                catch(Exception error){callback.finish(false,error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());}
            });
        }catch(Exception error){callback.finish(false,error.getMessage());}
    }
    static JSONObject dispatch(Context app,String op,JSONObject args,AgentClient.ResultCallback active)throws Exception {
        if(op.equals("capabilities")||op.equals("health"))return capabilities(app)
            .put("recorder_active",BrowserRecording.active()).put("recording_api_supported",android.os.Build.VERSION.SDK_INT>=26);
        if(op.equals("project_create"))return ProductionProject.create(app,args);
        if(op.equals("project_status"))return ProductionProject.status(app,args);
        if(op.equals("record_status"))return BrowserRecording.status(app,args);
        if(op.equals("narration_status"))return ProductionNarration.status(app,args);
        if(op.equals("assemble"))return ProductionMontage.assemble(app,args);
        if(op.equals("asset_add"))return addAsset(app,args,active);
        if(op.equals("export_status"))return new JSONObject().put("engine",VideoEditorEngine.get().status())
            .put("queue",StudioAutomation.queueSnapshot(app)).put("project",ProductionProject.status(app,args));
        String root=ProductionProject.root(args);JSONObject production=ProductionProject.read(app,root);
        if(op.equals("verify")){
            String path=production.getJSONObject("montage").getString("montage_path");
            JSONObject montage=AgentFiles.read(new WorkspaceStore(app).file(path));
            JSONObject result=VideoEditorEngine.get().verifyProjectOutput(app,montage);
            if(result.optBoolean("valid")&&result.optBoolean("has_audio"))result.put("audio_analysis",
                StudioAudioAnalysis.measure(app,montage.getString("output"),0,StudioEdits.total(montage),active));
            return result.put("subtitle_timing_method",montage.optString("subtitle_timing_method"))
                .put("last_frame_hold_ms",montage.optLong("last_frame_hold_ms"));
        }
        if(op.equals("publication_prepare")){
            JSONObject montage=production.getJSONObject("montage");
            JSONObject project=AgentFiles.read(new WorkspaceStore(app).file(montage.getString("montage_path")));
            JSONObject verification=VideoEditorEngine.get().verifyProjectOutput(app,project);
            if(!verification.optBoolean("valid"))throw new IOException("Vérifie le MP4 final avant de préparer sa publication");
            String id="production-"+AgentVision.digest(root+"/"+ProductionProject.id(args)).substring(0,40);
            JSONObject input=new JSONObject(args.toString()).put("id",id)
                .put("media_paths",new JSONArray().put(project.getString("output")));
            JSONObject result=PublicationJobs.command(app,PublicationJobs.ACTION_PREPARE,input);
            StudioAutomation.write(new WorkspaceStore(app).file(root+"/Publications/"+ProductionProject.id(args)+".json"),result);
            return result;
        }
        if(op.equals("publication_status"))return PublicationJobs.command(app,PublicationJobs.ACTION_STATUS,
            new JSONObject().put("id","production-"+AgentVision.digest(root+"/"+ProductionProject.id(args)).substring(0,40)));
        throw new IOException("Opération de production inconnue");
    }
    private static JSONObject addAsset(Context app,JSONObject args,AgentClient.ResultCallback active)throws Exception {
        String root=ProductionProject.root(args);ProductionProject.read(app,root);
        WorkspaceStore store=new WorkspaceStore(app);String source=args.getString("source_path");File original=store.file(source);
        if(!original.isFile()||original.length()==0)throw new IOException("Média source absent");
        String mime=store.mime(source),kind=mime.startsWith("video/")?"video":mime.startsWith("image/")?"image":mime.startsWith("audio/")?"audio":"document";
        String target=root+"/"+(kind.equals("document")?"Documents":"Medias")+"/"+original.getName();
        if(!source.equals(target)){
            File copy=store.file(target);
            if(copy.exists()){
                if(!WorkspaceTransfer.hash(original).equals(WorkspaceTransfer.hash(copy)))throw new IOException("Un média différent porte déjà ce nom dans le projet");
            }else WorkspaceArchive.copy(app,source,target,active);
        }
        return ProductionProject.asset(app,root,target,kind,new JSONObject().put("source_path",source));
    }
}
