package com.chk.agentbrowser;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.media3.common.MediaItem;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import org.json.JSONObject;

/** Still image support plus animated GIF metadata and frame access. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class StudioImages {
    static boolean isImage(File file){
        return file.getName().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp|gif)$");
    }
    static boolean isGif(File file){
        return file!=null && file.getName().toLowerCase(Locale.ROOT).endsWith(".gif");
    }
    static long gifDuration(File file)throws IOException{
        if(!GifPlaybackView.hasGifHeader(file))throw new IOException("GIF invalide");
        android.graphics.Movie movie=android.graphics.Movie.decodeFile(file.getAbsolutePath());
        if(movie==null)throw new IOException("GIF illisible");
        return Math.max(100,movie.duration());
    }
    static BitmapFactory.Options bounds(File file)throws IOException{
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        if(options.outWidth<=0||options.outHeight<=0||options.outWidth>16384||options.outHeight>16384
                ||(long)options.outWidth*options.outHeight>100000000L)throw new IOException("Photographie illisible ou trop grande : "+file.getName());
        return options;
    }
    static JSONObject info(File file)throws Exception{
        BitmapFactory.Options b=bounds(file);
        JSONObject result=new JSONObject().put("width",b.outWidth).put("height",b.outHeight).put("rotation",0)
            .put("mime_type",isGif(file)?"image/gif":b.outMimeType).put("image",true)
            .put("default_duration_ms",5000);
        if(isGif(file))result.put("animated_gif",true).put("loop_duration_ms",gifDuration(file))
            .put("duration_ms",gifDuration(file)).put("gif_animation_preserved_in_preview",true);
        return result;
    }
    static Bitmap thumbnail(File file,int maximum)throws IOException{
        if(isGif(file))return GifPlaybackView.frame(file,0,maximum);
        BitmapFactory.Options b=bounds(file);b.inJustDecodeBounds=false;b.inSampleSize=1;
        while(Math.max(b.outWidth,b.outHeight)/b.inSampleSize>maximum*2)b.inSampleSize*=2;
        Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),b);
        if(bitmap==null)throw new IOException("Photographie indécodable");return bitmap;
    }
    static MediaItem media(File file,long duration)throws IOException{
        BitmapFactory.Options b=bounds(file);
        return new MediaItem.Builder().setUri(android.net.Uri.fromFile(file))
            .setMimeType(b.outMimeType).setImageDurationMs(duration).build();
    }
}
