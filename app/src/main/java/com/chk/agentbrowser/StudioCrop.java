package com.chk.agentbrowser;

import android.content.Context;
import androidx.media3.common.Effect;
import androidx.media3.effect.Crop;
import androidx.media3.effect.ScaleAndRotateTransformation;
import org.json.*;
import java.util.*;
import java.io.IOException;

/** Spatial edits only. Coordinates refer to the upright source, before crop rotation. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class StudioCrop {
    private StudioCrop(){}
    static JSONObject value(JSONObject clip)throws Exception{return clip.has("crop")?new JSONObject(clip.getJSONObject("crop").toString()):reset();}
    static JSONObject reset()throws Exception{return new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1).put("ratio","free").put("rotation",0).put("flip_x",false).put("flip_y",false).put("zoom",1).put("pan_x",0).put("pan_y",0);}
    static void validate(JSONObject clip)throws Exception{
        if(!clip.has("crop"))return;JSONObject c=clip.getJSONObject("crop");double x=StudioMix.number(c,"x",0,0,1),y=StudioMix.number(c,"y",0,0,1),w=StudioMix.number(c,"width",1,.001,1),h=StudioMix.number(c,"height",1,.001,1);
        if(x+w>1.000001||y+h>1.000001)throw new IOException("Le cadre dépasse la source");
        if(!Arrays.asList("free","9:16","16:9","1:1","4:5").contains(c.optString("ratio","free")))throw new IOException("Ratio de recadrage inconnu");
        StudioMix.number(c,"rotation",0,-180,180);StudioMix.number(c,"zoom",1,1,4);StudioMix.number(c,"pan_x",0,-1,1);StudioMix.number(c,"pan_y",0,-1,1);
        if(c.has("keyframes"))throw new IOException("Recadrage animé non pris en charge : aucun keyframe ignoré silencieusement");
    }
    static JSONObject dimensions(Context app,JSONObject clip)throws Exception{
        JSONObject info=MediaInspection.info(app,clip.getString("path"));int w=info.getInt("width"),h=info.getInt("height");if(Math.abs(info.optInt("rotation"))%180==90){int t=w;w=h;h=t;}
        return new JSONObject().put("width",w).put("height",h).put("source_rotation",info.optInt("rotation"));
    }
    static JSONObject describe(Context app,JSONObject project,JSONObject clip)throws Exception{
        JSONObject c=value(clip),size=dimensions(app,clip);return new JSONObject().put("project_id",project.getString("project_id")).put("clip_id",clip.getString("clip_id")).put("revision",project.optInt("revision")).put("reference","display_oriented_source").put("units","normalized").put("source",size).put("crop",c).put("effective_rectangle",effective(c)).put("duration_ms",clip.optLong("duration_ms")).put("timeline_duration_ms",StudioEdits.duration(clip)).put("limitations",new JSONArray().put("Animated crop keyframes are not supported").put("Arbitrary rotation can create transparent corners before final aspect presentation"));
    }
    static JSONObject effective(JSONObject c)throws Exception{
        double z=c.optDouble("zoom",1),w=c.optDouble("width",1)/z,h=c.optDouble("height",1)/z;
        return new JSONObject().put("x",c.optDouble("x")+(c.optDouble("width",1)-w)*(c.optDouble("pan_x")+1)/2).put("y",c.optDouble("y")+(c.optDouble("height",1)-h)*(c.optDouble("pan_y")+1)/2).put("width",w).put("height",h);
    }
    static void edit(Context app,JSONObject clip,String action,JSONObject args)throws Exception{
        JSONObject c=value(clip),size=dimensions(app,clip);boolean pixels=args.optString("units","normalized").equals("pixels");
        if(!Arrays.asList("normalized","pixels").contains(args.optString("units","normalized")))throw new IOException("units : normalized ou pixels");
        if(action.equals("studio_crop_set")){
            if(args.optBoolean("reset"))c=reset();else{
                if(!args.optString("reference").equals("display_oriented_source"))throw new IOException("reference=display_oriented_source requis");
                JSONObject rect=args.optJSONObject("rectangle");if(rect!=null)for(String key:new String[]{"x","y","width","height"})c.put(key,rect.getDouble(key)/(pixels?(key.equals("x")||key.equals("width")?size.getInt("width"):size.getInt("height")):1));
                for(String key:new String[]{"zoom","pan_x","pan_y","ratio"})if(args.has(key))c.put(key,args.get(key));
            }
        }else if(action.equals("studio_crop_move")){
            c.put("x",Math.max(0,Math.min(1-c.getDouble("width"),c.getDouble("x")+args.optDouble("dx")/(pixels?size.getInt("width"):1))));
            c.put("y",Math.max(0,Math.min(1-c.getDouble("height"),c.getDouble("y")+args.optDouble("dy")/(pixels?size.getInt("height"):1))));
        }else if(action.equals("studio_crop_resize")){
            double w=args.getDouble("width")/(pixels?size.getInt("width"):1),h=args.getDouble("height")/(pixels?size.getInt("height"):1);
            c.put("width",w).put("height",h);if(args.has("ratio"))c.put("ratio",args.getString("ratio"));
        }else if(action.equals("studio_crop_rotate")){double v=args.getDouble("degrees");if(Double.isNaN(v)||Double.isInfinite(v))throw new IOException("Rotation non finie");c.put("rotation",((v+180)%360+360)%360-180);
        }else if(action.equals("studio_crop_flip")){if(!args.has("horizontal")&&!args.has("vertical"))throw new IOException("horizontal et/ou vertical requis");if(args.has("horizontal"))c.put("flip_x",args.getBoolean("horizontal"));if(args.has("vertical"))c.put("flip_y",args.getBoolean("vertical"));
        }else if(!action.equals("studio_crop_apply"))throw new IOException("Commande crop inconnue");
        String ratio=c.optString("ratio","free");if(!ratio.equals("free")){double r=VideoEditorEngine.ratio(ratio),w=c.getDouble("width"),h=w*size.getDouble("width")/size.getDouble("height")/r;if(h>1-c.getDouble("y")){h=1-c.getDouble("y");w=h*size.getDouble("height")*r/size.getDouble("width");}c.put("width",w).put("height",h);}
        clip.put("crop",c);validate(clip);
    }
    static List<Effect> effects(JSONObject clip)throws Exception{
        List<Effect> out=new ArrayList<>();if(!clip.has("crop"))return out;validate(clip);JSONObject c=value(clip),r=effective(c);
        out.add(new Crop((float)(2*r.getDouble("x")-1),(float)(2*(r.getDouble("x")+r.getDouble("width"))-1),(float)(1-2*(r.getDouble("y")+r.getDouble("height"))),(float)(1-2*r.getDouble("y"))));
        out.add(new ScaleAndRotateTransformation.Builder().setScale(c.optBoolean("flip_x")?-1:1,c.optBoolean("flip_y")?-1:1).setRotationDegrees((float)c.optDouble("rotation")).build());return out;
    }
}
