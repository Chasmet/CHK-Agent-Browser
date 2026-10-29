package com.chk.agentbrowser;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Locale;

/** Local 3 native studio actions over the existing desktop transport. */
final class StudioMcp {
    private StudioMcp(){}
    static void execute(Context app,JSONObject args,AgentClient.ResultCallback cb){
        VideoEditorEngine.DISK.execute(()->{
            if(!cb.isActive())return;
            try{
                JSONObject result=dispatch(app,args);
                WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(true,result.toString());});
            }catch(Exception error){
                String detail=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
                WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(false,detail);});
            }
        });
    }
    static JSONObject dispatch(Context app,JSONObject args)throws Exception {
        String op=args.optString("operation","");
        if(op.equals("capabilities"))return new JSONObject().put("transport","task_submit action=desktop, args.tool=studio")
            .put("operations",new JSONArray("[\"audio_tracks\",\"audio_set_volume\",\"crop_open\",\"crop_set\",\"crop_move\",\"crop_resize\",\"crop_rotate\",\"crop_flip\",\"crop_apply\"]"))
            .put("project_id","required for mutations; compare active project identity")
            .put("expected_revision","required for mutations")
            .put("request_id","required stable id for mutations (idempotent journal)")
            .put("units","volume percent 0..200 or dB; crop normalized or pixels")
            .put("limitations","Keyframe processing, ducking, peak limiting, crop handles and verified video export are not yet implemented here.");
        JSONObject p=VideoEditorEngine.get().load(app);
        String pid=p.optString("project_id","");
        if(args.has("project_id")&&!pid.equals(args.getString("project_id")))
            throw new Exception("Projet différent : ouvrir le projet indiqué avant d'éditer");
        if(op.equals("audio_tracks"))return tracks(p);
        int index=args.optInt("index",-1);
        String lane=op.startsWith("audio_")?args.optString("track","audio"):"clips";
        if(!lane.equals("clips")&&!lane.equals("audio"))throw new Exception("Piste clips ou audio requise");
        JSONArray items=p.optJSONArray(lane);
        if(items==null||index<0||index>=items.length())throw new Exception("Index de piste/clip introuvable");
        JSONObject target=items.getJSONObject(index);
        if(op.equals("crop_open")||op.equals("crop_apply"))return cropResult(app,p,target,index);
        if(!op.equals("audio_set_volume")&&!op.matches("crop_(set|move|resize|rotate|flip)"))
            throw new Exception("Opération Studio inconnue ou non prise en charge : "+op);
        if(!args.has("project_id")||pid.isEmpty()||!args.has("expected_revision")||
           args.getInt("expected_revision")!=p.optInt("revision",0))
            throw new Exception("Révision de projet incompatible : relire audio_tracks ou crop_open");
        String id=args.getString("request_id");
        if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new Exception("request_id stable requis");
        File journal=AgentFiles.journal(app,"studio",id);
        JSONObject normalized=new JSONObject(args.toString());normalized.remove("tool");
        String fingerprint=AgentVision.digest(normalized.toString());
        if(journal.isFile()){
            JSONObject saved=AgentFiles.read(journal);
            if(!fingerprint.equals(saved.optString("fingerprint")))throw new Exception("request_id déjà utilisé pour une autre action");
            JSONObject receipt=new JSONObject(saved.getJSONObject("result").toString());
            return receipt.put("replayed",true);
        }
        String state=VideoEditorEngine.get().status().optString("state");
        if(state.equals("preparing")||state.equals("running"))throw new Exception("Export en cours : édition suspendue");
        if(op.equals("audio_set_volume")){
            double gain;
            if(args.has("percent"))gain=args.getDouble("percent")/100.0;
            else if(args.has("db"))gain=Math.pow(10,args.getDouble("db")/20.0);
            else throw new Exception("percent ou db requis");
            if(!Double.isFinite(gain)||gain<0||gain>2)
                throw new Exception("Gain hors plage de 0 à 200 %");
            target.put("volume",gain);
            if(args.has("mute"))target.put("mute",args.getBoolean("mute"));
        }else{
            if(!lane.equals("clips"))throw new Exception("Le recadrage concerne uniquement les vidéos");
            JSONObject info=MediaInspection.info(app,target.getString("path"));
            int sw=info.optInt("width"),sh=info.optInt("height");
            JSONObject crop=StudioCrop.changed(StudioCrop.read(target),op.substring(5),args,sw,sh);
            target.put("crop",crop);
        }
        assignStableIds(p);
        VideoEditorEngine.get().validate(app,p);
        p.put("revision",p.optInt("revision",0)+1);
        StudioProjectLibrary.save(app,p);
        JSONObject result=op.equals("audio_set_volume")?
            new JSONObject().put("track",lane).put("index",index).put("volume_percent",target.optDouble("volume",1)*100)
                .put("gain_db",target.optDouble("volume",1)==0?"-Infinity":20*Math.log10(target.optDouble("volume",1)))
                .put("mute",target.optBoolean("mute",false)):
            new JSONObject().put("index",index).put("crop",StudioCrop.read(target));
        result.put("state","completed").put("verified",true).put("project_id",pid)
            .put("revision",p.getInt("revision")).put("request_id",id)
            .put("note","Projet sauvegardé, export MP4 encore à vérifier");
        AgentFiles.save(journal,new JSONObject().put("fingerprint",fingerprint).put("result",result));
        return result;
    }
    private static void assignStableIds(JSONObject p)throws Exception {
        for(String name:new String[]{"clips","audio"}){
            JSONArray items=p.optJSONArray(name);
            if(items==null)continue;
            for(int i=0;i<items.length();i++){
                JSONObject item=items.getJSONObject(i);
                if(item.optString("studio_id","").isEmpty())item.put("studio_id",java.util.UUID.randomUUID().toString());
            }
        }
    }
    private static JSONObject tracks(JSONObject p)throws Exception{
        JSONArray rows=new JSONArray();
        for(String lane:new String[]{"clips","audio"}){
            JSONArray items=p.optJSONArray(lane);
            if(items==null)continue;
            for(int i=0;i<items.length();i++){
                JSONObject c=items.getJSONObject(i);
                double gain=c.optDouble("volume",1);
                rows.put(new JSONObject().put("track",lane).put("index",i)
                    .put("source_path",c.optString("path","")).put("duration_ms",c.optLong("duration_ms"))
                    .put("mute",c.optBoolean("mute",false)).put("volume_percent",gain*100)
                    .put("gain_db",gain<=0?"-Infinity":20*Math.log10(gain))
                    .put("stable_id_available",c.has("studio_id")).put("studio_id",c.optString("studio_id","")));
            }
        }
        return new JSONObject().put("state","completed").put("project_id",p.optString("project_id"))
            .put("revision",p.optInt("revision")).put("mute_original",p.optBoolean("mute_original",false))
            .put("tracks",rows).put("verified",true);
    }
    private static JSONObject cropResult(Context app,JSONObject p,JSONObject clip,int i)throws Exception{
        JSONObject info=MediaInspection.info(app,clip.getString("path"));
        JSONObject crop=StudioCrop.read(clip);
        return new JSONObject().put("state","completed").put("verified",true)
            .put("project_id",p.optString("project_id")).put("revision",p.optInt("revision"))
            .put("index",i).put("source_width",info.optInt("width")).put("source_height",info.optInt("height"))
            .put("crop",crop).put("duration_ms",clip.optLong("duration_ms"))
            .put("note","Géométrie du projet, pas une preuve de pixels MP4 exportés");
    }
}
