package com.chk.agentbrowser;

import org.json.JSONArray;
import org.json.JSONObject;

/** Source-time edits shared by touch controls; project copies/history remain owned by the engine. */
final class StudioEdits {
    private StudioEdits() {}
    static long duration(JSONObject clip){return Math.round(clip.optLong("duration_ms",5000)/clip.optDouble("speed",1));}
    static long total(JSONObject project){long n=0;JSONArray clips=project.optJSONArray("clips");if(clips!=null)for(int i=0;i<clips.length();i++)n+=duration(clips.optJSONObject(i));return n;}
    static JSONObject trimmed(JSONObject clip,boolean left,long timelineDelta,long sourceLength)throws Exception {
        JSONObject out=new JSONObject(clip.toString());boolean photo=StudioImages.isImage(new java.io.File(clip.optString("path")));
        long begin=clip.optLong("start_ms"),length=clip.optLong("duration_ms"),minimum=photo?1000:500;
        long delta=Math.round(timelineDelta*clip.optDouble("speed",1));
        if(photo){length=Math.max(minimum,Math.min(60000,length+(left?-delta:delta)));begin=0;}
        else if(left){long change=Math.max(-begin,Math.min(length-minimum,delta));begin+=change;length-=change;}
        else length=Math.max(minimum,Math.min(Math.max(minimum,sourceLength-begin),length+delta));
        out.put("start_ms",begin).put("duration_ms",length);clampFade(out);return out;
    }
    /** Sequential soundtrack follows project end, without changing or deleting any source. */
    static void fitAudio(JSONObject project)throws Exception {
        JSONArray sounds=project.optJSONArray("audio");if(sounds==null)return;
        long available=total(project);JSONArray out=new JSONArray();
        for(int i=0;i<sounds.length()&&available>=500;i++){
            JSONObject copy=new JSONObject(sounds.getJSONObject(i).toString());long len=Math.min(available,copy.optLong("duration_ms"));
            if(len>=500){copy.put("duration_ms",len);out.put(copy);available-=len;}
        }
        project.put("audio",out);
    }
    static void clampFade(JSONObject clip)throws Exception {if(clip.has("fade_ms"))clip.put("fade_ms",Math.min(clip.optLong("fade_ms"),Math.min(1500,clip.optLong("duration_ms")/3)));}
    static void conform(JSONObject project)throws Exception {fitAudio(project);JSONArray clips=project.optJSONArray("clips");if(clips!=null)for(int i=0;i<clips.length();i++)clampFade(clips.getJSONObject(i));}
    static void duplicate(JSONObject project,int index)throws Exception {
        JSONArray clips=project.getJSONArray("clips"),out=new JSONArray();
        for(int i=0;i<clips.length();i++){out.put(clips.get(i));if(i==index)out.put(new JSONObject(clips.getJSONObject(i).toString()));}
        project.put("clips",out);
    }
}
