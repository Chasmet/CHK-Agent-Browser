package com.chk.agentbrowser;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;

/** Native Studio API transported by the unchanged Local 3 desktop command. */
final class StudioMcp {
    private StudioMcp(){}
    static final List<String> OPERATIONS=Arrays.asList("capabilities","audio_tracks","audio_analyze","audio_set_volume","audio_track_create","audio_clip_set","audio_keyframes","audio_ducking","audio_mix","audio_mix_status","audio_verify","crop_open","crop_set","crop_move","crop_resize","crop_rotate","crop_flip","crop_preview","crop_apply");
    static String operation(JSONObject args){String op=args.optString("operation");return op.startsWith("studio_")?op.substring(7):op;}
    static boolean readOnly(JSONObject args){String op=operation(args);return Arrays.asList("capabilities","audio_tracks","audio_analyze","audio_mix_status","audio_verify","crop_open","crop_preview").contains(op)||op.equals("audio_keyframes")&&args.optString("mode","read").equals("read");}
    static void execute(Context app,JSONObject args,AgentClient.ResultCallback cb){
        VideoEditorEngine.DISK.execute(()->{if(!cb.isActive())return;try{
            String op=operation(args);JSONObject p=op.equals("capabilities")?null:project(app,args);
            if(op.equals("audio_mix")){mix(app,args,p,cb);return;}
            if(op.equals("crop_preview")){preview(app,args,p,cb);return;}
            JSONObject result=dispatch(app,args,cb);finish(cb,true,result);
        }catch(Exception e){fail(cb,e);}});
    }
    static JSONObject project(Context app,JSONObject args)throws Exception{
        JSONObject p=VideoEditorEngine.get().load(app);
        if(!args.has("project_id")||!p.getString("project_id").equals(args.getString("project_id")))throw new IOException("PROJECT_MISMATCH: project_id requis ; ouvrir puis relire le projet");return p;
    }
    static JSONObject dispatch(Context app,JSONObject args)throws Exception{return dispatch(app,args,null);}
    static JSONObject dispatch(Context app,JSONObject args,AgentClient.ResultCallback active)throws Exception{
        String op=operation(args);if(!OPERATIONS.contains(op))throw new IOException("UNSUPPORTED_STUDIO_OPERATION: "+op);
        if(op.equals("capabilities"))return new JSONObject().put("protocol",3).put("transport","task_submit action=desktop, args.tool=studio")
            .put("operations",new JSONArray(OPERATIONS)).put("time_units","integer milliseconds; audio summing at 48000 samples/s")
            .put("write_guard","project_id, expected_revision, request_id required; atomic project receipts; stable clip_id and track_id")
            .put("gain","0..200 percent, db=20*log10(linear); -Infinity or percent=0 means silence")
            .put("limitations",new JSONArray(Arrays.asList("LUFS and true-peak not measured on Android; independent FFmpeg verification required","speed resampling changes pitch in audio model v2","animated spatial crop is unsupported","Tor network state is not observable from external Tor Browser")));
        JSONObject p=project(app,args);
        if(op.equals("audio_tracks"))return tracks(app,p);
        if(op.equals("audio_mix_status"))return new JSONObject().put("state","completed").put("engine",VideoEditorEngine.get().status()).put("project_id",p.getString("project_id")).put("revision",p.getInt("revision"));
        if(op.equals("audio_analyze")){
            JSONObject target=clip(p,args),source=source(p,target);long duration=Math.min(target.optLong("duration_ms",10000),args.optLong("duration_ms",120000));
            JSONObject analysis=StudioAudioAnalysis.measure(app,target.getString("path"),target.optLong("start_ms")+args.optLong("start_ms"),duration,active);
            if(args.optBoolean("detect_speech")){JSONArray spans=StudioAudioMixer.speech(app,p,source.getString("track_id"),active);analysis.put("voice_regions",spans).put("speech_presence_verified",true).put("speech_engine","vosk");}
            return analysis.put("project_id",p.getString("project_id")).put("revision",p.getInt("revision")).put("clip_id",target.getString("studio_id"));
        }
        if(op.equals("audio_verify")){
            JSONObject metadata=VideoEditorEngine.get().verifyProjectOutput(app,p);
            if(!metadata.optBoolean("exists"))throw new IOException("OUTPUT_NOT_FOUND");
            JSONObject measured=StudioAudioAnalysis.measure(app,p.getString("output"),0,StudioEdits.total(p),active);
            return new JSONObject().put("state","completed").put("project_id",p.getString("project_id")).put("revision",p.getInt("revision"))
                .put("metadata",metadata).put("decoded_audio",measured).put("sources_audible_verified",false).put("lip_sync_verified",false)
                .put("verification_complete",false).put("note","Décodage et niveaux vérifiés ; contrôler les sources et la synchronisation avec un lecteur indépendant");
        }
        if(op.equals("crop_open"))return cropResult(app,p,clip(p,args));
        if(op.equals("audio_keyframes")&&args.optString("mode","read").equals("read"))return result(p,target(p,args)).put("gain_keyframes",target(p,args).optJSONArray("gain_keyframes")==null?new JSONArray():target(p,args).getJSONArray("gain_keyframes"));
        JSONObject replay=replay(p,args);if(replay!=null)return replay;
        guard(p,args);String state=VideoEditorEngine.get().status().optString("state");if(state.equals("preparing")||state.equals("running"))throw new IOException("EXPORT_ACTIVE: édition suspendue");
        JSONObject target=null;
        if(op.startsWith("audio_")){
            StudioAudio.enable(p);
            if(op.equals("audio_track_create")){
                String id=args.getString("track_id");if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IOException("TRACK_ID_INVALID");
                JSONArray tracks=p.getJSONArray("audio_tracks");for(int i=0;i<tracks.length();i++)if(id.equals(tracks.getJSONObject(i).optString("id")))throw new IOException("TRACK_EXISTS");
                target=StudioAudio.track(id,args.optString("name",id));tracks.put(target);
            }else target=target(p,args);
            if(op.equals("audio_set_volume")){
                if(args.has("percent")||args.has("db"))target.put("volume",StudioAudio.value(args));
                for(String key:new String[]{"mute","solo"})if(args.has(key)){
                    if(key.equals("solo")&&!args.optString("scope","clip").equals("track"))throw new IOException("Solo concerne une piste");target.put(key,args.getBoolean(key));
                }
                for(String key:new String[]{"audio_fade_in_ms","audio_fade_out_ms"})if(args.has(key))target.put(key,args.getLong(key));
            }else if(op.equals("audio_clip_set")){
                if(args.optString("scope","clip").equals("track"))throw new IOException("CLIP_REQUIRED");
                JSONObject c=source(p,target);if(!c.getString("lane").equals("audio"))throw new IOException("Les clips vidéo suivent la timeline vidéo");
                if(args.has("track_id")){StudioAudio.track(p,args.getString("track_id"));target.put("track_id",args.getString("track_id"));}
                for(String key:new String[]{"offset_ms","speed"})if(args.has(key))target.put(key,args.get(key));
            }else if(op.equals("audio_keyframes"))keyframes(target,args);
            else if(op.equals("audio_ducking")){
                if(!args.optString("scope").equals("track"))throw new IOException("TRACK_SCOPE_REQUIRED");
                JSONObject config=args.getJSONObject("config");String voice=config.optString("voice_track_id","original");StudioAudio.track(p,voice);
                if(voice.equals(args.getString("track_id")))throw new IOException("DUCKING_SELF_SIDECHAIN");
                for(Iterator<String> keys=config.keys();keys.hasNext();){String k=keys.next();if(!Arrays.asList("enabled","voice_track_id","reduction_db","threshold_dbfs","attack_ms","release_ms").contains(k))throw new IOException("Ducking : champ inconnu "+k);}
                target.put("ducking",new JSONObject(config.toString()));
            }
        }else{
            target=clip(p,args);if(!source(p,target).getString("lane").equals("clips"))throw new IOException("VIDEO_CLIP_REQUIRED");JSONObject info=dimensions(app,target);
            JSONObject crop=op.equals("crop_apply")?new JSONObject(args.getJSONObject("crop").toString()):StudioCrop.changed(StudioCrop.read(target),op.substring(5),args,info.getInt("width"),info.getInt("height"));
            StudioCrop.validate(crop);target.put("crop",crop);
        }
        StudioAudio.ids(p);VideoEditorEngine.get().validate(app,p);p.put("revision",p.getInt("revision")+1);
        JSONObject receipt=op.startsWith("crop_")?cropResult(app,p,target):result(p,target);
        receipt.put("request_id",args.getString("request_id")).put("verified","project_persistence_only");
        commit(app,p,args,receipt);
        return receipt;
    }
    static JSONObject replay(JSONObject p,JSONObject args)throws Exception{
        String id=args.getString("request_id");if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IOException("REQUEST_ID_INVALID");
        JSONObject ledger=p.optJSONObject("studio_receipts"),record=ledger==null?null:ledger.optJSONObject(id);if(record==null)return null;
        if(!record.getString("fingerprint").equals(fingerprint(args)))throw new IOException("REQUEST_ID_REUSED_WITH_DIFFERENT_PARAMETERS");
        return new JSONObject(record.getJSONObject("result").toString()).put("replayed",true).put("current_revision",p.getInt("revision"));
    }
    static void guard(JSONObject p,JSONObject args)throws Exception{if(!args.has("expected_revision")||args.getInt("expected_revision")!=p.getInt("revision"))throw new IOException("REVISION_CONFLICT: actual="+p.getInt("revision"));}
    static void commit(Context app,JSONObject p,JSONObject args,JSONObject result)throws Exception{
        JSONObject ledger=p.optJSONObject("studio_receipts");if(ledger==null)ledger=new JSONObject();
        if(ledger.length()>=128)throw new IOException("RECEIPT_LEDGER_FULL: dupliquer le projet pour poursuivre sans perdre la protection contre les doublons");
        ledger.put(args.getString("request_id"),new JSONObject().put("fingerprint",fingerprint(args)).put("result",result));p.put("studio_receipts",ledger);
        StudioProjectLibrary.save(app,p);JSONObject actual=VideoEditorEngine.get().load(app);
        if(actual.getInt("revision")!=p.getInt("revision")||!actual.getJSONObject("studio_receipts").has(args.getString("request_id")))throw new IOException("PERSISTENCE_UNCONFIRMED: relire avant répétition");
    }
    static String fingerprint(JSONObject args)throws Exception{return AgentVision.digest(canonical(args));}
    static String canonical(Object value)throws Exception{
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;ArrayList<String> keys=new ArrayList<>();for(Iterator<String> k=o.keys();k.hasNext();)keys.add(k.next());Collections.sort(keys);StringBuilder s=new StringBuilder("{");for(String k:keys)s.append(JSONObject.quote(k)).append(':').append(canonical(o.get(k))).append(',');return s.append('}').toString();}
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;StringBuilder s=new StringBuilder("[");for(int i=0;i<a.length();i++)s.append(canonical(a.get(i))).append(',');return s.append(']').toString();}return value instanceof String?JSONObject.quote((String)value):String.valueOf(JSONObject.wrap(value));
    }
    static JSONObject clip(JSONObject p,JSONObject args)throws Exception{
        String id=args.optString("clip_id");for(String lane:new String[]{"clips","audio"}){JSONArray items=p.optJSONArray(lane);if(items==null)continue;for(int i=0;i<items.length();i++){JSONObject c=items.getJSONObject(i);if(!id.isEmpty()&&id.equals(c.optString("studio_id")))return c;}}
        throw new IOException("CLIP_NOT_FOUND: clip_id stable requis");
    }
    static JSONObject source(JSONObject p,JSONObject clip)throws Exception{JSONArray a=StudioAudio.sources(p);for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("studio_id").equals(clip.getString("studio_id")))return a.getJSONObject(i);throw new IOException("CLIP_NOT_FOUND");}
    static JSONObject target(JSONObject p,JSONObject args)throws Exception{return args.optString("scope","clip").equals("track")?StudioAudio.track(p,args.getString("track_id")):clip(p,args);}
    static JSONObject result(JSONObject p,JSONObject target)throws Exception{return new JSONObject().put("state","completed").put("project_id",p.getString("project_id")).put("revision",p.getInt("revision"))
        .put("target",new JSONObject(target.toString())).put("volume_percent",target.optDouble("volume",1)*100).put("gain_db",StudioAudio.db(target.optDouble("volume",1)));}
    static void keyframes(JSONObject target,JSONObject args)throws Exception{
        String mode=args.optString("mode","read");JSONArray list=target.optJSONArray("gain_keyframes");if(list==null)list=new JSONArray();
        if(mode.equals("replace"))list=new JSONArray(args.getJSONArray("points").toString());
        else if(mode.equals("upsert")||mode.equals("delete")){
            String id=args.getString("point_id");if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IOException("KEYFRAME_ID_INVALID");
            JSONArray out=new JSONArray();for(int i=0;i<list.length();i++)if(!id.equals(list.getJSONObject(i).getString("id")))out.put(list.get(i));
            if(mode.equals("upsert"))out.put(new JSONObject().put("id",id).put("time_ms",args.getLong("time_ms")).put("gain",StudioAudio.value(args)));list=out;
        }else throw new IOException("KEYFRAME_MODE_INVALID");
        ArrayList<JSONObject> points=new ArrayList<>();for(int i=0;i<list.length();i++)points.add(list.getJSONObject(i));Collections.sort(points,(a,b)->Long.compare(a.optLong("time_ms"),b.optLong("time_ms")));target.put("gain_keyframes",new JSONArray(points));
    }
    static JSONObject tracks(Context app,JSONObject p)throws Exception{
        JSONArray rows=StudioAudio.sources(p);for(int i=0;i<rows.length();i++){
            JSONObject c=rows.getJSONObject(i);c.put("format",MediaInspection.info(app,c.getString("path"))).put("volume_percent",c.optDouble("volume",1)*100).put("gain_db",StudioAudio.db(c.optDouble("volume",1))).put("audible_by_project_settings",StudioAudio.audible(p,c));
        }
        return new JSONObject().put("state","completed").put("project_id",p.getString("project_id")).put("revision",p.getInt("revision"))
            .put("audio_model_version",p.optInt("audio_model_version",1)).put("mute_original",p.optBoolean("mute_original",false)).put("tracks",p.optJSONArray("audio_tracks")==null?new JSONArray().put(StudioAudio.track("original","Voix originale")).put(StudioAudio.track("audio","Audio séquentiel")):p.getJSONArray("audio_tracks")).put("clips",rows);
    }
    static JSONObject dimensions(Context app,JSONObject c)throws Exception{JSONObject info=MediaInspection.info(app,c.getString("path"));if(Math.abs(info.optInt("rotation"))%180==90){int width=info.optInt("width");info.put("width",info.optInt("height")).put("height",width);}return info;}
    static JSONObject cropResult(Context app,JSONObject p,JSONObject c)throws Exception{
        JSONObject info=dimensions(app,c);if(info.optInt("width")<=0)throw new IOException("VIDEO_DIMENSIONS_UNAVAILABLE");
        return result(p,c).put("clip_id",c.getString("studio_id")).put("source_width",info.getInt("width")).put("source_height",info.getInt("height"))
            .put("reference","display-oriented source, top-left origin; before clip effects").put("crop",StudioCrop.read(c)).put("duration_ms",c.getLong("duration_ms")).put("animated_crop_supported",false);
    }
    static void preview(Context app,JSONObject args,JSONObject p,AgentClient.ResultCallback cb)throws Exception{
        if(!AgentClient.get(app).isPreviewAllowed())throw new IOException("PREVIEW_SHARING_DISABLED");JSONObject c=clip(p,args),source=source(p,c);if(!source.getString("lane").equals("clips"))throw new IOException("VIDEO_CLIP_REQUIRED");
        long time=source.getLong("timeline_ms")+Math.min(StudioAudio.duration(c)-1,Math.max(0,args.optLong("time_ms",StudioAudio.duration(c)/2)));
        WorkspaceCommands.UI.post(()->{
            StudioRenderPreview preview=new StudioRenderPreview(app);preview.start(p,new StudioRenderPreview.Result(){
                public void failed(String error){preview.cancel();fail(cb,new IOException(error));}
                public void ready(File file){VideoEditorEngine.DISK.execute(()->{try{
                    if(!cb.isActive())return;JSONObject now=project(app,args);if(now.getInt("revision")!=p.optInt("revision"))throw new IOException("REVISION_CHANGED_DURING_PREVIEW");
                    android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever();android.graphics.Bitmap bitmap;
                    try{retriever.setDataSource(file.getAbsolutePath());bitmap=retriever.getFrameAtTime(time*1000,android.media.MediaMetadataRetriever.OPTION_CLOSEST);}finally{retriever.release();}
                    if(bitmap==null)throw new IOException("PREVIEW_FRAME_MISSING");ByteArrayOutputStream bytes=new ByteArrayOutputStream();try{bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,88,bytes);}finally{bitmap.recycle();}
                    JSONObject result=cropResult(app,p,c).put("render_source","media3_composition_preview_mp4").put("time_ms",time).put("preview_sha256",WorkspaceTransfer.hash(file))
                        .put("jpeg_base64",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP));finish(cb,true,result);
                }catch(Exception e){fail(cb,e);}finally{WorkspaceCommands.UI.post(preview::cancel);}});}
            });
        });
    }
    static void mix(Context app,JSONObject args,JSONObject p,AgentClient.ResultCallback cb)throws Exception{
        String request=args.getString("request_id");if(!request.matches("[A-Za-z0-9_-]{1,80}"))throw new IOException("REQUEST_ID_INVALID");guard(p,args);if(!StudioAudio.enabled(p))throw new IOException("AUDIO_MODEL_V2_REQUIRED: régler une piste avant de mixer");
        // Durable queue snapshots and its request IDs protect repeat submissions and process restarts.
        JSONObject submit=new JSONObject().put("request_id",request).put("projects",new JSONArray().put(new JSONObject().put("project",p)));
        VideoEditorEngine.get().command(app,"video_editor_queue_add",submit,(ok,text)->{if(!ok){fail(cb,new IOException(text));return;}pollMix(app,args,p,cb);});
    }
    static void pollMix(Context app,JSONObject args,JSONObject p,AgentClient.ResultCallback cb){WorkspaceCommands.UI.postDelayed(()->{
        if(!cb.isActive())return;
        VideoEditorEngine.get().command(app,"video_editor_queue_status",new JSONObject(),(ok,text)->{
            if(!cb.isActive())return;try{if(!ok)throw new IOException(text);JSONArray jobs=new JSONObject(text).getJSONArray("jobs");JSONObject job=null;
                for(int i=0;i<jobs.length();i++)if(args.optString("request_id").equals(jobs.getJSONObject(i).optString("request_id")))job=jobs.getJSONObject(i);
                if(job==null)throw new IOException("MIX_JOB_NOT_FOUND");String state=job.getString("state");
                if(state.equals("completed")){finish(cb,true,new JSONObject().put("state","completed").put("project_id",p.optString("project_id")).put("revision",p.optInt("revision")).put("job",job).put("full_audio_verification",false));return;}
                if(Arrays.asList("failed","cancelled","interrupted").contains(state))throw new IOException(job.toString());pollMix(app,args,p,cb);
            }catch(Exception e){fail(cb,e);}
        });
    },1000);}
    private static void finish(AgentClient.ResultCallback cb,boolean ok,JSONObject value){WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(ok,value.toString());});}
    private static void fail(AgentClient.ResultCallback cb,Exception e){WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(false,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());});}
}
