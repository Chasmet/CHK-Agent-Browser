package com.chk.agentbrowser;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Native, zero-copy transfer of explicitly requested CHK workspace files to WebView.
 * The website receives Android content:// URIs through WebChromeClient's normal
 * onShowFileChooser callback. Neither the file bytes nor the owner's cookies pass
 * through MCP/Render or through an evaluateJavascript base64 buffer.
 *
 * Only an exact, inspected file input on the current HTTP/HTTPS document is eligible.
 * A bounded native touch on its visible chooser control starts the site's real
 * chooser request. If the renderer suppresses that request, never claim success:
 * small files retain the old JS fallback; large files return a precise error.
 */
public final class BrowserNativeUploader {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long CHOOSER_TIMEOUT_MS = 4500L;
    private static final long JS_FALLBACK_LIMIT = 32L * 1024 * 1024;
    private static final Map<WebView, Pending> ACTIVE = new WeakHashMap<>();

    private BrowserNativeUploader() {}

    private static final class Pending {
        final Context context;
        final WebView web;
        final String selector, document, token;
        final long documentVersion;
        final Uri[] files;
        final long totalBytes;
        final JSONArray manifest;
        final AgentClient.ResultCallback cb;

        Pending(Context context, WebView web, String selector, Uri[] files,
                long totalBytes, JSONArray manifest, AgentClient.ResultCallback cb) {
            this.context=context.getApplicationContext();
            this.web=web;
            this.selector=selector;
            this.files=files;
            this.totalBytes=totalBytes;this.manifest=manifest;
            this.cb=cb;
            this.document=web.getUrl();
            this.documentVersion=BrowserWebState.documentVersion(web);
            this.token=java.util.UUID.randomUUID().toString();
        }

        boolean valid() {
            return cb.isActive() && BrowserWebState.alive(web)
                && document.equals(web.getUrl())
                && documentVersion==BrowserWebState.documentVersion(web);
        }
    }

