package com.chk.agentbrowser;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Uploads owner-authorized CHK files into a site's HTML file input without
 * launching the Android picker (which cannot be opened reliably by a background
 * WebView's synthetic click). The transfer is explicitly scoped to the selected
 * field/host by the caller and never reads unrequested phone files.
 *
 * Uses small, serial, bounded chunks rather than putting the full media in one
 * evaluateJavascript call. Some sites can reject untrusted change events; in that
 * case the caller receives an honest failure when the HTML input is not populated.
 */
public final class BrowserJsUploader {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final int CHUNK=192*1024;
    private static final long MAX_TOTAL=32L*1024*1024;
    private BrowserJsUploader(){}

    public static void upload(Context context,WebView web,String selector,Uri[] uris,
                              AgentClient.ResultCallback result){
        if(Looper.myLooper()!=Looper.getMainLooper()){
            MAIN.post(()->upload(context,web,selector,uris,result));return;
        }
        if(uris==null||uris.length<1||uris.length>8){result.finish(false,"1 à 8 fichiers requis");return;}
        if(selector==null||selector.length()>340||selector.isEmpty()){result.finish(false,"Sélecteur invalide");return;}
        if(!result.isActive())return;
        if(!BrowserWebState.alive(web)){result.finish(false,"L'onglet est fermé. Rouvre la page avant de relancer l'importation.");return;}
        final String document=web.getUrl();
        final long documentVersion=BrowserWebState.documentVersion(web);
        final String token=java.util.UUID.randomUUID().toString();
        final String stage="var s=window.__chkStage;if(!s||s.token!=="+JSONObject.quote(token)+")return 'STAGE_LOST';";
        Context app=context.getApplicationContext();
        IO.execute(()->{
            boolean ok=false;String message;
            try{
                String init="(function(){try{if(location.href!=="+JSONObject.quote(document)+")return 'PAGE_CHANGED';var el=document.querySelector("+JSONObject.quote(selector)+");"
                    +"if(!el){var all=document.querySelectorAll('iframe');for(var i=0;i<all.length;i++){try{el=all[i].contentDocument.querySelector("+JSONObject.quote(selector)+");if(el)break;}catch(e){}}}"
                    +"if(!el||el.type!=='file'||el.disabled)return 'CHAMP_FICHIER_ABSENT';"
                    +"if("+uris.length+">1&&!el.multiple)return 'CHAMP_NON_MULTIPLE';"
                    +"window.__chkStage={token:"+JSONObject.quote(token)+",input:el,files:[]};return 'READY';"
                    +"}catch(e){return 'ERROR:'+e.message;}})()";
                String initialState=eval(web,init,result,documentVersion);
                if(!"READY".equals(initialState))throw new Exception(initialError(initialState));
                long total=0;
                for(int i=0;i<uris.length;i++){
                    Uri uri=uris[i];
                    String name=uri.getLastPathSegment()==null?"media-"+(i+1):uri.getLastPathSegment();
                    try(Cursor c=app.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                        if(c!=null&&c.moveToFirst()){
                            int n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            if(n>=0&&!c.isNull(n))name=c.getString(n);
                            int size=c.getColumnIndex(OpenableColumns.SIZE);
                            if(size>=0&&!c.isNull(size)&&c.getLong(size)>MAX_TOTAL-total)
                                throw new Exception("Import MCP limité à 32 Mo. Utilise la sélection CHK pour les fichiers plus volumineux.");
                        }
                    }
                    String mime=app.getContentResolver().getType(uri);
                    if(mime==null)mime="application/octet-stream";
                    String create="(function(){"+stage
                        +"s.files.push({name:"+JSONObject.quote(name)+",mime:"+JSONObject.quote(mime)+",chunks:[]});return 'READY';})()";
                    if(!"READY".equals(eval(web,create,result,documentVersion)))throw new Exception("La page ou le champ fichier a changé pendant le transfert. Relance l'importation.");
                    try(InputStream stream=app.getContentResolver().openInputStream(uri)){
                        if(stream==null)throw new Exception("Fichier non lisible");
                        byte[] buffer=new byte[CHUNK];
                        int n;
                        while((n=stream.read(buffer))!=-1){
                            if(n==0)continue;
                            total+=n;if(total>MAX_TOTAL)throw new Exception("Import MCP limité à 32 Mo, utiliser la sélection CHK pour les gros fichiers");
                            String part=Base64.encodeToString(buffer,0,n,Base64.NO_WRAP);
                            String append="(function(){try{"+stage
                                +"if(!s||!s.files["+i+"])return 'STAGE_LOST';"
                                +"var bin=atob("+JSONObject.quote(part)+");"
                                +"var b=new Uint8Array(bin.length);for(var j=0;j<bin.length;j++)b[j]=bin.charCodeAt(j);"
                                +"s.files["+i+"].chunks.push(b);return 'READY';"
                                +"}catch(e){return 'ERROR:'+e.message;}})()";
                            if(!"READY".equals(eval(web,append,result,documentVersion)))throw new Exception("Bloc média refusé par la page");
                        }
                    }
                }
                String finish="(function(){try{"+stage+"if(!s.input||!s.input.isConnected)return JSON.stringify({ok:false,error:'Champ fichier remplacé'});"
                    +"var w=s.input.ownerDocument.defaultView,dt=new w.DataTransfer();"
                    +"s.files.forEach(function(m){dt.items.add(new w.File(m.chunks,m.name,{type:m.mime}));});"
                    +"s.input.files=dt.files;"
                    +"s.input.dispatchEvent(new w.Event('input',{bubbles:true}));"
                    +"s.input.dispatchEvent(new w.Event('change',{bubbles:true}));"
                    +"var f=s.input.files;"
                    +"var r=JSON.stringify({ok:f.length===s.files.length,count:f.length,names:Array.from(f).map(function(x){return x.name;})});"
                    +"delete window.__chkStage;return r;}catch(e){delete window.__chkStage;return JSON.stringify({ok:false,error:e.message});}})()";
                JSONObject finalResult=new JSONObject(eval(web,finish,result,documentVersion));
                ok=finalResult.optBoolean("ok")&&finalResult.optInt("count")==uris.length;
                message=ok?"Fichiers CHK injectés dans le champ du site : "+finalResult.toString()
                    :"Le champ n'a pas confirmé les médias : "+finalResult.toString();
            }catch(Exception e){
                message="Transfert direct impossible : "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
                try{eval(web,"(function(){if(window.__chkStage&&window.__chkStage.token==="+JSONObject.quote(token)+")delete window.__chkStage;return 'OK';})()",null,-1);}catch(Exception ignored){}
            }
            boolean success=ok;String response=message;
            MAIN.post(()->result.finish(success,response));
        });
    }

    private static String initialError(String state){
        if("PAGE_CHANGED".equals(state))return "La page a changé pendant le transfert. Relance l'importation sur la bonne page.";
        if("CHAMP_NON_MULTIPLE".equals(state))return "Ce champ accepte un seul fichier. Sélectionne un seul média.";
        if("CHAMP_FICHIER_ABSENT".equals(state))return "Champ fichier absent ou désactivé. Ouvre d'abord le formulaire d'importation du site.";
        return "Champ fichier indisponible ou non compatible";
    }

    /** Evaluate on WebView UI thread; do not block it while awaiting results. */
    private static String eval(WebView web,String script,AgentClient.ResultCallback active,long documentVersion)throws Exception{
        CountDownLatch done=new CountDownLatch(1);
        AtomicReference<String> result=new AtomicReference<>("");
        AtomicReference<Exception> failure=new AtomicReference<>();
        MAIN.post(()->{
            try{
                if(!BrowserWebState.alive(web))throw new Exception("L'onglet a été fermé pendant le transfert.");
                if(active!=null&&!active.isActive())throw new Exception("Commande arrêtée ou expirée.");
                if(documentVersion>=0&&BrowserWebState.documentVersion(web)!=documentVersion)
                    throw new Exception("La page a changé pendant le transfert. Relance l'importation sur la bonne page.");
                web.evaluateJavascript(script,raw->{
                try{result.set(new JSONArray("["+raw+"]").getString(0));}
                catch(Exception e){result.set("");}
                done.countDown();
            });}catch(Exception e){failure.set(e);done.countDown();}
        });
        if(!done.await(20,TimeUnit.SECONDS))throw new Exception("Délai WebView dépassé");
        if(failure.get()!=null)throw failure.get();
        return result.get();
    }
}
