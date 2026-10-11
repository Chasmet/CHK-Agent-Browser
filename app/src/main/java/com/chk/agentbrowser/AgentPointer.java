package com.chk.agentbrowser;

import android.content.Context;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.webkit.WebView;
import org.json.JSONObject;

/** Actual Android touch events: canvas, native controls and WebView use the same input path. */
final class AgentPointer {
    private AgentPointer(){}
    static void execute(Context app,WebView web,JSONObject p,AgentClient.ResultCallback cb){
        if(p.optString("target","native").equals("web")){
            try{AgentVision.target(p,web);AgentVision.observeWeb(app,web,new AgentVision.Observation(){public void ready(JSONObject info){if(cb.isActive())executeReady(app,web,p,cb);}public void failed(String error){if(cb.isActive())cb.finish(false,error);}});}catch(Exception e){cb.finish(false,e.getMessage());}
        }else executeReady(app,web,p,cb);
    }
    private static void executeReady(Context app,WebView web,JSONObject p,AgentClient.ResultCallback cb){
        if(!cb.isActive())return;
        try{
            View root=AgentVision.target(p,web);AgentVision.guard(root,p);
            String gesture=p.optString("gesture","tap");
            if(!java.util.Arrays.asList("tap","double_tap","long_press","drag","swipe","back").contains(gesture))throw new Exception("Geste tactile inconnu.");
            if(gesture.equals("back")){
                if(root instanceof WebView){if(!((WebView)root).canGoBack())throw new Exception("Aucune page précédente.");((WebView)root).goBack();}
                else {
                    android.view.Window.Callback receiver=NativeKeyboard.window().getCallback();
                    receiver.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_BACK));
                    receiver.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_BACK));
                }
                cb.finish(true,new JSONObject().put("ok",true).put("gesture_sent",true).put("outcome_confirmed",false).toString());return;
            }
            float sx=1,sy=1;if(p.optString("coordinate_space","view").equals("image")){JSONObject state=AgentVision.inspect(root);sx=root.getWidth()/(float)state.getInt("image_width");sy=root.getHeight()/(float)state.getInt("image_height");}
            else if(!p.optString("coordinate_space","view").equals("view"))throw new Exception("Coordonnées attendues : view ou image.");
            float x=point(p,"x",sx,root.getWidth()),y=point(p,"y",sy,root.getHeight());
            boolean move=gesture.equals("drag")||gesture.equals("swipe");float endX=move?point(p,"end_x",sx,root.getWidth()):x,endY=move?point(p,"end_y",sy,root.getHeight()):y;
            int duration=p.optInt("duration_ms",move?650:gesture.equals("long_press")?ViewConfiguration.getLongPressTimeout()+200:70);
            if(duration<40||duration>5000||gesture.equals("long_press")&&duration<ViewConfiguration.getLongPressTimeout()+50)throw new Exception("Durée tactile invalide.");
            new Gesture(root,web,p,cb,gesture,x,y,endX,endY,duration).start();
        }catch(Exception e){cb.finish(false,e.getMessage()==null?"Geste impossible.":e.getMessage());}
    }
    private static float point(JSONObject p,String key,float scale,int bound)throws Exception{
        Object raw=p.get(key);if(!(raw instanceof Number))throw new Exception("Coordonnée numérique requise : "+key);
        double n=((Number)raw).doubleValue()*scale;if(Double.isNaN(n)||Double.isInfinite(n)||n<0||n>=bound)throw new Exception("Coordonnée hors de l’écran : "+key);return (float)n;
    }
    private static final class Gesture {
        final View root;final WebView web;final JSONObject p;final AgentClient.ResultCallback cb;final String kind;final float x,y,ex,ey;final int duration;long down;boolean accepted,held;int tap;
        Gesture(View r,WebView w,JSONObject p,AgentClient.ResultCallback cb,String kind,float x,float y,float ex,float ey,int duration){root=r;web=w;this.p=p;this.cb=cb;this.kind=kind;this.x=x;this.y=y;this.ex=ex;this.ey=ey;this.duration=duration;}
        boolean valid(){try{return cb.isActive()&&AgentVision.target(p,web)==root&&AgentVision.context(root).equals(p.optString("expected_context"));}catch(Exception e){return false;}}
        boolean event(int action,float x,float y){MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{return root.dispatchTouchEvent(e);}catch(RuntimeException failure){return false;}finally{e.recycle();}}
        void start(){if(!valid())return;down=SystemClock.uptimeMillis();held=true;accepted=event(MotionEvent.ACTION_DOWN,x,y);WorkspaceCommands.UI.postDelayed(this::tick,16);}
        void tick(){
            if(!valid()){if(held){event(MotionEvent.ACTION_CANCEL,x,y);held=false;}if(cb.isActive()){try{cb.finish(true,new JSONObject().put("ok",accepted).put("gesture_sent",true).put("screen_changed",true).put("outcome_confirmed",false).toString());}catch(Exception ignored){cb.finish(false,"Écran changé pendant le geste ; résultat à vérifier.");}}return;}
            long elapsed=SystemClock.uptimeMillis()-down;
            if(elapsed<duration){if(kind.equals("drag")||kind.equals("swipe")){float t=elapsed/(float)duration;event(MotionEvent.ACTION_MOVE,x+(ex-x)*t,y+(ey-y)*t);}WorkspaceCommands.UI.postDelayed(this::tick,16);return;}
            event(MotionEvent.ACTION_UP,ex,ey);held=false;
            if(kind.equals("double_tap")&&tap++==0){WorkspaceCommands.UI.postDelayed(this::start,100);return;}
            WorkspaceCommands.UI.postDelayed(()->{
                if(!cb.isActive())return;
                try{JSONObject out=new JSONObject().put("ok",accepted).put("gesture_sent",true).put("touch_accepted",accepted).put("outcome_confirmed",false);
                    try{out.put("screen_after",AgentVision.inspect(AgentVision.target(p,web)));}catch(Exception e){out.put("screen_changed",true);}
                    cb.finish(true,out.toString());
                }catch(Exception e){cb.finish(false,e.getMessage());}
            },160);
        }
    }
}