    public static void upload(Context context, WebView web, String selector,
                              Uri[] uris, AgentClient.ResultCallback cb) {
        if (Looper.myLooper()!=Looper.getMainLooper()) {
            MAIN.post(()->upload(context,web,selector,uris,cb));
            return;
        }
        if (uris==null || uris.length<1 || uris.length>8
                || selector==null || selector.isEmpty() || selector.length()>340) {
            cb.finish(false,"Sélecteur ou fichiers invalides.");return;
        }
        if (!BrowserWebState.alive(web) || !cb.isActive()
                || web.getUrl()==null || !BrowserPolicy.webUrl(web.getUrl())) {
            cb.finish(false,"La page HTTP/HTTPS de destination est indisponible.");return;
        }
        if (ACTIVE.containsKey(web)) {
            cb.finish(false,"Un autre fichier est déjà en cours de sélection.");return;
        }
        AgentVision.ensureMeasured(web);
        long total=0;JSONArray manifest=new JSONArray();
        try {
            String ownAuthority=context.getPackageName()+".browserfiles";
            for (Uri uri:uris) {
                if (uri==null || !"content".equals(uri.getScheme())
                        || !ownAuthority.equals(uri.getAuthority()))
                    throw new Exception("Seuls les fichiers CHK explicitement préparés sont autorisés.");
                try(android.database.Cursor c=context.getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.SIZE},null,null,null)) {
                    if(c==null || !c.moveToFirst())throw new Exception("Fichier local illisible.");
                    int ix=c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if(ix<0 || c.isNull(ix) || c.getLong(ix)<=0)
                        throw new Exception("Taille de fichier absente.");
                    long size=c.getLong(ix);
                    if(size>WorkspaceStore.MAX_IMPORT-total)
                        throw new Exception("La sélection dépasse 6 Go.");
                    int ni=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if(ni<0||c.isNull(ni))throw new Exception("Nom du fichier absent.");
                    manifest.put(new JSONObject().put("name",c.getString(ni)).put("size",size));
                    total+=size;
                }
            }
        } catch(Exception e) {
            cb.finish(false,"Sélection CHK refusée : "+e.getMessage());return;
        }
        final Pending pending=new Pending(context,web,selector,uris,total,manifest,cb);
        web.evaluateJavascript(BrowserScripts.nativeFileTarget(JSONObject.quote(selector)),raw->{
            if(!pending.valid()){
                if(cb.isActive())cb.finish(false,"Page modifiée pendant la sélection.");return;
            }
            try {
                JSONObject target=new JSONObject(new JSONArray("["+raw+"]").getString(0));
                if(!target.optBoolean("ok")) {
                    String problem=target.optString("error","Champ de fichier absent.");
                    if(pending.totalBytes<=JS_FALLBACK_LIMIT) {
                        BrowserJsUploader.upload(pending.context,web,selector,uris,cb);
                    } else if(pending.totalBytes<=64L*1024*1024){BrowserStreamUploader.upload(pending.context,web,selector,uris,cb);}
                    else cb.finish(false,"Sélecteur natif non identifié : "+problem
                        +". Aucun fichier envoyé. Ouvre le dialogue d'importation du site.");
                    return;
                }
                if(uris.length>1 && !target.optBoolean("multiple"))
                    throw new Exception("Ce champ n'accepte qu'un seul fichier.");
                float x=(float)(target.getDouble("x")*web.getWidth()/target.getDouble("viewportWidth"));
                float y=(float)(target.getDouble("y")*web.getHeight()/target.getDouble("viewportHeight"));
                if(!(x>0 && x<web.getWidth() && y>0 && y<web.getHeight()))
                    throw new Exception("Bouton d'importation hors écran.");
                ACTIVE.put(web,pending);
                long down=SystemClock.uptimeMillis();
                MotionEvent touchDown=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,y,0);
                MotionEvent touchUp=MotionEvent.obtain(down,down+65,MotionEvent.ACTION_UP,x,y,0);
                touchDown.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                touchUp.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try {
                    web.dispatchTouchEvent(touchDown);
                    web.dispatchTouchEvent(touchUp);
                } finally {touchDown.recycle();touchUp.recycle();}
                MAIN.postDelayed(()->{
                    if(ACTIVE.get(web)!=pending)return;
                    ACTIVE.remove(web);
                    if(!cb.isActive())return;
                    if(!pending.valid()){cb.finish(false,"Page remplacée pendant l'importation.");return;}
                    if(pending.totalBytes<=JS_FALLBACK_LIMIT)
                        BrowserJsUploader.upload(pending.context,web,selector,uris,cb);
                    else if(pending.totalBytes<=64L*1024*1024)
                        BrowserStreamUploader.upload(pending.context,web,selector,uris,cb);
                    else
                        cb.finish(false,"Le site n'a pas ouvert son sélecteur natif. Aucun fichier n'a été envoyé. "
                            +"Le transfert local de "+pending.totalBytes+" octets nécessite un champ fichier activable dans cette WebView.");
                },CHOOSER_TIMEOUT_MS);
            }catch(Exception e){
                if(cb.isActive())cb.finish(false,"Sélection native impossible : "+e.getMessage());
            }
        });
    }

    /** Called before the ordinary Android picker in both visible/background WebViews. */
    public static boolean offer(WebView web, ValueCallback<Uri[]> chooser,
                                WebChromeClient.FileChooserParams params) {
        Pending p=ACTIVE.remove(web);
        if(p==null)return false;
        if(!p.valid()){
            chooser.onReceiveValue(null);
            if(p.cb.isActive())p.cb.finish(false,"La page a changé avant l'envoi du fichier.");
            return true;
        }
        if(p.files.length>1 && params.getMode()!=WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE){
            chooser.onReceiveValue(null);
            p.cb.finish(false,"Le site n'accepte pas plusieurs fichiers.");return true;
        }
        try {
            if(Build.VERSION.SDK_INT>=26){
                android.content.pm.PackageInfo pkg=WebView.getCurrentWebViewPackage();
                if(pkg!=null)for(Uri uri:p.files)
                    p.context.grantUriPermission(pkg.packageName,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            chooser.onReceiveValue(p.files);
            MAIN.postDelayed(()->{
                if(!p.cb.isActive())return;
                if(!p.valid()){p.cb.finish(false,"Le document a changé après la sélection.");return;}
                web.evaluateJavascript(BrowserScripts.fileReceipt(JSONObject.quote(p.selector)),raw->{
                    if(!p.cb.isActive())return;
                    try {
                        JSONObject info=new JSONObject(new JSONArray("["+raw+"]").getString(0));
                        boolean ok=info.optBoolean("ok")&&info.optInt("count")==p.files.length;
                        JSONArray names=info.optJSONArray("names"),sizes=info.optJSONArray("sizes");
                        ok=ok&&names!=null&&sizes!=null&&names.length()==p.files.length&&sizes.length()==p.files.length;
                        if(ok)for(int i=0;i<p.files.length;i++)
                            ok=ok&&p.manifest.getJSONObject(i).getString("name").equals(names.optString(i))
                                &&p.manifest.getJSONObject(i).getLong("size")==sizes.optLong(i,-1);
                        p.cb.finish(ok,ok?"Fichiers sélectionnés dans le champ HTML (transfert serveur encore à vérifier) : "+info
                            :"Sélection transmise au WebView, mais champ non confirmé : "+info);
                    }catch(Exception e){
                        p.cb.finish(false,"La sélection a été remise au WebView, mais sa réception reste non confirmée.");
                    }
                });
            },1100L);
        }catch(Exception e){
            chooser.onReceiveValue(null);
            p.cb.finish(false,"Échec du sélecteur natif : "+e.getMessage());
        }
        return true;
    }
}
