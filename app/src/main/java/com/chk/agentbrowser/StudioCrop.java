package com.chk.agentbrowser;

import android.graphics.Matrix;
import androidx.media3.effect.MatrixTransformation;
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
        if(!value.has("degrees"))value.put("degrees",0);
        if(!value.has("flip_horizontal"))value.put("flip_horizontal",false);
        if(!value.has("flip_vertical"))value.put("flip_vertical",false);
        validate(value);return value;
    }
    static void validate(JSONObject c){
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
                if(!Double.isFinite(dx)||!Double.isFinite(dy))throw new Exception("Déplacement non fini");
                c.put("x",Math.max(0,Math.min(1-c.getDouble("width"),c.getDouble("x")+dx)));
                c.put("y",Math.max(0,Math.min(1-c.getDouble("height"),c.getDouble("y")+dy)));
                break;
            }
            case "resize":{
                double w=args.getDouble("width"),h=args.getDouble("height");
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
        validate(c);return c;
    }
    /** Same Media3 effect object is used for preview and final export. */
    static MatrixTransformation effect(JSONObject clip)throws Exception{
        final JSONObject c=read(clip);
        final float x=(float)c.getDouble("x"),y=(float)c.getDouble("y");
        final float w=(float)c.getDouble("width"),h=(float)c.getDouble("height");
        final float rotation=(float)c.getDouble("degrees");
        final boolean fh=c.getBoolean("flip_horizontal"),fv=c.getBoolean("flip_vertical");
        return timeUs->{
            Matrix m=new Matrix();
            m.setScale(1f/w,1f/h);
            // Incoming GPU coordinates are centered [-1,+1], Y axis up.
            m.postTranslate((1-w-2*x)/w,(h+2*y-1)/h);
            if(fh||fv)m.postScale(fh?-1f:1f,fv?-1f:1f);
            if(rotation!=0)m.postRotate(rotation);
            return m;
        };
    }
}
