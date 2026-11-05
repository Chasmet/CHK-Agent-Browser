package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Actual animated GIF playback in CHK's own viewers.
 * Android 9+ uses Android's streaming AnimatedImageDrawable; API 23-27 uses
 * software-rendered Movie with bounded inputs. No WebView, server or network.
 */
final class GifPlaybackView extends ImageView {
    private static final long MAX_GIF_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_SOURCE_PIXELS = 12000000;
    private Animatable animation;
    private int generation;
    private boolean paused;

    GifPlaybackView(Context context) {
        super(context);
        setScaleType(ScaleType.FIT_CENTER);
        setContentDescription("GIF animé - lecture en boucle");
    }
    static boolean hasGifHeader(File file) {
        if (file == null || !file.isFile() || file.length() < 13) return false;
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] head = new byte[6];
            if (stream.read(head) != head.length) return false;
            String signature = new String(head, "US-ASCII");
            return "GIF87a".equals(signature) || "GIF89a".equals(signature);
        } catch (Exception ignored) { return false; }
    }
    static boolean isGif(File file) {
        return file != null && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".gif")
            && hasGifHeader(file);
    }
    private static int[] validate(File file) throws IOException {
        if (!hasGifHeader(file)) throw new IOException("Ce fichier n'est pas un GIF valide.");
        if (file.length() > MAX_GIF_BYTES)
            throw new IOException("GIF trop volumineux pour une lecture sûre sur ce téléphone (64 Mo maximum).");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        int w=o.outWidth, h=o.outHeight;
        if(w<=0||h<=0||w>8192||h>8192||(long)w*h>MAX_SOURCE_PIXELS)
            throw new IOException("Dimensions du GIF incompatibles avec une lecture mobile sûre.");
        return new int[]{w,h};
    }
    static Drawable decode(File file, int maxEdge) throws IOException {
        int[] dim = validate(file);
        if (Build.VERSION.SDK_INT >= 28) {
            final int sample = sample(dim[0],dim[1],maxEdge);
            Drawable drawable = android.graphics.ImageDecoder.decodeDrawable(
                android.graphics.ImageDecoder.createSource(file),
                (decoder, info, source) -> decoder.setTargetSampleSize(sample));
            if (drawable instanceof android.graphics.drawable.AnimatedImageDrawable) {
                ((android.graphics.drawable.AnimatedImageDrawable)drawable).setRepeatCount(
                    android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE);
            }
            return drawable;
        }
        Movie movie=Movie.decodeFile(file.getAbsolutePath());
        if(movie==null||movie.width()<=0||movie.height()<=0)
            throw new IOException("GIF animé impossible à décoder sur cet appareil.");
        return new LegacyMovieDrawable(movie);
    }
    private static int sample(int w,int h,int maxEdge) {
        int size=Math.max(240,maxEdge);
        int sample=1;
        while(Math.max(w,h)/sample>size && sample<32)sample*=2;
        return sample;
    }
    /** Real frame at a requested time, for Studio thumbnails and MCP media_frame. */
    static Bitmap frame(File file, long timeMs, int maxEdge) throws IOException {
        int[] dim=validate(file);
        Movie movie=Movie.decodeFile(file.getAbsolutePath());
        if(movie==null)throw new IOException("GIF illisible.");
        int factor=sample(dim[0],dim[1],maxEdge);
        int w=Math.max(1,dim[0]/factor),h=Math.max(1,dim[1]/factor);
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas=new Canvas(bitmap);
            canvas.drawColor(Color.TRANSPARENT);
            canvas.scale(w/(float)dim[0],h/(float)dim[1]);
            long duration=Math.max(1,movie.duration());
            movie.setTime((int)(Math.max(0,timeMs)%duration));
            movie.draw(canvas,0,0);
            return bitmap;
        } catch (RuntimeException error) {
            bitmap.recycle();
            throw new IOException("Décodage GIF interrompu.",error);
        }
    }
    /** Asynchronous decode; never decode an animated GIF on the UI thread. */
    void load(File file, int maxEdge, Runnable failure) {
        final int ticket=++generation;
        stop();
        setImageDrawable(null);
        WorkspaceCommands.IO.execute(()->{
            Drawable drawable;
            try{drawable=decode(file,maxEdge);}
            catch(Exception | OutOfMemoryError error){
                post(()->{if(ticket==generation&&failure!=null)failure.run();});
                return;
            }
            post(()->{
                if(ticket!=generation||getWindowToken()==null)return;
                setGifDrawable(drawable);
            });
        });
    }
    void setGifDrawable(Drawable drawable) {
        stop();
        if (Build.VERSION.SDK_INT<28 && drawable instanceof LegacyMovieDrawable)
            setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        else setLayerType(View.LAYER_TYPE_NONE,null);
        setImageDrawable(drawable);
        animation=drawable instanceof Animatable?(Animatable)drawable:null;
        if(!paused && getWindowToken()!=null)start();
    }
    void start() {
        paused=false;
        if(animation!=null && getWindowToken()!=null && getWindowVisibility()==View.VISIBLE)
            animation.start();
    }
    void stop() {if(animation!=null)animation.stop();}
    void pause() {paused=true;stop();}
    void clear() {generation++;pause();animation=null;setImageDrawable(null);}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(!paused)start();}
    @Override protected void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){
        super.onWindowVisibilityChanged(visibility);
        if(visibility!=View.VISIBLE)stop();else if(!paused)start();
    }

    /** Legacy animated drawable; Movie is deprecated but works on Android 23-27. */
    private static final class LegacyMovieDrawable extends Drawable implements Animatable {
        private final Movie movie;
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private boolean playing;
        private long since;
        private final Runnable tick=()->invalidateSelf();

        LegacyMovieDrawable(Movie movie){this.movie=movie;}
        @Override public void draw(Canvas canvas) {
            if (playing) {
                long now=SystemClock.uptimeMillis();
                movie.setTime((int)((now-since)%Math.max(100,movie.duration())));
                unscheduleSelf(tick);
                scheduleSelf(tick,now+50);
            }
            Rect b=getBounds();
            if(b.isEmpty())return;
            canvas.save();
            float scale=Math.min(b.width()/(float)Math.max(1,movie.width()),
                                 b.height()/(float)Math.max(1,movie.height()));
            canvas.translate(b.left+(b.width()-movie.width()*scale)/2,
                             b.top+(b.height()-movie.height()*scale)/2);
            canvas.scale(scale,scale);
            movie.draw(canvas,0,0,paint);
            canvas.restore();
        }
        @Override public int getIntrinsicWidth(){return movie.width();}
        @Override public int getIntrinsicHeight(){return movie.height();}
        @Override public void start(){if(playing)return;playing=true;since=SystemClock.uptimeMillis();invalidateSelf();}
        @Override public void stop(){playing=false;unscheduleSelf(tick);}
        @Override public boolean isRunning(){return playing;}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(android.graphics.ColorFilter color){paint.setColorFilter(color);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
