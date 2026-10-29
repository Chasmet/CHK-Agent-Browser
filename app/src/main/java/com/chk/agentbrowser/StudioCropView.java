package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Touch-only crop rectangle with four handles; edits a private copy until Apply. */
final class StudioCropView extends View {
    private final Paint p=new Paint(3);
    private final RectF frame=new RectF();
    private Bitmap source;
    private float x=0,y=0,w=1,h=1,lastX,lastY;
    private int grabbed=-1;
    private float ratio=0,sourceAspect=1;
    private final android.view.ScaleGestureDetector zoom;
    private boolean pinching;
    StudioCropView(Context context){super(context);setTag("studio_crop_handles");setMinimumHeight(320);
        zoom=new android.view.ScaleGestureDetector(context,new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener(){
            public boolean onScale(android.view.ScaleGestureDetector d){pinching=true;float factor=d.getScaleFactor();if(!Float.isFinite(factor)||factor<=0)return false;
                float cx=x+w/2,cy=y+h/2;float nw=w/factor,nh=h/factor;float limit=Math.min(1/Math.max(.025f,nw),1/Math.max(.025f,nh));if(limit<1){nw*=limit;nh*=limit;}
                if(nw<.025f||nh<.025f)return false;w=nw;h=nh;x=Math.max(0,Math.min(1-w,cx-w/2));y=Math.max(0,Math.min(1-h,cy-h/2));invalidate();return true;}
        });
    }
    void source(Bitmap bitmap){
        source=bitmap;
        if(bitmap!=null)sourceAspect=bitmap.getWidth()/(float)Math.max(1,bitmap.getHeight());
        invalidate();
    }
    void setCrop(double left,double top,double width,double height){
        x=(float)left;y=(float)top;w=(float)width;h=(float)height;invalidate();
    }
    void setRatio(float r){ratio=r;if(r>0){float aspect=r/sourceAspect;float nh=Math.min(h,w/aspect);float nw=nh*aspect;w=nw;h=nh;x=Math.min(x,1-w);y=Math.min(y,1-h);}invalidate();}
    float left(){return x;}float top(){return y;}float widthNorm(){return w;}float heightNorm(){return h;}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float vw=getWidth(),vh=getHeight();
        if(source!=null){
            float aspect=source.getWidth()/(float)source.getHeight();
            float dw=Math.min(vw,vh*aspect),dh=dw/aspect;
            frame.set((vw-dw)/2,(vh-dh)/2,(vw+dw)/2,(vh+dh)/2);
            canvas.drawBitmap(source,null,frame,p);
        }else frame.set(4,4,vw-4,vh-4);
        float l=frame.left+x*frame.width(),t=frame.top+y*frame.height();
        float r=l+w*frame.width(),b=t+h*frame.height();
        p.setColor(0xff12121f);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5);
        canvas.drawRect(l,t,r,b,p);
        p.setColor(0xffddc7ff);p.setStrokeWidth(3);canvas.drawRect(l,t,r,b,p);
        p.setStyle(Paint.Style.FILL);
        for(float xx:new float[]{l,r})for(float yy:new float[]{t,b})canvas.drawCircle(xx,yy,10,p);
        p.setTextSize(24);canvas.drawText(String.format(java.util.Locale.FRANCE,"x %.3f y %.3f · %.3f × %.3f",x,y,w,h),10,Math.max(30,t-14),p);
    }
    @Override protected void onDetachedFromWindow(){if(source!=null){source.recycle();source=null;}super.onDetachedFromWindow();}
    @Override public boolean onTouchEvent(MotionEvent e){
        zoom.onTouchEvent(e);if(e.getPointerCount()>1){pinching=true;getParent().requestDisallowInterceptTouchEvent(true);return true;}if(pinching){if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){pinching=false;grabbed=-1;getParent().requestDisallowInterceptTouchEvent(false);}return true;}
        float px=e.getX(),py=e.getY();
        float l=frame.left+x*frame.width(),t=frame.top+y*frame.height(),r=l+w*frame.width(),b=t+h*frame.height();
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:{
                grabbed=-1;
                float best=Float.MAX_VALUE;
                for(int i=0;i<4;i++){float hx=(i%2==0)?l:r,hy=(i/2==0)?t:b;float dist=(hx-px)*(hx-px)+(hy-py)*(hy-py);
                    if(dist<best&&dist<3600){best=dist;grabbed=i;}}
                if(grabbed<0&&px>=l&&px<=r&&py>=t&&py<=b)grabbed=4;
                if(grabbed<0)return false;
                lastX=px;lastY=py;getParent().requestDisallowInterceptTouchEvent(true);return true;
            }
            case MotionEvent.ACTION_MOVE:{
                if(grabbed<0)return false;
                float dx=(px-lastX)/Math.max(1,frame.width()),dy=(py-lastY)/Math.max(1,frame.height());
                if(grabbed==4){x=Math.max(0,Math.min(1-w,x+dx));y=Math.max(0,Math.min(1-h,y+dy));}
                else{
                    float left=x,right=x+w,top=y,bottom=y+h;
                    if(grabbed%2==0)left=Math.max(0,Math.min(right-.025f,left+dx));
                    else right=Math.min(1,Math.max(left+.025f,right+dx));
                    if(grabbed/2==0)top=Math.max(0,Math.min(bottom-.025f,top+dy));
                    else bottom=Math.min(1,Math.max(top+.025f,bottom+dy));
                    if(ratio>0){float aspect=ratio/sourceAspect;
                        float newW=right-left,newH=Math.min(bottom-top,newW/aspect);
                        newW=newH*aspect;
                        right=Math.min(1,left+newW);bottom=Math.min(1,top+newH);
                    }
                    x=left;y=top;w=right-left;h=bottom-top;
                }
                lastX=px;lastY=py;invalidate();return true;
            }
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:grabbed=-1;getParent().requestDisallowInterceptTouchEvent(false);return true;
            default:return true;
        }
    }
}
