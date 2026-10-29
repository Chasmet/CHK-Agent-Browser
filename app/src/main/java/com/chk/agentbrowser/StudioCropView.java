package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONObject;

/** Four draggable crop handles with interior drag and two-finger zoom. */
final class StudioCropView extends View {
    private final Paint paint=new Paint(3);
    private Bitmap image;
    private final RectF canvas=new RectF();
    private float x,y,w,h;
    private String ratio="Libre";
    private int mode=-1;
    private float lastX,lastY,pinchDistance;
    private int sourceW=1,sourceH=1;
    StudioCropView(Context context,JSONObject crop){
        super(context);
        x=(float)crop.optDouble("x",0);y=(float)crop.optDouble("y",0);
        w=(float)crop.optDouble("w",1);h=(float)crop.optDouble("h",1);
        ratio=crop.optString("ratio","Libre");
        setBackgroundColor(0xff111319);
        setMinimumHeight(Math.round(300*getResources().getDisplayMetrics().density));
        setTag("studio_crop_handles");
    }
    void image(Bitmap value){image=value;if(value!=null){sourceW=value.getWidth();sourceH=value.getHeight();}invalidate();}
    void ratio(String value){
        ratio=value;if(!value.equals("Libre")){
            String[] q=value.split(":");
            float aspect=Float.parseFloat(q[0])/Float.parseFloat(q[1]);
            float newH=w*sourceW/(Math.max(1,sourceH)*aspect);
            if(newH>1){newH=1;w=newH*sourceH*aspect/Math.max(1,sourceW);}
            h=Math.min(1,newH);x=Math.max(0,Math.min(x,1-w));y=Math.max(0,Math.min(y,1-h));
        }invalidate();
    }
    void reset(){x=0;y=0;w=1;h=1;ratio="Libre";invalidate();}
    JSONObject rect()throws Exception{return new JSONObject().put("x",x).put("y",y).put("w",w).put("h",h).put("ratio",ratio);}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        float inset=16*getResources().getDisplayMetrics().density;
        float aspect=sourceW/(float)Math.max(1,sourceH);
        float vw=getWidth()-2*inset,vh=getHeight()-2*inset;
        if(vw/vh>aspect)vw=vh*aspect;else vh=vw/aspect;
        float left=(getWidth()-vw)/2f,top=(getHeight()-vh)/2f;
        canvas.set(left,top,left+vw,top+vh);
        if(image!=null){paint.setColor(0xffffffff);c.drawBitmap(image,null,canvas,paint);}
        else{paint.setColor(0xff273044);c.drawRect(canvas,paint);}
        RectF crop=new RectF(left+x*vw,top+y*vh,left+(x+w)*vw,top+(y+h)*vh);
        paint.setColor(0xa8000000);
        c.drawRect(canvas.left,canvas.top,canvas.right,crop.top,paint);
        c.drawRect(canvas.left,crop.bottom,canvas.right,canvas.bottom,paint);
        c.drawRect(canvas.left,crop.top,crop.left,crop.bottom,paint);
        c.drawRect(crop.right,crop.top,canvas.right,crop.bottom,paint);
        paint.setColor(0xff69e2c6);paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3*getResources().getDisplayMetrics().density);
        c.drawRect(crop,paint);paint.setStyle(Paint.Style.FILL);
        float handle=8*getResources().getDisplayMetrics().density;
        for(float px:new float[]{crop.left,crop.right})
            for(float py:new float[]{crop.top,crop.bottom})c.drawCircle(px,py,handle,paint);
        paint.setTextSize(13*getResources().getDisplayMetrics().scaledDensity);
        c.drawText(String.format(java.util.Locale.ROOT,"x %.3f  y %.3f  w %.3f  h %.3f",x,y,w,h),canvas.left,Math.max(18,canvas.top-5),paint);
    }
    private float distance(MotionEvent e){float dx=e.getX(1)-e.getX(0),dy=e.getY(1)-e.getY(0);return (float)Math.hypot(dx,dy);}
    private void constrain(float xx,float yy,float ww,float hh){
        ww=Math.max(.02f,Math.min(1,ww));hh=Math.max(.02f,Math.min(1,hh));
        if(!ratio.equals("Libre")){
            String[] parts=ratio.split(":");float a=Float.parseFloat(parts[0])/Float.parseFloat(parts[1]);
            hh=ww*sourceW/(Math.max(1,sourceH)*a);
            if(hh>1){hh=1;ww=hh*sourceH*a/Math.max(1,sourceW);}
        }
        w=ww;h=hh;x=Math.max(0,Math.min(1-w,xx));y=Math.max(0,Math.min(1-h,yy));invalidate();
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_POINTER_DOWN&&e.getPointerCount()>=2){pinchDistance=distance(e);mode=5;return true;}
        if(action==MotionEvent.ACTION_DOWN){
            float px=(e.getX()-canvas.left)/Math.max(1,canvas.width()),py=(e.getY()-canvas.top)/Math.max(1,canvas.height());
            float hx=Math.min(.08f,24/Math.max(1,canvas.width())),hy=Math.min(.08f,24/Math.max(1,canvas.height()));
            mode=-1;
            float[] cx={x,x+w,x,x+w},cy={y,y,y+h,y+h};
            for(int i=0;i<4;i++)if(Math.abs(px-cx[i])<hx&&Math.abs(py-cy[i])<hy){mode=i;break;}
            if(mode<0&&px>=x&&px<=x+w&&py>=y&&py<=y+h)mode=4;
            lastX=px;lastY=py;return true;
        }
        if(action==MotionEvent.ACTION_MOVE){
            if(mode==5&&e.getPointerCount()>=2){
                float d=distance(e);float scale=pinchDistance<=0?1:pinchDistance/d;
                float ww=w*scale,hh=h*scale;
                constrain(x+(w-ww)/2,y+(h-hh)/2,ww,hh);pinchDistance=d;return true;
            }
            if(mode<0)return true;
            float px=(e.getX()-canvas.left)/Math.max(1,canvas.width()),py=(e.getY()-canvas.top)/Math.max(1,canvas.height());
            float dx=px-lastX,dy=py-lastY;lastX=px;lastY=py;
            switch(mode){
                case 0:constrain(x+dx,y+dy,w-dx,h-dy);break;
                case 1:constrain(x,y+dy,w+dx,h-dy);break;
                case 2:constrain(x+dx,y,w-dx,h+dy);break;
                case 3:constrain(x,y,w+dx,h+dy);break;
                default:constrain(x+dx,y+dy,w,h);
            }return true;
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){mode=-1;performClick();return true;}
        if(action==MotionEvent.ACTION_POINTER_UP){mode=-1;return true;}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}