package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.util.LruCache;
import org.json.*;
import java.util.*;

/** Fixed playhead; the actual tracks scroll underneath. Trim commits once per gesture. */
final class StudioTimeline extends View {
    interface Listener {
        void seek(long position,int clip);
        default void scrubbing(boolean active){}
        default void move(int from,int to){}
        default void trim(int clip,JSONObject value){}
        default void audio(int index){}
        default void text(int index){}
        default void add(String kind){}
    }
    private final Paint paint=new Paint(3);
    private final RectF rect=new RectF();
    private JSONObject project=new JSONObject();
    private final LruCache<String,Bitmap> thumbnails=new LruCache<>(48);
    private final Set<String> loading=new HashSet<>();
    private final Map<String,Long> sources=new HashMap<>();
    private long total,position,downPosition;
    private int selected,generation,dragFrom,dragTo,trimEdge,selectedAudio=-1;
    private float scale=1,downX,downY,lastX;
    private boolean moving,dragging,pinching;
    private JSONObject pendingTrim;
    private Listener listener;
    private final ScaleGestureDetector zoom;
    private final GestureDetector gestures;
    private final Runnable startDrag=()->{
        if(moving||pinching||trimEdge!=0||downY<dp(36)||downY>dp(100)||clips().length()==0||downX<x(0)||downX>x(total))return;
        dragging=true;dragFrom=indexAt(timeAt(downX));dragTo=dragFrom;
        getParent().requestDisallowInterceptTouchEvent(true);performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);invalidate();
    };
    StudioTimeline(Context context){
        super(context);setTag("studio_timeline");setFocusable(true);
        setContentDescription("Timeline : glisser pour parcourir, pincer pour zoomer, poignées pour découper, appui long pour déplacer");
        zoom=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            public boolean onScale(ScaleGestureDetector detector){pinching=true;removeCallbacks(startDrag);zoomBy(detector.getScaleFactor());return true;}
        });
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            public boolean onDown(MotionEvent e){return true;}
            public boolean onDoubleTap(MotionEvent e){if(e.getY()<dp(32)){fit();return true;}return false;}
        });
    }
    void setListener(Listener value){listener=value;}
    private JSONArray clips(){JSONArray a=project.optJSONArray("clips");return a==null?new JSONArray():a;}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    private float pixelsPerMs(){return dp(48)*scale/1000f;}
    private float x(long ms){return getWidth()/2f+(ms-position)*pixelsPerMs();}
    private long timeAt(float x){return Math.max(0,Math.min(total,position+Math.round((x-getWidth()/2f)/pixelsPerMs())));}
    void zoomBy(float factor){scale=Math.max(.15f,Math.min(64,scale*factor));invalidate();}
    void fit(){scale=Math.max(.15f,Math.min(64,(getWidth()-dp(40))/Math.max(1,total*dp(48)/1000f)));seek(total/2);invalidate();}
    float zoomLevel(){return scale;}
    long playhead(){return position;}
    void audioSelection(int index){selectedAudio=index;invalidate();}
    void sourceDuration(String path,long duration){sources.put(path,duration);}
    void setProject(JSONObject value,int clip){
        project=value;selected=clip;total=StudioEdits.total(project);position=Math.min(position,total);
        generation++;loading.clear();pendingTrim=null;invalidate();
    }
    void position(long value){if(moving||dragging||trimEdge!=0||pinching)return;position=Math.max(0,Math.min(total,value));selected=indexAt(position);invalidate();}
    @Override protected void onDetachedFromWindow(){generation++;removeCallbacks(startDrag);loading.clear();super.onDetachedFromWindow();}
    private void text(Canvas canvas,String value,float x,float y,int size,int color){paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));paint.setTextSize(dp(size));canvas.drawText(value,x,y,paint);}
    private String time(long ms){return String.format(java.util.Locale.ROOT,"%02d:%02d.%01d",ms/60000,ms/1000%60,ms%1000/100);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.drawColor(0xff191c23);
        float step=100;while(step*pixelsPerMs()<dp(56))step*=2;
        long first=Math.max(0,(long)((position-getWidth()/2f/pixelsPerMs())/step)*(long)step);
        for(long t=first;t<=total&&x(t)<getWidth();t+=(long)step){text(canvas,time(t),x(t)+dp(3),dp(18),10,0xffb7c3d7);paint.setColor(0xff657084);canvas.drawLine(x(t),dp(24),x(t),dp(29),paint);}
        long at=0;JSONArray clips=clips();
        for(int i=0;i<clips.length();i++){
            JSONObject clip=clips.optJSONObject(i);long len=StudioEdits.duration(clip);float left=x(at),right=x(at+len);at+=len;
            if(i==selected&&pendingTrim!=null){float length=StudioEdits.duration(pendingTrim)*pixelsPerMs();if(trimEdge<0)left=right-length;else right=left+length;}
            if(right<0||left>getWidth())continue;
            rect.set(left,dp(38),right,dp(100));paint.setStyle(Paint.Style.FILL);paint.setColor(0xff333f52);canvas.drawRoundRect(rect,dp(6),dp(6),paint);
            canvas.save();canvas.clipRect(rect);
            for(float tile=Math.max(left,left+(float)Math.floor(Math.max(0,-left)/dp(60))*dp(60));tile<Math.min(right,getWidth());tile+=dp(60)){
                double fraction=Math.max(0,Math.min(.999,(tile-left)/(right-left)));int sample=Math.min(2,(int)(fraction*3));
                long sourceAt=clip.optLong("start_ms")+clip.optLong("duration_ms")*(sample*2+1)/6;
                Bitmap bitmap=thumbnail(clip,sourceAt);
                if(bitmap!=null){paint.setColor(0xffffffff);canvas.drawBitmap(bitmap,null,new RectF(tile,dp(38),tile+dp(60),dp(100)),paint);}
            }
            paint.setColor(0xbb10151f);canvas.drawRect(left,dp(79),right,dp(100),paint);
            text(canvas,(i+1)+" · "+String.format(java.util.Locale.ROOT,"%.1f s",len/1000f),Math.max(left,0)+dp(8),dp(94),11,0xffffffff);canvas.restore();
            if(i==selected&&selectedAudio<0||dragging&&i==dragTo){
                paint.setColor(0xff55e2ca);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));canvas.drawRoundRect(rect,dp(6),dp(6),paint);paint.setStyle(Paint.Style.FILL);
                for(float hx:new float[]{left,right-dp(12)}){canvas.drawRoundRect(hx,dp(38),hx+dp(12),dp(100),dp(4),dp(4),paint);}
                paint.setColor(0xff142c2a);paint.setStrokeWidth(dp(2));canvas.drawLine(left+dp(6),dp(57),left+dp(6),dp(81),paint);canvas.drawLine(right-dp(6),dp(57),right-dp(6),dp(81),paint);
            }
        }
        JSONArray audio=project.optJSONArray("audio");long audioAt=0;
        if(audio!=null&&audio.length()>0)for(int i=0;i<audio.length();i++){
            JSONObject c=audio.optJSONObject(i);long len=c.optLong("duration_ms");segment(canvas,x(audioAt),x(audioAt+len),111,145,i==selectedAudio?0xff148d8a:0xff22575f,"♫ "+filename(c.optString("path")));audioAt+=len;
        }else segment(canvas,dp(12),getWidth()-dp(12),111,145,0xff28353f,"＋ Ajouter un audio");
        long textAt=0;boolean captions=false;
        for(int i=0;i<clips.length();i++){JSONObject c=clips.optJSONObject(i);long len=StudioEdits.duration(c);
            if(!c.optString("text").isEmpty()){captions=true;segment(canvas,x(textAt),x(textAt+len),154,184,0xff53457b,"T  "+c.optString("text"));}textAt+=len;}
        if(!captions)segment(canvas,dp(12),getWidth()-dp(12),154,184,0xff302d40,"＋ Ajouter un texte");
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xfff1f6ff);paint.setStrokeWidth(dp(2));canvas.drawLine(getWidth()/2f,dp(27),getWidth()/2f,dp(190),paint);canvas.drawRoundRect(getWidth()/2f-dp(4),dp(27),getWidth()/2f+dp(4),dp(34),dp(2),dp(2),paint);
        if(pendingTrim!=null)text(canvas,"Durée : "+String.format(java.util.Locale.ROOT,"%.2f s",StudioEdits.duration(pendingTrim)/1000f),dp(12),dp(201),12,0xff55e2ca);
        else text(canvas,dragging?"Relâcher pour déplacer le plan":"Glisser · pincer pour zoomer · appui long pour déplacer",dp(12),dp(201),10,0xff98a9c2);
    }
    private Bitmap thumbnail(JSONObject clip,long at){
        String path=clip.optString("path");boolean photo=StudioImages.isImage(new java.io.File(path));if(photo)at=0;
        String key=path+":"+at;Bitmap existing=thumbnails.get(key);
        if(existing==null&&!loading.contains(key)){
            loading.add(key);int ticket=generation;
            ThumbnailLoader.load(getContext(),path,at,!photo,new ThumbnailLoader.Result(){
                public boolean current(){return ticket==generation;}
                public void ready(Bitmap b){loading.remove(key);thumbnails.put(key,b);invalidate();}
            });
        }
        return existing;
    }
    private String filename(String path){return path.substring(path.lastIndexOf('/')+1);}
    private void segment(Canvas canvas,float left,float right,int top,int bottom,int color,String label){
        if(right<=0||left>=getWidth()||right<=left)return;
        paint.setColor(color);paint.setStyle(Paint.Style.FILL);rect.set(Math.max(-dp(5),left),dp(top),Math.min(getWidth()+dp(5),right),dp(bottom));canvas.drawRoundRect(rect,dp(5),dp(5),paint);
        canvas.save();canvas.clipRect(rect);text(canvas,label,Math.max(dp(3),left)+dp(8),dp(bottom-10),12,0xfff1f6ff);canvas.restore();
    }
    private int indexAt(long time){long at=0;JSONArray clips=clips();for(int i=0;i<clips.length();i++){at+=StudioEdits.duration(clips.optJSONObject(i));if(time<at)return i;}return Math.max(0,clips.length()-1);}
    private long start(int index){long n=0;for(int i=0;i<index;i++)n+=StudioEdits.duration(clips().optJSONObject(i));return n;}
    private void seek(long at){position=Math.max(0,Math.min(total,at));selected=indexAt(position);selectedAudio=-1;invalidate();if(listener!=null)listener.seek(position,selected);}
    @Override public boolean onTouchEvent(MotionEvent event){
        zoom.onTouchEvent(event);gestures.onTouchEvent(event);
        if(event.getPointerCount()>1){pinching=true;removeCallbacks(startDrag);getParent().requestDisallowInterceptTouchEvent(true);return true;}
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                downX=lastX=event.getX();downY=event.getY();downPosition=position;moving=false;dragging=false;pinching=false;trimEdge=0;pendingTrim=null;
                if(downY>=dp(38)&&downY<=dp(100)&&selectedAudio<0&&clips().length()>0){float left=x(start(selected)),right=x(start(selected)+StudioEdits.duration(clips().optJSONObject(selected)));
                    if(Math.abs(downX-left)<dp(18))trimEdge=-1;else if(Math.abs(downX-right)<dp(18))trimEdge=1;}
                postDelayed(startDrag,450);getParent().requestDisallowInterceptTouchEvent(true);return true;
            case MotionEvent.ACTION_MOVE:
                if(pinching)return true;
                if(Math.abs(event.getX()-downX)>dp(6)||Math.abs(event.getY()-downY)>dp(6)){moving=true;removeCallbacks(startDrag);}
                if(dragging){dragTo=indexAt(timeAt(event.getX()));if(event.getX()<dp(32))position=Math.max(0,position-100);else if(event.getX()>getWidth()-dp(32))position=Math.min(total,position+100);invalidate();}
                else if(trimEdge!=0){try{JSONObject c=clips().getJSONObject(selected);long source=sources.containsKey(c.optString("path"))?sources.get(c.optString("path")):c.optLong("start_ms")+c.optLong("duration_ms");pendingTrim=StudioEdits.trimmed(c,trimEdge<0,Math.round((event.getX()-downX)/pixelsPerMs()),source);invalidate();}catch(Exception ignored){}}
                else if(moving){if(listener!=null)listener.scrubbing(true);seek(downPosition-Math.round((event.getX()-downX)/pixelsPerMs()));}
                lastX=event.getX();return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(startDrag);
                if(pinching){pinching=false;finishGesture();return true;}
                if(dragging){dragging=false;if(listener!=null&&dragFrom!=dragTo)listener.move(dragFrom,dragTo);}
                else if(trimEdge!=0&&pendingTrim!=null&&moving){if(listener!=null)listener.trim(selected,pendingTrim);}
                else if(!moving){long at=timeAt(event.getX());
                    if(downY>=dp(111)&&downY<dp(146)){
                        JSONArray a=project.optJSONArray("audio");int hit=-1;long n=0;if(a!=null)for(int i=0;i<a.length();i++){n+=a.optJSONObject(i).optLong("duration_ms");if(at<n){hit=i;break;}}
                        selectedAudio=hit;if(listener!=null){if(hit>=0)listener.audio(hit);else listener.add("audio");}
                    }else if(downY>=dp(154)&&downY<dp(185)){int index=indexAt(at);if(listener!=null)listener.text(index);}
                    else seek(at);
                    performClick();
                }
                finishGesture();return true;
            case MotionEvent.ACTION_CANCEL:removeCallbacks(startDrag);dragging=false;pinching=false;finishGesture();return true;
        }
        return true;
    }
    private void finishGesture(){trimEdge=0;moving=false;pendingTrim=null;getParent().requestDisallowInterceptTouchEvent(false);if(listener!=null)listener.scrubbing(false);invalidate();}
    @Override public boolean performClick(){super.performClick();return true;}
}
