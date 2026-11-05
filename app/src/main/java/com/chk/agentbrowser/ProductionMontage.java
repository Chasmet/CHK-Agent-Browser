package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.TreeSet;

/** Builds normal, editable CHK Studio projects from verified production assets and real speech files. */
final class ProductionMontage {
    private ProductionMontage(){}
    static JSONObject assemble(Context app,JSONObject args)throws Exception {
        String root=ProductionProject.root(args),id=ProductionProject.id(args);
        WorkspaceStore store=new WorkspaceStore(app);
        JSONObject production=ProductionProject.read(app,root);
        String snapshot=root+"/Montage/"+id+".json",receipt=root+"/Montage/"+id+"-receipt.json";
        String fingerprint=AgentVision.digest(StudioMcp.canonical(args));
        if(store.file(receipt).isFile()){
            JSONObject previous=AgentFiles.read(store.file(receipt));
            if(!fingerprint.equals(previous.getString("fingerprint")))throw new IOException("Ce request_id appartient à un autre montage");
            return previous;
        }
        String state=VideoEditorEngine.get().status().optString("state");
        if("running".equals(state)||"preparing".equals(state))throw new IOException("Un export Studio est en cours");
        JSONArray input=args.optJSONArray("clips"),clips=new JSONArray();
        if(input==null){
            input=new JSONArray();JSONArray assets=production.getJSONArray("assets");
            for(int i=0;i<assets.length();i++){
                JSONObject asset=assets.getJSONObject(i);String kind=asset.getString("kind");
                if(kind.equals("browser_recording")||kind.equals("video")||kind.equals("image"))input.put(new JSONObject().put("path",asset.getString("path")));
            }
        }
        if(input.length()==0||input.length()>90)throw new IOException("Ajoute entre 1 et 90 captures ou médias au projet");
        String aspect=args.optString("aspect_ratio","9:16");
        if(!aspect.equals("9:16")&&!aspect.equals("16:9")&&!aspect.equals("1:1"))throw new IOException("Format 9:16, 16:9 ou 1:1 requis");
        String title=args.optString("title","").trim(),credits=args.optString("credits","").trim();
        long intro=title.isEmpty()?0:2000;
        if(intro>0)clips.put(card(app,root,id+"-titre",title,aspect,intro));
        for(int i=0;i<input.length();i++){
            JSONObject original=input.getJSONObject(i);String path=original.getString("path");
            if(!path.startsWith(root+"/"))throw new IOException("Importe d'abord le média avec asset_add");
            File file=store.file(path);if(!file.isFile())throw new IOException("Média absent : "+path);
            JSONObject clip=new JSONObject(original.toString());boolean image=StudioImages.isImage(file);
            long start=clip.optLong("start_ms",0),available=image?60000:MediaInspection.info(app,path).getLong("duration_ms")-start;
            long duration=clip.optLong("duration_ms",image?5000:available);
            if(start<0||duration<500||duration>available+50)throw new IOException("Découpe hors du média : "+path);
            clip.put("start_ms",start).put("duration_ms",duration).put("fade_ms",Math.min(args.optInt("fade_ms",250),duration/3));
            if(args.optBoolean("camera_motion",false))clip.put("zoom",1).put("zoom_end",1.04);
            clips.put(clip);
        }
        JSONObject project=StudioProjectLibrary.blank().put("name",production.getString("name"))
            .put("clips",clips).put("aspect_ratio",aspect).put("aspect_mode","fit")
            .put("resolution",1080).put("fps",24).put("output",root+"/Exports/"+id+".mp4")
            .put("speech_language","fr").put("mute_original",args.optBoolean("mute_original",false));
        StudioAudio.enable(project);
        project.getJSONArray("audio_tracks").put(StudioAudio.track("narration","Narration française"))
            .put(StudioAudio.track("music","Musique d'ambiance"));
        JSONArray subtitles=new JSONArray(),voice=new JSONArray();
        long voiceEnd=0;
        if(args.has("narration_id")){
            JSONObject narration=ProductionNarration.status(app,new JSONObject().put("project_path",root).put("request_id",args.getString("narration_id")));
            if(!"completed".equals(narration.getString("state")))throw new IOException("Termine la narration avant le montage");
            JSONArray segments=narration.getJSONArray("segments");
            long offset=intro+args.optLong("narration_offset_ms",0);
            for(int i=0;i<segments.length();i++){
                JSONObject item=segments.getJSONObject(i);String path=item.getString("path");
                if(!WorkspaceTransfer.hash(store.file(path)).equals(item.getString("sha256")))throw new IOException("Un segment vocal a changé");
                long start=offset+item.getLong("start_ms"),end=offset+item.getLong("end_ms");
                project.getJSONArray("audio").put(new JSONObject().put("path",path).put("start_ms",item.optLong("source_start_ms",0))
                    .put("duration_ms",item.getLong("duration_ms")).put("offset_ms",start).put("track_id","narration").put("volume",1));
                voice.put(new JSONObject().put("start_ms",start).put("end_ms",end));
                appendCues(subtitles,item.getString("text"),start,end);
                voiceEnd=Math.max(voiceEnd,end);
            }
        }
        long total=StudioEdits.total(project),hold=Math.max(0,voiceEnd-total);
        if(hold>15000)throw new IOException("La narration dépasse les captures de "+hold+" ms. Ajoute des plans ou raccourcis la narration");
        if(hold>0){
            JSONObject last=clips.getJSONObject(clips.length()-1);File source=store.file(last.getString("path"));
            String frozen=root+"/Montage/"+id+"-derniere-image.png";
            Bitmap bitmap;
            if(StudioImages.isImage(source))bitmap=android.graphics.BitmapFactory.decodeFile(source.getAbsolutePath());
            else{
                MediaMetadataRetriever reader=new MediaMetadataRetriever();
                try{reader.setDataSource(source.getAbsolutePath());bitmap=reader.getFrameAtTime((last.optLong("start_ms")+last.getLong("duration_ms")-100)*1000);}
                finally{reader.release();}
            }
            if(bitmap==null)throw new IOException("Dernière image réelle inaccessible");
            saveBitmap(store.file(frozen),bitmap);clips.put(new JSONObject().put("path",frozen).put("duration_ms",Math.max(1000,hold)));
        }
        if(!credits.isEmpty())clips.put(card(app,root,id+"-generique",credits,aspect,2000));
        total=StudioEdits.total(project);
        if(args.has("music_path")){
            String music=args.getString("music_path");
            if(!music.startsWith(root+"/"))throw new IOException("Importe la musique dans le projet avec asset_add");
            long length=Math.min(total,MediaInspection.info(app,music).getLong("duration_ms"));
            project.getJSONArray("audio").put(new JSONObject().put("path",music).put("start_ms",0).put("duration_ms",length)
                .put("offset_ms",0).put("track_id","music").put("volume",args.optDouble("music_volume",.25))
                .put("audio_fade_in_ms",Math.min(500,length)).put("audio_fade_out_ms",Math.min(800,length)));
            StudioAudio.track(project,"music").put("gain_keyframes",ducking(voice,total));
        }
        project.put("subtitles",subtitles).put("subtitle_timing_method","synthesized_utterance_segments")
            .put("production_project_path",root).put("last_frame_hold_ms",hold);
        VideoEditorEngine.get().validate(app,project);
        JSONObject saved=StudioProjectLibrary.create(app,project);
        StudioAutomation.write(store.file(snapshot),saved);
        JSONObject result=new JSONObject().put("state","completed").put("fingerprint",fingerprint)
            .put("project_path",root).put("studio_project_id",saved.getString("project_id"))
            .put("montage_path",snapshot).put("output",saved.getString("output"))
            .put("duration_ms",StudioEdits.total(saved)).put("resolution",1080).put("clips",clips.length())
            .put("narration_segments",voice.length()).put("subtitle_cues",subtitles.length()).put("last_frame_hold_ms",hold);
        StudioAutomation.write(store.file(receipt),result);ProductionProject.update(app,root,"montage",result);return result;
    }
    static JSONArray ducking(JSONArray voice,long duration)throws Exception {
        TreeSet<Long> points=new TreeSet<>();points.add(0L);points.add(duration);
        for(int i=0;i<voice.length();i++){
            JSONObject segment=voice.getJSONObject(i);long start=segment.getLong("start_ms"),end=segment.getLong("end_ms");
            points.add(Math.max(0,start-120));points.add(start);
            points.add(end);points.add(Math.min(duration,end+350));
        }
        // Evaluate overlapping attack/release windows with the existing mixer logic.
        JSONObject policy=new JSONObject().put("enabled",true).put("reduction_db",20*Math.log10(.22))
            .put("attack_ms",120).put("release_ms",350).put("threshold_dbfs",-120);
        JSONArray spans=new JSONArray();
        for(int i=0;i<voice.length();i++)spans.put(new JSONObject(voice.getJSONObject(i).toString()).put("rms_dbfs",0));
        JSONArray result=new JSONArray();int index=0;
        for(long time:points)result.put(new JSONObject().put("id","duck-"+index++)
            .put("time_ms",time).put("gain",StudioAudioMixer.duckGain(spans,policy,time)));
        return result;
    }
    static void appendCues(JSONArray cues,String text,long start,long end)throws Exception {
        String[] words=text.trim().split("\\s+");java.util.ArrayList<String> lines=new java.util.ArrayList<>();String line="";
        for(String word:words){
            if(word.length()>110)throw new IOException("Un mot de narration dépasse 110 caractères");
            if(!line.isEmpty()&&line.length()+word.length()+1>110){lines.add(line);line="";}
            line=line.isEmpty()?word:line+" "+word;
        }
        if(!line.isEmpty())lines.add(line);
        for(int i=0;i<lines.size();i++)cues.put(new JSONObject().put("start_ms",start+(end-start)*i/lines.size())
            .put("end_ms",start+(end-start)*(i+1)/lines.size()).put("text",lines.get(i)));
    }
    private static JSONObject card(Context app,String root,String id,String text,String aspect,long duration)throws Exception {
        if(text.length()>500)throw new IOException("Titre ou générique limité à 500 caractères");
        int width=aspect.equals("16:9")?1920:1080,height=aspect.equals("9:16")?1920:1080;
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(0xff111827);
        TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.WHITE);paint.setTextSize(56);
        StaticLayout layout=StaticLayout.Builder.obtain(text,0,text.length(),paint,width-160)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build();
        canvas.save();canvas.translate(80,Math.max(80,(height-layout.getHeight())/2f));layout.draw(canvas);canvas.restore();
        String path=root+"/Montage/"+id+".png";saveBitmap(new WorkspaceStore(app).file(path),bitmap);
        return new JSONObject().put("path",path).put("duration_ms",duration).put("fade_ms",250);
    }
    private static void saveBitmap(File file,Bitmap bitmap)throws Exception {
        try(FileOutputStream output=new FileOutputStream(file)){
            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,output))throw new IOException("Image du montage non enregistrée");
        }finally{bitmap.recycle();}
    }
}
