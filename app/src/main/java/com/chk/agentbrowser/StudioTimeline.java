package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import org.json.*;
import java.util.*;

/** Draws time-proportional clips and actual audio/text segments without rebuilding views per frame. */
final class StudioTimeline extends View {
    interface Listener {void seek(long position,int clip);default void scrubbing(boolean active){}}
    private final Paint paint=new Paint(3);private final RectF rect=new RectF();
    private JSONObject project=new JSONObject();private final Map<String,Bitmap> thumbnails=new HashMap<>();
    private long total,position;private int selected,generation;private float scale=1,downX,downY;private boolean scrub;
    private Listener listener;private final ScaleGestureDetector zoom;
    StudioTimeline(Context context){super(context);setFocusable(true);setContentDescription("Timeline du projet : toucher un plan ou glisser sur la règle pour déplacer le curseur");zoom=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){scale=Math.max(.3f,Math.min(4,scale*d.getScaleFactor()));requestLayout();invalidate();return true;}});}
    void setListener(Listener value){listener=value;}
    void setProject(JSONObject value,int clip){project=value;selected=clip;generation++;total=0;JSONArray clips=project.optJSONArray("clips");Set<String> currentKeys=new HashSet<>();if(clips!=null)for(int i=0;i<clips.length();i++){JSONObject c=clips.optJSONObject(i);total+=duration(c);final int ticket=generation;String path=c.optString("path");long at=c.optLong("start_ms");String key=path+":"+at;currentKeys.add(key);if(!thumbnails.containsKey(key))ThumbnailLoader.load(getContext(),path,at,true,new ThumbnailLoader.Result(){public boolean current(){return ticket==generation&&isAttachedToWindow();}public void ready(Bitmap b){thumbnails.put(key,b);invalidate();}});}thumbnails.keySet().retainAll(currentKeys);requestLayout();invalidate();}
    void position(long value){position=value;invalidate();}
    private long duration(JSONObject c){return c==null?0:Math.round(c.optLong("duration_ms",10000)/c.optDouble("speed",1));}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    private float pixelsPerMs(){return dp(30)*scale/1000f;}
    private float x(long ms){return dp(24)+ms*pixelsPerMs();}
    @Override protected void onMeasure(int widthSpec,int heightSpec){int viewport=getResources().getDisplayMetrics().widthPixels;setMeasuredDimension(Math.max(viewport,(int)(x(total)+dp(60))),MeasureSpec.getSize(heightSpec));}
    private void text(Canvas canvas,String value,float x,float y,int size,int color){paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));paint.setTextSize(dp(size));canvas.drawText(value,x,y,paint);}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.save();canvas.scale(1,getHeight()/dp(186));canvas.drawColor(0xff1b1c20);Rect visible=new Rect();canvas.getClipBounds(visible);float step=5000;while(step*pixelsPerMs()<dp(52))step*=2;
        long first=Math.max(0,(long)((visible.left-dp(24))/pixelsPerMs()/step)* (long)step);
        for(long t=first;t<=total&&x(t)<visible.right;t+=(long)step){text(canvas,String.format(java.util.Locale.ROOT,"%02d:%02d",t/60000,(t/1000)%60),x(t)+dp(2),dp(16),10,0xffb7bac5);paint.setColor(0xff50535e);canvas.drawLine(x(t),dp(24),x(t),dp(29),paint);}
        JSONArray clips=project.optJSONArray("clips");long start=0;if(clips!=null)for(int i=0;i<clips.length();i++){JSONObject c=clips.optJSONObject(i);float left=x(start),right=x(start+duration(c));start+=duration(c);if(right<visible.left||left>visible.right)continue;rect.set(left,dp(35),right,dp(94));paint.setColor(0xff343740);paint.setStyle(Paint.Style.FILL);canvas.drawRect(rect,paint);Bitmap b=thumbnails.get(c.optString("path")+":"+c.optLong("start_ms"));if(b!=null){canvas.save();canvas.clipRect(rect);for(float tx=left+(float)Math.floor(Math.max(0,visible.left-left)/dp(64))*dp(64);tx<Math.min(right,visible.right);tx+=dp(64)){RectF tile=new RectF(tx,dp(35),tx+dp(64),dp(94));canvas.drawBitmap(b,null,tile,paint);}canvas.restore();}if(i==selected){paint.setStyle(Paint.Style.STROKE);paint.setColor(0xff20d4d3);paint.setStrokeWidth(dp(3));canvas.drawRoundRect(rect,dp(3),dp(3),paint);paint.setStyle(Paint.Style.FILL);canvas.drawRect(left,dp(35),left+dp(4),dp(94),paint);canvas.drawRect(right-dp(4),dp(35),right,dp(94),paint);}paint.setStyle(Paint.Style.FILL);paint.setColor(0x99000000);canvas.drawRect(left,dp(75),right,dp(94),paint);canvas.save();canvas.clipRect(left,dp(75),right,dp(94));text(canvas,(i+1)+" · "+String.format(java.util.Locale.ROOT,"%.1f s",duration(c)/1000f),left+dp(6),dp(88),10,0xffffffff);canvas.restore();}
        JSONArray audio=project.optJSONArray("audio");long audioAt=0;if(audio!=null&&audio.length()>0)for(int i=0;i<audio.length();i++){JSONObject c=audio.optJSONObject(i);long len=c.optLong("duration_ms");segment(canvas,x(audioAt),x(audioAt+len),106,136,0xff17626c,"♫ "+filename(c.optString("path")));audioAt+=len;}else segment(canvas,x(0),x(total),106,136,0xff284148,project.optBoolean("mute_original",false)?"Son original coupé":"♫ Son original");
        long textAt=0;if(clips!=null)for(int i=0;i<clips.length();i++){JSONObject c=clips.optJSONObject(i);long len=duration(c);if(!c.optString("text").isEmpty())segment(canvas,x(textAt),x(textAt+len),144,174,0xff514b8d,"T  "+c.optString("text"));textAt+=len;}
        paint.setColor(0xffffffff);paint.setStrokeWidth(dp(1.5f));canvas.drawLine(x(position),dp(25),x(position),dp(181),paint);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(x(position),dp(28),dp(3),paint);canvas.restore();
    }
    private String filename(String path){return path.substring(path.lastIndexOf('/')+1);}
    private void segment(Canvas canvas,float left,float right,int top,int bottom,int color,String label){if(right<=left)return;paint.setColor(color);paint.setStyle(Paint.Style.FILL);rect.set(left,dp(top),right,dp(bottom));canvas.drawRoundRect(rect,dp(4),dp(4),paint);canvas.save();canvas.clipRect(rect);text(canvas,label,left+dp(8),dp(bottom-9),11,0xffeeeeff);canvas.restore();}
    private void seek(float where){position=Math.max(0,Math.min(total,(long)((where-dp(24))/pixelsPerMs())));JSONArray clips=project.optJSONArray("clips");long start=0;int index=0;if(clips!=null)for(int i=0;i<clips.length();i++){start+=duration(clips.optJSONObject(i));if(position<start){index=i;break;}}selected=index;invalidate();if(listener!=null)listener.seek(position,index);}
    @Override public boolean onTouchEvent(MotionEvent event){zoom.onTouchEvent(event);if(event.getPointerCount()>1){getParent().requestDisallowInterceptTouchEvent(true);return true;}switch(event.getActionMasked()){case MotionEvent.ACTION_DOWN:downX=event.getX();downY=event.getY();scrub=downY<getHeight()*32/186f;if(scrub){getParent().requestDisallowInterceptTouchEvent(true);if(listener!=null)listener.scrubbing(true);}return true;case MotionEvent.ACTION_MOVE:if(scrub)seek(event.getX());return true;case MotionEvent.ACTION_UP:if(scrub||Math.abs(event.getX()-downX)<dp(8)){seek(event.getX());performClick();}scrub=false;if(listener!=null)listener.scrubbing(false);getParent().requestDisallowInterceptTouchEvent(false);return true;case MotionEvent.ACTION_CANCEL:scrub=false;if(listener!=null)listener.scrubbing(false);return true;}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
}
