package com.chk.agentbrowser;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import org.json.JSONObject;

/**
 * In-app, non-exported on-device translator isolated from both CHK Web and Tor WebView.
 * It receives only a bounded plain-text excerpt, never URL, cookies or HTML.
 * This process intentionally has no WebView and never calls downloadModelIfNeeded().
 * Any model failure or process exit must not terminate the Tor browser.
 */
public final class TorOfflineTranslationService extends Service {
    static final int PING=1, CHECK=2, TRANSLATE=3;
    static final int MAX_TEXT=12000;
    private final Messenger binder=new Messenger(new Handler(Looper.getMainLooper(),request->{
        Messenger reply=request.replyTo;
        if(reply==null)return true;
        try{
            if(request.what==PING){respond(reply,true,"TRANSLATION_WORKER_READY");return true;}
            if(request.what==CHECK){
                TorFrenchTranslator.isReady(this,(ready,detail)->respond(reply,ready,detail));
                return true;
            }
            if(request.what!=TRANSLATE){respond(reply,false,"UNKNOWN_TRANSLATION_OPERATION");return true;}
            String text=request.getData().getString("text","");
            if(text.length()>MAX_TEXT){respond(reply,false,"TRANSLATION_TEXT_TOO_LONG");return true;}
            if(text.trim().isEmpty()){respond(reply,false,"Aucun texte lisible sur cette page.");return true;}
            // Public EN/FR models must already exist; the worker never downloads
            // models and has no access to CHK's normal browser WebView.
            TorFrenchTranslator.translate(this,text,
                (partial,done,total)->progress(reply,partial,done,total),
                (ok,result)->respond(reply,ok,result));
        }catch(Exception|LinkageError error){
            respond(reply,false,"TRANSLATION_WORKER_ERROR: "+error.getClass().getSimpleName());
        }
        return true;
    }));
    @Override public IBinder onBind(Intent intent){return binder.getBinder();}
    private static void respond(Messenger to,boolean ok,String detail){
        try{
            Message m=Message.obtain(null,1);
            Bundle b=new Bundle();b.putBoolean("ok",ok);b.putString("detail",detail);
            m.setData(b);to.send(m);
        }catch(Exception ignored){}
    }
    private static void progress(Messenger to,String partial,int done,int total){
        try{
            Message m=Message.obtain(null,2);
            Bundle b=new Bundle();b.putString("partial",partial);
            b.putInt("done",done);b.putInt("total",total);
            m.setData(b);to.send(m);
        }catch(Exception ignored){}
    }
}
