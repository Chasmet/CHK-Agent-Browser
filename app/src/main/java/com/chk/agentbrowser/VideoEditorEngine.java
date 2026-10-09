package com.chk.agentbrowser;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.RgbAdjustment;
import androidx.media3.effect.RgbFilter;
import androidx.media3.effect.RgbMatrix;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native, fully local editor. Source files stay in CHK Files. No cloud upload,
 * secret-dependent API, FFmpeg binary or CapCut account is needed.
 * Transformer runs on main Looper; large media are streamed by Media3.
 */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class VideoEditorEngine {
    private static final String SAVE = "video_editor_project.json";
    private static final String ALPHA_OMEGA_OFFICIAL_OUTPUT =
        "musique/chknoirshadow/clip-grok-videos/ALPHA_OMEGA_CLIP_OFFICIEL_9x16.mp4";
    private static final String DEFAULT_OUTPUT =
        "musique/chknoirshadow/clip-grok-videos/ALPHA_OMEGA_CLIP_FINAL_GROK.mp4";
    private static final ExecutorService DISK=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final VideoEditorEngine INSTANCE=new VideoEditorEngine();
    private volatile String state="idle", error="",lastOutput="";
    private volatile int percent;
    private Transformer transformer;
    private File activeOutput;
    private Context app;

    private VideoEditorEngine(){}
    public static VideoEditorEngine get(){return INSTANCE;}

    public interface Done {void completed(boolean ok,String json);}
    public void command(Context context,String action,JSONObject args,Done cb){
        Context application=context.getApplicationContext();
        if("video_editor_status".equals(action)){
            MAIN.post(()->cb.completed(true,status().toString()));return;
        }
        if("video_editor_verify_output".equals(action)){
            DISK.execute(()->{
                try{cb.completed(true,verifyOutput(application).toString());}
                catch(Exception ex){cb.completed(false,ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage());}
            });
            return;
        }
        if("video_editor_export".equals(action)){
            MAIN.post(()->startExport(application,args.optBoolean("replace",false),cb));return;
        }
        if("video_editor_cancel".equals(action)){
            MAIN.post(()->{
                if(transformer!=null && "running".equals(state)){
                    transformer.cancel();transformer=null;state="cancelled";
                    if(activeOutput!=null)activeOutput.delete();
                }
                cb.completed(true,status().toString());
            });return;
        }
        DISK.execute(()->{
            try{
                JSONObject data;
                if("video_editor_status".equals(action))data=status();
                else if("video_editor_project_read".equals(action))data=load(application);
                else if("video_editor_project_save".equals(action)){
                    if("running".equals(state))throw new IllegalStateException("Export actif");
                    JSONObject project=args.getJSONObject("project");
                    validate(application,project);
                    persist(application,project);
                    data=new JSONObject().put("saved",true).put("project",project);
                }else if("video_editor_preset_alpha_omega".equals(action)){
                    if("running".equals(state))throw new IllegalStateException("Export actif");
                    JSONObject project=alphaOmega(application);
                    persist(application,project);
                    data=new JSONObject().put("saved",true).put("project",project);
                }else throw new IllegalArgumentException("Commande vidéo inconnue");
                cb.completed(true,data.toString());
            }catch(Exception ex){cb.completed(false,ex.getMessage());}
        });
    }
    public JSONObject status(){
        JSONObject o=new JSONObject();
        try{o.put("state",state).put("progress_percent",percent)
           .put("error",error).put("output",lastOutput)
           .put("editing_local",true).put("engine","androidx.media3.transformer");
           if("running".equals(state)&&transformer!=null&&Looper.myLooper()==Looper.getMainLooper()){
               ProgressHolder h=new ProgressHolder();
               int result=transformer.getProgress(h);
               if(result==Transformer.PROGRESS_STATE_AVAILABLE){
                   percent=h.progress; o.put("progress_percent",percent);
               }
           }
        }catch(Exception ignored){}
        return o;
    }
    /** Read-only verification of the MP4 on the phone; survives activity/process restart. */
    public JSONObject verifyOutput(Context context)throws Exception{
        JSONObject project=load(context);
        String output=project.optString("output",DEFAULT_OUTPUT);
        WorkspaceStore store=new WorkspaceStore(context);
        File file=store.file(output);
        JSONArray videos=project.optJSONArray("clips"),audio=project.optJSONArray("audio");
        int videoCount=videos==null?0:videos.length(),audioCount=audio==null?0:audio.length();
        long expectedMs=0;
        boolean sourcesPresent=videoCount>0;
        for(int i=0;i<videoCount;i++){
            JSONObject item=videos.optJSONObject(i);
            if(item==null){sourcesPresent=false;continue;}
            expectedMs+=item.optLong("duration_ms",10000);
            try{sourcesPresent &= requireSource(store,item.optString("path"),"video").isFile();}
            catch(Exception ignored){sourcesPresent=false;}
        }
        for(int i=0;i<audioCount;i++){
            JSONObject item=audio.optJSONObject(i);
            try{sourcesPresent &= item!=null && requireSource(store,item.optString("path"),"audio").isFile();}
            catch(Exception ignored){sourcesPresent=false;}
        }
        JSONObject result=new JSONObject().put("output",output).put("exists",file.isFile())
            .put("size_bytes",file.isFile()?file.length():0)
            .put("expected_duration_ms",expectedMs)
            .put("project_video_count",videoCount).put("project_audio_count",audioCount)
            .put("sources_present",sourcesPresent).put("valid",false);
        if(!file.isFile()||file.length()<10000)
            return result.put("reason","Fichier MP4 absent ou incomplet");
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try{
            retriever.setDataSource(file.getAbsolutePath());
            String duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String hasVideo=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            String hasAudio=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            String width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            long durationMs=duration==null?-1:Long.parseLong(duration);
            boolean picture="yes".equalsIgnoreCase(hasVideo),sound="yes".equalsIgnoreCase(hasAudio);
            boolean correctDuration=expectedMs>0&&durationMs>=0&&Math.abs(durationMs-expectedMs)<=500;
            boolean correctAspect=!"9:16".equals(project.optString("aspect_ratio","source"))
                || ("720".equals(width)&&"1280".equals(height));
            boolean valid=picture&&(!(audioCount>0)||sound)&&correctDuration&&sourcesPresent&&correctAspect;
            result.put("duration_ms",durationMs).put("has_video",picture)
                .put("has_audio",sound).put("width_px",width).put("height_px",height)
                .put("duration_matches_project",correctDuration)
                .put("aspect_ratio_matches_project",correctAspect).put("valid",valid);
            if(!valid)result.put("reason","Contrôler la durée, les pistes et les sources du projet");
            return result;
        }catch(Exception ex){
            return result.put("reason","Métadonnées MP4 illisibles : "+ex.getClass().getSimpleName());
        }finally{retriever.release();}
    }
    public JSONObject load(Context context)throws Exception{
        File f=new File(context.getFilesDir(),SAVE);
        if(!f.isFile())return new JSONObject().put("name","Nouveau montage")
                .put("output",DEFAULT_OUTPUT).put("clips",new JSONArray()).put("audio",new JSONArray());
        if(f.length()>200000)throw new IllegalStateException("Projet trop grand");
        byte[] bytes=new byte[(int)f.length()];
        try(FileInputStream in=new FileInputStream(f)){
            int read=0,n;while(read<bytes.length&&(n=in.read(bytes,read,bytes.length-read))>0)read+=n;
            if(read!=bytes.length)throw new IllegalStateException("Projet incomplet");
        }
        return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
    }
    private void persist(Context app,JSONObject project)throws Exception{
        File output=new File(app.getFilesDir(),SAVE),tmp=new File(app.getFilesDir(),SAVE+".tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){
            out.write(project.toString(2).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if(!tmp.renameTo(output))throw new IllegalStateException("Sauvegarde impossible");
    }
    private static File requireSource(WorkspaceStore store,String path,String type)throws Exception{
        if(path==null||path.length()>700||path.isEmpty())throw new IllegalArgumentException("Chemin source invalide");
        File f=store.file(path);
        if(!f.isFile()||!f.canRead()||f.length()==0)throw new IllegalArgumentException("Fichier absent: "+path);
        if("video".equals(type)&&!path.toLowerCase(Locale.ROOT).endsWith(".mp4"))
            throw new IllegalArgumentException("Vidéo MP4 requise: "+path);
        if("audio".equals(type)&&!(path.toLowerCase(Locale.ROOT).endsWith(".wav")||
                path.toLowerCase(Locale.ROOT).endsWith(".mp3")||
                path.toLowerCase(Locale.ROOT).endsWith(".m4a")))
            throw new IllegalArgumentException("Audio WAV/MP3/M4A requis: "+path);
        return f;
    }
    public JSONObject alphaOmega(Context app)throws Exception{
        String video="musique/chknoirshadow/clip-grok-videos/GROK_";
        String aud="musique/chknoirshadow/cut alpha de 1 a 6/cut_";
        WorkspaceStore store=new WorkspaceStore(app);
        JSONArray clips=new JSONArray(),sounds=new JSONArray();
        String[] filters={"froid","cinema","cinema","contraste","nuit","chaud"};
        for(int i=1;i<=6;i++){
            String v=video+String.format(Locale.ROOT,"%02d",i)+".mp4";
            String a=aud+i+".wav";
            requireSource(store,v,"video");requireSource(store,a,"audio");
            clips.put(new JSONObject().put("path",v).put("start_ms",0).put("duration_ms",10000)
                    .put("filter",filters[i-1]).put("fade_ms",180));
            sounds.put(new JSONObject().put("path",a).put("start_ms",0).put("duration_ms",10000));
        }
        JSONObject p=new JSONObject().put("name","ALPHA OMEGA · Clip Grok 60s")
                .put("output",ALPHA_OMEGA_OFFICIAL_OUTPUT)
                .put("aspect_ratio","9:16").put("aspect_mode","crop")
                .put("clips",clips).put("audio",sounds);
        validate(app,p);return p;
    }
    public void validate(Context context,JSONObject p)throws Exception{
        if(p.toString().length()>64000)throw new IllegalArgumentException("Projet trop volumineux");
        String aspect=p.optString("aspect_ratio","source");
        String layout=p.optString("aspect_mode","crop");
        if(!"source".equals(aspect)&&!"9:16".equals(aspect))
            throw new IllegalArgumentException("Format vidéo inconnu: "+aspect);
        if(!"crop".equals(layout)&&!"fit".equals(layout))
            throw new IllegalArgumentException("Recadrage inconnu: "+layout);
        WorkspaceStore store=new WorkspaceStore(context);
        JSONArray clips=p.getJSONArray("clips"),sounds=p.optJSONArray("audio");
        if(clips.length()==0||clips.length()>40)throw new IllegalArgumentException("1 à 40 vidéos requises");
        long videoDuration=0,audioDuration=0;
        for(int i=0;i<clips.length();i++){
            JSONObject c=clips.getJSONObject(i);
            File f=requireSource(store,c.getString("path"),"video");
            long clipStart=c.optLong("start_ms",0),duration=c.optLong("duration_ms",10000);
            if(clipStart<0||duration<500||duration>600000)throw new IllegalArgumentException("Découpe vidéo invalide");
            checkMediaDuration(f,clipStart+duration);
            String filter=c.optString("filter","aucun");
            if(!filter.equals("aucun")&&!filter.equals("noir")&&!filter.equals("vintage")
                    &&!filter.equals("chaud")&&!filter.equals("froid")
                    &&!filter.equals("cinema")&&!filter.equals("contraste")
                    &&!filter.equals("nuit"))throw new IllegalArgumentException("Filtre inconnu: "+filter);
            long fade=c.optLong("fade_ms",0);
            if(fade<0||fade>Math.min(1500,duration/3))throw new IllegalArgumentException("Effet de fondu invalide");
            videoDuration+=duration;
        }
        if(sounds!=null){
            if(sounds.length()>40)throw new IllegalArgumentException("Trop de pistes audio");
            for(int i=0;i<sounds.length();i++){
                JSONObject a=sounds.getJSONObject(i);
                File f=requireSource(store,a.getString("path"),"audio");
                long start=a.optLong("start_ms",0),duration=a.optLong("duration_ms",10000);
                if(start<0||duration<500||duration>600000)throw new IllegalArgumentException("Découpe audio invalide");
                checkMediaDuration(f,start+duration);
                audioDuration+=duration;
            }
            if(sounds.length()>0&&Math.abs(audioDuration-videoDuration)>1000)
                throw new IllegalArgumentException("Durées image / audio différentes de plus de 1 seconde");
        }
        if(videoDuration>20*60*1000)throw new IllegalArgumentException("Montage limité à 20 minutes");
        String output=p.optString("output","");
        if(!output.endsWith(".mp4")||output.length()>400)throw new IllegalArgumentException("Destination MP4 obligatoire");
        File dest=store.file(output);
        if(dest.isDirectory()||!dest.getParentFile().isDirectory())
            throw new IllegalArgumentException("Dossier de sortie inexistant");
        for(int i=0;i<clips.length();i++)if(output.equals(clips.getJSONObject(i).getString("path")))
            throw new IllegalArgumentException("Sortie identique à une vidéo source");
    }
    private void checkMediaDuration(File f,long requested)throws Exception{
        MediaMetadataRetriever r=new MediaMetadataRetriever();
        try{
            r.setDataSource(f.getAbsolutePath());
            String value=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            // Some Android extractors don't expose duration for WAV: validate byte file above
            if(value!=null&&!value.isEmpty()&&requested>Long.parseLong(value)+250)
                throw new IllegalArgumentException("Découpe au-delà du média: "+f.getName());
        }finally{r.release();}
    }
    private static MediaItem media(File file,long start,long duration){
        return new MediaItem.Builder().setUri(android.net.Uri.fromFile(file))
           .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
               .setStartPositionMs(start).setEndPositionMs(start+duration).build()).build();
    }
    private static List<androidx.media3.common.Effect> effects(String name,long length,long fade){
        List<androidx.media3.common.Effect> effects=new ArrayList<>();
        switch(name){
          case "noir":effects.add(RgbFilter.createGrayscaleFilter());break;
          case "vintage":effects.add(new RgbAdjustment.Builder().setRedScale(1.15f).setBlueScale(.78f).build());break;
          case "chaud":effects.add(new RgbAdjustment.Builder().setRedScale(1.09f).setBlueScale(.92f).build());break;
          case "froid":effects.add(new RgbAdjustment.Builder().setRedScale(.88f).setBlueScale(1.10f).build());break;
          case "cinema":effects.add(new Contrast(.13f));break;
          case "contraste":effects.add(new Contrast(.32f));break;
          case "nuit":effects.add(new RgbAdjustment.Builder().setRedScale(.8f).setGreenScale(.87f).setBlueScale(1.04f).build());break;
          default:break;
        }
        if(fade>0){
            effects.add((RgbMatrix)(timeUs,useHdr)->{
                long t=timeUs/1000;
                float gain=Math.min(1f,Math.min((float)t/fade,(float)(length-t)/fade));
                gain=Math.max(0f,gain);
                return new float[]{gain,0,0,0, 0,gain,0,0, 0,0,gain,0, 0,0,0,1};
            });
        }
        return effects;
    }
    private void startExport(Context context,boolean replace,Done callback){
        if("running".equals(state)){callback.completed(false,"Un rendu est déjà en cours");return;}
        try{
            JSONObject project=load(context);
            validate(context,project);
            WorkspaceStore store=new WorkspaceStore(context);
            String output=project.getString("output");
            File file=store.file(output);
            if(file.exists()){
                if(!replace)throw new IllegalArgumentException("Le MP4 existe déjà; replace=true nécessaire");
                if(!file.delete())throw new IllegalStateException("Impossible de remplacer l'ancien rendu");
            }
            JSONArray clips=project.getJSONArray("clips"),audios=project.optJSONArray("audio");
            List<EditedMediaItem> videos=new ArrayList<>(),sounds=new ArrayList<>();
            for(int i=0;i<clips.length();i++){
                JSONObject c=clips.getJSONObject(i);
                long start=c.optLong("start_ms",0),len=c.optLong("duration_ms",10000);
                List<androidx.media3.common.Effect> videoEffects=effects(
                    c.optString("filter","aucun"),len,c.optLong("fade_ms",0));
                if("9:16".equals(project.optString("aspect_ratio","source"))){
                    int layout="fit".equals(project.optString("aspect_mode","crop"))
                        ? Presentation.LAYOUT_SCALE_TO_FIT
                        : Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP;
                    videoEffects.add(Presentation.createForWidthAndHeight(720,1280,layout));
                }
                videos.add(new EditedMediaItem.Builder(media(store.file(c.getString("path")),start,len))
                    .setRemoveAudio(true)
                    .setEffects(new Effects(Collections.emptyList(),videoEffects))
                    .build());
            }
            if(audios!=null)for(int i=0;i<audios.length();i++){
                JSONObject a=audios.getJSONObject(i);
                sounds.add(new EditedMediaItem.Builder(media(store.file(a.getString("path")),
                        a.optLong("start_ms",0),a.optLong("duration_ms",10000)))
                        .setRemoveVideo(true).build());
            }
            EditedMediaItemSequence videoSequence=EditedMediaItemSequence.withVideoFrom(videos);
            Composition composition=sounds.isEmpty()
                    ?new Composition.Builder(videoSequence).build()
                    :new Composition.Builder(videoSequence,EditedMediaItemSequence.withAudioFrom(sounds)).build();
            app=context;activeOutput=file;lastOutput=output;error="";percent=0;state="running";
            transformer=new Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264)
               .addListener(new Transformer.Listener(){
                 @Override public void onCompleted(Composition c,ExportResult result){
                    if(!"running".equals(state))return;
                    if(activeOutput==null||!activeOutput.isFile()||activeOutput.length()<10000){
                        state="failed";error="MP4 exporté mais invalide ou vide";
                    }else{state="completed";percent=100;}
                    transformer=null;
                 }
                 @Override public void onError(Composition c,ExportResult result,ExportException e){
                    state="failed";error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                    if(activeOutput!=null)activeOutput.delete();
                    transformer=null;
                 }
               }).build();
            transformer.start(composition,file.getAbsolutePath());
            callback.completed(true,new JSONObject().put("state",state).put("output",output).toString());
        }catch(Exception e){
            state="failed";error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            callback.completed(false,error);
        }
    }
}
