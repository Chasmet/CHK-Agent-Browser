package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Matrix;
import androidx.media3.effect.MatrixTransformation;
import org.json.JSONArray;
import org.json.JSONObject;

/** Source-normalized, non-destructive per-clip spatial crop, revision guarded. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class StudioCropControl {
    private StudioCropControl(){}
    static boolean handles(String action){return action.startsWith("video_editor_crop_");}
    static JSONObject identity(){return new JSONObject();}
    static JSONObject initial()throws Exception{
        return new JSONObject().put("x",0).put("y",0).put("w",1).put("h",1)
            .put("rotate_deg",0).put("flip_h",false).put("flip_v",false).put("ratio","Libre");
    }
    static JSONObject rect(JSONObject clip)throws Exception{
        JSONObject crop=clip.optJSONObject("crop");
        return crop==null?initial():new JSONObject(crop.toString());
    }
    static void validate(JSONObject crop)throws Exception{
        for(String key:new String[]{"x","y","w","h","rotate_deg"}){
            if(!crop.has(key))throw new IllegalArgumentException("Recadrage incomplet : "+key);
            double v=crop.getDouble(key);if(!Double.isFinite(v))throw new IllegalArgumentException("Valeur de recadrage non finie");
        }
        double x=crop.getDouble("x"),y=crop.getDouble("y"),w=crop.getDouble("w"),h=crop.getDouble("h");
        if(x<0||y<0||w<=.015||h<=.015||x+w>1.00001||y+h>1.00001)
            throw new IllegalArgumentException("Cadre hors image source (coordonnées normalisées 0..1)");
        if(Math.abs(crop.getDouble("rotate_deg"))>180)throw new IllegalArgumentException("Rotation limitée à ±180°");
        if(!java.util.Arrays.asList("Libre","9:16","16:9","1:1","4:5").contains(crop.optString("ratio","Libre")))
            throw new IllegalArgumentException("Ratio de recadrage non supporté");
    }
    /** Crop and flips share one GPU matrix in both Transformer and rendered preview. */
    static MatrixTransformation effect(JSONObject crop)throws Exception{
        validate(crop);
        final float x=(float)crop.getDouble("x"),y=(float)crop.getDouble("y"),
            w=(float)crop.getDouble("w"),h=(float)crop.getDouble("h");
        final float degrees=(float)crop.optDouble("rotate_deg",0);
        final float sx=(crop.optBoolean("flip_h",false)?-1:1)/w;
        final float sy=(crop.optBoolean("flip_v",false)?-1:1)/h;
        final float centerX=2*x+w-1,centerY=1-2*y-h;
        return timeUs->{
            Matrix m=new Matrix();
            m.setScale(sx,sy);
            m.postTranslate(-centerX*sx,-centerY*sy);
            if(degrees!=0)m.postRotate(degrees);
            return m;
        };
    }
    static int clipIndex(JSONObject p,String id)throws Exception{
        if(!id.matches("video:([0-9]{1,3}|[a-f0-9-]{36})"))
            throw new IllegalArgumentException("clip_id attendu : video:<media_id>");
        String selector=id.substring(6);JSONArray arr=p.getJSONArray("clips");
        if(selector.matches("[0-9]{1,3}")){
            int index=Integer.parseInt(selector);
            if(index>=arr.length())throw new IllegalArgumentException("Plan absent");
            return index;
        }
        for(int i=0;i<arr.length();i++)
            if(selector.equals(arr.getJSONObject(i).optString("media_id")))return i;
        throw new IllegalArgumentException("Plan introuvable");
    }
    private static JSONObject clip(JSONObject p,String id)throws Exception{
        return p.getJSONArray("clips").getJSONObject(clipIndex(p,id));
    }
    static void command(Context app,String action,JSONObject args,VideoEditorEngine.Done cb){
        VideoEditorEngine.DISK.execute(()->{
            try{
                JSONObject p=VideoEditorEngine.get().load(app);
                String op=action.substring("video_editor_crop_".length());
                String id=args.getString("clip_id");
                JSONObject c=clip(p,id);
                JSONObject crop=rect(c);
                if(op.equals("open")||op.equals("apply")){
                    if(op.equals("apply"))guard(p,args);
                    JSONObject info=view(app,p,id,crop).put("state","completed");
                    if(op.equals("apply"))info.put("applied",true).put("rendered",false)
                        .put("note","Le projet existant a été relu; exporter pour vérifier les pixels finaux.");
                    cb.completed(true,info.toString());
                    return;
                }
                if(op.equals("preview")){
                    StudioCropPreview.render(app,new JSONObject(p.toString()),id,cb);
                    return;
                }
                guard(p,args);
                JSONObject modified=new JSONObject(crop.toString());
                switch(op){
                    case "set":{
                        JSONObject r=args.getJSONObject("rect");
                        String unit=args.optString("units","normalized");
                        double x=r.getDouble("x"),y=r.getDouble("y"),w=r.getDouble("w"),h=r.getDouble("h");
                        if(unit.equals("pixels")){
                            JSONObject info=MediaInspection.info(app,c.getString("path"));
                            double sw=info.getDouble("width"),sh=info.getDouble("height");
                            if(sw<=0||sh<=0)throw new IllegalArgumentException("Dimensions source absentes");
                            x/=sw;y/=sh;w/=sw;h/=sh;
                        }else if(!unit.equals("normalized"))throw new IllegalArgumentException("units: pixels ou normalized");
                        modified.put("x",x).put("y",y).put("w",w).put("h",h);
                        if(args.has("ratio"))modified.put("ratio",args.getString("ratio"));
                        break;
                    }
                    case "move":
                        modified.put("x",modified.getDouble("x")+args.getDouble("dx"))
                            .put("y",modified.getDouble("y")+args.getDouble("dy"));break;
                    case "resize":{
                        double w=args.getDouble("w"),h=args.getDouble("h");
                        if(args.has("ratio"))modified.put("ratio",args.getString("ratio"));
                        modified.put("w",w).put("h",h);break;
                    }
                    case "rotate":
                        modified.put("rotate_deg",args.getDouble("degrees"));break;
                    case "flip":
                        if(args.has("horizontal"))modified.put("flip_h",args.getBoolean("horizontal"));
                        if(args.has("vertical"))modified.put("flip_v",args.getBoolean("vertical"));break;
                    default:throw new IllegalArgumentException("Commande recadrage inconnue");
                }
                validate(modified);
                JSONObject next=new JSONObject(p.toString());
                clip(next,id).put("crop",modified);
                VideoEditorEngine.get().validate(app,next);
                next.put("revision",p.getInt("revision")+1);
                StudioProjectLibrary.save(app,next);
                JSONObject proof=VideoEditorEngine.get().load(app);
                if(!modified.toString().equals(rect(clip(proof,id)).toString())||
                   proof.getInt("revision")!=next.getInt("revision"))throw new IllegalStateException("Recadrage non sauvegardé");
                cb.completed(true,view(app,proof,id,modified).put("state","completed")
                    .put("applied",true).put("rendered",false).toString());
            }catch(Exception e){cb.completed(false,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
        });
    }
    private static void guard(JSONObject p,JSONObject args)throws Exception{
        if(!p.optString("project_id").equals(args.optString("project_id"))||args.optString("project_id").isEmpty())
            throw new IllegalStateException("project_id incorrect");
        if(!args.has("expected_revision")||args.getInt("expected_revision")!=p.optInt("revision"))
            throw new IllegalStateException("Révision du projet périmée");
        if(!args.optString("request_id","").matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Identifiant de requête stable requis");
        String status=VideoEditorEngine.get().status().optString("state");
        if(status.equals("running")||status.equals("preparing"))throw new IllegalStateException("Export actif");
    }
    private static JSONObject view(Context app,JSONObject p,String id,JSONObject crop)throws Exception{
        JSONObject c=clip(p,id);
        JSONObject info=MediaInspection.info(app,c.getString("path"));
        return new JSONObject().put("project_id",p.optString("project_id"))
            .put("revision",p.optInt("revision")).put("clip_id",id)
            .put("source_width",info.optInt("width")).put("source_height",info.optInt("height"))
            .put("rect",crop).put("coordinate_space","source_normalized_0_1")
            .put("duration_ms",c.optLong("duration_ms"))
            .put("preview_scope","GPU crop applied to rendered previews and MP4, not direct source ExoPlayer");
    }
}