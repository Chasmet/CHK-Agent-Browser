package com.chk.agentbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;

/**
 * A separate Android WebView owned by the foreground MCP service.
 *
 * This allows opted-in, in-app browser navigation when MainActivity is paused
 * (e.g., while ChatGPT is in the foreground). Cookies and WebView storage are
 * shared with the visible browser. It is not screen automation for other apps.
 * Android may suspend it under Doze, extreme power savings, or a powered-off phone.
 */
public final class BackgroundBrowser implements AgentClient.CommandHandler {
    private static final String PREF = "browser_mcp_v1";
    private static final String DEFAULT_URL = "https://www.google.com/";
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WebView web;
    private String currentUrl = "";
    private String currentTitle = "";
    private boolean loading = false;
    private boolean closed;
    private Runnable awaiting;

    public BackgroundBrowser(Context context) {
        app = context.getApplicationContext();
        web = new WebView(app);
        WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri link=request.getUrl();
                return link==null || !isPublicHttps(link);
            }
            @Override public void onPageFinished(WebView view,String url) {
                loading=false;
                currentUrl=url==null?"":url;
                currentTitle=view.getTitle()==null?"":view.getTitle();
                if(awaiting!=null){Runnable next=awaiting;awaiting=null;main.post(next);}
            }
        });
        web.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(540,android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(960,android.view.View.MeasureSpec.EXACTLY));
        web.layout(0,0,540,960);
    }

    public void syncFromVisible(String url) {
        if(closed)return;
        if(url==null||!isPublicHttps(Uri.parse(url)))return;
        SharedPreferences p=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        p.edit().putString("last_page",url).apply();
        if(url.equals(currentUrl) && !loading)return;
        loading=true;web.loadUrl(url);
    }
    public void restore() {
        String url=app.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .getString("last_page",DEFAULT_URL);
        if(!isPublicHttps(Uri.parse(url)))url=DEFAULT_URL;
        if(currentUrl.isEmpty()&&!loading) {
            loading=true;web.loadUrl(url);
        }
    }
    private static boolean isPublicHttps(Uri uri) {
        if(uri==null||!"https".equalsIgnoreCase(uri.getScheme()))return false;
        String host=uri.getHost();
        if(host==null||uri.getUserInfo()!=null)return false;
        host=host.toLowerCase(java.util.Locale.ROOT);
        if(host.equals("localhost")||host.endsWith(".local")||host.endsWith(".internal")
            ||host.equals("0.0.0.0")||host.equals("::1")
            ||host.startsWith("127.")||host.startsWith("10.")
            ||host.startsWith("192.168.")||host.startsWith("169.254."))return false;
        if(host.startsWith("172.")) {
            String[] parts=host.split("\\.");
            try{int i=Integer.parseInt(parts[1]);if(i>=16&&i<=31)return false;}
            catch(Exception ignored){}
        }
        return true;
    }
    public String pageUrl(){return currentUrl;}
    public String pageTitle(){return currentTitle;}
    public void destroy(){
        closed=true;
        main.removeCallbacksAndMessages(null);
        web.stopLoading();
        web.destroy();
    }
    private void afterLoad(Runnable task) {
        if(loading){
            awaiting=task;
            main.postDelayed(()->{
                if(awaiting==task){awaiting=null;task.run();}
            },8000);
        }else task.run();
    }
    @Override public void onCommand(JSONObject command,AgentClient.ResultCallback callback) {
        if(closed){callback.finish(false,"Moteur en arrière-plan arrêté.");return;}
        if(!AgentClient.get(app).isAutonomous()){
            callback.finish(false,"Le mode autonome doit être activé dans les réglages.");
            return;
        }
        String action=command.optString("action","");
        JSONObject args=command.optJSONObject("args");
        if(args==null)args=new JSONObject();
        final JSONObject parameters=args;
        if(action.equals("open_url")) {
            String url=args.optString("url","");
            if(url.length()>2000||!isPublicHttps(Uri.parse(url))){
                callback.finish(false,"URL HTTPS publique requise.");return;
            }
            loading=true;
            web.loadUrl(url);
            currentUrl=url;
            callback.finish(true,"Navigation demandée vers "+url);
            return;
        }
        restore();
        afterLoad(()->execute(action,parameters,callback));
    }
    private static String jsResult(String value) {
        try{return new JSONArray("["+value+"]").getString(0);}
        catch(Exception ignored){return "";}
    }
    private void execute(String action,JSONObject args,AgentClient.ResultCallback cb) {
        if(closed||!AgentClient.get(app).isAutonomous()){
            cb.finish(false,"Session autonome arrêtée par le propriétaire.");return;
        }
        try {
            switch(action) {
                case "tabs":{
                    JSONArray tabs=new JSONArray();
                    JSONObject entry=new JSONObject();
                    entry.put("title",web.getTitle()==null?"":web.getTitle());
                    entry.put("url",web.getUrl()==null?"":web.getUrl());
                    tabs.put(entry);
                    cb.finish(true,tabs.toString());return;
                }
                case "read_page":{
                    web.evaluateJavascript(BrowserScripts.readPage(),raw->{
                        try{
                            JSONObject page=new JSONObject();
                            String url=web.getUrl()==null?"":web.getUrl();
                            page.put("url",url.substring(0,Math.min(500,url.length())));
                            String title=web.getTitle()==null?"":web.getTitle();
                            page.put("title",title.substring(0,Math.min(150,title.length())));
                            JSONObject details=new JSONObject(jsResult(raw));
                            page.put("text",details.optString("text"));
                            page.put("elements",details.optJSONArray("elements"));
                            cb.finish(true,page.toString());
                        }catch(Exception ignored){cb.finish(false,"Lecture de page impossible.");}
                    });return;
                }
                case "scroll":{
                    int offset=(int)(web.getHeight()*0.7f);
                    if("up".equals(args.optString("direction")))offset=-offset;
                    web.scrollBy(0,offset);
                    cb.finish(true,"Défilement effectué.");return;
                }
                case "click":{
                    String selector=args.optString("selector","");
                    if(selector.isEmpty()||selector.length()>=350){cb.finish(false,"Sélecteur invalide.");return;}
                    String javascript="(function(){try{var el=document.querySelector("+JSONObject.quote(selector)+");"
                        +"if(!el)return 'Élément introuvable';el.click();return 'Clic effectué';}"
                        +"catch(e){return 'Erreur : '+e.message;}})()";
                    web.evaluateJavascript(javascript,raw->{
                        String text=jsResult(raw);
                        cb.finish("Clic effectué".equals(text),text);
                    });return;
                }
                case "type":{
                    String selector=args.optString("selector","");
                    String value=args.optString("text","");
                    if(selector.isEmpty()||selector.length()>=350||value.length()>8000) {
                        cb.finish(false,"Champ ou texte invalide.");return;
                    }
                    String javascript=BrowserScripts.type(JSONObject.quote(selector),JSONObject.quote(value));
                    web.evaluateJavascript(javascript,raw->{
                        String text=jsResult(raw);
                        cb.finish("Saisie effectuée".equals(text),text);
                    });return;
                }
                case "preview":preview(cb);return;
                default:cb.finish(false,"Commande inconnue.");
            }
        }catch(Exception e){cb.finish(false,"Commande impossible : "+e.getClass().getSimpleName());}
    }

    private void preview(AgentClient.ResultCallback cb) {
        if(!AgentClient.get(app).isPreviewAllowed()) {
            cb.finish(false,"Autorise l'aperçu visuel dans les réglages Android.");return;
        }
        try {
            // Detached WebView snapshots are best-effort: some GPU-rendered sites may be blank.
            int width=web.getWidth(),height=web.getHeight();
            if(width<=0||height<=0){cb.finish(false,"WebView non dessiné.");return;}
            Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.RGB_565);
            Canvas canvas=new Canvas(bitmap);
            canvas.drawColor(android.graphics.Color.WHITE);
            web.draw(canvas);
            byte[] bytes=null;
            int quality=55;
            for(int attempt=0;attempt<4;attempt++) {
                ByteArrayOutputStream output=new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG,quality,output);
                bytes=output.toByteArray();
                if(bytes.length<=150000)break;
                quality-=12;
            }
            bitmap.recycle();
            if(bytes==null||bytes.length>150000){
                cb.finish(false,"Capture trop volumineuse.");return;
            }
            JSONObject out=new JSONObject();
            out.put("url",web.getUrl()==null?"":web.getUrl());
            out.put("jpeg_base64",Base64.encodeToString(bytes,Base64.NO_WRAP));
            cb.finish(true,out.toString());
        }catch(Exception e){cb.finish(false,"Capture non disponible : "+e.getClass().getSimpleName());}
    }
}
