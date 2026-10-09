package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.util.LruCache;
import java.io.File;
import java.util.concurrent.*;

/** Bounded shared cache and two decoders, independent of workspace command I/O. */
final class ThumbnailLoader {
    interface Result {boolean current();void ready(Bitmap bitmap);}
    private static final ExecutorService DECODERS=Executors.newFixedThreadPool(2);
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final LruCache<String,Bitmap> CACHE=new LruCache<String,Bitmap>(12*1024*1024){protected int sizeOf(String key,Bitmap b){return b.getAllocationByteCount();}};
    static void load(Context context,String path,long at,boolean video,Result result){
        final Context app=context.getApplicationContext();
        DECODERS.execute(()->{if(!result.current())return;try{
            File file=new WorkspaceStore(app).file(path);String key=path+":"+file.lastModified()+":"+file.length()+":"+at;
            Bitmap b=CACHE.get(key);
            if(b==null){if(video)b=MediaInspection.thumbnail(file,at,240);else{BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),o);o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>360)o.inSampleSize*=2;o.inJustDecodeBounds=false;b=BitmapFactory.decodeFile(file.getPath(),o);}if(b!=null)CACHE.put(key,b);}
            Bitmap ready=b;if(ready!=null)MAIN.post(()->{if(result.current())result.ready(ready);});
        }catch(Exception ignored){}});
    }
}
