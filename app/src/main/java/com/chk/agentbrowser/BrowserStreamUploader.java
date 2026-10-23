package com.chk.agentbrowser;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-use, origin-bound local response streams. No listening socket, relay bytes or Base64. */
final class BrowserStreamUploader {
    private static final String PREFIX="/.chk-local-upload/";
    private static final Handler UI=new Handler(Looper.getMainLooper());
    private static final java.util.Map<WebView,String> ACTIVE=new java.util.WeakHashMap<>();
    private static final ConcurrentHashMap<String,Ticket> TICKETS=new ConcurrentHashMap<>();
    private BrowserStreamUploader(){}
    private static final class Ticket {
        final WeakReference<WebView> web;final Context app;final Uri uri;final String address;final long size;
        Ticket(WebView web,Context app,Uri uri,String address,long size){this.web=new WeakReference<>(web);this.app=app;this.uri=uri;this.address=address;this.size=size;}
    }
    static void revoke(WebView web){ACTIVE.remove(web);for(Map.Entry<String,Ticket> e:TICKETS.entrySet())if(e.getValue().web.get()==web)TICKETS.remove(e.getKey(),e.getValue());}
    private static WebResourceResponse denied(int status){
        return new WebResourceResponse("text/plain","UTF-8",status,"Unavailable",
            java.util.Collections.singletonMap("Cache-Control","no-store"),new ByteArrayInputStream(new byte[0]));
    }
    /** Called on the WebView IO thread. Never inspect a WebView or Activity here. */
    static WebResourceResponse intercept(WebView web,WebResourceRequest request){
        Uri url=request.getUrl();String path=url==null?null:url.getPath();
        if(path==null||!path.startsWith(PREFIX))return null;
        String key=path.substring(PREFIX.length());Ticket ticket=TICKETS.get(key);
        if(ticket==null||ticket.web.get()!=web||!ticket.address.equals(url.toString())||!"GET".equals(request.getMethod()))return denied(404);
        if(!TICKETS.remove(key,ticket))return denied(410);
        try{
            InputStream source=ticket.app.getContentResolver().openInputStream(ticket.uri);
            if(source==null)return denied(410);
            InputStream bounded=new FilterInputStream(source){
                long left=ticket.size;
                @Override public int read()throws java.io.IOException{if(left<=0)return -1;int b=super.read();if(b>=0)left--;return b;}
                @Override public int read(byte[] b,int off,int len)throws java.io.IOException{if(len==0)return 0;if(left<=0)return -1;int n=in.read(b,off,(int)Math.min(len,left));if(n>0)left-=n;return n;}
            };
            Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");
            headers.put("Content-Length",String.valueOf(ticket.size));headers.put("X-Content-Type-Options","nosniff");
            return new WebResourceResponse("application/octet-stream",null,200,"OK",headers,bounded);
        }catch(Exception e){return denied(410);}
    }
    static void upload(Context context,WebView web,String selector,Uri[] uris,AgentClient.ResultCallback cb){
        if(Looper.myLooper()!=Looper.getMainLooper()){UI.post(()->upload(context,web,selector,uris,cb));return;}
        if(!cb.isActive())return;
        if(ACTIVE.containsKey(web)){cb.finish(false,"Une sélection est déjà active sur cet onglet.");return;}
        ACTIVE.put(web,"active");
        final Context app=context.getApplicationContext();final String token=UUID.randomUUID().toString();
        final String page=web.getUrl();final long version=BrowserWebState.documentVersion(web);
        try{
            if(page==null||!page.startsWith("https://")||uris==null||uris.length<1||uris.length>8)throw new Exception("Page HTTPS et sélection locale requises.");
            JSONArray files=new JSONArray();long total=0;
            for(Uri uri:uris){
                if(uri==null||!"content".equals(uri.getScheme())||!(app.getPackageName()+".browserfiles").equals(uri.getAuthority()))throw new Exception("URI locale CHK requise.");
                String name;long size;
                try(Cursor c=app.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                    if(c==null||!c.moveToFirst())throw new Exception("Fichier illisible.");
                    name=c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));size=c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE));
                }
                if(size<=0||size>WorkspaceStore.MAX_IMPORT-total)throw new Exception("Sélection vide ou supérieure à 512 Mio.");
                total+=size;String key=UUID.randomUUID().toString();
                String address=new Uri.Builder().scheme("https").encodedAuthority(Uri.parse(page).getEncodedAuthority())
                    .encodedPath(PREFIX+key).build().toString();
                TICKETS.put(key,new Ticket(web,app,uri,address,size));
                String mime=app.getContentResolver().getType(uri);
                files.put(new JSONObject().put("url",address).put("name",name).put("size",size).put("mime",mime==null?"application/octet-stream":mime));
            }
            final AtomicBoolean finished=new AtomicBoolean();long deadline=SystemClock.elapsedRealtime()+90000L;
            // Native streams are exposed only to this document, once per URI. Chromium constructs
            // the File/Blob locally; Android and MCP never accumulate the video in a byte array.
            String script="(function(){try{"+BrowserScripts.finderForUpload()
                +"var input=deepFind("+JSONObject.quote(selector)+");if(!input||input.type!=='file'||input.disabled)throw Error('Champ fichier absent ou désactivé');"
                +"var files="+files+";if(files.length>1&&!input.multiple)throw Error('Champ limité à un fichier');"
                +"var s={token:"+JSONObject.quote(token)+",state:'reading',active:true,controller:new AbortController()};window.__chkStreamUpload=s;"
                +"(async function(){try{var w=input.ownerDocument.defaultView,dt=new w.DataTransfer();"
                +"for(var i=0;i<files.length;i++){var spec=files[i];var response=await fetch(spec.url,{cache:'no-store',signal:s.controller.signal});"
                +"if(!response.ok)throw Error('Flux local indisponible');var blob=await response.blob();"
                +"if(!s.active||window.__chkStreamUpload!==s)return;if(blob.size!==spec.size)throw Error('Taille locale non confirmée');"
                +"dt.items.add(new w.File([blob],spec.name,{type:spec.mime}));}"
                +"if(!s.active||window.__chkStreamUpload!==s||!input.isConnected)throw Error('Champ remplacé pendant la lecture');"
                +"input.files=dt.files;var selected=Array.from(input.files);if(selected.length!==files.length)throw Error('Sélection refusée');"
                +"for(var i=0;i<selected.length;i++)if(selected[i].name!==files[i].name||selected[i].size!==files[i].size)throw Error('Fichier reçu différent');"
                +"input.dispatchEvent(new w.Event('input',{bubbles:true,composed:true}));input.dispatchEvent(new w.Event('change',{bubbles:true,composed:true}));"
                +"s.receipt={ok:true,count:selected.length,names:selected.map(function(f){return f.name;}),sizes:selected.map(function(f){return f.size;}),transport:'local_response_stream',server_upload_verified:false};s.state='selected';"
                +"}catch(e){s.state='error';s.error='Sélection locale refusée par le document ou sa politique réseau';}})();return 'STARTED';"
                +"}catch(e){return 'REFUSED';}})()";
            web.evaluateJavascript(script,raw->{
                if(!"\"STARTED\"".equals(raw)){
                    revoke(web);if(finished.compareAndSet(false,true))cb.finish(false,"Champ fichier non préparé. Aucun fichier envoyé.");return;
                }
                Runnable poll=new Runnable(){
                    void finish(boolean ok,String message){if(finished.compareAndSet(false,true)){revoke(web);cb.finish(ok,message);}}
                    @Override public void run(){
                        if(finished.get())return;
                        if(!BrowserWebState.alive(web)||web.getUrl()==null||!Uri.parse(page).getEncodedAuthority().equals(Uri.parse(web.getUrl()).getEncodedAuthority())||version!=BrowserWebState.documentVersion(web)){
                            finish(false,"Document remplacé pendant la sélection. Vérifier le site avant de recommencer.");return;
                        }
                        if(!cb.isActive()||SystemClock.elapsedRealtime()>=deadline){
                            web.evaluateJavascript("(function(){var s=window.__chkStreamUpload;if(s&&s.token==="+JSONObject.quote(token)+"){s.active=false;s.controller.abort();}})()",null);
                            finish(false,"Sélection interrompue ou délai dépassé. Réception inconnue : vérifier le site avant de renvoyer.");return;
                        }
                        web.evaluateJavascript("(function(){var s=window.__chkStreamUpload;if(!s||s.token!=="+JSONObject.quote(token)+")return '{}';return JSON.stringify({state:s.state,receipt:s.receipt});})()",value->{
                            try{
                                JSONObject state=new JSONObject(new JSONArray("["+value+"]").getString(0));
                                if(state.optString("state").equals("selected")){finish(true,state.getJSONObject("receipt").toString());return;}
                                if(state.optString("state").equals("error")){finish(false,"Flux local refusé par la page. Aucune confirmation de sélection ; lire le formulaire avant de réessayer.");return;}
                                UI.postDelayed(this,250L);
                            }catch(Exception e){finish(false,"Réception locale non vérifiable. Inspecter la page avant de recommencer.");}
                        });
                    }
                };poll.run();
            });
        }catch(Exception e){revoke(web);cb.finish(false,e.getMessage()==null?"Flux local indisponible.":e.getMessage());}
    }
}
