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
        "musique/chknoirshadow/clip-grok-videos/ALPHA_OMEGA_CLIP_OFFICIEL_9x16_V2.mp4";
    private static final String DEFAULT_OUTPUT =
        "musique/chknoirshadow/clip-grok-videos/ALPHA_OMEGA_CLIP_FINAL_GROK.mp4";
    static final ExecutorService DISK=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final VideoEditorEngine INSTANCE=new VideoEditorEngine();
    private volatile String state="idle", error="",lastOutput="";
    private volatile int percent;
    private Transformer transformer;
    private final Object exportLock=new Object();
    private volatile long exportGeneration;
    private File activeOutput, finalOutput;
    private Context app;

    private VideoEditorEngine(){}
    public static VideoEditorEngine get(){return INSTANCE;}

    public interface Done {void completed(boolean ok,String json);}
    public void command(Context context,String action,JSONObject args,Done cb){
        Context application=context.getApplicationContext();
        if(StudioAutomation.handles(action)){StudioAutomation.command(application,action,args,cb);return;}
        if("video_editor_status".equals(action)){
            MAIN.post(()->cb.completed(true,status().toString()));return;
        }
        if("video_editor_verify_output".equals(action)){
            DISK.execute(()->{
                try{cb.completed(true,(args.has("project")?verifyProjectOutput(application,args.getJSONObject("project")):verifyOutput(application)).toString());}
                catch(Exception ex){cb.completed(false,ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage());}
            });
            return;
        }
        if("video_editor_export".equals(action)){
            MAIN.post(()->{
                if("running".equals(state)||"preparing".equals(state)){cb.completed(false,"Un export est déjà actif");return;}
                final long ticket; synchronized(exportLock){ticket=++exportGeneration;state="preparing";error="";percent=0;}
                DISK.execute(()->{try{
                    JSONObject project=args.has("project")?new JSONObject(args.getJSONObject("project").toString()):load(application);validate(application,project);
                    if(project.getJSONArray("clips").length()==0)throw new IllegalArgumentException("Ajoute une vidéo avant d’exporter");
                    Composition composition=composition(application,project);
                    MAIN.post(()->{if(ticket!=exportGeneration||!"preparing".equals(state)){cb.completed(false,"Export annulé");return;}startExport(application,project,composition,args.optBoolean("replace",false),ticket,cb);});
                }catch(Exception ex){synchronized(exportLock){if(ticket==exportGeneration){state="failed";error=ex.getMessage();}}cb.completed(false,ex.getMessage());}});
            });return;
        }
        if("video_editor_cancel".equals(action)){
            MAIN.post(()->{
                synchronized(exportLock){
                    if("running".equals(state)||"preparing".equals(state)){
                        exportGeneration++;
                        if(transformer!=null)transformer.cancel();transformer=null;state="cancelled";
                        if(activeOutput!=null)activeOutput.delete();
                    }
                }
                cb.completed(true,status().toString());
            });return;
        }
        DISK.execute(()->{
            try{
                JSONObject data;
                if("video_editor_status".equals(action))data=status();
                else if("video_editor_project_read".equals(action))data=load(application);
                else if("video_editor_offline_audit".equals(action))
                    data=OfflineStudioAudit.analyze(application,load(application));
                else if("video_editor_project_list".equals(action))
                    data=StudioProjectLibrary.list(application,args.optBoolean("trash",false));
                else if("video_editor_project_save".equals(action)){
                    if("running".equals(state)||"preparing".equals(state))throw new IllegalStateException("Export actif");
                    JSONObject project=args.getJSONObject("project");
                    JSONObject current=load(application);
                    if(args.has("expected_revision")&&args.getInt("expected_revision")!=current.optInt("revision",0))throw new IllegalStateException("Le projet a changé ailleurs. Rouvre Studio avant de modifier.");
                    validate(application,project);project.put("revision",current.optInt("revision",0)+1);
                    persist(application,project);
                    data=new JSONObject().put("saved",true).put("project",project);
                }else if("video_editor_project_new".equals(action)){
                    blockProjectSwitch();
                    JSONObject fresh=StudioProjectLibrary.blank();
                    validate(application,fresh);
                    data=new JSONObject().put("project",StudioProjectLibrary.create(application,fresh));
                }else if("video_editor_project_open".equals(action)){
                    blockProjectSwitch();
                    data=new JSONObject().put("project",StudioProjectLibrary.open(application,args.getString("id")));
                }else if("video_editor_project_duplicate".equals(action)){
                    blockProjectSwitch();
                    data=new JSONObject().put("project",StudioProjectLibrary.duplicate(application,args.getString("id")));
                }else if("video_editor_project_rename".equals(action)){
                    blockProjectSwitch();
                    data=new JSONObject().put("project",StudioProjectLibrary.rename(application,args.getString("id"),args.getString("name")));
                }else if("video_editor_project_trash".equals(action)){
                    blockProjectSwitch();
                    StudioProjectLibrary.trash(application,args.getString("id"));
                    data=new JSONObject().put("trashed",true);
                }else if("video_editor_project_restore".equals(action)){
                    blockProjectSwitch();
                    StudioProjectLibrary.restore(application,args.getString("id"));
                    data=new JSONObject().put("restored",true);
                }else if("video_editor_preset_alpha_omega".equals(action)){
                    if("running".equals(state)||"preparing".equals(state))throw new IllegalStateException("Export actif");
                    JSONObject project=alphaOmega(application);
                    project=StudioProjectLibrary.create(application,project);
                    data=new JSONObject().put("saved",true).put("project",project);
                }else throw new IllegalArgumentException("Commande vidéo inconnue");
                cb.completed(true,data.toString());
            }catch(Exception ex){cb.completed(false,ex.getMessage());}
        });
    }
    private void blockProjectSwitch(){
        if("running".equals(state)||"preparing".equals(state))
            throw new IllegalStateException("Export en cours : attends la fin avant de changer de projet");
    }
        public JSONObject status(){
        JSONObject o=new JSONObject();
        try{o.put("state",state).put("progress_percent",percent)
           .put("error",error).put("output",lastOutput)
           .put("background_protected",VideoExportService.active()||(app!=null&&AgentClient.get(app).isEnabled())).put("editing_local",true).put("engine","androidx.media3.transformer");
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
        File record=new File(context.getFilesDir(),"video_last_export.json");
        JSONObject project=record.isFile()?readProject(record):load(context);
        return verifyProjectOutput(context,project);
    }
    JSONObject verifyProjectOutput(Context context,JSONObject project)throws Exception{
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
            expectedMs+=Math.round(item.optLong("duration_ms",10000)/item.optDouble("speed",1));
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
            .put("sources_present",sourcesPresent).put("verification_scope","technical_metadata_and_sampled_frames").put("lip_sync_verified",false).put("audio_levels_verified",false).put("valid",false);
        if(!file.isFile()||file.length()<1000)
            return result.put("reason","Fichier MP4 absent ou incomplet");
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try{
            retriever.setDataSource(file.getAbsolutePath());
            String duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String hasVideo=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            String hasAudio=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            String width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rotationValue=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rotation=rotationValue==null?0:Integer.parseInt(rotationValue);
            boolean rotated=Math.abs(rotation)%180==90;
            String displayWidth=rotated?height:width,displayHeight=rotated?width:height;
            long durationMs=duration==null?-1:Long.parseLong(duration);
            boolean picture="yes".equalsIgnoreCase(hasVideo),sound="yes".equalsIgnoreCase(hasAudio);
            boolean correctDuration=expectedMs>0&&durationMs>=0&&Math.abs(durationMs-expectedMs)<=500;
            float ratio=ratio(project.optString("aspect_ratio","source"));
            boolean correctAspect=ratio==0 || (displayWidth!=null&&displayHeight!=null&&Math.abs(Float.parseFloat(displayWidth)/Float.parseFloat(displayHeight)-ratio)<0.015f);
            JSONObject frames=inspectRenderedClips(context,file,project);
            boolean noBlackCuts=frames.getJSONArray("black_video_segments").length()==0;
            // A rotated 1920x1080 stream is visually portrait but must not be
            // reported as a native 1080x1920 export.
            boolean requireNativePortrait=ratio>0f&&ratio<1f&&project.optBoolean("native_portrait",true);
            boolean nativePortraitMatches=!requireNativePortrait||(width!=null&&height!=null
                &&Integer.parseInt(width)<Integer.parseInt(height)&&Math.abs(rotation)%180==0);
            boolean valid=picture&&(!(audioCount>0)||sound)&&correctDuration&&sourcesPresent&&correctAspect&&nativePortraitMatches&&noBlackCuts&&frames.getInt("unverified_video_segments")==0;
            result.put("duration_ms",durationMs).put("has_video",picture)
                .put("has_audio",sound).put("width_px",displayWidth).put("height_px",displayHeight)
                .put("encoded_width_px",width).put("encoded_height_px",height).put("rotation_degrees",rotation)
                .put("duration_matches_project",correctDuration)
                .put("aspect_ratio_matches_project",correctAspect)
                .put("native_portrait_matches_project",nativePortraitMatches)
                .put("verified_video_segments",frames.getInt("verified_video_segments"))
                .put("unverified_video_segments",frames.getInt("unverified_video_segments"))
                .put("black_video_segments",frames.getJSONArray("black_video_segments"))
                .put("valid",valid);
            if(!valid)result.put("reason","Contrôler la durée, les pistes et les sources du projet");
            return result;
        }catch(Exception ex){
            return result.put("reason","Métadonnées MP4 illisibles : "+ex.getClass().getSimpleName());
        }finally{retriever.release();}
    }
    /** Inspect actual decoded MP4 frames in the middle of every clip.
     * Metadata-only validation previously approved exports with 50 s of black.
     * Seek exact clip midpoints, not just nearest keyframes (which coincide with fades).\n     * Compare against the corresponding source so an intentionally dark scene
     * is not automatically rejected. This protects the final existing MP4. */
    private JSONObject inspectRenderedClips(Context context,File render,JSONObject project)throws Exception{
        JSONArray clips=project.optJSONArray("clips");
        JSONArray black=new JSONArray(),samples=new JSONArray();
        int checked=0,unverifiable=0;
        if(clips==null||clips.length()==0)return new JSONObject()
            .put("verified_video_segments",0).put("unverified_video_segments",0)
            .put("black_video_segments",black).put("frame_samples",samples);
        MediaMetadataRetriever output=new MediaMetadataRetriever();
        long timelineMs=0;
        try{
            output.setDataSource(render.getAbsolutePath());
            WorkspaceStore store=new WorkspaceStore(context);
            for(int i=0;i<clips.length();i++){
                JSONObject clip=clips.getJSONObject(i);
                long duration=clip.optLong("duration_ms",10000);
                double speed=clip.optDouble("speed",1);
                long realDuration=Math.round(duration/speed);
                long at=timelineMs+realDuration/2;
                timelineMs+=realDuration;
                MediaMetadataRetriever source=new MediaMetadataRetriever();
                android.graphics.Bitmap rendered=null,original=null;
                try{
                    File input=store.file(clip.getString("path"));
                    boolean image=StudioImages.isImage(input);
                    if(!image)source.setDataSource(input.getAbsolutePath());
                    long srcAt=(clip.optLong("start_ms",0)+duration/2)*1000;
                    if(android.os.Build.VERSION.SDK_INT>=27){
                        rendered=output.getScaledFrameAtTime(at*1000,
                            MediaMetadataRetriever.OPTION_CLOSEST,64,64);
                        original=image?StudioImages.thumbnail(input,64):source.getScaledFrameAtTime(srcAt,
                            MediaMetadataRetriever.OPTION_CLOSEST,64,64);
                    }else{
                        rendered=output.getFrameAtTime(at*1000,MediaMetadataRetriever.OPTION_CLOSEST);
                        original=image?StudioImages.thumbnail(input,64):source.getFrameAtTime(srcAt,MediaMetadataRetriever.OPTION_CLOSEST);
                    }
                    if(rendered==null||original==null){unverifiable++;continue;}
                    checked++;
                    float sourceBrightness=sampleBrightness(original);
                    float renderedBrightness=sampleBrightness(rendered);
                    samples.put(new JSONObject().put("cut",i+1)
                        .put("source_luma",Math.round(sourceBrightness))
                        .put("export_luma",Math.round(renderedBrightness)));
                    if(sourceBrightness>24f&&renderedBrightness<4f)
                        black.put(i+1);
                }catch(Exception ex){
                    android.util.Log.w("ChkVideoExport","Image segment "+(i+1)+" non vérifiable",ex);
                    unverifiable++;
                }finally{
                    if(rendered!=null)rendered.recycle();
                    if(original!=null)original.recycle();
                    source.release();
                }
            }
        }finally{output.release();}
        return new JSONObject().put("verified_video_segments",checked)
            .put("unverified_video_segments",unverifiable)
            .put("black_video_segments",black).put("frame_samples",samples);
    }
    private static float sampleBrightness(android.graphics.Bitmap picture){
        int w=picture.getWidth(),h=picture.getHeight();
        long sum=0,count=0;
        for(int y=0;y<h;y+=Math.max(1,h/16))
            for(int x=0;x<w;x+=Math.max(1,w/16)){
                int c=picture.getPixel(x,y);
                sum+=android.graphics.Color.red(c)+android.graphics.Color.green(c)
                    +android.graphics.Color.blue(c);
                count++;
            }
        return count==0?0f:sum/(3f*count);
    }
    public JSONObject load(Context context)throws Exception{
        return StudioProjectLibrary.active(context);
    }
    private JSONObject readProject(File f)throws Exception{
        if(f.length()>200000)throw new IllegalStateException("Projet trop grand");
        byte[] bytes=new byte[(int)f.length()];
        try(FileInputStream in=new FileInputStream(f)){
            int read=0,n;while(read<bytes.length&&(n=in.read(bytes,read,bytes.length-read))>0)read+=n;
            if(read!=bytes.length)throw new IllegalStateException("Projet incomplet");
        }
        return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
    }
    private void persist(Context app,JSONObject project)throws Exception{
        StudioProjectLibrary.save(app,project);
    }
    private void persistNamed(Context app,String name,JSONObject project)throws Exception{
        File output=new File(app.getFilesDir(),name),tmp=new File(app.getFilesDir(),name+".tmp");
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
        if("video".equals(type)&&StudioImages.isImage(f)){StudioImages.bounds(f);return f;}
        android.media.MediaExtractor extractor=new android.media.MediaExtractor();
        boolean found=false;
        try{extractor.setDataSource(f.getAbsolutePath());for(int i=0;i<extractor.getTrackCount();i++){
            String mime=extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
            if(mime!=null&&mime.startsWith(type+"/"))found=true;
        }}finally{extractor.release();}
        if(!found)throw new IllegalArgumentException("Aucune piste "+type+" lisible : "+path);
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
        ratio(aspect);
        int height=p.optInt("resolution",720);
        if(height!=480&&height!=720&&height!=1080)throw new IllegalArgumentException("Résolution 480, 720 ou 1080 requise");
        if(p.optString("name","").length()>120)throw new IllegalArgumentException("Nom trop long");
        if(!"crop".equals(layout)&&!"fit".equals(layout))
            throw new IllegalArgumentException("Recadrage inconnu: "+layout);
        WorkspaceStore store=new WorkspaceStore(context);
        JSONArray clips=p.getJSONArray("clips"),sounds=p.optJSONArray("audio");
        if(clips.length()>100)throw new IllegalArgumentException("100 plans maximum");
        int fps=p.optInt("fps",30);if(fps!=24&&fps!=30&&fps!=60)throw new IllegalArgumentException("Cadence 24, 30 ou 60 images/s requise");
        long videoDuration=0,audioDuration=0;
        for(int i=0;i<clips.length();i++){
            JSONObject c=clips.getJSONObject(i);
            File f=requireSource(store,c.getString("path"),"video");
            long clipStart=c.optLong("start_ms",0),duration=c.optLong("duration_ms",10000);
            if(clipStart<0||duration<500||duration>1200000)throw new IllegalArgumentException("Découpe vidéo invalide");
            if(StudioImages.isImage(f)){if(clipStart!=0||duration<1000||duration>60000)throw new IllegalArgumentException("Une photo dure de 1 à 60 secondes, sans découpe de départ");}
            else checkMediaDuration(f,clipStart+duration);
            String filter=c.optString("filter","aucun");
            if(!filter.equals("aucun")&&!filter.equals("noir")&&!filter.equals("vintage")
                    &&!filter.equals("chaud")&&!filter.equals("froid")
                    &&!filter.equals("cinema")&&!filter.equals("contraste")
                    &&!filter.equals("nuit")&&!filter.equals("vibrant")
                    &&!filter.equals("doux")&&!filter.equals("desature")
                    &&!filter.equals("sepia"))throw new IllegalArgumentException("Filtre inconnu: "+filter);
            long fade=c.optLong("fade_ms",0);
            if(fade<0||fade>Math.min(1500,duration/3))throw new IllegalArgumentException("Effet de fondu invalide");
            validateVolume(c);
            if(c.has("crop"))StudioCrop.validate(c.getJSONObject("crop"));
            double speed=c.optDouble("speed",1);
            if((Double.isNaN(speed)||Double.isInfinite(speed))||speed<0.25||speed>4)throw new IllegalArgumentException("Vitesse entre 0,25 et 4 requise");
            double rotation=c.optDouble("rotation",0);
            if((Double.isNaN(rotation)||Double.isInfinite(rotation))||rotation<-180||rotation>180)throw new IllegalArgumentException("Rotation entre -180 et 180 requise");
            if(c.optString("text","").length()>500)throw new IllegalArgumentException("Texte limité à 500 caractères");
            for(String key:new String[]{"grade_exposure","grade_exposure_end","grade_warmth","grade_warmth_end",
                     "grade_saturation","grade_saturation_end"}){
                if(!c.has(key))continue;
                double x=c.optDouble(key,0);
                double max=key.contains("saturation")?2:0.5, min=key.contains("saturation")?0:-0.5;
                if(Double.isNaN(x)||Double.isInfinite(x)||x<min||x>max)
                    throw new IllegalArgumentException("Étalonnage invalide : "+key);
            }
            for(String key:new String[]{"zoom","zoom_end","pan_x","pan_x_end","pan_y","pan_y_end"})if(c.has(key)){
                double v=c.optDouble(key,Double.NaN);double min=key.startsWith("zoom")?1:-1,max=key.startsWith("zoom")?4:1;
                if(Double.isNaN(v)||Double.isInfinite(v)||v<min||v>max)throw new IllegalArgumentException("Animation invalide : "+key);
            }
            videoDuration+=Math.round(duration/speed);
        }
        if(sounds!=null){
            if(sounds.length()>40)throw new IllegalArgumentException("Trop de pistes audio");
            for(int i=0;i<sounds.length();i++){
                JSONObject a=sounds.getJSONObject(i);validateVolume(a);
                File f=requireSource(store,a.getString("path"),"audio");
                long start=a.optLong("start_ms",0),duration=a.optLong("duration_ms",10000);
                if(start<0||duration<500||duration>1200000)throw new IllegalArgumentException("Découpe audio invalide");
                checkMediaDuration(f,start+duration);
                audioDuration+=duration;
            }
            if(sounds.length()>0&&audioDuration>videoDuration+1000)throw new IllegalArgumentException("Audio plus long que la vidéo : raccourcis la piste");
        }
        StudioLayers.validate(context,p,videoDuration);
        StudioSubtitles.validate(p,videoDuration);
        if(videoDuration>20*60*1000)throw new IllegalArgumentException("Montage limité à 20 minutes");
        String output=p.optString("output","");
        if(!output.toLowerCase(Locale.ROOT).endsWith(".mp4")||output.length()>400)throw new IllegalArgumentException("Destination MP4 obligatoire");
        File dest=store.file(output);
        if(dest.isDirectory()||!dest.getParentFile().isDirectory())
            throw new IllegalArgumentException("Dossier de sortie inexistant");
        for(int i=0;i<clips.length();i++)if(output.equals(clips.getJSONObject(i).getString("path")))
            throw new IllegalArgumentException("Sortie identique à une vidéo source");
        if(sounds!=null)for(int i=0;i<sounds.length();i++)if(output.equals(sounds.getJSONObject(i).getString("path")))
            throw new IllegalArgumentException("Sortie identique à une source audio");
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
    private static MediaItem media(File file,long start,long duration)throws Exception{
        if(StudioImages.isImage(file))return StudioImages.media(file,duration);
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
          case "vibrant":effects.add(new HslAdjustment.Builder().adjustSaturation(22f).build());break;
          case "doux":effects.add(new HslAdjustment.Builder().adjustLightness(6f).adjustSaturation(-8f).build());break;
          case "desature":effects.add(new HslAdjustment.Builder().adjustSaturation(-42f).build());break;
          case "sepia":effects.add((RgbMatrix)(timeUs,useHdr)->
              new float[]{.393f,.349f,.272f,0,.769f,.686f,.534f,0,.189f,.168f,.131f,0,0,0,0,1});break;
          default:break;
        }
        if(fade>0){
            // Media3 may pass composition-wide timestamps to per-item effects.
            // NEVER compare the global PTS with a single clip's duration: after
            // the first clip, (length - timeUs/1000) becomes negative and turns
            // all remaining video permanently black while audio continues.
            final java.util.concurrent.atomic.AtomicLong firstFrameUs =
                new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
            effects.add((RgbMatrix)(timeUs,useHdr)->{
                firstFrameUs.compareAndSet(Long.MIN_VALUE,timeUs);
                long localMs=(timeUs-firstFrameUs.get())/1000;
                float gain=clipFadeGain(localMs,length,fade);
                return new float[]{gain,0,0,0, 0,gain,0,0, 0,0,gain,0, 0,0,0,1};
            });
        }
        return effects;
    }
    /** Fade is anchored to THIS clip's first video frame, not the full timeline.
     *  Out-of-range timestamps remain fully visible as a safeguard against
     *  different time bases during preview, seeking and composition export. */
    static float clipFadeGain(long localMs,long durationMs,long fadeMs){
        if(fadeMs<=0||durationMs<=0||localMs<0||localMs>durationMs)return 1f;
        if(localMs<fadeMs)return Math.max(0f,(float)localMs/fadeMs);
        if(localMs>durationMs-fadeMs)return Math.max(0f,(float)(durationMs-localMs)/fadeMs);
        return 1f;
    }
    /** AndroidX Media3 open-source GPU filters, shared by live preview and MP4 export.
     * Fades are export-only and handled on each clip timeline. */
    public static List<androidx.media3.common.Effect> previewEffects(String name){
        return effects(name,1000,0);
    }
    /**
     * Animated on-device 3x3 colour grading. 0 is neutral exposure/temperature;
     * saturation 1 is neutral. Time is anchored to each source clip's first frame
     * because Media3 can expose composition-global presentation timestamps.
     * No extra decoder, GPU shader download or API is used.
     */
    private static RgbMatrix gradedColor(JSONObject c,long durationMs){
        final float exposure0=(float)c.optDouble("grade_exposure",0);
        final float exposure1=(float)c.optDouble("grade_exposure_end",exposure0);
        final float warmth0=(float)c.optDouble("grade_warmth",0);
        final float warmth1=(float)c.optDouble("grade_warmth_end",warmth0);
        final float saturation0=(float)c.optDouble("grade_saturation",1);
        final float saturation1=(float)c.optDouble("grade_saturation_end",saturation0);
        final java.util.concurrent.atomic.AtomicLong first=new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
        return (timeUs,useHdr)->{
            first.compareAndSet(Long.MIN_VALUE,timeUs);
            float t=Math.max(0f,Math.min(1f,(timeUs-first.get())/(durationMs*1000f)));
            float e=exposure0+(exposure1-exposure0)*t;
            float warmth=warmth0+(warmth1-warmth0)*t;
            float saturation=saturation0+(saturation1-saturation0)*t;
            float gain=(float)Math.pow(2,e), gray=1f-saturation;
            float r=gain*(1f+warmth*.3f),g=gain,b=gain*(1f-warmth*.3f);
            return new float[]{
                r*(saturation+gray*.2126f),r*gray*.7152f,r*gray*.0722f,0f,
                g*gray*.2126f,g*(saturation+gray*.7152f),g*gray*.0722f,0f,
                b*gray*.2126f,b*gray*.7152f,b*(saturation+gray*.0722f),0f,
                0f,0f,0f,1f
            };
        };
    }

    /** Ratios are independent from container and codec support. */
    public static float ratio(String value){
        if("source".equals(value))return 0;
        String[] parts=value.split(":");
        try{if(parts.length!=2)throw new Exception();float w=Float.parseFloat(parts[0]),h=Float.parseFloat(parts[1]);
            float r=w/h;if((Float.isNaN(r)||Float.isInfinite(r))||w<=0||h<=0||r<0.2f||r>5)throw new Exception();return r;
        }catch(Exception ex){throw new IllegalArgumentException("Ratio largeur:hauteur requis (entre 1:5 et 5:1)");}
    }
    /** Used by export and the live CompositionPlayer so previews include real effects. */
    public Composition composition(Context context,JSONObject project)throws Exception{
        WorkspaceStore store=new WorkspaceStore(context);
        JSONArray clips=project.getJSONArray("clips"),audios=project.optJSONArray("audio");
        List<EditedMediaItem> videos=new ArrayList<>(),sounds=new ArrayList<>();
        boolean layered=StudioLayers.items(project).length()>0;
        float layerAspect=ratio(project.optString("aspect_ratio","source"));
        if(layered&&layerAspect==0&&clips.length()>0){JSONObject info=MediaInspection.info(context,clips.getJSONObject(0).getString("path"));layerAspect=info.optInt("width",1)/(float)Math.max(1,info.optInt("height",1));if((Math.abs(info.optInt("rotation")+(int)clips.getJSONObject(0).optDouble("rotation",0))%180)==90)layerAspect=1/layerAspect;}
        int layerQuality=project.optInt("resolution",720);
        final int layerWidth=layerAspect>=1?Math.max(2,Math.round(layerQuality*layerAspect/2)*2):layerQuality;
        final int layerHeight=layerAspect>=1?layerQuality:Math.max(2,Math.round(layerQuality/Math.max(.2f,layerAspect)/2)*2);
        java.util.Map<String,Long> sourceDurations=new java.util.HashMap<>();
        boolean external=audios!=null&&audios.length()>0;
        boolean original=!project.optBoolean("mute_original",false);
        for(int i=0;i<clips.length();i++){
            JSONObject c=clips.getJSONObject(i);long len=c.optLong("duration_ms",10000);
            List<androidx.media3.common.Effect> fx=effects(c.optString("filter","aucun"),len,c.optLong("fade_ms",0));
            if(c.has("grade_exposure")||c.has("grade_warmth")||c.has("grade_saturation")||
               c.has("grade_exposure_end")||c.has("grade_warmth_end")||c.has("grade_saturation_end"))
                fx.add(gradedColor(c,len));
            if(c.has("zoom")||c.has("zoom_end")||c.has("pan_x")||c.has("pan_x_end")||c.has("pan_y")||c.has("pan_y_end")){
                final java.util.concurrent.atomic.AtomicLong first=new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
                final float z0=(float)c.optDouble("zoom",1),z1=(float)c.optDouble("zoom_end",z0);
                final float x0=(float)c.optDouble("pan_x",0),x1=(float)c.optDouble("pan_x_end",x0);
                final float y0=(float)c.optDouble("pan_y",0),y1=(float)c.optDouble("pan_y_end",y0);
                fx.add((androidx.media3.effect.MatrixTransformation)(timeUs)->{
                    first.compareAndSet(Long.MIN_VALUE,timeUs);float t=Math.max(0,Math.min(1,(timeUs-first.get())/(len*1000f)));
                    android.graphics.Matrix matrix=new android.graphics.Matrix();float z=z0+(z1-z0)*t;
                    matrix.setScale(z,z);matrix.postTranslate(x0+(x1-x0)*t,y0+(y1-y0)*t);return matrix;
                });
            }
            if(c.has("crop"))fx.add(StudioCrop.effect(c));
            if(c.optDouble("rotation",0)!=0)fx.add(new androidx.media3.effect.ScaleAndRotateTransformation.Builder().setRotationDegrees((float)c.optDouble("rotation")).build());
            String caption=c.optString("text","");
            if(!caption.isEmpty()){
                android.text.SpannableString text=new android.text.SpannableString(caption);
                text.setSpan(new android.text.style.ForegroundColorSpan(android.graphics.Color.WHITE),0,text.length(),0);
                text.setSpan(new android.text.style.AbsoluteSizeSpan(32),0,text.length(),0);
                androidx.media3.effect.StaticOverlaySettings settings=new androidx.media3.effect.StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0,-0.75f).build();
                fx.add(new androidx.media3.effect.OverlayEffect(Collections.singletonList(androidx.media3.effect.TextOverlay.createStaticTextOverlay(text,settings))));
            }
            if(layered)fx.add(Presentation.createForWidthAndHeight(layerWidth,layerHeight,"fit".equals(project.optString("aspect_mode","fit"))?Presentation.LAYOUT_SCALE_TO_FIT:Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
            final float speed=(float)c.optDouble("speed",1);
            EditedMediaItem.Builder video=new EditedMediaItem.Builder(media(store.file(c.getString("path")),c.optLong("start_ms",0),len))
                .setFrameRate(project.optInt("fps",30))
                .setRemoveAudio(StudioImages.isImage(store.file(c.getString("path")))||!original||c.optBoolean("mute",false))
                .setEffects(new Effects(volumeProcessors(c),fx));
            if(!StudioImages.isImage(store.file(c.getString("path"))))video.setDurationUs(sourceDurationUs(store,c,sourceDurations));
            if(speed!=1)video.setSpeed(new androidx.media3.common.audio.SpeedProvider(){public float getSpeed(long timeUs){return speed;}public long getNextSpeedChangeTimeUs(long timeUs){return androidx.media3.common.C.TIME_UNSET;}});
            videos.add(video.build());
        }
        if(external)for(int i=0;i<audios.length();i++){
            JSONObject a=audios.getJSONObject(i);
            sounds.add(new EditedMediaItem.Builder(media(store.file(a.getString("path")),a.optLong("start_ms",0),a.optLong("duration_ms",10000))).setDurationUs(sourceDurationUs(store,a,sourceDurations)).setRemoveVideo(true).setEffects(new Effects(volumeProcessors(a),Collections.emptyList())).build());
        }
        EditedMediaItemSequence sequence=original?EditedMediaItemSequence.withAudioAndVideoFrom(videos):EditedMediaItemSequence.withVideoFrom(videos);
        Composition.Builder builder=sounds.isEmpty()?new Composition.Builder(sequence):new Composition.Builder(sequence,EditedMediaItemSequence.withAudioFrom(sounds));
        if(layered){
            List<EditedMediaItemSequence> sequences=StudioLayers.sequences(context,project,StudioEdits.total(project));sequences.add(sequence);if(!sounds.isEmpty())sequences.add(EditedMediaItemSequence.withAudioFrom(sounds));
            Composition.Builder layeredBuilder=new Composition.Builder(sequences).setVideoCompositorSettings(StudioLayers.compositor(project,layerWidth,layerHeight));
            if(StudioSubtitles.items(project).length()>0)layeredBuilder.setEffects(new Effects(Collections.emptyList(),Collections.singletonList(StudioSubtitles.effect(project))));
            return layeredBuilder.build();
        }
        float aspect=ratio(project.optString("aspect_ratio","source"));
        if(aspect==0){
            JSONObject first=clips.getJSONObject(0);JSONObject metadata=MediaInspection.info(context,first.getString("path"));
            int width=metadata.optInt("width",0),height=metadata.optInt("height",0);
            if(width>0&&height>0){aspect=(float)width/height;int rotation=metadata.optInt("rotation",0)+(int)first.optDouble("rotation",0);if(Math.abs(rotation)%180==90)aspect=1f/aspect;}
        }
        List<androidx.media3.common.Effect> compositionEffects=new ArrayList<>();
        if(aspect>0){
            int quality=project.optInt("resolution",720);
            int width=aspect>=1?Math.max(2,Math.round(quality*aspect/2)*2):quality;
            int height=aspect>=1?quality:Math.max(2,Math.round(quality/aspect/2)*2);
            int layout="fit".equals(project.optString("aspect_mode","fit"))?Presentation.LAYOUT_SCALE_TO_FIT:Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP;
            compositionEffects.add(Presentation.createForWidthAndHeight(width,height,layout));
        }
        if(StudioSubtitles.items(project).length()>0)compositionEffects.add(StudioSubtitles.effect(project));
        if(!compositionEffects.isEmpty())builder.setEffects(new Effects(Collections.emptyList(),compositionEffects));
        return builder.build();
    }
    private static void validateVolume(JSONObject clip){double v=clip.optDouble("volume",1);if(Double.isNaN(v)||Double.isInfinite(v)||v<0||v>2)throw new IllegalArgumentException("Volume entre 0 et 2 requis");}
    static List<androidx.media3.common.audio.AudioProcessor> volumeProcessors(JSONObject clip){
        float volume=(float)clip.optDouble("volume",1);if(volume==1)return Collections.emptyList();
        androidx.media3.common.audio.ChannelMixingAudioProcessor processor=new androidx.media3.common.audio.ChannelMixingAudioProcessor();
        for(int channels=1;channels<=8;channels++){
            float[] values=new float[channels*channels];for(int i=0;i<channels;i++)values[i*channels+i]=volume;
            processor.putChannelMixingMatrix(new androidx.media3.common.audio.ChannelMixingMatrix(channels,channels,values));
        }
        return Collections.singletonList(processor);
    }
    private long sourceDurationUs(WorkspaceStore store,JSONObject clip,java.util.Map<String,Long> cache)throws Exception{
        String path=clip.getString("path");Long value=cache.get(path);if(value==null){MediaMetadataRetriever retriever=new MediaMetadataRetriever();try{retriever.setDataSource(store.file(path).getAbsolutePath());String duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);value=duration==null?0:Long.parseLong(duration);}finally{retriever.release();}cache.put(path,value);}return Math.max(value,clip.optLong("start_ms")+clip.optLong("duration_ms",10000))*1000;
    }
    private void startExport(Context context,JSONObject project,Composition composition,boolean replace,long ticket,Done callback){
        try{
            WorkspaceStore store=new WorkspaceStore(context);String output=project.getString("output");
            File destination=store.file(output);
            if(destination.exists()&&!replace)throw new IllegalArgumentException("Une vidéo porte déjà ce nom. Choisis Remplacer ou Nouvelle copie dans Studio.");
            // Render beside the destination and preserve the previous completed file on failure.
            File partial=new File(destination.getParentFile(),".render-"+java.util.UUID.randomUUID()+".mp4");
            app=context;activeOutput=partial;finalOutput=destination;lastOutput=output;error="";percent=0;state="running";
            // Encode portrait frames physically upright (1080x1920), not landscape
            // H.264 with a 90-degree display matrix. The previous default confused
            // clients that inspect raw track dimensions instead of rotation tags.
            final float requestedAspect=ratio(project.optString("aspect_ratio","source"));
            final boolean nativePortrait=requestedAspect>0f&&requestedAspect<1f
                &&project.optBoolean("native_portrait",true);
            transformer=new Transformer.Builder(context).setPortraitEncodingEnabled(nativePortrait).setMuxerFactory(new androidx.media3.transformer.InAppMp4Muxer.Factory()).setMaxDelayBetweenMuxerSamplesMs(60000).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
               .addListener(new Transformer.Listener(){
                 @Override public void onCompleted(Composition c,ExportResult result){
                    if(ticket!=exportGeneration||!"running".equals(state))return;
                    transformer=null;percent=99;
                    WorkspaceCommands.IO.execute(()->{
                        if(ticket!=exportGeneration||!"running".equals(state))return;
                        try{
                            if(!partial.isFile()||partial.length()<1000)
                                throw new IllegalStateException("Rendu MP4 absent ou incomplet");
                            JSONObject renderedInfo=MediaInspection.infoFile(partial);
                            if(nativePortrait){
                                int encodedWidth=renderedInfo.optInt("width",-1);
                                int encodedHeight=renderedInfo.optInt("height",-1);
                                int rotationDegrees=0;
                                try{rotationDegrees=Integer.parseInt(renderedInfo.optString("rotation","0"));}
                                catch(NumberFormatException ignored){}
                                if(encodedWidth<1||encodedHeight<=encodedWidth||Math.abs(rotationDegrees)%180!=0)
                                    throw new IllegalStateException("Export 9:16 non natif : "
                                        +encodedWidth+"x"+encodedHeight+", rotation "+rotationDegrees
                                        +". Ancien MP4 conservé; vérifier le codec portrait Android.");
                            }
                            long expected=0;JSONArray cuts=project.getJSONArray("clips");for(int k=0;k<cuts.length();k++){JSONObject cut=cuts.getJSONObject(k);expected+=Math.round(cut.optLong("duration_ms",10000)/cut.optDouble("speed",1));}
                            if(Math.abs(renderedInfo.optLong("duration_ms",-1)-expected)>500)throw new IllegalStateException("Durée MP4 incorrecte");
                            if(project.optJSONArray("audio")!=null&&project.getJSONArray("audio").length()>0&&!renderedInfo.optBoolean("has_audio"))throw new IllegalStateException("Piste audio absente du MP4");
                            JSONObject visual=inspectRenderedClips(context,partial,project);
                            if(visual.optInt("unverified_video_segments")>0)throw new IllegalStateException("Images MP4 non vérifiables");
                            if(visual.getJSONArray("black_video_segments").length()>0)
                                throw new IllegalStateException("Export rejeté : images noires dans les cuts "
                                    +visual.getJSONArray("black_video_segments")
                                    +" (luminosité "+visual.getJSONArray("frame_samples")
                                    +"). L'ancien clip est conservé.");
                            synchronized(exportLock){
                                if(ticket!=exportGeneration||!"running".equals(state)){partial.delete();return;}
                                if((!replace&&destination.exists())||!partial.renameTo(destination))
                                    throw new IllegalStateException("Impossible de finaliser le MP4");
                                try{persistNamed(context,"video_last_export.json",project);}
                                catch(Exception ex){error="MP4 créé ; historique du rendu non sauvegardé";}
                                state="completed";percent=100;
                            }
                        }catch(Exception ex){
                            synchronized(exportLock){if(ticket==exportGeneration){state="failed";error=ex.getMessage();}}
                            partial.delete();
                        }
                    });
                 }
                 @Override public void onError(Composition c,ExportResult result,ExportException e){
                    if(ticket!=exportGeneration){partial.delete();return;}
                    android.util.Log.e("ChkVideoExport","Échec du rendu MP4",e);state="failed";error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                    partial.delete();transformer=null;
                 }
               }).build();
            if(!VideoExportService.start(context)&&!AgentClient.get(context).isEnabled())throw new IllegalStateException("Ouvre Studio sur le téléphone pour démarrer un export protégé");
            transformer.start(composition,partial.getAbsolutePath());callback.completed(true,status().toString());
        }catch(Exception e){state="failed";error=e.getMessage();callback.completed(false,error);}
    }
}
