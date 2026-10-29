package com.chk.agentbrowser;

import androidx.media3.common.Effect;
import androidx.media3.effect.Crop;
import androidx.media3.effect.ScaleAndRotateTransformation;
import java.util.*;
import org.json.JSONObject;

/** Non-destructive, per-clip source crop. Coordinates are normalized top-left. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class StudioCrop {
    private StudioCrop(){}
    static JSONObject read(JSONObject clip)throws Exception {
        JSONObject saved=clip.optJSONObject("crop");
        JSONObject value=saved==null?new JSONObject():new JSONObject(saved.toString());
        if(!value.has("x"))value.put("x",0);
        if(!value.has("y"))value.put("y",0);
        if(!value.has("width"))value.put("width",1);
        if(!value.has("height"))value.put("height",1);
        if(!value.has("ratio"))value.put("ratio","free");
        if(!value.has("degrees"))value.put("degrees",0);
        if(!value.has("flip_horizontal"))value.put("flip_horizontal",false);
        if(!value.has("flip_vertical"))value.put("flip_vertical",false);
        validate(value);return value;
    }
    static void validate(JSONObject c){
        for(java.util.Iterator<String> keys=c.keys();keys.hasNext();){String key=keys.next();if(!java.util.Arrays.asList("x","y","width","height","degrees","flip_horizontal","flip_vertical","ratio").contains(key))throw new IllegalArgumentException("Paramètre spatial non pris en charge : "+key);}
        if(c.has("ratio")&&!java.util.Arrays.asList("free","9:16","16:9","1:1","4:5").contains(c.optString("ratio")))throw new IllegalArgumentException("Ratio non pris en charge");
        double x=c.optDouble("x",Double.NaN),y=c.optDouble("y",Double.NaN);
        double w=c.optDouble("width",Double.NaN),h=c.optDouble("height",Double.NaN);
        double angle=c.optDouble("degrees",0);
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(w)||!Double.isFinite(h)||
           x<0||y<0||w<=0.01||h<=0.01||x+w>1.000001||y+h>1.000001||
           !Double.isFinite(angle)||angle<-180||angle>180)
            throw new IllegalArgumentException("Recadrage hors de l'image source : rectangle normalisé x,y,width,height et angle ±180°");
    }
    static JSONObject changed(JSONObject old,String operation,JSONObject args,int sw,int sh)throws Exception {
        JSONObject c=new JSONObject(old.toString());
        switch(operation){
            case "set":{
                JSONObject rect=args.getJSONObject("rectangle");
                String units=args.optString("units","normalized");
                if(!units.equals("normalized")&&!units.equals("pixels"))throw new Exception("Unités: normalized ou pixels");
                double divX=units.equals("pixels")?sw:1,divY=units.equals("pixels")?sh:1;
                if(divX<=0||divY<=0)throw new Exception("Dimensions source requises pour pixels");
                c.put("x",rect.getDouble("x")/divX);
                c.put("y",rect.getDouble("y")/divY);
                c.put("width",rect.getDouble("width")/divX);
                c.put("height",rect.getDouble("height")/divY);
                break;
            }
            case "move":{
                double dx=args.getDouble("dx"),dy=args.getDouble("dy");
                if(args.optString("units","normalized").equals("pixels")){if(sw<=0||sh<=0)throw new Exception("Dimensions source requises");dx/=sw;dy/=sh;}
                else if(!args.optString("units","normalized").equals("normalized"))throw new Exception("Unités invalides");
                if(!Double.isFinite(dx)||!Double.isFinite(dy))throw new Exception("Déplacement non fini");
                c.put("x",Math.max(0,Math.min(1-c.getDouble("width"),c.getDouble("x")+dx)));
                c.put("y",Math.max(0,Math.min(1-c.getDouble("height"),c.getDouble("y")+dy)));
                break;
            }
            case "resize":{
                double w=args.getDouble("width"),h=args.getDouble("height");
                if(args.optString("units","normalized").equals("pixels")){if(sw<=0||sh<=0)throw new Exception("Dimensions source requises");w/=sw;h/=sh;}
                else if(!args.optString("units","normalized").equals("normalized"))throw new Exception("Unités invalides");
                if(!Double.isFinite(w)||!Double.isFinite(h))throw new Exception("Dimensions non finies");
                c.put("width",w);c.put("height",h);
                c.put("x",Math.min(c.getDouble("x"),1-w));
                c.put("y",Math.min(c.getDouble("y"),1-h));
                break;
            }
            case "rotate":c.put("degrees",args.getDouble("degrees"));break;
            case "flip":{
                if(args.has("horizontal"))c.put("flip_horizontal",args.getBoolean("horizontal"));
                if(args.has("vertical"))c.put("flip_vertical",args.getBoolean("vertical"));
                break;
            }
            case "reset":return read(new JSONObject());
            default:throw new Exception("Opération de recadrage inconnue");
        }
        String ratio=args.optString("ratio",c.optString("ratio","free"));
        if(!Arrays.asList("free","9:16","16:9","1:1","4:5").contains(ratio))throw new Exception("Ratio non pris en charge");
        c.put("ratio",ratio);
        if((operation.equals("set")||operation.equals("resize"))&&!ratio.equals("free")){
            if(sw<=0||sh<=0)throw new Exception("Dimensions source requises");String[] parts=ratio.split(":");double wanted=Double.parseDouble(parts[0])/Double.parseDouble(parts[1])*sh/sw;
            double w=c.getDouble("width"),h=c.getDouble("height");if(w/h>wanted)w=h*wanted;else h=w/wanted;c.put("width",w).put("height",h);
        }
        validate(c);return c;
    }
    /** Same Media3 effect object is used for preview and final export. */
    static List<Effect> effects(JSONObject clip)throws Exception{
        JSONObject c=read(clip);float x=(float)c.getDouble("x"),y=(float)c.getDouble("y"),w=(float)c.getDouble("width"),h=(float)c.getDouble("height");
        List<Effect> out=new ArrayList<>();
        // Crop.configure changes output dimensions: anisotropic scaling alone stretched the frame.
        out.add(new Crop(2*x-1,2*(x+w)-1,1-2*(y+h),1-2*y));
        out.add(new ScaleAndRotateTransformation.Builder()
            .setScale(c.optBoolean("flip_horizontal")?-1:1,c.optBoolean("flip_vertical")?-1:1)
            .setRotationDegrees((float)c.getDouble("degrees")).build());
        return out;
    }
}
