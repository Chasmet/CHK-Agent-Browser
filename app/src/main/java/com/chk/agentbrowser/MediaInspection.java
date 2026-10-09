package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.*;
import android.util.Base64;
import org.json.*;
import java.io.*;

/** Bounded local inspection. Decoders belong to the phone; unknown codecs are reported. */
public final class MediaInspection {
    private MediaInspection(){}
    public static JSONObject info(Context context,String path)throws Exception{
        WorkspaceStore store=new WorkspaceStore(context);File file=store.file(path);
        if(!file.isFile())throw new IOException("Média absent");
        JSONObject result=new JSONObject().put("path",path).put("size_bytes",file.length()).put("mime_type",store.mime(path));
        JSONArray tracks=new JSONArray();MediaExtractor extractor=new MediaExtractor();
        try{extractor.setDataSource(file.getPath());for(int i=0;i<extractor.getTrackCount();i++){
            MediaFormat format=extractor.getTrackFormat(i);String mime=format.getString(MediaFormat.KEY_MIME);
            JSONObject track=new JSONObject().put("index",i).put("mime_type",mime);
            for(String key:new String[]{MediaFormat.KEY_WIDTH,MediaFormat.KEY_HEIGHT,MediaFormat.KEY_SAMPLE_RATE,MediaFormat.KEY_CHANNEL_COUNT})if(format.containsKey(key))track.put(key,format.getInteger(key));
            if(format.containsKey(MediaFormat.KEY_DURATION))track.put("duration_us",format.getLong(MediaFormat.KEY_DURATION));
            track.put("decoder_available",new MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format)!=null);tracks.put(track);
        }}finally{extractor.release();}
        result.put("tracks",tracks);
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try{retriever.setDataSource(file.getPath());String duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if(duration!=null)result.put("duration_ms",Long.parseLong(duration));
            String width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if(width!=null&&height!=null)result.put("width",Integer.parseInt(width)).put("height",Integer.parseInt(height));
            result.put("rotation",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        }catch(RuntimeException ignored){}finally{retriever.release();}
        return result;
    }
    public static JSONObject codecs()throws Exception{
        JSONArray list=new JSONArray();for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()){
            JSONArray types=new JSONArray();for(String type:info.getSupportedTypes())types.put(type);
            list.put(new JSONObject().put("name",info.getName()).put("encoder",info.isEncoder()).put("mime_types",types));
        }return new JSONObject().put("codecs",list).put("android_api",android.os.Build.VERSION.SDK_INT).put("export","MP4 H.264/AAC");
    }
    public static Bitmap thumbnail(File file,long timeMs,int maximum)throws Exception{
        MediaMetadataRetriever r=new MediaMetadataRetriever();Bitmap b=null;
        try{r.setDataSource(file.getPath());
            if(android.os.Build.VERSION.SDK_INT>=27)b=r.getScaledFrameAtTime(timeMs*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,maximum,maximum);
            else b=r.getFrameAtTime(timeMs*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if(b==null)throw new IOException("Image vidéo non disponible");
            float scale=Math.min(1f,(float)maximum/Math.max(b.getWidth(),b.getHeight()));
            if(scale<1){Bitmap small=Bitmap.createScaledBitmap(b,Math.max(1,Math.round(b.getWidth()*scale)),Math.max(1,Math.round(b.getHeight()*scale)),true);if(small!=b)b.recycle();b=small;}
            return b;
        }finally{r.release();}
    }
    public static JSONObject frame(Context c,String path,long timeMs)throws Exception{
        if(!AgentClient.get(c).isPreviewAllowed())throw new IOException("Active le partage d’aperçus dans Réglages");
        if(timeMs<0||timeMs>24L*60*60*1000)throw new IOException("Instant vidéo invalide");
        JSONObject info=info(c,path);if(timeMs>info.optLong("duration_ms",Long.MAX_VALUE))throw new IOException("Instant au-delà du média");
        Bitmap bitmap=thumbnail(new WorkspaceStore(c).file(path),timeMs,640);
        try{ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,80,out);
            if(out.size()>150000){out.reset();bitmap.compress(Bitmap.CompressFormat.JPEG,45,out);}
            if(out.size()>150000)throw new IOException("Image trop grande");
            return new JSONObject().put("path",path).put("requested_time_ms",timeMs).put("frame_selection","nearest_keyframe").put("width",bitmap.getWidth()).put("height",bitmap.getHeight()).put("jpeg_base64",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP));
        }finally{bitmap.recycle();}
    }
}
