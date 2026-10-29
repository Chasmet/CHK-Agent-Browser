package com.chk.agentbrowser;

import android.os.Handler;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared foreground/background scroll with observed movement and page guards. */
final class BrowserScroll {
    private BrowserScroll() {}
    static void run(WebView target, JSONObject args, Handler handler, AgentClient.ResultCallback callback) {
        if(target==null || !BrowserWebState.alive(target)){callback.finish(false,"Page indisponible.");return;}
        AgentVision.ensureMeasured(target);
        final String url=target.getUrl();
        final long version=BrowserWebState.documentVersion(target);
        final String direction=args.optString("direction","down"),selector=args.optString("selector",""),until=args.optString("until_text","");
        if(!java.util.Arrays.asList("up","down","left","right").contains(direction)||selector.length()>350||until.length()>180){
            callback.finish(false,"Paramètres de défilement invalides.");return;
        }
        final int pixels=Math.max(60,Math.min(2400,args.optInt("pixels",Math.max(350,(int)(target.getHeight()*0.6f)))));
        final int limit=Math.max(1,Math.min(20,args.optInt("max_steps",1)));
        final String script=BrowserScripts.scrollContainer(direction,pixels,selector,until);
        new Runnable(){
            int steps=0;boolean moved=false;
            @Override public void run(){
                if(!callback.isActive())return;
                if(!BrowserWebState.alive(target)||!java.util.Objects.equals(url,target.getUrl())||version!=BrowserWebState.documentVersion(target)){
                    callback.finish(false,"Page modifiée durant le défilement. Relire avant de continuer.");return;
                }
                target.evaluateJavascript(script,raw->{
                    if(!callback.isActive())return;
                    if(!BrowserWebState.alive(target)||!java.util.Objects.equals(url,target.getUrl())||version!=BrowserWebState.documentVersion(target)){
                        callback.finish(false,"Page modifiée durant le défilement. Relire avant de continuer.");return;
                    }
                    try{
                        JSONObject state=new JSONObject(new JSONArray("["+raw+"]").getString(0));
                        steps++;moved|=state.optBoolean("moved",false);
                        state.put("steps",steps).put("movement_verified",moved);
                        if(!state.optBoolean("ok",false)){callback.finish(false,state.toString());return;}
                        if(state.optBoolean("found",false)||!state.optBoolean("moved",false)||steps>=limit){
                            callback.finish(moved||state.optBoolean("found",false),state.toString());return;
                        }
                        handler.postDelayed(this,85L);
                    }catch(Exception error){callback.finish(false,"Impossible de vérifier le déplacement de la liste.");}
                });
            }
        }.run();
    }
}
