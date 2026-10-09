package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

/** Small, sharp native icons; no emoji font or remote image request on navigation. */
final class UiIcons {
    static Drawable icon(Context c,String kind,int color,int size){return new Glyph(kind,color,MobileUi.dp(c,size));}
    static TextView button(Context c,String label,String kind,int color){
        TextView view=MobileUi.text(c,label,11,color);view.setGravity(android.view.Gravity.CENTER);
        view.setPadding(0,MobileUi.dp(c,5),0,MobileUi.dp(c,5));view.setCompoundDrawablesWithIntrinsicBounds(null,icon(c,kind,color,24),null,null);
        view.setCompoundDrawablePadding(MobileUi.dp(c,4));view.setMinHeight(MobileUi.dp(c,52));view.setFocusable(true);view.setContentDescription(label);return view;
    }
    private static final class Glyph extends Drawable {
        final String kind;final int color,size;final Paint p=new Paint(3);
        Glyph(String k,int c,int s){kind=k;color=c;size=s;setBounds(0,0,s,s);}
        public int getIntrinsicWidth(){return size;}public int getIntrinsicHeight(){return size;}
        public void draw(Canvas c){c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);p.setColor(color);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStyle(Paint.Style.STROKE);
            switch(kind){
                case "folder":p.setStyle(Paint.Style.FILL);Path folder=new Path();folder.moveTo(2,6);folder.lineTo(10,6);folder.lineTo(12,8);folder.lineTo(22,8);folder.lineTo(22,21);folder.lineTo(2,21);folder.close();c.drawPath(folder,p);break;
                case "video":case "play":p.setStyle(Paint.Style.FILL);Path tri=new Path();tri.moveTo(8,4);tri.lineTo(21,12);tri.lineTo(8,20);tri.close();c.drawPath(tri,p);break;
                case "image":c.drawRoundRect(2,3,22,21,3,3,p);c.drawCircle(8,8,1.5f,p);Path mountain=new Path();mountain.moveTo(3,19);mountain.lineTo(10,12);mountain.lineTo(14,16);mountain.lineTo(18,11);mountain.lineTo(22,17);c.drawPath(mountain,p);break;
                case "audio":c.drawLine(10,18,10,5,p);c.drawLine(10,5,20,3,p);c.drawLine(20,3,20,16,p);p.setStyle(Paint.Style.FILL);c.drawOval(3,16,10,21,p);c.drawOval(13,14,20,19,p);break;
                case "document":case "text":c.drawRoundRect(4,2,20,22,2,2,p);for(int y=7;y<=17;y+=5)c.drawLine(8,y,16,y,p);break;
                case "download":c.drawLine(12,2,12,16,p);c.drawLine(7,11,12,16,p);c.drawLine(12,16,17,11,p);c.drawLine(3,18,3,22,p);c.drawLine(3,22,21,22,p);c.drawLine(21,22,21,18,p);break;
                case "history":case "speed":c.drawCircle(12,12,9,p);c.drawLine(12,6,12,12,p);c.drawLine(12,12,17,14,p);break;
                case "globe":c.drawCircle(12,12,9,p);c.drawOval(8,3,16,21,p);c.drawLine(3,12,21,12,p);break;
                case "search":c.drawCircle(10,10,7,p);c.drawLine(15,15,22,22,p);break;
                case "plus":c.drawLine(12,4,12,20,p);c.drawLine(4,12,20,12,p);break;
                case "menu":for(int y=6;y<=18;y+=6)c.drawLine(3,y,21,y,p);break;
                case "sort":c.drawLine(3,6,21,6,p);c.drawLine(3,12,16,12,p);c.drawLine(3,18,11,18,p);break;
                case "grid":for(int x=3;x<20;x+=11)for(int y=3;y<20;y+=11)c.drawRect(x,y,x+7,y+7,p);break;
                case "layers":Path layer=new Path();layer.moveTo(12,3);layer.lineTo(22,9);layer.lineTo(12,15);layer.lineTo(2,9);layer.close();c.drawPath(layer,p);c.drawLine(2,15,12,21,p);c.drawLine(12,21,22,15,p);break;
                case "crop":c.drawLine(6,2,6,18,p);c.drawLine(6,18,22,18,p);c.drawLine(2,6,18,6,p);c.drawLine(18,6,18,22,p);break;
                case "scissors":c.drawCircle(6,18,3,p);c.drawCircle(18,18,3,p);c.drawLine(4,2,17,16,p);c.drawLine(20,2,7,16,p);break;
                case "effects":Path star=new Path();for(int i=0;i<10;i++){double a=-Math.PI/2+i*Math.PI/5;float r=i%2==0?10:5;float x=12+(float)Math.cos(a)*r,y=12+(float)Math.sin(a)*r;if(i==0)star.moveTo(x,y);else star.lineTo(x,y);}star.close();c.drawPath(star,p);break;
                default:c.drawRoundRect(4,4,20,20,3,3,p);break;
            }c.restore();
        }
        public void setAlpha(int alpha){p.setAlpha(alpha);}public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
