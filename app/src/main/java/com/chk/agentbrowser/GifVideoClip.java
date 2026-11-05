package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Convert an animated GIF into a silent, real H.264 MP4 for CHK Studio preview,
 * timeline and export. Android's Media3 image loader only renders frame zero.
 * Derived clips are cached privately: source GIF is never modified.
 * Runs only on Studio's background I/O executor.
 */
final class GifVideoClip {
    private static final Object LOCK=new Object();
    private static final int FPS=15;
    private static final int MAX_EDGE=720;
    private GifVideoClip(){}

    static File ensure(Context context,File gif,long durationMs)throws Exception {
        if(durationMs<500||durationMs>60000)throw new IOException("GIF Studio : durée de 0,5 à 60 secondes.");
        if(!GifPlaybackView.isGif(gif))throw new IOException("Le fichier n'est pas un GIF compatible.");
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
            throw new IOException("La conversion GIF ne peut pas bloquer l'interface.");
        String name=hash(gif.getCanonicalPath()+"|"+gif.length()+"|"+gif.lastModified()+"|"+durationMs);
        File dir=new File(context.getCacheDir(),"gif-video");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cache vidéo GIF indisponible.");
        File output=new File(dir,name+".mp4");
        synchronized(LOCK){
            if(valid(output,durationMs))return output;
            if(output.exists()&&!output.delete())throw new IOException("Ancien cache GIF inaccessible.");
            File tmp=new File(dir,name+".part.mp4");
            if(tmp.exists()&&!tmp.delete())throw new IOException("Cache partiel GIF inaccessible.");
            try {
                transcode(gif,tmp,durationMs);
                if(!valid(tmp,durationMs))throw new IOException("Le MP4 du GIF n'a pas été validé.");
                if(!tmp.renameTo(output))throw new IOException("Impossible de mettre en cache le GIF animé.");
                return output;
            } catch(Exception error) {
                tmp.delete();
                throw error;
            }
        }
    }
    private static boolean valid(File file,long length) {
        if(!file.isFile()||file.length()<1000)return false;
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String video=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            String duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return "yes".equals(video) && duration!=null
                && Long.parseLong(duration)>=Math.max(200,length-250);
        }catch(Exception ignored){return false;}finally{try{retriever.release();}catch(Exception ignored){}}
    }
    private static String hash(String value)throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder();
        for(int i=0;i<12;i++)result.append(String.format(Locale.ROOT,"%02x",bytes[i]&255));
        return result.toString();
    }
    private static void transcode(File gif,File destination,long length)throws Exception {
        // The GIF has been header checked, bounded and opened by the preview decoder.
        android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();
        options.inJustDecodeBounds=true;
        android.graphics.BitmapFactory.decodeFile(gif.getAbsolutePath(),options);
        int srcW=options.outWidth, srcH=options.outHeight;
        if(srcW<1||srcH<1||(long)srcW*srcH>12000000L)
            throw new IOException("Dimensions GIF invalides.");
        float scale=Math.min(1f,(float)MAX_EDGE/Math.max(srcW,srcH));
        int w=Math.max(2,(Math.round(srcW*scale)+1)&~1);
        int h=Math.max(2,(Math.round(srcH*scale)+1)&~1);
        Movie movie=Movie.decodeFile(gif.getAbsolutePath());
        if(movie==null)throw new IOException("GIF impossible à animer.");
        int period=Math.max(100,movie.duration());
        Bitmap frame=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(frame);
        int[] pixels=new int[w*h];
        MediaCodec codec=null;
        MediaMuxer muxer=null;
        boolean codecStarted=false,muxStarted=false;
        try {
            codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,w,h);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE,Math.max(400000,Math.min(2500000,w*h*FPS/5)));
            format.setInteger(MediaFormat.KEY_FRAME_RATE,FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();codecStarted=true;
            muxer=new MediaMuxer(destination.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            EncoderOutput output=new EncoderOutput(codec,muxer);
            int count=Math.max(1,(int)Math.ceil(length*FPS/1000.0));
            for(int i=0;i<count;i++){
                long timeMs=(long)i*1000/FPS;
                movie.setTime((int)(timeMs%period));
                canvas.drawColor(Color.BLACK);
                canvas.save();
                canvas.scale(w/(float)srcW,h/(float)srcH);
                movie.draw(canvas,0,0);
                canvas.restore();
                frame.getPixels(pixels,0,w,0,0,w,h);
                int input=-1;
                for(int tries=0;tries<90&&input<0;tries++){
                    input=codec.dequeueInputBuffer(100000);
                    if(input<0)output.drain(false);
                }
                if(input<0)throw new IOException("Encodeur H.264 bloqué pendant le GIF.");
                Image image=codec.getInputImage(input);
                if(image==null)throw new IOException("Encodeur Android ne prend pas en charge les images YUV flexibles.");
                try {
                    putYuv(pixels,w,h,image);
                    codec.queueInputBuffer(input,0,w*h*3/2,i*1000000L/FPS,0);
                } finally {image.close();}
                output.drain(false);
            }
            int endIndex=-1;
            for(int tries=0;tries<90&&endIndex<0;tries++){
                endIndex=codec.dequeueInputBuffer(100000);
                if(endIndex<0)output.drain(false);
            }
            if(endIndex<0)throw new IOException("Encodeur GIF : fin de flux impossible.");
            codec.queueInputBuffer(endIndex,0,0,Math.max(0,count)*1000000L/FPS,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            output.drain(true);
            muxStarted=output.muxerStarted;
            if(!output.finished||!muxStarted)
                throw new IOException("GIF : flux MP4 incomplet.");
        } finally {
            frame.recycle();
            if(codec!=null){if(codecStarted)try{codec.stop();}catch(Exception ignored){}codec.release();}
            if(muxer!=null){ // MediaMuxer cannot stop an unstarted muxer.
                // stop() is safe only after addTrack/start, tracked via output format.
                try {if(muxStarted || destination.length()>1000)muxer.stop();}catch(Exception ignored){}
                try {muxer.release();}catch(Exception ignored){}
            }
        }
    }
    private static int clamp(int value){return Math.max(0,Math.min(255,value));}
    private static void putYuv(int[] rgba,int w,int h,Image image)throws IOException {
        Image.Plane[] planes=image.getPlanes();
        if(planes.length!=3)throw new IOException("YUV420 non disponible sur cet encodeur.");
        ByteBuffer y=planes[0].getBuffer(),u=planes[1].getBuffer(),v=planes[2].getBuffer();
        int ys=planes[0].getRowStride(),yp=planes[0].getPixelStride();
        int us=planes[1].getRowStride(),up=planes[1].getPixelStride();
        int vs=planes[2].getRowStride(),vp=planes[2].getPixelStride();
        try {
            for(int row=0;row<h;row++)for(int col=0;col<w;col++){
                int argb=rgba[row*w+col],r=(argb>>16)&255,g=(argb>>8)&255,b=argb&255;
                y.put(row*ys+col*yp,(byte)clamp(((66*r+129*g+25*b+128)>>8)+16));
                if((row&1)==0&&(col&1)==0){
                    int rr=0,gg=0,bb=0;
                    for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++){
                        int color=rgba[(row+dy)*w+col+dx];
                        rr+=(color>>16)&255;gg+=(color>>8)&255;bb+=color&255;
                    }
                    rr/=4;gg/=4;bb/=4;
                    int uvRow=row/2,uvCol=col/2;
                    u.put(uvRow*us+uvCol*up,(byte)clamp(((-38*rr-74*gg+112*bb+128)>>8)+128));
                    v.put(uvRow*vs+uvCol*vp,(byte)clamp(((112*rr-94*gg-18*bb+128)>>8)+128));
                }
            }
        }catch(IndexOutOfBoundsException error){
            throw new IOException("Format YUV incompatible : dimensions non accessibles.",error);
        }
    }
    private static final class EncoderOutput {
        final MediaCodec codec;
        final MediaMuxer muxer;
        final MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        int track=-1;
        boolean muxerStarted,finished;
        EncoderOutput(MediaCodec c,MediaMuxer m){codec=c;muxer=m;}
        void drain(boolean untilEos)throws IOException {
            int idle=0;
            while(idle<90){
                int index=codec.dequeueOutputBuffer(info,untilEos?100000:0);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    if(muxerStarted)throw new IOException("Plusieurs formats MP4 reçus.");
                    track=muxer.addTrack(codec.getOutputFormat());
                    muxer.start();muxerStarted=true;continue;
                }
                if(index==MediaCodec.INFO_TRY_AGAIN_LATER){
                    if(!untilEos)return;
                    idle++;continue;
                }
                if(index>=0){
                    ByteBuffer buffer=codec.getOutputBuffer(index);
                    if(buffer!=null && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0 && info.size>0){
                        if(!muxerStarted)throw new IOException("Encodeur MP4 sans piste vidéo.");
                        buffer.position(info.offset);buffer.limit(info.offset+info.size);
                        muxer.writeSampleData(track,buffer,info);
                    }
                    boolean eos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    codec.releaseOutputBuffer(index,false);
                    if(eos){finished=true;return;}
                    idle=0;
                    continue;
                }
                if(index==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)continue;
                idle++;
            }
            if(untilEos)throw new IOException("Temps de finalisation MP4 GIF dépassé.");
        }
    }
}
