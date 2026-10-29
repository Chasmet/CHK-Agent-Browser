package com.chk.agentbrowser;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Native commands carried by desktop/tool=studio; no PC catalogue change. */
final class StudioCommands {
    private StudioCommands(){}
    static final String[] ACTIONS={"studio_audio_tracks","studio_audio_analyze","studio_audio_set_volume","studio_audio_keyframes","studio_audio_ducking","studio_audio_mix","studio_audio_verify","studio_crop_open","studio_crop_set","studio_crop_move","studio_crop_resize","studio_crop_rotate","studio_crop_flip","studio_crop_preview","studio_crop_apply"};
    static boolean handles(String action){return Arrays.asList(ACTIONS).contains(action);}
    static void run(Context app,String action,JSONObject args,AgentClient.ResultCallback cb){
        if(!handles(action)){cb.finish(false,"Commande Studio inconnue");return;}
        if(action.equals("studio_audio_mix")&&args.optString("mode").equals("status")){StudioAutomation.command(app,"video_editor_queue_status",args,(ok,s)->reply(cb,ok,s));return;}
        if(action.equals("studio_audio_mix")&&args.optString("mode").equals("cancel")){StudioAutomation.command(app,"video_editor_queue_cancel",args,(ok,s)->reply(cb,ok,s));return;}
        VideoEditorEngine.DISK.execute(()->{try{
            if(!cb.isActive())return;JSONObject p=VideoEditorEngine.get().load(app);if(!p.getString("project_id").equals(args.getString("project_id")))throw new IOException("project_id ne correspond pas au projet ouvert");
            if(action.equals("studio_audio_tracks")){reply(cb,true,StudioMix.catalog(app,p).toString());return;}
            if(action.equals("studio_crop_open")){reply(cb,true,StudioCrop.describe(app,p,clip(p,args.getString("clip_id"))).toString());return;}
            if(action.equals("studio_crop_preview")){StudioPreviewCommands.crop(app,p,args,cb);return;}
            if(action.equals("studio_audio_analyze")){JSONArray measures=new JSONArray();for(StudioMix.Source source:StudioMix.sources(p)){
                    if(args.has("clip_id")&&!args.getString("clip_id").equals(source.clip.optString("clip_id")))continue;
                    if(args.has("track_id")&&!args.getString("track_id").equals(source.track))continue;
                    try{measures.put(StudioAudioAnalysis.levels(new WorkspaceStore(app).file(source.clip.getString("path")),source.clip.optLong("start_ms"),source.clip.optLong("duration_ms"),cb).put("clip_id",source.clip.getString("clip_id")).put("track_id",source.track));}
                    catch(IOException e){measures.put(new JSONObject().put("clip_id",source.clip.getString("clip_id")).put("state","failed").put("error",e.getMessage()));}
                }
                JSONObject out=new JSONObject().put("project_id",p.getString("project_id")).put("revision",p.optInt("revision")).put("measurements",measures).put("state","completed");if(args.optBoolean("recognize_voice"))out.put("voice_regions",StudioAudioAnalysis.voices(app,p,args.optString("language","fr"),cb)).put("speech_detector","vosk_local_word_timestamps");reply(cb,true,out.toString());return;
            }
            if(action.equals("studio_audio_verify")){verify(app,p,args,cb);return;}
            if(action.equals("studio_audio_keyframes")&&args.optString("mode").equals("read")){JSONObject t=target(p,args);reply(cb,true,new JSONObject().put("revision",p.optInt("revision")).put("keyframes",t.optJSONArray("gain_keyframes")).toString());return;}
            if(action.equals("studio_audio_ducking")&&args.optString("mode").equals("read")){reply(cb,true,new JSONObject().put("revision",p.optInt("revision")).put("ducking",p.optJSONObject("audio_ducking")).toString());return;}
            String request=args.getString("request_id");if(!request.matches("[A-Za-z0-9_-]{1,80}"))throw new IOException("request_id invalide");String hash=fingerprint(action,args);JSONObject receipts=p.optJSONObject("studio_receipts");if(receipts==null){receipts=new JSONObject();p.put("studio_receipts",receipts);}
            JSONObject old=receipts.optJSONObject(request);boolean replay=old!=null;
            if(replay&&!hash.equals(old.optString("hash")))throw new IOException("request_id réutilisé avec des paramètres différents");
            if(!replay){
                if(args.getInt("expected_revision")!=p.optInt("revision"))throw new IOException("Révision périmée : relire le projet");
                String state=VideoEditorEngine.get().status().optString("state");if(state.equals("running")||state.equals("preparing"))throw new IOException("Export actif : mutation refusée");
                if(action.startsWith("studio_crop_"))StudioCrop.edit(app,clip(p,args.getString("clip_id")),action,args);
                else{
                    p.put("audio_mix_version",2);if(!p.has("mute_original"))p.put("mute_original",false);
                    if(action.equals("studio_audio_set_volume")){JSONObject t=target(p,args);if(args.has("role")&&!args.has("track_id"))throw new IOException("Le rôle cible une piste track_id");if(args.has("mute")&&!args.getBoolean("mute")){if(args.optString("track_id").equals("original"))p.put("mute_original",false);else if(args.has("clip_id")&&isVideo(p,t)&&p.optBoolean("mute_original",false))StudioEdits.clipVolume(p,t,t.optDouble("volume",1));}if(args.has("percent")||args.has("db"))t.put("volume",StudioMix.linear(args));
                        for(String key:new String[]{"mute","solo","fade_in_ms","fade_out_ms","role"})if(args.has(key))t.put(key,args.get(key));
                        if(args.has("offset_ms")){if(!args.has("clip_id"))throw new IOException("offset_ms cible un clip");JSONObject c=clip(p,args.getString("clip_id"));if(isVideo(p,c))throw new IOException("Déplacer un plan vidéo via video_editor_timeline");c.put("offset_ms",args.getLong("offset_ms"));}
                        if(args.has("assign_track_id")){if(!args.has("clip_id"))throw new IOException("clip_id requis");StudioMix.settings(p,args.getString("assign_track_id"));clip(p,args.getString("clip_id")).put("track_id",args.getString("assign_track_id"));}
                    }else if(action.equals("studio_audio_keyframes")){JSONObject t=target(p,args);JSONArray frames=t.optJSONArray("gain_keyframes");if(frames==null)frames=new JSONArray();String mode=args.optString("mode","set");
                        if(mode.equals("replace"))frames=new JSONArray(args.getJSONArray("keyframes").toString());
                        else{long at=args.getLong("time_ms");int found=-1;for(int i=0;i<frames.length();i++)if(frames.getJSONObject(i).getLong("time_ms")==at)found=i;
                            if(mode.equals("delete")||mode.equals("move")){if(found<0)throw new IOException("Keyframe absent");JSONObject point=frames.getJSONObject(found);frames.remove(found);if(mode.equals("move"))frames.put(new JSONObject(point.toString()).put("time_ms",args.getLong("to_ms")));}
                            else if(mode.equals("set")){JSONObject k=new JSONObject().put("time_ms",at).put("volume",StudioMix.linear(args));if(found>=0)frames.put(found,k);else frames.put(k);}else throw new IOException("Mode keyframes inconnu");
                        }
                        List<JSONObject> sorted=new ArrayList<>();for(int i=0;i<frames.length();i++)sorted.add(frames.getJSONObject(i));Collections.sort(sorted,(a,b)->Long.compare(a.optLong("time_ms"),b.optLong("time_ms")));frames=new JSONArray();for(JSONObject f:sorted)frames.put(f);t.put("gain_keyframes",frames);
                    }else if(action.equals("studio_audio_ducking")){JSONObject d=p.optJSONObject("audio_ducking");if(d==null)d=new JSONObject();
                        if(args.has("limiter_ceiling_db"))p.put("limiter_ceiling_db",args.getDouble("limiter_ceiling_db"));
                        for(String key:new String[]{"enabled","reduction_db","threshold_db","attack_ms","release_ms"})if(args.has(key))d.put(key,args.get(key));
                        if(args.optBoolean("analyze")){d.put("voice_regions",StudioAudioAnalysis.voices(app,p,args.optString("language","fr"),cb)).put("detector","vosk_local_word_timestamps").put("analysis_revision",p.optInt("revision")).put("voice_timeline_signature",StudioMix.voiceSignature(app,p));}
                        if(args.has("voice_regions"))d.put("voice_regions",new JSONArray(args.getJSONArray("voice_regions").toString())).put("detector","explicit_regions");
                        if(d.optBoolean("enabled")&&!d.has("voice_regions"))throw new IOException("Analyser la voix avant d'activer le ducking");p.put("audio_ducking",d);
                    }else if(!action.equals("studio_audio_mix"))throw new IOException("Commande audio inconnue");
                }
                if(!cb.isActive())throw new IOException("Commande interrompue avant sauvegarde");VideoEditorEngine.get().validate(app,p);int revision=p.optInt("revision")+1;p.put("revision",revision);receipts.put(request,new JSONObject().put("hash",hash).put("revision",revision));while(receipts.length()>32)receipts.remove(receipts.keys().next());StudioProjectLibrary.save(app,p);
            }
            if(action.equals("studio_audio_mix")){JSONObject snapshot=new JSONObject(p.toString());if(args.has("output"))snapshot.put("output",args.getString("output"));
                JSONObject queueArgs=new JSONObject().put("request_id",request).put("projects",new JSONArray().put(new JSONObject().put("project",snapshot)));StudioAutomation.command(app,"video_editor_queue_add",queueArgs,(ok,s)->reply(cb,ok,s));return;}
            JSONObject out=action.startsWith("studio_crop_")?StudioCrop.describe(app,p,clip(p,args.getString("clip_id"))):StudioMix.catalog(app,p);out.put("state","completed").put("request_id",request).put("request_replayed",replay).put("original_revision",receipts.getJSONObject(request).getInt("revision"));reply(cb,true,out.toString());
        }catch(Exception e){reply(cb,false,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}});
    }
    static JSONObject clip(JSONObject p,String id)throws Exception{for(String key:new String[]{"clips","audio"}){JSONArray a=p.optJSONArray(key);if(a!=null)for(int i=0;i<a.length();i++)if(id.equals(a.getJSONObject(i).optString("clip_id")))return a.getJSONObject(i);}throw new IOException("clip_id introuvable");}
    static boolean isVideo(JSONObject p,JSONObject c)throws Exception{JSONArray a=p.getJSONArray("clips");for(int i=0;i<a.length();i++)if(a.getJSONObject(i)==c)return true;return false;}
    static JSONObject target(JSONObject p,JSONObject args)throws Exception{if(args.has("clip_id")&&args.has("track_id"))throw new IOException("Cibler clip_id OU track_id");if(args.has("clip_id"))return clip(p,args.getString("clip_id"));return StudioMix.settings(p,args.getString("track_id"));}
    private static String canonical(Object o)throws Exception{if(o instanceof JSONObject){JSONObject j=(JSONObject)o;List<String> keys=new ArrayList<>();for(Iterator<String> it=j.keys();it.hasNext();)keys.add(it.next());Collections.sort(keys);StringBuilder s=new StringBuilder("{");for(String key:keys)s.append(JSONObject.quote(key)).append(':').append(canonical(j.get(key))).append(',');return s.append('}').toString();}if(o instanceof JSONArray){StringBuilder s=new StringBuilder("[");JSONArray a=(JSONArray)o;for(int i=0;i<a.length();i++)s.append(canonical(a.get(i))).append(',');return s.append(']').toString();}return String.valueOf(o);}
    private static String fingerprint(String action,JSONObject input)throws Exception{JSONObject copy=new JSONObject(input.toString());copy.remove("expected_revision");copy.remove("request_id");return android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest((action+canonical(copy)).getBytes(StandardCharsets.UTF_8)),android.util.Base64.NO_WRAP);}
    private static void reply(AgentClient.ResultCallback cb,boolean ok,String s){WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(ok,s);});}
    private static void verify(Context app,JSONObject p,JSONObject args,AgentClient.ResultCallback cb)throws Exception{
        if(!StudioMix.enabled(p))throw new IOException("Activer le mixage V2 avant vérification");JSONObject candidate=new JSONObject(p.toString());if(args.has("output"))candidate.put("output",args.getString("output"));File file=new WorkspaceStore(app).file(candidate.getString("output"));JSONObject metadata=VideoEditorEngine.get().verifyProjectOutput(app,candidate);if(!metadata.optBoolean("valid"))throw new IOException("MP4 non valide : "+metadata);
        File reference=StudioPcmMix.render(app,p,cb,null);int seconds=(int)Math.min(3,StudioEdits.total(p)/1000);if(seconds<1)throw new IOException("Au moins une seconde requise pour vérifier l'audio");long start=Math.max(0,Math.min(args.optLong("start_ms"),StudioEdits.total(p)-seconds*1000));JSONObject correlation=StudioAudioAnalysis.correlation(StudioAudioAnalysis.samples(reference,start,seconds,cb),StudioAudioAnalysis.samples(file,start,seconds,cb));JSONObject levels=StudioAudioAnalysis.levels(file,0,StudioEdits.total(p),cb);
        boolean valid=correlation.optBoolean("matches_reference")&&levels.optLong("clipped_samples")==0;reply(cb,true,new JSONObject().put("state","completed").put("project_id",p.getString("project_id")).put("revision",p.optInt("revision")).put("verified",valid).put("metadata",metadata).put("audio",levels).put("reference_comparison",correlation).put("sampled_start_ms",start).put("sampled_duration_ms",seconds*1000).put("mix_receipt",StudioAutomation.read(new File(reference+".json"))).put("all_sources_individually_identified",false).put("lip_sync_verified",false).toString());
    }
}
