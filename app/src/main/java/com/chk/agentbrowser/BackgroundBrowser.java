package com.chk.agentbrowser;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Owner-authorized background WebView engine. It shares Android WebView cookies/storage
 * with the visible browser and never controls other applications.
 */
public final class BackgroundBrowser implements AgentClient.CommandHandler {
    private static final String PREF="browser_mcp_v1";
    private static final String DEFAULT_URL="https://www.google.com/";
    private final Context app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<WebView> tabs=new ArrayList<>();
    private WebView current;
    private boolean loading;
    private boolean closed;
    private Runnable awaiting;
    private Uri[] pendingUploadUris;
    private AgentClient.ResultCallback pendingUploadCallback;
    private String pendingUploadSelector="";
    private WebView pendingUploadWeb;

    public BackgroundBrowser(Context context){
        app=context.getApplicationContext();
        current=createWebView();
        tabs.add(current);
    }

    private WebView createWebView(){
        WebView web=new WebView(app);
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);s.setSupportMultipleWindows(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setLoadsImagesAutomatically(true);s.setBlockNetworkImage(false);
        if(Build.VERSION.SDK_INT>=26)s.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                Uri link=request.getUrl();return link==null||!isPublicHttps(link);
            }
            @Override public void onPageStarted(WebView view,String url,Bitmap favicon){
                applyCookiePolicy(view,url);
                if(view==current)loading=true;
            }
            @Override public void onPageFinished(WebView view,String url){
                applyCookiePolicy(view,url);
                if(view==current){
                    loading=false;
                    saveLast(url);
                    if(awaiting!=null){Runnable r=awaiting;awaiting=null;main.post(r);}
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onCreateWindow(WebView view,boolean isDialog,boolean isUserGesture,android.os.Message resultMsg){
                if(closed||tabs.size()>=8)return false;
                WebView popup=createWebView();tabs.add(popup);current=popup;
                WebView.WebViewTransport transport=(WebView.WebViewTransport)resultMsg.obj;
                transport.setWebView(popup);resultMsg.sendToTarget();return true;
            }
            @Override public void onCloseWindow(WebView window){closeTab(window);}
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){
                return deliverPendingUpload(view,callback);
            }
        });
        web.measure(android.view.View.MeasureSpec.makeMeasureSpec(540,android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(960,android.view.View.MeasureSpec.EXACTLY));
        web.layout(0,0,540,960);
        return web;
    }

    private void applyCookiePolicy(WebView web,String url){
        boolean allow=false;
        try{
            String host=Uri.parse(url==null?"":url).getHost();
            if(host!=null){
                host=host.toLowerCase(java.util.Locale.ROOT);
                allow=host.equals("google.com")||host.endsWith(".google.com")
                    ||host.endsWith(".googleusercontent.com")||host.endsWith(".gstatic.com");
            }
        }catch(Exception ignored){}
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,allow);
    }

    private void saveLast(String url){
        if(url!=null&&isPublicHttps(Uri.parse(url)))
            app.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString("last_page",url).apply();
    }
    public void syncFromVisible(String url){
        if(closed||url==null||!isPublicHttps(Uri.parse(url)))return;
        saveLast(url);
        if(current==null){current=createWebView();tabs.add(current);}
        String active=current.getUrl();
        if(!url.equals(active)){loading=true;current.loadUrl(url);}
    }
    public void restore(){
        if(closed)return;
        String url=app.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString("last_page",DEFAULT_URL);
        if(!isPublicHttps(Uri.parse(url)))url=DEFAULT_URL;
        if(current==null){current=createWebView();tabs.add(current);}
        if(current.getUrl()==null&&!loading){loading=true;current.loadUrl(url);}
    }
    private static boolean isPublicHttps(Uri uri){
        if(uri==null||!"https".equalsIgnoreCase(uri.getScheme()))return false;
        String host=uri.getHost();if(host==null||uri.getUserInfo()!=null)return false;
        host=host.toLowerCase(java.util.Locale.ROOT);
        if(host.equals("localhost")||host.endsWith(".local")||host.endsWith(".internal")
            ||host.equals("0.0.0.0")||host.equals("::1")||host.startsWith("127.")
            ||host.startsWith("10.")||host.startsWith("192.168.")||host.startsWith("169.254."))return false;
        if(host.startsWith("172.")){String[] p=host.split("\\.");try{int i=Integer.parseInt(p[1]);if(i>=16&&i<=31)return false;}catch(Exception ignored){}}
        return true;
    }
    public String pageUrl(){return current==null||current.getUrl()==null?"":current.getUrl();}
    public String pageTitle(){return current==null||current.getTitle()==null?"":current.getTitle();}

    public void destroy(){
        closed=true;main.removeCallbacksAndMessages(null);
        for(WebView web:new ArrayList<>(tabs)){try{web.stopLoading();web.destroy();}catch(Exception ignored){}}
        tabs.clear();current=null;
        if(pendingUploadCallback!=null)pendingUploadCallback.finish(false,"Moteur autonome arrêté pendant l'importation.");
        pendingUploadCallback=null;pendingUploadUris=null;pendingUploadWeb=null;
    }
    private void closeTab(WebView web){
        if(web==null||!tabs.contains(web))return;
        int index=tabs.indexOf(web);tabs.remove(web);try{web.destroy();}catch(Exception ignored){}
        if(current==web)current=tabs.isEmpty()?null:tabs.get(Math.max(0,Math.min(index-1,tabs.size()-1)));
        if(current==null&&!closed){current=createWebView();tabs.add(current);restore();}
    }
    private void afterLoad(Runnable task){
        if(loading){
            awaiting=task;
            main.postDelayed(()->{if(awaiting==task){awaiting=null;task.run();}},10000L);
        }else task.run();
    }

    @Override public void onCommand(JSONObject command,AgentClient.ResultCallback callback){
        if(closed){callback.finish(false,"Moteur en arrière-plan arrêté.");return;}
        if(!AgentClient.get(app).isAutonomous()){callback.finish(false,"Le mode autonome doit être activé dans les réglages.");return;}
        String action=command.optString("action","");
        JSONObject args=command.optJSONObject("args");if(args==null)args=new JSONObject();
        final JSONObject parameters=args;
        if("open_url".equals(action)){
            String url=args.optString("url","");
            if(url.length()>2000||!isPublicHttps(Uri.parse(url))){callback.finish(false,"URL HTTPS publique requise.");return;}
            if(current==null){current=createWebView();tabs.add(current);}
            loading=true;current.loadUrl(url);saveLast(url);
            callback.finish(true,"Navigation demandée vers "+url);return;
        }
        restore();
        afterLoad(()->execute(action,parameters,callback));
    }
    private static String jsResult(String value){
        try{return new JSONArray("["+value+"]").getString(0);}catch(Exception ignored){return "";}
    }

    private void execute(String action,JSONObject args,AgentClient.ResultCallback cb){
        if(closed||current==null||!AgentClient.get(app).isEnabled()||!AgentClient.get(app).isAutonomous()){
            cb.finish(false,"Session autonome arrêtée par le propriétaire.");return;
        }
        try{
            switch(action){
                case "tabs":{
                    JSONArray out=new JSONArray();for(WebView tab:tabs){JSONObject e=new JSONObject();e.put("title",tab.getTitle()==null?"":tab.getTitle());e.put("url",tab.getUrl()==null?"":tab.getUrl());out.put(e);}
                    cb.finish(true,out.toString());return;
                }
                case "read_page":{
                    WebView web=current;String page=pageUrl(),title=pageTitle();
                    web.evaluateJavascript(BrowserScripts.readPage(),raw->{
                        try{JSONObject result=new JSONObject();result.put("url",page);result.put("title",title);JSONObject details=new JSONObject(jsResult(raw));result.put("text",details.optString("text"));result.put("elements",details.optJSONArray("elements"));result.put("document_url",details.optString("document_url"));result.put("session_source","background");result.put("visibility",details.optString("visibility"));result.put("forms",details.optInt("forms"));result.put("ready",details.optString("ready"));cb.finish(true,result.toString());}
                        catch(Exception e){cb.finish(false,"Lecture de page impossible.");}
                    });return;
                }
                case "scroll":{
                    int offset=(int)(current.getHeight()*0.7f);if("up".equals(args.optString("direction")))offset=-offset;
                    current.scrollBy(0,offset);cb.finish(true,"Défilement effectué.");return;
                }
                case "click_verified":{
                    VerifiedClick.run(current,args,main,cb);
                    return;
                }
                case "click":{
                    String selector=args.optString("selector","");
                    current.evaluateJavascript(BrowserScripts.click(JSONObject.quote(selector)),raw->{String text=jsResult(raw);cb.finish("Clic effectué".equals(text),text);});return;
                }
                case "type":{
                    String selector=args.optString("selector",""),value=args.optString("text","");
                    if(value.length()>8000){cb.finish(false,"Texte trop long.");return;}
                    current.evaluateJavascript(BrowserScripts.type(JSONObject.quote(selector),JSONObject.quote(value)),raw->{String text=jsResult(raw);cb.finish("Saisie effectuée".equals(text),text);});return;
                }
                case "select_option":{
                    String selector=args.optString("selector",""),value=args.optString("value",""),label=args.optString("label","");
                    int index=args.has("index")?args.optInt("index",-1):-1;
                    current.evaluateJavascript(BrowserScripts.select(JSONObject.quote(selector),JSONObject.quote(value),JSONObject.quote(label),index),raw->{String text=jsResult(raw);cb.finish(text.startsWith("Option sélectionnée"),text);});return;
                }
                case "check":{
                    current.evaluateJavascript(BrowserScripts.check(JSONObject.quote(args.optString("selector","")),args.optBoolean("checked",true)),raw->{String text=jsResult(raw);cb.finish(text.startsWith("État"),text);});return;
                }
                case "wait_for_element":waitForElement(current,args.optString("selector",""),Math.min(30000,Math.max(500,args.optInt("timeout_ms",10000))),cb);return;
                case "get_form":current.evaluateJavascript(BrowserScripts.getForm(),raw->cb.finish(true,jsResult(raw)));return;
                case "get_page_status":current.evaluateJavascript(BrowserScripts.pageStatus(),raw->cb.finish(true,jsResult(raw)));return;
                case "switch_tab":{
                    int index=args.optInt("index",-1);String contains=args.optString("url_contains","");WebView target=null;
                    if(index>=0&&index<tabs.size())target=tabs.get(index);
                    if(target==null&&!contains.isEmpty())for(WebView tab:tabs){String u=tab.getUrl();if(u!=null&&u.contains(contains)){target=tab;break;}}
                    if(target==null){cb.finish(false,"Onglet introuvable.");return;}current=target;cb.finish(true,"Onglet actif : "+pageUrl());return;
                }
                case "close_popup":{
                    if(tabs.size()<=1){cb.finish(false,"Aucune fenêtre secondaire à fermer.");return;}WebView closing=current;closeTab(closing);cb.finish(true,"Fenêtre secondaire fermée.");return;
                }
                case "upload_file":startMcpUpload(current,args,cb);return;
                case "download_file":startMcpDownload(current,args,cb);return;
                case "preview":case "screenshot":preview(cb);return;
                default:cb.finish(false,"Commande inconnue.");
            }
        }catch(Exception e){cb.finish(false,"Commande impossible : "+e.getClass().getSimpleName());}
    }

    private boolean deliverPendingUpload(WebView web,ValueCallback<Uri[]> chooser){
        if(pendingUploadCallback==null||pendingUploadUris==null||pendingUploadWeb!=web)return false;
        Uri[] staged=pendingUploadUris;String selector=pendingUploadSelector;AgentClient.ResultCallback result=pendingUploadCallback;
        pendingUploadCallback=null;pendingUploadUris=null;pendingUploadWeb=null;pendingUploadSelector="";
        if(Build.VERSION.SDK_INT>=26){
            android.content.pm.PackageInfo pkg=WebView.getCurrentWebViewPackage();
            if(pkg!=null)for(Uri uri:staged)app.grantUriPermission(pkg.packageName,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        chooser.onReceiveValue(staged);
        main.postDelayed(()->web.evaluateJavascript(BrowserScripts.fileInfo(JSONObject.quote(selector)),raw->{
            try{JSONObject info=new JSONObject(jsResult(raw));boolean ok=info.optBoolean("ok")&&info.optInt("count")==staged.length;result.finish(ok,ok?"Importation confirmée par la page : "+info.toString():"Le site n'a pas confirmé tous les fichiers : "+info.toString());}
            catch(Exception e){result.finish(false,"Impossible de confirmer les fichiers reçus par le site.");}
        }),700L);
        return true;
    }
    private void startMcpUpload(WebView web,JSONObject args,AgentClient.ResultCallback cb){
        String selector=args.optString("selector","");JSONArray files=args.optJSONArray("files");
        if(selector.isEmpty()||selector.length()>=350){cb.finish(false,"Sélecteur de fichier invalide.");return;}
        String expected=args.optString("expected_host","");String host=web.getUrl()==null?null:Uri.parse(web.getUrl()).getHost();
        if(host==null||(!expected.isEmpty()&&!expected.equalsIgnoreCase(host))){cb.finish(false,"Destination d'importation différente de celle autorisée.");return;}
        BrowserTransferManager.prepareUploads(app,files,AgentClient.get(app).deviceTokenForTransfers(),(ok,uris,message)->{
            if(!ok){cb.finish(false,message);return;}if(pendingUploadCallback!=null){cb.finish(false,"Une autre importation est déjà en cours.");return;}
            pendingUploadUris=uris;pendingUploadCallback=cb;pendingUploadSelector=selector;pendingUploadWeb=web;
            web.evaluateJavascript(BrowserScripts.fileClick(JSONObject.quote(selector)),raw->{String result=jsResult(raw);if(!"Sélecteur fichier ouvert".equals(result)&&pendingUploadCallback==cb){pendingUploadUris=null;pendingUploadCallback=null;pendingUploadWeb=null;pendingUploadSelector="";cb.finish(false,result);}});
            main.postDelayed(()->{if(pendingUploadCallback==cb){pendingUploadUris=null;pendingUploadCallback=null;pendingUploadWeb=null;pendingUploadSelector="";cb.finish(false,"Le site n'a pas ouvert son sélecteur de fichiers.");}},7000L);
        });
    }
    private void startMcpDownload(WebView web,JSONObject args,AgentClient.ResultCallback cb){
        String expected=args.optString("expected_host","");String host=web.getUrl()==null?null:Uri.parse(web.getUrl()).getHost();
        if(host==null||(!expected.isEmpty()&&!expected.equalsIgnoreCase(host))){cb.finish(false,"Destination de téléchargement différente de celle autorisée.");return;}
        String direct=args.optString("url","");
        if(!direct.isEmpty()){downloadResolved(web,direct,args.optString("mime_type",""),cb);return;}
        String selector=args.optString("selector","");if(selector.isEmpty()){cb.finish(false,"URL ou sélecteur requis.");return;}
        web.evaluateJavascript(BrowserScripts.linkUrl(JSONObject.quote(selector)),raw->{String url=jsResult(raw);if(url.isEmpty())cb.finish(false,"Lien de téléchargement introuvable.");else downloadResolved(web,url,args.optString("mime_type",""),cb);});
    }
    private void downloadResolved(WebView web,String url,String mime,AgentClient.ResultCallback cb){
        if(!url.startsWith("https://")){cb.finish(false,"Téléchargement HTTPS uniquement.");return;}
        BrowserTransferManager.downloadToApp(app,url,web.getSettings().getUserAgentString(),mime,(ok,message)->cb.finish(ok,message));
    }
    private void waitForElement(WebView web,String selector,int timeout,AgentClient.ResultCallback cb){
        if(selector.isEmpty()||selector.length()>=350){cb.finish(false,"Sélecteur invalide.");return;}
        long start=android.os.SystemClock.elapsedRealtime();Runnable[] poll=new Runnable[1];
        poll[0]=()->web.evaluateJavascript(BrowserScripts.exists(JSONObject.quote(selector)),raw->{if("true".equals(raw)){cb.finish(true,"Élément disponible.");return;}if(android.os.SystemClock.elapsedRealtime()-start>=timeout){cb.finish(false,"Élément absent après "+timeout+" ms.");return;}main.postDelayed(poll[0],350L);});
        poll[0].run();
    }

    private void preview(AgentClient.ResultCallback cb){
        if(!AgentClient.get(app).isPreviewAllowed()){cb.finish(false,"Autorise l'aperçu visuel dans les réglages Android.");return;}
        try{
            WebView web=current;int width=web.getWidth(),height=web.getHeight();if(width<=0||height<=0){cb.finish(false,"WebView non dessiné.");return;}
            Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.RGB_565);Canvas canvas=new Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE);web.draw(canvas);
            byte[] bytes=null;int quality=55;
            for(int attempt=0;attempt<4;attempt++){ByteArrayOutputStream output=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,quality,output);bytes=output.toByteArray();if(bytes.length<=150000)break;quality-=12;}
            bitmap.recycle();if(bytes==null||bytes.length>150000){cb.finish(false,"Capture trop volumineuse.");return;}
            JSONObject out=new JSONObject();out.put("url",pageUrl());out.put("jpeg_base64",Base64.encodeToString(bytes,Base64.NO_WRAP));cb.finish(true,out.toString());
        }catch(Exception e){cb.finish(false,"Capture non disponible : "+e.getClass().getSimpleName());}
    }
}
