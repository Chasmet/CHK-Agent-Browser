package com.chk.agentbrowser;

import android.content.Context;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Non-destructive, revision-checked Studio controls exposed to Local 3.
 * Commands never claim a rendered/exported effect without independent proof.
 */
final class StudioAdvancedCommands {
    private StudioAdvancedCommands() {}
    static boolean handles(String action) {
        return action.startsWith("video_editor_studio_audio_")
            || action.startsWith("video_editor_studio_crop_");
    }
    static void command(Context app,String action,JSONObject args,VideoEditorEngine.Done cb) {
        VideoEditorEngine.DISK.execute(()->{
            try {cb.completed(true,execute(app,action.substring("video_editor_".length()),args).toString());}
            catch(Exception e){cb.completed(false,e.getMessage()==null?"Commande Studio non confirmée":e.getMessage());}
        });
    }
    static double linearToDb(double gain) {return gain==0?Double.NEGATIVE_INFINITY:20*Math.log10(gain);}
    static double dbToLinear(double db) {
        if(Double.isInfinite(db)&&db<0)return 0;
        if(!Double.isFinite(db))throw new IllegalArgumentException("dB non fini");
        return Math.pow(10,db/20);
    }
    private static double range(double v,double low,double high,String key) {
        if(!Double.isFinite(v)||v<low||v>high)throw new IllegalArgumentException(key+" hors limites");
        return v;
    }
    private static JSONObject track(JSONObject project,JSONObject args,boolean video)throws Exception{
        JSONArray tracks=project.optJSONArray(video?"clips":"audio");
        if(tracks==null)throw new IllegalArgumentException("Piste absente");
        int index=args.getInt(video?"clip_index":"track_index");
        if(index<0||index>=tracks.length())throw new IllegalArgumentException("Index de piste absent");
        JSONObject item=tracks.getJSONObject(index);
        String path=args.optString("source_path","");
        if(!path.isEmpty()&&!path.equals(item.optString("path")))
            throw new IllegalStateException("La piste a changé de position : relire studio_audio_tracks");
        return item;
    }
    static JSONObject execute(Context app,String action,JSONObject args)throws Exception{
        if(!handles("video_editor_"+action))throw new IllegalArgumentException("Commande Studio inconnue");
        VideoEditorEngine engine=VideoEditorEngine.get();
        JSONObject current=engine.load(app);
        String id=current.optString("project_id");
        if(!id.equals(args.optString("project_id","")))
            throw new IllegalArgumentException("project_id obligatoire et égal au projet actif");
        boolean audio=action.startsWith("studio_audio_");
        if(action.equals("studio_audio_tracks")){
            JSONArray out=new JSONArray();
            for(String key:new String[]{"clips","audio"}){
                JSONArray items=current.optJSONArray(key);
                if(items==null)continue;
                for(int i=0;i<items.length();i++){
                    JSONObject item=items.getJSONObject(i);
                    double gain=item.optDouble("volume",1);
                    out.put(new JSONObject().put("type",key.equals("clips")?"original_video":"external_audio")
                        .put("index",i).put("source_path",item.optString("path"))
                        .put("track_id",key+":"+i+":"+item.optString("path").hashCode())
                        .put("duration_ms",item.optLong("duration_ms",0))
                        .put("volume_percent",gain*100)
                        .put("volume_db",gain==0?"-Infinity":linearToDb(gain))
                        .put("muted",item.optBoolean("mute",false))
                        .put("original_enabled",!current.optBoolean("mute_original",false)));
                }
            }
            return new JSONObject().put("state","completed").put("project_id",id)
                .put("revision",current.optInt("revision")).put("tracks",out)
                .put("audio_layout","Current engine mixes the video sequence with one sequential external audio sequence; overlapping external clips are not supported yet.");
        }
        if(action.equals("studio_crop_open")){
            JSONObject item=track(current,args,true);
            JSONObject crop=item.optJSONObject("crop");
            return new JSONObject().put("state","completed").put("project_id",id)
                .put("revision",current.optInt("revision")).put("clip_index",args.getInt("clip_index"))
                .put("source_path",item.getString("path"))
                .put("crop",crop==null?new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1):crop)
                .put("rotation_degrees",item.optDouble("rotation",0))
                .put("flip_horizontal",item.optBoolean("flip_horizontal",false))
                .put("flip_vertical",item.optBoolean("flip_vertical",false));
        }
        if(action.equals("studio_audio_analyze")||action.equals("studio_audio_ducking")
                ||action.equals("studio_audio_keyframes")||action.equals("studio_audio_mix")
                ||action.equals("studio_audio_verify")||action.equals("studio_crop_preview"))
            throw new UnsupportedOperationException("Opération non implémentée dans cette version : aucun mixage ni rendu prétendu réussi");
        String request=args.getString("request_id");
        if(!request.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("request_id stable obligatoire");
        String fingerprint=AgentVision.digest(action+"|"+args.toString());
        JSONArray receipts=current.optJSONArray("studio_advanced_receipts");
        if(receipts==null)receipts=new JSONArray();
        for(int i=0;i<receipts.length();i++){
            JSONObject prior=receipts.getJSONObject(i);
            if(request.equals(prior.optString("request_id"))){
                if(!fingerprint.equals(prior.optString("fingerprint")))
                    throw new IllegalArgumentException("request_id déjà utilisé avec d'autres paramètres");
                return new JSONObject().put("state","completed").put("replayed",true)
                    .put("project_id",id).put("revision",prior.getInt("revision"))
                    .put("verified_at_commit",true).put("result",prior.getJSONObject("result"));
            }
        }
        if(!args.has("expected_revision")||args.getInt("expected_revision")!=current.optInt("revision"))
            throw new IllegalStateException("Révision périmée : relire le projet avant toute modification");
        String exportState=engine.status().optString("state");
        if(exportState.equals("running")||exportState.equals("preparing"))
            throw new IllegalStateException("Export actif : modification interdite");
        JSONObject updated=new JSONObject(current.toString());
        JSONObject item;
        JSONObject detail=new JSONObject();
        if(action.equals("studio_audio_set_volume")){
            boolean original=args.optString("kind","audio").equals("video");
            item=track(updated,args,original);
            double volume=args.has("percent")?range(args.getDouble("percent"),0,200,"pourcentage")/100:
                range(dbToLinear(args.getDouble("db")) ,0,2,"volume dB");
            item.put("volume",volume);
            if(args.has("mute"))item.put("mute",args.getBoolean("mute"));
            detail.put("volume_percent",volume*100)
                .put("volume_db",volume==0?"-Infinity":linearToDb(volume))
                .put("muted",item.optBoolean("mute",false))
                .put("kind",original?"video":"audio");
        }else if(action.startsWith("studio_crop_")){
            item=track(updated,args,true);
            JSONObject c=item.optJSONObject("crop");
            if(c==null)c=new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1);
            double x=c.getDouble("x"),y=c.getDouble("y"),w=c.getDouble("width"),h=c.getDouble("height");
            switch(action){
                case "studio_crop_set":
                    JSONObject rect=args.getJSONObject("rect");
                    if(!args.optString("coordinates","normalized").equals("normalized"))
                        throw new IllegalArgumentException("Seules les coordonnées normalisées sont actuellement acceptées");
                    x=rect.getDouble("x");y=rect.getDouble("y");w=rect.getDouble("width");h=rect.getDouble("height");
                    break;
                case "studio_crop_move":x+=args.getDouble("dx");y+=args.getDouble("dy");break;
                case "studio_crop_resize":w=args.getDouble("width");h=args.getDouble("height");break;
                case "studio_crop_rotate":item.put("rotation",range(args.getDouble("degrees"),-180,180,"rotation"));break;
                case "studio_crop_flip":
                    if(args.has("horizontal"))item.put("flip_horizontal",args.getBoolean("horizontal"));
                    if(args.has("vertical"))item.put("flip_vertical",args.getBoolean("vertical"));
                    break;
                case "studio_crop_apply":break;
                default:throw new IllegalArgumentException("Commande recadrage inconnue");
            }
            range(x,0,1,"x");range(y,0,1,"y");range(w,0.02,1,"width");range(h,0.02,1,"height");
            if(x+w>1.0000001||y+h>1.0000001)throw new IllegalArgumentException("Le cadre déborde de l'image");
            c.put("x",x).put("y",y).put("width",w).put("height",h);
            item.put("crop",c);
            detail.put("crop",c).put("rotation_degrees",item.optDouble("rotation",0))
                .put("flip_horizontal",item.optBoolean("flip_horizontal",false))
                .put("flip_vertical",item.optBoolean("flip_vertical",false))
                .put("duration_ms",item.optLong("duration_ms"));
        }else throw new IllegalArgumentException("Opération non supportée");
        engine.validate(app,updated);
        int next=updated.optInt("revision")+1;
        updated.put("revision",next);
        receipts.put(new JSONObject().put("request_id",request).put("fingerprint",fingerprint)
            .put("revision",next).put("result",detail));
        while(receipts.length()>48)receipts.remove(0);
        updated.put("studio_advanced_receipts",receipts);
        StudioProjectLibrary.save(app,updated);
        JSONObject actual=engine.load(app);
        if(actual.optInt("revision")!=next||!id.equals(actual.optString("project_id")))
            throw new IllegalStateException("Sauvegarde non vérifiée; ne pas répéter l'opération");
        return new JSONObject().put("state","completed").put("project_id",id)
            .put("revision",next).put("applied",true).put("result",detail);
    }
}
