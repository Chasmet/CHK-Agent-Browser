package com.chk.agentbrowser;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Non-destructive audio model. All automation times are project milliseconds. */
final class StudioMix {
    static final int RATE=48000;
    private StudioMix(){}
    static boolean enabled(JSONObject p){return p.optInt("audio_mix_version")>=2;}
    static boolean ids(JSONObject p)throws Exception{
        boolean changed=false;Set<String> seen=new HashSet<>();
        for(String array:new String[]{"clips","audio","layers"}){JSONArray a=p.optJSONArray(array);if(a==null)continue;
            for(int i=0;i<a.length();i++){JSONObject c=a.getJSONObject(i);String id=c.optString("clip_id");
                if(id.isEmpty()||!seen.add(id)){id=UUID.randomUUID().toString();c.put("clip_id",id);seen.add(id);changed=true;}
                if(!c.has("track_id")){c.put("track_id",array.equals("clips")?"original":array.equals("audio")?"music":"layers");changed=true;}
            }}return changed;
    }
    static JSONObject track(JSONObject p,String id)throws Exception{
        JSONObject all=p.optJSONObject("audio_tracks");return all!=null&&all.optJSONObject(id)!=null?all.getJSONObject(id):new JSONObject();
    }
    static JSONObject settings(JSONObject p,String id)throws Exception{
        if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IOException("track_id invalide");
        JSONObject all=p.optJSONObject("audio_tracks");if(all==null){all=new JSONObject();p.put("audio_tracks",all);}
        JSONObject t=all.optJSONObject(id);if(t==null){t=new JSONObject().put("volume",1);all.put(id,t);}return t;
    }
    static double number(JSONObject o,String key,double fallback,double min,double max)throws Exception{
        double v=o.optDouble(key,fallback);if((Double.isNaN(v)||Double.isInfinite(v))||v<min||v>max)throw new IOException(key+" hors limites ["+min+", "+max+"]");return v;
    }
    static double db(double linear){return linear==0?Double.NEGATIVE_INFINITY:20*Math.log10(linear);}
    static double linear(JSONObject request)throws Exception{
        if(request.has("percent")==request.has("db"))throw new IOException("Fournir percent OU db (silence : percent=0)");
        double v=request.has("percent")?number(request,"percent",100,0,200)/100:Math.pow(10,number(request,"db",0,-120,20*Math.log10(2))/20);
        return v;
    }
    static void validateGain(JSONObject o,long duration)throws Exception{
        number(o,"volume",1,0,2);number(o,"fade_in_ms",0,0,duration);number(o,"fade_out_ms",0,0,duration);
        JSONArray a=o.optJSONArray("gain_keyframes");if(a==null)return;if(a.length()>1000)throw new IOException("1000 keyframes audio maximum");long before=-1;
        for(int i=0;i<a.length();i++){JSONObject k=a.getJSONObject(i);long at=k.getLong("time_ms");if(at<=before||at<0||at>duration)throw new IOException("Keyframes audio : temps croissants uniques requis");number(k,"volume",1,0,2);before=at;}
    }
    static void validate(JSONObject p)throws Exception{
        long total=StudioEdits.total(p);JSONObject tracks=p.optJSONObject("audio_tracks");
        if(tracks!=null){if(tracks.length()>40)throw new IOException("40 pistes maximum");for(Iterator<String> it=tracks.keys();it.hasNext();)validateGain(tracks.getJSONObject(it.next()),total);}
        for(Source s:sources(p)){validateGain(s.clip,s.length);if(s.offset<0||total>0&&s.offset+s.length>total+1)throw new IOException("Clip audio hors de la timeline");}
        JSONObject d=p.optJSONObject("audio_ducking");if(d!=null){number(d,"reduction_db",-12,-60,0);number(d,"threshold_db",-40,-100,0);number(d,"attack_ms",80,1,5000);number(d,"release_ms",350,1,10000);
            JSONArray regions=d.optJSONArray("voice_regions");if(regions!=null){if(regions.length()>2000)throw new IOException("2000 régions vocales maximum");for(int i=0;i<regions.length();i++){JSONObject r=regions.getJSONObject(i);if(r.getLong("start_ms")<0||r.getLong("end_ms")<=r.getLong("start_ms")||r.getLong("end_ms")>total)throw new IOException("Région vocale invalide");}}
        }
        number(p,"limiter_ceiling_db",-1,-12,-.1);
    }
    static final class Source {JSONObject clip;String track;long offset,length;double speed;boolean original;Source(JSONObject c,String t,long o,boolean v){clip=c;track=t;offset=o;original=v;speed=v?c.optDouble("speed",1):1;length=Math.round(c.optLong("duration_ms")/speed);}}
    static List<Source> sources(JSONObject p)throws Exception{
        List<Source> out=new ArrayList<>();long position=0;JSONArray clips=p.getJSONArray("clips");
        for(int i=0;i<clips.length();i++){JSONObject c=clips.getJSONObject(i);if(!StudioImages.isImage(new File(c.optString("path"))))out.add(new Source(c,c.optString("track_id","original"),position,true));position+=StudioEdits.duration(c);}
        JSONArray audio=p.optJSONArray("audio");position=0;if(audio!=null)for(int i=0;i<audio.length();i++){JSONObject c=audio.getJSONObject(i);long offset=c.optLong("offset_ms",position);out.add(new Source(c,c.optString("track_id","music"),offset,false));position=offset+c.optLong("duration_ms");}return out;
    }
    static double envelope(JSONObject o,double time,double duration){
        double value=o.optDouble("volume",1);JSONArray a=o.optJSONArray("gain_keyframes");
        if(a!=null&&a.length()>0){JSONObject first=a.optJSONObject(0);value=first.optDouble("volume",1);for(int i=1;i<a.length();i++){JSONObject left=a.optJSONObject(i-1),right=a.optJSONObject(i);double start=left.optDouble("time_ms"),end=right.optDouble("time_ms");if(time<end){double t=Math.max(0,Math.min(1,(time-start)/(end-start)));value=left.optDouble("volume",1)*(1-t)+right.optDouble("volume",1)*t;break;}value=right.optDouble("volume",1);}}
        double fadeIn=o.optDouble("fade_in_ms"),fadeOut=o.optDouble("fade_out_ms");if(fadeIn>0)value*=Math.min(1,Math.max(0,time/fadeIn));if(fadeOut>0)value*=Math.min(1,Math.max(0,(duration-time)/fadeOut));return value;
    }
    static boolean audible(JSONObject p,Source s)throws Exception{
        JSONObject t=track(p,s.track);if(s.clip.optBoolean("mute")||t.optBoolean("mute")||s.original&&p.optBoolean("mute_original",false))return false;
        JSONObject all=p.optJSONObject("audio_tracks");boolean any=false;if(all!=null)for(Iterator<String> it=all.keys();it.hasNext();)any|=all.getJSONObject(it.next()).optBoolean("solo");
        for(Source other:sources(p))any|=other.clip.optBoolean("solo");
        return !any||t.optBoolean("solo")||s.clip.optBoolean("solo");
    }
    static double duck(JSONObject p,Source s,double ms)throws Exception{
        JSONObject d=p.optJSONObject("audio_ducking");if(d==null||!d.optBoolean("enabled")||s.original)return 1;
        String role=track(p,s.track).optString("role","music");if(!role.equals("music"))return 1;
        JSONArray regions=d.optJSONArray("voice_regions");if(regions==null)return 1;double amount=0,attack=d.optDouble("attack_ms",80),release=d.optDouble("release_ms",350);
        for(int i=0;i<regions.length();i++){JSONObject r=regions.getJSONObject(i);if(r.optDouble("rms_db",0)<d.optDouble("threshold_db",-40))continue;double a=r.optDouble("start_ms"),b=r.optDouble("end_ms");if(ms>=a&&ms<=b)amount=1;else if(ms<a&&ms>a-attack)amount=Math.max(amount,1-(a-ms)/attack);else if(ms>b&&ms<b+release)amount=Math.max(amount,1-(ms-b)/release);}
        return 1-amount*(1-Math.pow(10,d.optDouble("reduction_db",-12)/20));
    }
    static double gain(JSONObject p,Source s,double local)throws Exception{return envelope(s.clip,local,s.length)*envelope(track(p,s.track),s.offset+local,StudioEdits.total(p))*duck(p,s,s.offset+local);}
    static JSONObject catalog(Context app,JSONObject p)throws Exception{
        JSONArray all=new JSONArray();for(Source s:sources(p)){JSONObject row=new JSONObject().put("clip_id",s.clip.optString("clip_id")).put("track_id",s.track).put("original",s.original).put("path",s.clip.getString("path")).put("offset_ms",s.offset).put("duration_ms",s.length).put("clip",s.clip).put("track",track(p,s.track)).put("audible",audible(p,s));
            row.put("format",MediaInspection.info(app,s.clip.getString("path")));all.put(row);}
        return new JSONObject().put("project_id",p.getString("project_id")).put("revision",p.optInt("revision")).put("clips",all).put("mix_version",p.optInt("audio_mix_version",1)).put("time_unit","project_ms").put("gain_conversion","dB = 20 log10(linear); 0% = silence (-infinity dB)").put("ducking",p.optJSONObject("audio_ducking"));
    }
}
