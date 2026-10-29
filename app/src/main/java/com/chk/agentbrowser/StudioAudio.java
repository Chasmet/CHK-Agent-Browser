package com.chk.agentbrowser;

import org.json.*;
import java.util.*;

/** Versioned, non-destructive audio model. All times are integer timeline milliseconds. */
final class StudioAudio {
    private StudioAudio(){}
    static boolean enabled(JSONObject p){return p.optInt("audio_model_version")==2;}
    static boolean ids(JSONObject p)throws Exception{
        boolean changed=false;Set<String> used=new HashSet<>();
        for(String lane:new String[]{"clips","audio"}){
            JSONArray items=p.optJSONArray(lane);if(items==null)continue;
            for(int i=0;i<items.length();i++){
                JSONObject c=items.getJSONObject(i);String id=c.optString("studio_id");
                if(!id.matches("[A-Za-z0-9_-]{1,80}")||!used.add(id)){
                    c.put("studio_id",UUID.randomUUID().toString());used.add(c.getString("studio_id"));changed=true;
                }
            }
        }return changed;
    }
    /** Opt in explicitly. Old sequential soundtrack positions are preserved. */
    static void enable(JSONObject p)throws Exception{
        ids(p);if(enabled(p))return;
        JSONArray tracks=new JSONArray().put(track("original","Voix originale")).put(track("audio","Audio existant"));
        long at=0;JSONArray sounds=p.optJSONArray("audio");
        if(sounds!=null)for(int i=0;i<sounds.length();i++){
            JSONObject c=sounds.getJSONObject(i);c.put("track_id","audio").put("offset_ms",at);at+=duration(c);
        }
        p.put("audio_tracks",tracks).put("audio_model_version",2);
    }
    static JSONObject track(String id,String name)throws Exception{return new JSONObject().put("id",id).put("name",name).put("volume",1).put("mute",false).put("solo",false);}
    static JSONObject track(JSONObject p,String id)throws Exception{
        JSONArray tracks=p.optJSONArray("audio_tracks");
        if(tracks!=null)for(int i=0;i<tracks.length();i++)if(id.equals(tracks.getJSONObject(i).optString("id")))return tracks.getJSONObject(i);
        if(!enabled(p)&&(id.equals("original")||id.equals("audio")))return track(id,id);
        throw new IllegalArgumentException("TRACK_NOT_FOUND: "+id);
    }
    static long duration(JSONObject c){return Math.round(c.optLong("duration_ms",10000)/c.optDouble("speed",1));}
    static JSONArray sources(JSONObject p)throws Exception{
        JSONArray out=new JSONArray();long at=0;
        for(String lane:new String[]{"clips","audio"}){
            JSONArray items=p.optJSONArray(lane);if(items==null)continue;at=0;
            for(int i=0;i<items.length();i++){
                JSONObject c=items.getJSONObject(i),copy=new JSONObject(c.toString());long len=duration(c);
                copy.put("lane",lane).put("index",i).put("timeline_ms",lane.equals("clips")?at:enabled(p)?c.optLong("offset_ms"):at)
                    .put("track_id",lane.equals("clips")?"original":c.optString("track_id","audio")).put("timeline_duration_ms",len);
                out.put(copy);at+=len;
            }
        }return out;
    }
    static boolean audible(JSONObject p,JSONObject c)throws Exception{
        JSONObject t=track(p,c.getString("track_id"));boolean solo=false;
        JSONArray tracks=p.optJSONArray("audio_tracks");if(tracks!=null)for(int i=0;i<tracks.length();i++)solo|=tracks.getJSONObject(i).optBoolean("solo");
        return !c.optBoolean("mute")&&!t.optBoolean("mute")&&(!solo||t.optBoolean("solo"))
            &&!(c.optString("lane").equals("clips")&&p.optBoolean("mute_original",false));
    }
    static double gain(JSONObject item,long ms,long len)throws Exception{
        double g=item.optDouble("volume",1);JSONArray points=item.optJSONArray("gain_keyframes");
        if(points!=null&&points.length()>0){
            JSONObject first=points.getJSONObject(0),last=points.getJSONObject(points.length()-1);
            double v=first.getDouble("gain");
            if(ms>=last.getLong("time_ms"))v=last.getDouble("gain");
            else for(int i=1;i<points.length();i++){
                JSONObject a=points.getJSONObject(i-1),b=points.getJSONObject(i);long ta=a.getLong("time_ms"),tb=b.getLong("time_ms");
                if(ms>=ta&&ms<tb){v=a.getDouble("gain")+(b.getDouble("gain")-a.getDouble("gain"))*(ms-ta)/(double)(tb-ta);break;}
            }g*=v;
        }
        long in=item.optLong("audio_fade_in_ms"),out=item.optLong("audio_fade_out_ms");
        if(in>0)g*=Math.min(1,Math.max(0,ms/(double)in));
        if(out>0)g*=Math.min(1,Math.max(0,(len-ms)/(double)out));return g;
    }
    static double value(JSONObject args)throws Exception{
        if(args.has("percent")==args.has("db"))throw new IllegalArgumentException("Choisir percent OU db");
        double v=args.has("percent")?args.getDouble("percent")/100:args.optString("db").equals("-Infinity")?0:Math.pow(10,args.getDouble("db")/20);
        if(!Double.isFinite(v)||v<0||v>2)throw new IllegalArgumentException("Gain entre 0 et 200 % requis");return v;
    }
    static Object db(double gain){return gain==0?"-Infinity":20*Math.log10(gain);}
    static void validateItem(JSONObject c,long len)throws Exception{
        number(c,"volume",1,0,2);number(c,"speed",1,.25,4);
        for(String k:new String[]{"audio_fade_in_ms","audio_fade_out_ms"})number(c,k,0,0,len);
        JSONArray points=c.optJSONArray("gain_keyframes");long previous=-1;
        if(points!=null){if(points.length()>500)throw new IllegalArgumentException("500 keyframes maximum");Set<String> ids=new HashSet<>();
            for(int i=0;i<points.length();i++){JSONObject point=points.getJSONObject(i);long time=point.getLong("time_ms");
                if(time<0||time>len||time<=previous||!ids.add(point.getString("id")))throw new IllegalArgumentException("Keyframes triées, uniques et dans la durée requises");
                number(point,"gain",1,0,2);previous=time;
            }
        }
    }
    static void validate(JSONObject p)throws Exception{
        if(!enabled(p))return;long total=StudioEdits.total(p);JSONArray tracks=p.getJSONArray("audio_tracks");Set<String> ids=new HashSet<>();
        if(tracks.length()<1||tracks.length()>40)throw new IllegalArgumentException("1 à 40 pistes requises");
        for(int i=0;i<tracks.length();i++){
            JSONObject t=tracks.getJSONObject(i);String id=t.getString("id");
            if(!id.matches("[A-Za-z0-9_-]{1,80}")||!ids.add(id))throw new IllegalArgumentException("Identifiant de piste unique requis");
            validateItem(t,total);JSONObject d=t.optJSONObject("ducking");
            if(d!=null){number(d,"reduction_db",-18,-60,0);number(d,"threshold_dbfs",-40,-90,0);number(d,"attack_ms",100,1,5000);number(d,"release_ms",500,1,10000);}
        }
        if(!ids.contains("original"))throw new IllegalArgumentException("Piste original requise");
        JSONArray sources=sources(p);for(int i=0;i<sources.length();i++){
            JSONObject c=sources.getJSONObject(i);track(p,c.getString("track_id"));validateItem(c,c.getLong("timeline_duration_ms"));
            if(c.getLong("timeline_ms")<0||c.getLong("timeline_ms")>1200000)throw new IllegalArgumentException("Position audio invalide");
        }
    }
    static void number(JSONObject p,String key,double fallback,double min,double max){double n=p.optDouble(key,fallback);if(!Double.isFinite(n)||n<min||n>max)throw new IllegalArgumentException("Paramètre audio invalide : "+key);}
}
