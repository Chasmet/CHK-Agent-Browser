package com.chk.agentbrowser;

import android.content.Context;
import android.os.Build;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.FFprobeSession;
import com.arthenica.ffmpegkit.ReturnCode;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Strict local transcoding: no shell, no URLs or playlists, no replacement of sources. */
final class MediaConverter {
    private static final Set<String> INPUTS=new HashSet<>(Arrays.asList(
        "wav","mp3","flac","m4a","aac","ogg","oga","opus","aiff","aif","amr","ac3","eac3","ape","wma","wv","tta",
        "mp4","mov","mkv","webm","avi","3gp","3g2","mpg","mpeg","ts","m2ts","flv","wmv","m4v","mka"));
    private static final Set<String> AUDIO=new HashSet<>(Arrays.asList("mp3","wav","flac","m4a","aac","ogg","opus"));
    private static final Set<String> VIDEO=new HashSet<>(Arrays.asList("mp4","mov","mkv","avi","webm","3gp","gif"));
    private static final String DEMUXERS="wav,mp3,flac,mov,mp4,m4a,3gp,3g2,mj2,ogg,aac,aiff,amr,ac3,eac3,ape,asf,wv,tta,matroska,webm,avi,mpeg,mpegts,flv";
    private MediaConverter(){}
    static boolean isMediaInput(WorkspaceStore s,String path,JSONObject detected)throws IOException{
        String kind=detected.optString("kind"),mime=s.mime(path);
        return INPUTS.contains(FileFormats.extension(path))&&(kind.equals("audio")||kind.equals("media")||mime.startsWith("audio/")||mime.startsWith("video/"));
    }
    static boolean deviceSupported(){
        return Build.VERSION.SDK_INT>=24&&Arrays.asList(Build.SUPPORTED_ABIS).stream().anyMatch(a->a.equals("arm64-v8a")||a.equals("x86_64"));
    }
    static String[] command(File source,File stage,String format,JSONObject options,long limit)throws Exception{
        if(!AUDIO.contains(format)&&!VIDEO.contains(format))throw new IOException("Format média non pris en charge : "+format);
        int bitrate=options.optInt("bitrate_kbps",format.equals("mp3")?320:192);
        int rate=options.optInt("sample_rate",0),channels=options.optInt("channels",0);
        if(bitrate<32||bitrate>320||!Arrays.asList(0,8000,11025,12000,16000,22050,24000,32000,44100,48000).contains(rate)||channels<0||channels>2)
            throw new IOException("Débit 32–320 kb/s, fréquence standard et 0–2 canaux requis");
        List<String> a=new ArrayList<>(Arrays.asList("-hide_banner","-v","error","-nostdin","-n","-threads","2","-filter_threads","1",
            "-protocol_whitelist","file","-format_whitelist",DEMUXERS,"-i",source.getAbsolutePath(),"-map_metadata","0","-map_chapters","-1"));
        if(AUDIO.contains(format)){
            Collections.addAll(a,"-map","0:a:0","-vn","-sn","-dn");
            switch(format){
                case "mp3": Collections.addAll(a,"-c:a","libmp3lame","-b:a",bitrate+"k","-ac",String.valueOf(channels==0?2:channels));break;
                case "wav": Collections.addAll(a,"-c:a","pcm_s24le");break;
                case "flac": Collections.addAll(a,"-c:a","flac","-compression_level","5");break;
                case "aac": case "m4a": Collections.addAll(a,"-c:a","aac","-b:a",bitrate+"k");break;
                case "ogg": Collections.addAll(a,"-c:a","libvorbis","-b:a",bitrate+"k");break;
                case "opus": Collections.addAll(a,"-c:a","libopus","-b:a",bitrate+"k");break;
            }
            if(rate>0)Collections.addAll(a,"-ar",String.valueOf(rate));
            if(channels>0&&!format.equals("mp3"))Collections.addAll(a,"-ac",String.valueOf(channels));
        }else{
            Collections.addAll(a,"-map","0:v:0","-sn","-dn");
            int dimension=options.optInt("max_dimension",1920);
            if(dimension<16||dimension>4096)throw new IOException("Dimension vidéo : 16 à 4096");
            String scale="scale='min("+dimension+",iw)':'min("+dimension+",ih)':force_original_aspect_ratio=decrease:force_divisible_by=2";
            if(format.equals("gif")){
                Collections.addAll(a,"-an","-vf","fps=12,scale='min(720,iw)':-2","-loop","0","-c:v","gif");
            }else{
                Collections.addAll(a,"-map","0:a:0?","-vf",scale,"-pix_fmt","yuv420p");
                if(format.equals("webm"))Collections.addAll(a,"-c:v","libvpx","-b:v","2M","-deadline","good","-cpu-used","4","-c:a","libopus","-b:a","128k");
                else{
                    // Built-in MPEG-4 avoids depending on GPL encoders or vendor-specific H.264 MediaCodec.
                    Collections.addAll(a,"-c:v","mpeg4","-q:v","4","-c:a",format.equals("avi")?"libmp3lame":"aac","-b:a","192k");
                    if(format.equals("mp4")||format.equals("mov"))Collections.addAll(a,"-movflags","+faststart");
                }
            }
        }
        Collections.addAll(a,"-threads","2","-fs",Long.toString(limit),stage.getAbsolutePath());
        return a.toArray(new String[0]);
    }
    private static JSONObject probe(File file)throws Exception{
        FFprobeSession session=FFprobeKit.executeWithArguments(new String[]{"-v","error","-protocol_whitelist","file","-format_whitelist",DEMUXERS+",gif",
            "-probesize","5000000","-analyzeduration","5000000","-show_entries","stream=codec_type,codec_name:format=duration","-of","json",file.getAbsolutePath()});
        if(!ReturnCode.isSuccess(session.getReturnCode()))throw new IOException("Média illisible ou codec absent");
        return new JSONObject(session.getOutput());
    }
    private static boolean stream(JSONObject probe,String type){
        JSONArray streams=probe.optJSONArray("streams");if(streams==null)return false;
        for(int i=0;i<streams.length();i++){JSONObject x=streams.optJSONObject(i);if(x!=null&&type.equals(x.optString("codec_type")))return true;}
        return false;
    }
    private static double duration(JSONObject probe){
        JSONObject f=probe.optJSONObject("format");return f==null?0:f.optDouble("duration",0);
    }
    static JSONObject convert(Context app,WorkspaceStore store,String path,String destination,String format,JSONObject options,AgentClient.ResultCallback active)throws Exception{
        if(!deviceSupported())throw new IOException("Encodeur média disponible sur Android 7+ 64 bits (ARM64/x86_64)");
        File source=store.file(path),target=store.file(destination);
        if(!source.isFile()||source.length()==0||target.exists()||!target.getParentFile().isDirectory()||!FileFormats.extension(destination).equals(format))
            throw new IOException("Source lisible et nouvelle destination avec extension correcte requises");
        TransferLimits.checkSize(source.length());
        long limit=Math.min(TransferLimits.MAX_FILE_BYTES,target.getParentFile().getUsableSpace()-TransferLimits.FREE_RESERVE_BYTES);
        if(limit<1024*1024)throw new IOException("Espace disque insuffisant");
        File stage=new File(target.getParentFile(),".convert-"+UUID.randomUUID()+"."+format);
        String[] args=command(source,stage,format,options,limit);
        FFmpegSession session=null;
        try{
            if(!active.isActive())throw new IOException("Conversion annulée");
            JSONObject before=probe(source);String type=AUDIO.contains(format)?"audio":"video";
            if(!stream(before,type))throw new IOException("La source ne contient pas de piste "+type);
            CountDownLatch done=new CountDownLatch(1);
            session=FFmpegKit.executeWithArgumentsAsync(args,s->done.countDown());
            long deadline=android.os.SystemClock.elapsedRealtime()+840000L;
            while(!done.await(250,TimeUnit.MILLISECONDS)){
                if(!active.isActive()||android.os.SystemClock.elapsedRealtime()>deadline){
                    FFmpegKit.cancel(session.getSessionId());done.await(10,TimeUnit.SECONDS);
                    throw new IOException("Conversion arrêtée ou délai dépassé");
                }
            }
            if(!ReturnCode.isSuccess(session.getReturnCode())||!stage.isFile()||stage.length()==0)throw new IOException("Échec de l’encodage : "+format+" ou codec indisponible");
            TransferLimits.checkSize(stage.length());
            JSONObject after=probe(stage);
            if(!stream(after,type))throw new IOException("Sortie sans piste "+type);
            double d1=duration(before),d2=duration(after);
            if(d1<=0||d2<=0||Math.abs(d1-d2)>Math.max(0.75,d1*0.015))throw new IOException("Durée non vérifiable ou conversion incomplète");
            if(!active.isActive())throw new IOException("Conversion annulée");
            try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(stage,"rw")){file.getFD().sync();}
            if(target.exists()||!stage.renameTo(target))throw new IOException("Impossible de publier la copie sans écrasement");
            return WorkspaceTransfer.stat(store,destination,true).put("verified",true).put("duration_seconds",d2).put("format",format).put("streams",after.getJSONArray("streams"));
        }catch(LinkageError e){throw new IOException("Moteur FFmpeg indisponible pour cette architecture",e);}
        catch(InterruptedException e){if(session!=null)FFmpegKit.cancel(session.getSessionId());Thread.currentThread().interrupt();throw new IOException("Conversion interrompue",e);}
        finally{stage.delete();}
    }
}
