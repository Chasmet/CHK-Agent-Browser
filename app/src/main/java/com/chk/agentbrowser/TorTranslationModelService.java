package com.chk.agentbrowser;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import com.google.mlkit.nl.translate.*;

/**
 * Main-process-only public language model installer for the isolated Tor reader.
 *
 * The input protocol accepts ONLY two numeric messages, no Bundle fields.
 * Specifically it does not accept an URL, HTML, page text, cookies or Tor data.
 * Downloading a public EN/FR model uses Android's usual network connection;
 * translation itself runs locally inside the :tor process, after installation.
 */
public final class TorTranslationModelService extends Service {
    static final int PREPARE_MODEL=1;
    static final int PING=2;
    private final Messenger binder=new Messenger(new Handler(Looper.getMainLooper(),request->{
        final Messenger reply=request.replyTo;
        if(reply==null)return true;
        if(request.what==PING){respond(reply,true,"MODEL_SERVICE_READY");return true;}
        if(request.what!=PREPARE_MODEL){respond(reply,false,"MODEL_COMMAND_UNSUPPORTED");return true;}
        try{
            TranslatorOptions options=new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.FRENCH).build();
            final Translator translator=Translation.getClient(options);
            translator.downloadModelIfNeeded()
                .addOnSuccessListener(ignored->{
                    translator.close();
                    respond(reply,true,"MODELE_FR_INSTALLE");
                })
                .addOnFailureListener(error->{
                    translator.close();
                    respond(reply,false,"MODELE_FR_ECHEC: "+error.getMessage());
                });
        }catch(Exception error){respond(reply,false,"MODELE_FR_ECHEC: "+error.getMessage());}
        return true;
    }));
    @Override public IBinder onBind(Intent intent){return binder.getBinder();}
    private static void respond(Messenger reply,boolean ok,String detail){
        try{
            Message out=Message.obtain(null,1);
            Bundle b=new Bundle();
            b.putBoolean("ok",ok);
            b.putString("detail",detail);
            out.setData(b);
            reply.send(out);
        }catch(Exception ignored){}
    }
}
