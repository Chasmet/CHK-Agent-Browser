package com.chk.agentbrowser;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.util.WeakHashMap;

/** CHK-only visual feedback. ViewOverlay never intercepts touch or adds an accessibility control. */
final class AgentFeedback {
    private static final WeakHashMap<View,Mark> MARKS=new WeakHashMap<>();
    private AgentFeedback(){}
    static void point(View root,float x,float y,String label){
        Mark old=MARKS.get(root);if(old!=null)root.getOverlay().remove(old);
        Mark mark=new Mark(x,y,label,root.getResources().getDisplayMetrics().density);
        mark.setBounds(0,0,root.getWidth(),root.getHeight());MARKS.put(root,mark);root.getOverlay().add(mark);
        WorkspaceCommands.UI.postDelayed(()->{if(MARKS.get(root)==mark){root.getOverlay().remove(mark);MARKS.remove(root);}},1300L);
    }
    static void field(View view){if(view.getWidth()>0&&view.getHeight()>0)point(view,Math.min(18,view.getWidth()/2f),Math.min(18,view.getHeight()/2f),"Clavier CHK");}
    private static final class Mark extends Drawable {
        final float x,y,d;final String label;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Mark(float x,float y,String label,float density){this.x=x;this.y=y;this.label=label;this.d=density;}
        @Override public void draw(Canvas c){
            p.setColor(0xff19d6cc);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3*d);c.drawCircle(x,y,11*d,p);
            p.setStyle(Paint.Style.FILL);c.drawCircle(x,y,3*d,p);p.setTextSize(12*d);
            float width=p.measureText(label)+12*d,left=Math.max(0,Math.min(x+14*d,getBounds().width()-width)),top=Math.max(0,y-30*d);
            p.setColor(0xee12313a);c.drawRoundRect(left,top,left+width,top+23*d,5*d,5*d,p);
            p.setColor(0xffbffff9);c.drawText(label,left+6*d,top+16*d,p);
        }
        @Override public void setAlpha(int alpha){p.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
