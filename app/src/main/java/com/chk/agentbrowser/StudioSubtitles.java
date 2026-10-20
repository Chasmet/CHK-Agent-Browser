package com.chk.agentbrowser;
import org.json.*;
import android.text.SpannableString;
import android.text.style.*;
import androidx.media3.effect.TextOverlay;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.common.OverlaySettings;
import java.util.*;
import java.util.regex.*;

final class StudioSubtitles {
    private static long millis(String s)throws Exception{String[] p=s.trim().replace('.',',').split("[:,]");if(p.length!=4)throw new Exception("Horodatage SRT invalide");return Long.parseLong(p[0])*3600000+Long.parseLong(p[1])*60000+Long.parseLong(p[2])*1000+Long.parseLong(p[3]);}
    static JSONArray parse(String text)throws Exception{
        if(text.length()>60000)throw new Exception("Sous-titres limités à 60 000 caractères");JSONArray out=new JSONArray();
        for(String block:text.replace("\r","").trim().split("\n\\s*\n")){
            if(block.trim().isEmpty())continue;String[] lines=block.split("\n");int n=lines[0].contains("-->")?0:1;
            if(n>=lines.length||!lines[n].contains("-->"))throw new Exception("Bloc SRT sans horodatage");String[] times=lines[n].split("-->");if(times.length!=2)throw new Exception("Intervalle SRT invalide");
            StringBuilder body=new StringBuilder();for(int i=n+1;i<lines.length;i++){if(body.length()>0)body.append('\n');body.append(lines[i]);}
            long a=millis(times[0]),b=millis(times[1]);if(a<0||b<=a||body.length()==0||body.length()>500||out.length()>=200)throw new Exception("Sous-titre invalide ou limite de 200 dépassée");
            out.put(new JSONObject().put("start_ms",a).put("end_ms",b).put("text",body.toString()));
        }return out;
    }
    private static String time(long ms){return String.format(Locale.ROOT,"%02d:%02d:%02d,%03d",ms/3600000,ms/60000%60,ms/1000%60,ms%1000);}
    static String export(JSONArray captions){StringBuilder text=new StringBuilder();if(captions!=null)for(int i=0;i<captions.length();i++){JSONObject c=captions.optJSONObject(i);text.append(i+1).append('\n').append(time(c.optLong("start_ms"))).append(" --> ").append(time(c.optLong("end_ms"))).append('\n').append(c.optString("text")).append("\n\n");}return text.toString();}
    static String text(JSONObject project,long time){JSONArray captions=project.optJSONArray("subtitles");StringBuilder out=new StringBuilder();if(captions!=null)for(int i=0;i<captions.length();i++){JSONObject c=captions.optJSONObject(i);if(c!=null&&time>=c.optLong("start_ms")&&time<c.optLong("end_ms")){if(out.length()>0)out.append('\n');out.append(c.optString("text"));}}return out.toString();}
    static TextOverlay overlay(JSONObject project){return new TextOverlay(){
        @Override public SpannableString getText(long timeUs){String value=text(project,timeUs/1000);SpannableString text=new SpannableString(value.isEmpty()?" ":value);text.setSpan(new ForegroundColorSpan(value.isEmpty()?android.graphics.Color.TRANSPARENT:android.graphics.Color.WHITE),0,text.length(),0);text.setSpan(new AbsoluteSizeSpan(project.optInt("subtitle_size",36)),0,text.length(),0);text.setSpan(new BackgroundColorSpan(value.isEmpty()?0:0x99000000),0,text.length(),0);return text;}
        @Override public OverlaySettings getOverlaySettings(long timeUs){return new StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0,-.75f).build();}
    };}
    static void validate(JSONObject project,long duration)throws Exception{JSONArray subtitles=project.optJSONArray("subtitles");if(subtitles==null)return;if(subtitles.length()>200)throw new Exception("200 sous-titres maximum");for(int i=0;i<subtitles.length();i++){JSONObject c=subtitles.getJSONObject(i);if(c.getLong("start_ms")<0||c.getLong("end_ms")<=c.getLong("start_ms")||c.getLong("end_ms")>duration||c.getString("text").length()>500)throw new Exception("Sous-titre hors du montage ou trop long");}int size=project.optInt("subtitle_size",36);if(size<16||size>96)throw new Exception("Taille des sous-titres : 16 à 96");}
}
