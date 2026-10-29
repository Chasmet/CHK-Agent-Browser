package com.chk.agentbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.UUID;

/** Revision-guarded audio commands shared by UI and MCP Local 3. */
final class StudioAudioControl {
    private StudioAudioControl(){}
    static boolean handles(String action){return action.startsWith("video_editor_audio_");}
    static void command(Context app,String action,JSONObject args,VideoEditorEngine.Done cb){
        if("video_editor_audio_mix".equals(action)){
            // Queue the REAL Media3 export with a stable request ID; an enqueued
            // render never masquerades as a completed or verified MP4.
            VideoEditorEngine.DISK.execute(()->{
                try{
                    JSONObject current=VideoEditorEngine.get().load(app);
                    guard(current,args);
                    JSONObject project=new JSONObject(current.toString());
                    StudioAutomation.command(app,"video_editor_queue_add",
                        new JSONObject().put("request_id",args.getString("request_id"))
                            .put("projects",new JSONArray().put(new JSONObject().put("project",project))),
                        (ok,reply)->{
                            if(!ok){cb.completed(false,reply);return;}
                            try{
                                JSONObject queue=new JSONObject(reply);JSONArray rows=queue.getJSONArray("jobs");
                                JSONObject selected=null;
                                for(int i=0;i<rows.length();i++)if(args.getString("request_id").equals(rows.getJSONObject(i).optString("request_id")))
                                    selected=rows.getJSONObject(i);
                                if(selected==null)throw new IllegalStateException("Aucun export portant cette requête n'a été enregistré.");
                                JSONObject status=new JSONObject().put("state",selected.optString("state","queued"))
                                    .put("request_id",args.getString("request_id")).put("project_id",project.optString("project_id"))
                                    .put("revision",project.optInt("revision")).put("export_id",selected.optString("id"))
                                    .put("output",project.optString("output")).put("verified",selected.optJSONObject("verification")!=null&&selected.getJSONObject("verification").optBoolean("valid"))
                                    .put("follow_up","video_editor_queue_status puis video_editor_audio_verify");
                                cb.completed(true,status.toString());
                            }catch(Exception ex){cb.completed(false,ex.getMessage());}
                        });
                }catch(Exception ex){cb.completed(false,ex.getMessage());}
            });return;
        }
        VideoEditorEngine.DISK.execute(()->{
            try{
                JSONObject p=VideoEditorEngine.get().load(app);
                String op=action.substring("video_editor_audio_".length());
                JSONObject result;
                switch(op){
                    case "tracks":result=tracks(p);break;
                    case "analyze":result=analyze(app,p,args,false);break;
                    case "verify":result=analyze(app,p,args,true);break;
                    case "set_volume":case "keyframes":case "ducking":{
                        guard(p,args);
                        String request=args.getString("request_id");
                        String fingerprint=AgentVision.digest(new JSONObject(args.toString()).put("operation",op).toString());
                        SharedPreferences receipts=app.getSharedPreferences("chk_studio_audio_receipts_v1",0);
                        String previous=receipts.getString(request,"");
                        if(!previous.isEmpty()){
                            JSONObject saved=new JSONObject(previous);
                            if(!fingerprint.equals(saved.optString("fingerprint")))throw new IllegalStateException("Identifiant réutilisé pour un autre changement");
                            result=saved.getJSONObject("result").put("replayed",true);
                            break;
                        }
                        JSONObject target=target(p,args.getString("target_id"));
                        JSONObject copy=new JSONObject(p.toString());
                        JSONObject editable=target(copy,args.getString("target_id"));
                        if(op.equals("set_volume"))setVolume(editable,args);
                        else if(op.equals("keyframes"))keyframes(editable,args);
                        else ducking(copy,editable,args);
                        VideoEditorEngine.get().validate(app,copy);
                        copy.put("revision",p.getInt("revision")+1);
                        StudioProjectLibrary.save(app,copy);
                        JSONObject verified=VideoEditorEngine.get().load(app);
                        JSONObject applied=target(verified,args.getString("target_id"));
                        if(verified.optInt("revision")!=copy.getInt("revision"))throw new IllegalStateException("Révision non confirmée");
                        result=new JSONObject().put("state","completed").put("applied",true).put("target_id",args.getString("target_id"))
                            .put("project_id",verified.optString("project_id")).put("revision",verified.getInt("revision"))
                            .put("target",applied).put("rendered",false)
                            .put("render_note","Réglages du projet enregistrés ; exporter puis utiliser audio_verify pour valider le MP4.");
                        receipts.edit().putString(request,new JSONObject().put("fingerprint",fingerprint).put("result",result).toString()).commit();
                        break;
                    }
                    default:throw new IllegalArgumentException("Commande audio inconnue : "+op);
                }
                cb.completed(true,result.toString());
            }catch(Exception e){cb.completed(false,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
        });
    }
    private static void guard(JSONObject p,JSONObject args)throws Exception{
        if(!args.has("project_id")||!p.optString("project_id").equals(args.getString("project_id")))
            throw new IllegalStateException("project_id requis et doit correspondre au projet ouvert");
        if(!args.has("expected_revision")||args.getInt("expected_revision")!=p.optInt("revision"))
            throw new IllegalStateException("Révision périmée : relire le projet Studio");
        if(!args.optString("request_id","").matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("request_id stable obligatoire");
        String status=VideoEditorEngine.get().status().optString("state");
        if(status.equals("running")||status.equals("preparing"))throw new IllegalStateException("Export actif");
    }
    private static JSONObject target(JSONObject p,String id)throws Exception{
        if(!id.matches("(audio|video):[0-9]{1,3}"))throw new IllegalArgumentException("target_id : audio:0 ou video:0");
        String[] parts=id.split(":");int index=Integer.parseInt(parts[1]);
        JSONArray tracks=p.optJSONArray(parts[0].equals("audio")?"audio":"clips");
        if(tracks==null||index>=tracks.length())throw new IllegalArgumentException("Piste inexistante");
        return tracks.getJSONObject(index);
    }
    private static JSONObject tracks(JSONObject p)throws Exception{
        JSONArray result=new JSONArray();
        for(String kind:new String[]{"video","audio"}){
            JSONArray arr=p.optJSONArray(kind.equals("video")?"clips":"audio");
            long elapsed=0;
            if(arr==null)continue;
            for(int i=0;i<arr.length();i++){
                JSONObject c=arr.getJSONObject(i);
                long offset=c.optLong("offset_ms",elapsed),duration=c.optLong("duration_ms");
                result.put(new JSONObject().put("target_id",kind+":"+i).put("kind",kind)
                    .put("path",c.optString("path")).put("start_ms",c.optLong("start_ms"))
                    .put("offset_ms",offset).put("duration_ms",duration)
                    .put("volume_percent",100*c.optDouble("volume",1))
                    .put("gain_db",db(c.optDouble("volume",1))).put("mute",c.optBoolean("mute",false))
                    .put("solo",c.optBoolean("solo",false))
                    .put("fade_in_ms",c.optLong("fade_in_ms"))
                    .put("fade_out_ms",c.optLong("fade_out_ms"))
                    .put("keyframes",c.optJSONArray("volume_keyframes")==null?new JSONArray():c.getJSONArray("volume_keyframes"))
                    .put("ducking",c.optJSONObject("ducking")==null?new JSONObject():c.getJSONObject("ducking")));
                elapsed+=duration;
            }
        }
        return new JSONObject().put("project_id",p.optString("project_id")).put("revision",p.optInt("revision"))
            .put("layout",p.optString("audio_layout","sequential_legacy"))
            .put("original_audio_enabled",!p.optBoolean("mute_original",false))
            .put("tracks",result).put("state","completed");
    }
    static Object db(double v){return v<=0?"-Infinity":Math.round(20*Math.log10(v)*100.0)/100.0;}
    static double linear(double db){return Math.pow(10,db/20.0);}
    private static void setVolume(JSONObject target,JSONObject args)throws Exception{
        double gain=target.optDouble("volume",1);
        if(args.has("percent"))gain=args.getDouble("percent")/100;
        else if(args.has("db"))gain="-Infinity".equals(args.optString("db"))?0:linear(args.getDouble("db"));
        if(!Double.isFinite(gain)||gain<0||gain>2)throw new IllegalArgumentException("Gain 0..200 % (-∞ à +6,02 dB)");
        target.put("volume",gain);
        for(String flag:new String[]{"mute","solo"})if(args.has(flag)){
            if(!(args.get(flag) instanceof Boolean))throw new IllegalArgumentException("Mute/Solo doit être booléen");
            target.put(flag,args.getBoolean(flag));
        }
        for(String key:new String[]{"fade_in_ms","fade_out_ms"})if(args.has(key)){
            long ms=args.getLong(key);
            if(ms<0||ms>target.optLong("duration_ms"))throw new IllegalArgumentException("Fondu hors durée du clip");
            target.put(key,ms);
        }
    }
    private static void keyframes(JSONObject target,JSONObject args)throws Exception{
        JSONArray points=args.getJSONArray("points");
        if(points.length()>100)throw new IllegalArgumentException("100 keyframes maximum");
        JSONArray out=new JSONArray();long before=-1,limit=target.optLong("duration_ms");
        for(int i=0;i<points.length();i++){
            JSONObject row=points.getJSONObject(i);long at=row.getLong("time_ms");
            double gain=row.has("percent")?row.getDouble("percent")/100:row.has("db")?linear(row.getDouble("db")):row.getDouble("gain");
            if(at<0||at>limit||at<=before||!Double.isFinite(gain)||gain<0||gain>2)
                throw new IllegalArgumentException("Keyframe invalide ou hors limites à l'index "+i);
            out.put(new JSONObject().put("time_ms",at).put("gain",gain));before=at;
        }
        target.put("volume_keyframes",out);
    }
    private static void ducking(JSONObject p,JSONObject target,JSONObject args)throws Exception{
        boolean enabled=args.optBoolean("enabled",false);
        if(enabled&&StudioSubtitles.items(p).length()==0)
            throw new IllegalArgumentException("Ducking vocal non vérifié : générer d'abord les sous-titres Vosk horodatés.");
        JSONObject settings=new JSONObject().put("enabled",enabled);
        for(String key:new String[]{"reduction_db","attack_ms","release_ms"}){
            double x=args.optDouble(key,key.equals("reduction_db")?12:key.equals("attack_ms")?60:350);
            double max=key.equals("reduction_db")?30:5000;
            if(!Double.isFinite(x)||x<0||x>max)throw new IllegalArgumentException("Valeur de ducking invalide : "+key);
            settings.put(key,x);
        }
        target.put("ducking",settings);
    }
    private static JSONObject analyze(Context app,JSONObject project,JSONObject args,boolean verify)throws Exception{
        JSONObject base=tracks(project);
        String id=args.optString("target_id","");
        if(verify){
            JSONObject output=VideoEditorEngine.get().verifyProjectOutput(app,project);
            base.put("output",output).put("rendered",output.optBoolean("valid"))
                .put("source_isolation_verified",false)
                .put("warning","Les mesures de niveaux ne prouvent pas séparément la présence de chaque source dans un mixage.");
            if(!output.optBoolean("valid"))return base.put("state","failed");
            id="export";
        }
        if(id.isEmpty())return base.put("analysis_scope","metadata_only");
        File file;
        long start=0;
        if(id.equals("export"))file=new WorkspaceStore(app).file(project.getString("output"));
        else {
            JSONObject clip=target(project,id);
            file=new WorkspaceStore(app).file(clip.getString("path"));
            start=clip.optLong("start_ms");
        }
        final double[] energy={0,0};final long[] samples={0,0};
        AudioDecoder.Info info=AudioDecoder.read(file,start,Math.max(1,Math.min(15,args.optInt("sample_seconds",5))),(buffer,rate,channels,timeUs)->{
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            while(buffer.remaining()>=2){
                double s=buffer.getShort()/32768.0;
                energy[0]+=s*s;energy[1]=Math.max(energy[1],Math.abs(s));samples[0]++;
                if(Math.abs(s)<0.002)samples[1]++;
            }
        },null);
        double rms=samples[0]==0?0:Math.sqrt(energy[0]/samples[0]);
        return base.put("analysis",new JSONObject().put("target_id",id)
            .put("samples",samples[0]).put("sample_rate",info.rate).put("channels",info.channels)
            .put("peak_dbfs",db(energy[1])).put("rms_dbfs",db(rms))
            .put("near_silent_fraction",samples[0]==0?0:samples[1]/(double)samples[0])
            .put("clip_risk",energy[1]>=0.999)
            .put("lufs_verified",false).put("sampled_seconds",samples[0]/(double)Math.max(1,info.rate*info.channels)))
            .put("state","completed");
    }
}