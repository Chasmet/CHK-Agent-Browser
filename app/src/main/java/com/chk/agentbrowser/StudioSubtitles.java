package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import androidx.media3.effect.TextOverlay;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.common.OverlaySettings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Locally generated, editable subtitles use absolute project timeline milliseconds. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class StudioSubtitles {
    private StudioSubtitles(){}
    interface Progress{void update(int index,int total);}
    static JSONArray items(JSONObject project){JSONArray values=project.optJSONArray("subtitles");return values==null?new JSONArray():values;}
    static String at(JSONObject p,long ms){JSONArray values=items(p);for(int i=0;i<values.length();i++){JSONObject c=values.optJSONObject(i);if(c!=null&&ms>=c.optLong("start_ms")&&ms<c.optLong("end_ms"))return c.optString("text");}return "";}
    static void validate(JSONObject p,long duration)throws Exception{
        if(p.has("subtitles")&&!(p.get("subtitles") instanceof JSONArray))throw new IOException("Sous-titres invalides.");
        JSONArray a=items(p);if(a.length()>500)throw new IOException("500 sous-titres maximum.");long previousEnd=0;
        for(int i=0;i<a.length();i++){JSONObject c=a.getJSONObject(i);long start=c.getLong("start_ms"),end=c.getLong("end_ms");String text=c.getString("text").trim();
            if(start<previousEnd||start<0||end<=start||end>1200000L||text.isEmpty()||text.length()>120)throw new IOException("Sous-titre "+(i+1)+" invalide ou superposé.");previousEnd=end;}
    }
    static JSONArray generate(Context app,JSONObject project,String language,AgentClient.ResultCallback active,Progress progress)throws Exception{
        long duration=StudioEdits.total(project);if(duration<=0)throw new IOException("Ajoute une vidéo avant de créer les sous-titres.");
        JSONArray sources=project.optJSONArray("audio");boolean audio=sources!=null&&sources.length()>0;
        if(!audio){if(project.optBoolean("mute_original",false))throw new IOException("Le son d'origine est coupé : ajoute une piste audio.");sources=project.getJSONArray("clips");}
        JSONArray cues=new JSONArray();long timeline=0;
        for(int i=0;i<sources.length()&&timeline<duration;i++){
            if(active!=null&&!active.isActive())throw new IOException("Analyse annulée.");JSONObject source=sources.getJSONObject(i);long sourceStart=source.optLong("start_ms",0),length=source.optLong("duration_ms",0);double speed=audio?1:source.optDouble("speed",1);
            long timelineLength=audio?length:Math.round(length/speed);if(progress!=null)progress.update(i+1,sources.length());
            if(!audio&&(source.optBoolean("mute",false)||StudioImages.isImage(new java.io.File(source.optString("path"))))){timeline+=timelineLength;continue;}
            for(long part=0;part<length;part+=180000L){
                long remaining=Math.min(180000L,length-part);int seconds=(int)Math.max(1,(remaining+999)/1000);
                JSONArray words=MediaTranscriber.words(app,source.getString("path"),language,seconds,sourceStart+part,active);
                append(cues,words,sourceStart+part,remaining,timeline+Math.round(part/speed),speed,duration);
            }
            timeline+=timelineLength;
        }
        if(cues.length()==0)throw new IOException("Aucune parole reconnue : aucun sous-titre n'a été ajouté.");
        return cues;
    }
    /** Group actual word timings; no interpolation across pauses or source boundaries. */
    static void append(JSONArray cues,JSONArray words,long sourceStart,long sourceLength,long projectStart,double speed,long maxEnd)throws Exception{
        JSONObject current=null;int count=0;long endSource=sourceStart+sourceLength;
        for(int i=0;i<words.length();i++){
            JSONObject w=words.getJSONObject(i);long a=Math.max(sourceStart,w.getLong("start_ms")),b=Math.min(endSource,w.getLong("end_ms"));
            if(b<=a)continue;long start=projectStart+Math.round((a-sourceStart)/speed),end=Math.min(maxEnd,projectStart+Math.round((b-sourceStart)/speed));
            if(end<=start||start>=maxEnd)continue;String word=w.getString("text").trim();
            boolean join=current!=null&&count<4&&start-current.getLong("end_ms")<=600&&end-current.getLong("start_ms")<=2500&&(current.getString("text").length()+word.length()+1)<=42;
            if(join){current.put("text",current.getString("text")+" "+word).put("end_ms",Math.max(current.getLong("end_ms"),end));count++;}
            else{
                if(cues.length()>=500)throw new IOException("Plus de 500 sous-titres : analyse une séquence plus courte.");
                if(cues.length()>0){long previous=cues.getJSONObject(cues.length()-1).getLong("end_ms");start=Math.max(start,previous);}
                if(start>=end)continue;current=new JSONObject().put("start_ms",start).put("end_ms",end).put("text",word);cues.put(current);count=1;
            }
        }
    }
    private static SpannableString styled(String value){SpannableString text=new SpannableString(value);
        text.setSpan(new ForegroundColorSpan(Color.WHITE),0,text.length(),0);text.setSpan(new BackgroundColorSpan(0x99000000),0,text.length(),0);
        text.setSpan(new AbsoluteSizeSpan(32),0,text.length(),0);return text;}
    static androidx.media3.common.Effect effect(JSONObject project)throws Exception{
        JSONArray input=items(project);final long[] starts=new long[input.length()],ends=new long[input.length()];
        final SpannableString[] text=new SpannableString[input.length()];
        for(int i=0;i<input.length();i++){JSONObject cue=input.getJSONObject(i);starts[i]=cue.getLong("start_ms");ends[i]=cue.getLong("end_ms");text[i]=styled(cue.getString("text"));}
        final SpannableString empty=new SpannableString("\u00a0");empty.setSpan(new ForegroundColorSpan(Color.TRANSPARENT),0,empty.length(),0);
        final StaticOverlaySettings settings=new StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0,-0.75f).build();
        TextOverlay overlay=new TextOverlay(){
            public SpannableString getText(long presentationTimeUs){
                long time=Math.max(0,presentationTimeUs/1000);int lo=0,hi=starts.length-1,index=-1;
                while(lo<=hi){int mid=(lo+hi)>>>1;if(starts[mid]<=time){index=mid;lo=mid+1;}else hi=mid-1;}
                return index>=0&&time<ends[index]?text[index]:empty;
            }
            public OverlaySettings getOverlaySettings(long presentationTimeUs){return settings;}
        };
        return new androidx.media3.effect.OverlayEffect(java.util.Collections.singletonList(overlay));
    }
}
