package com.chk.agentbrowser;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
public class MainActivity extends Activity {
    private static final String HOME="https://www.google.com";
    private static final int FILE_REQUEST=221;
    private final List<WebView> tabs=new ArrayList<>();
    private WebView current;
    private BrowserStore store;
    private FrameLayout container;
    private LinearLayout strip;
    private EditText address;
    private ProgressBar progress;
    private TextView bookmark;
    private ValueCallback<Uri[]> fileCallback;
    private AgentClient agentClient;
    private Uri[] pendingUploadUris;
    private AgentClient.ResultCallback pendingUploadCallback;
    private String pendingUploadSelector="";
    private WebView pendingUploadWeb;
    private final android.os.Handler transferHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);setContentView(R.layout.activity_main);
        store=new BrowserStore(this);
        agentClient=AgentClient.get(this);
        container=findViewById(R.id.web_container);strip=findViewById(R.id.tab_strip);
        address=findViewById(R.id.address);progress=findViewById(R.id.progress);
        bookmark=findViewById(R.id.bookmark);
        findViewById(R.id.back).setOnClickListener(v->{if(current!=null&&current.canGoBack())current.goBack();});
        findViewById(R.id.forward).setOnClickListener(v->{if(current!=null&&current.canGoForward())current.goForward();});
        findViewById(R.id.go).setOnClickListener(v->navigate(address.getText().toString()));
        findViewById(R.id.reload).setOnClickListener(v->{if(current!=null)current.reload();});
        findViewById(R.id.new_tab).setOnClickListener(v->newTab(HOME));
        findViewById(R.id.settings).setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class)));
        findViewById(R.id.agent).setOnClickListener(v->readPage());
        findViewById(R.id.library).setOnClickListener(v->library());
        bookmark.setOnClickListener(v->toggleBookmark());
        address.setOnEditorActionListener((v,id,event)->{
            if(id==EditorInfo.IME_ACTION_GO || (event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER)){
                navigate(address.getText().toString());return true;
            }return false;
        });
        newTab(HOME);
        autoUpdate();
    }
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_SHORT).show();}
    private int dp(float p){return (int)(getResources().getDisplayMetrics().density*p+0.5f);}
    private void autoUpdate(){
        android.content.SharedPreferences pref=getSharedPreferences("update_monitor",MODE_PRIVATE);
        long now=System.currentTimeMillis();
        if(now-pref.getLong("last_check",0)<86400000L)return;
        pref.edit().putLong("last_check",now).apply();
        new UpdateManager(this).check((message,release)->{
            if(isFinishing()||isDestroyed()||release==null)return;
            new AlertDialog.Builder(this).setTitle("Nouvelle version "+release.version)
                .setMessage("Télécharger une mise à jour officielle depuis GitHub ?")
                .setNegativeButton("Plus tard",null)
                .setPositiveButton("Télécharger",(d,w)->{
                    try{new UpdateManager(this).download(release);toast("Téléchargement lancé. Une notification proposera l’installation sur ce téléphone.");}
                    catch(Exception e){toast(e.getMessage());}
                }).show();
        });
    }
    private String normalize(String value){
        String t=value==null?"":value.trim();
        if(t.isEmpty())return HOME;
        if(t.matches("(?i)^https?://.+"))return t;
        if(t.matches("(?i)^(file|javascript|data|intent|content):.*"))return "";
        if(!t.contains(" ")&&t.contains(".")&&!t.startsWith("."))return "https://"+t;
        try{return "https://www.google.com/search?q="+URLEncoder.encode(t,"UTF-8");}
        catch(Exception e){return HOME;}
    }
    private void navigate(String text){
        String url=normalize(text);
        if(url.isEmpty()){toast("Adresse non autorisée");return;}
        if(current!=null)current.loadUrl(url);
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);
        address.clearFocus();
    }
    private void newTab(String url){
        if(tabs.size()>=12){toast("Maximum 12 onglets");return;}
        WebView web=new WebView(this);web.setBackgroundColor(Color.WHITE);configure(web);
        tabs.add(web);select(web);web.loadUrl(url);
    }
    private void configure(WebView web){
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);s.setSupportMultipleWindows(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);
        if(Build.VERSION.SDK_INT>=26)s.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){
                String scheme=request.getUrl().getScheme();
                if("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme))return false;
                toast("Lien externe bloqué");return true;
            }
            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url){
                if(url.startsWith("https://")||url.startsWith("http://"))return false;
                toast("Lien non autorisé");return true;
            }
            @Override public void onPageFinished(WebView v,String url){
                if(url!=null)store.add("history",v.getTitle(),url);
                if(v==current){address.setText(url);updateBookmark();refreshTabs();}
            }
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView v,int percentage){
                if(v==current){progress.setVisibility(percentage>=100?View.GONE:View.VISIBLE);progress.setProgress(percentage);}
            }
            @Override public void onReceivedTitle(WebView v,String title){if(v==current)refreshTabs();}
            @Override public boolean onCreateWindow(WebView view,boolean isDialog,boolean isUserGesture,android.os.Message resultMsg){
                if(tabs.size()>=12){toast("Maximum 12 onglets");return false;}
                WebView popup=new WebView(MainActivity.this);popup.setBackgroundColor(Color.WHITE);configure(popup);
                tabs.add(popup);select(popup);
                WebView.WebViewTransport transport=(WebView.WebViewTransport)resultMsg.obj;
                transport.setWebView(popup);resultMsg.sendToTarget();
                return true;
            }
            @Override public void onCloseWindow(WebView window){if(tabs.contains(window))close(window);}
            @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> callback,FileChooserParams params){
                if(deliverPendingUpload(v,callback))return true;
                if(fileCallback!=null)fileCallback.onReceiveValue(null);
                fileCallback=callback;
                try{startActivityForResult(params.createIntent(),FILE_REQUEST);return true;}
                catch(Exception e){fileCallback=null;toast("Sélection de fichiers impossible");return false;}
            }
        });
        web.setDownloadListener((url,userAgent,contentDisposition,mimeType,length)->{
            if(!url.startsWith("https://")){toast("Téléchargement HTTPS uniquement");return;}
            new AlertDialog.Builder(this).setTitle("Télécharger ce fichier ?")
                .setMessage("Enregistrer dans les téléchargements de l'application ?")
                .setNegativeButton("Annuler",null)
                .setPositiveButton("Télécharger",(d,w)->startDownload(url,userAgent,mimeType)).show();
        });
    }
    private void startDownload(String url,String agent,String mime){
        try{
            String name=android.webkit.URLUtil.guessFileName(url,null,mime);
            DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url));
            request.setTitle(name);if(mime!=null)request.setMimeType(mime);
            request.addRequestHeader("User-Agent",agent==null?"CHK-Agent-Browser":agent);
            String cookies=CookieManager.getInstance().getCookie(url);
            if(cookies!=null)request.addRequestHeader("Cookie",cookies);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this,Environment.DIRECTORY_DOWNLOADS,name);
            ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
            toast("Téléchargement démarré");
        }catch(Exception e){toast("Échec du téléchargement");}
    }
    private void select(WebView web){
        if(!tabs.contains(web))return;
        if(current!=null)container.removeView(current);
        current=web;
        if(web.getParent() instanceof ViewGroup)((ViewGroup)web.getParent()).removeView(web);
        container.addView(web,new FrameLayout.LayoutParams(-1,-1));
        address.setText(web.getUrl()==null?"":web.getUrl());
        progress.setVisibility(View.GONE);updateBookmark();refreshTabs();
    }
    private void close(WebView web){
        int i=tabs.indexOf(web);if(i<0)return;
        tabs.remove(i);
        if(current==web){
            container.removeView(web);current=null;
            if(!tabs.isEmpty())select(tabs.get(Math.max(0,i-1)));
        }
        web.destroy();if(tabs.isEmpty())newTab(HOME);refreshTabs();
    }
    private void refreshTabs(){
        strip.removeAllViews();
        for(WebView web:tabs){
            LinearLayout item=new LinearLayout(this);item.setGravity(Gravity.CENTER_VERTICAL);
            item.setBackgroundColor(web==current?Color.rgb(39,76,104):Color.rgb(20,32,50));
            TextView label=new TextView(this);
            String title=web.getTitle();if(title==null||title.isEmpty())title="Nouvel onglet";
            label.setText(title.length()>19?title.substring(0,19)+"…":title);
            label.setTextColor(Color.WHITE);label.setTextSize(12);label.setPadding(dp(12),0,dp(5),0);
            item.addView(label,new LinearLayout.LayoutParams(dp(126),-1));
            TextView x=new TextView(this);x.setText("×");x.setTextSize(22);x.setGravity(Gravity.CENTER);x.setTextColor(Color.LTGRAY);
            item.addView(x,new LinearLayout.LayoutParams(dp(34),-1));
            item.setOnClickListener(v->select(web));x.setOnClickListener(v->close(web));
            strip.addView(item,new LinearLayout.LayoutParams(dp(160),-1));
        }
    }
    private void updateBookmark(){
        bookmark.setText(store.hasFavorite(current==null?"":current.getUrl())?"★ Favori":"☆ Favori");
    }
    private void toggleBookmark(){
        if(current==null||current.getUrl()==null)return;
        String url=current.getUrl();
        if(store.hasFavorite(url)){store.removeFavorite(url);toast("Favori retiré");}
        else{store.add("favorites",current.getTitle(),url);toast("Favori ajouté");}
        updateBookmark();
    }
    private void library(){
        new AlertDialog.Builder(this).setTitle("Bibliothèque")
        .setItems(new String[]{"Favoris","Historique"},(d,i)->showList(i==0?"favorites":"history"))
        .setNegativeButton("Fermer",null).show();
    }
    private void showList(String key){
        JSONArray rows=store.get(key);
        if(rows.length()==0){toast("Aucun élément");return;}
        String[] names=new String[rows.length()];
        for(int i=0;i<rows.length();i++){JSONObject o=rows.optJSONObject(i);names[i]=o==null?"":o.optString("title",o.optString("url"));}
        new AlertDialog.Builder(this).setTitle(key.equals("favorites")?"Favoris":"Historique")
        .setItems(names,(d,i)->{JSONObject o=rows.optJSONObject(i);if(o!=null&&current!=null)current.loadUrl(o.optString("url"));})
        .setNegativeButton("Fermer",null).show();
    }
    private void readPage(){
        if(current==null)return;
        new AlertDialog.Builder(this).setTitle("Lire la page")
        .setMessage("Extraire le texte de la page sur ce téléphone ? Aucun texte ne sera envoyé automatiquement.")
        .setNegativeButton("Annuler",null).setPositiveButton("Lire",(d,w)->
            current.evaluateJavascript("(document.body && document.body.innerText || '').slice(0,12000)",result->{
                String content;
                try{content=new JSONArray("["+result+"]").getString(0);}catch(Exception e){content="Lecture impossible";}
                final String copied=content;
                new AlertDialog.Builder(this).setTitle("Texte de la page").setMessage(content.isEmpty()?"Page vide":content)
                    .setPositiveButton("Copier",(dialog,which)->{
                        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("Texte de page",copied));toast("Texte copié");
                    }).setNegativeButton("Fermer",null).show();
            })
        ).show();
    }
    @Override protected void onResume(){
        super.onResume();
        if(agentClient!=null){
            AgentService.ensureRunning(this);
            if(agentClient.isAutonomous()&&current!=null){
                String latest=AgentService.latestBackgroundUrl();
                String open=current.getUrl();
                if(latest!=null&&latest.startsWith("https://")&&!latest.equals(open)){
                    current.loadUrl(latest);
                }
            }
            agentClient.attach(this,new AgentClient.CommandHandler(){
                @Override public String pageUrl(){return current==null||current.getUrl()==null?"":current.getUrl();}
                @Override public String pageTitle(){return current==null||current.getTitle()==null?"":current.getTitle();}
                @Override public void onCommand(JSONObject command,AgentClient.ResultCallback callback){
                    confirmAgentCommand(command,callback);
                }
            });
        }
    }
    @Override protected void onPause(){
        if(agentClient!=null) {
            if(current!=null)AgentService.syncVisible(this,current.getUrl());
            agentClient.detach(this);
        }
        super.onPause();
    }
    private void confirmAgentCommand(JSONObject command,AgentClient.ResultCallback callback){
        if(isFinishing()||current==null){
            callback.finish(false,"Navigateur indisponible.");return;
        }
        String action=command.optString("action","");
        JSONObject args=command.optJSONObject("args");
        if(args==null)args=new JSONObject();
        final JSONObject parameters=args;
        String extra="";
        switch(action){
            case "read_page":extra="Lire le texte actuellement visible dans la page";break;
            case "tabs":extra="Lister les onglets et leurs adresses";break;
            case "open_url":extra="Ouvrir : "+args.optString("url","");break;
            case "click":extra="Cliquer sur : "+args.optString("selector","");break;
            case "type":
                extra="Saisir dans "+args.optString("selector","")+
                    "\nTexte : "+args.optString("text","");break;
            case "scroll":extra="Faire défiler : "+args.optString("direction","");break;
            case "preview":case "screenshot":extra="Capturer un aperçu vérifiable de la page";break;
            case "select_option":extra="Choisir une option dans : "+args.optString("selector","");break;
            case "check":extra=(args.optBoolean("checked",true)?"Cocher : ":"Décocher : ")+args.optString("selector","");break;
            case "wait_for_element":extra="Attendre l'élément : "+args.optString("selector","");break;
            case "get_form":extra="Analyser les formulaires de la page";break;
            case "get_page_status":extra="Vérifier l'état réel de la page";break;
            case "switch_tab":extra="Basculer vers l'onglet demandé";break;
            case "close_popup":extra="Fermer l'onglet secondaire actuel";break;
            case "upload_file":extra="Importer des fichiers autorisés dans : "+args.optString("selector","");break;
            case "download_file":extra="Télécharger le document demandé et confirmer sa réception";break;
            default:callback.finish(false,"Commande MCP inconnue.");return;
        }
        if(agentClient.isAutonomous()){
            if(("preview".equals(action)||"screenshot".equals(action))&&!agentClient.isPreviewAllowed()) {
                callback.finish(false,"Active le partage d'aperçus dans les réglages.");
                return;
            }
            runAgentCommand(action,parameters,callback);
            return;
        }
        String page=current.getUrl()==null?"Aucune page":current.getUrl();
        new AlertDialog.Builder(this)
            .setTitle("Autoriser l'action MCP ?")
            .setMessage(extra+"\n\nPage actuelle : "+page+
                "\n\nVérifie l'action avant de l'autoriser. Aucun clic ou saisie n'est automatique.")
            .setNegativeButton("Refuser",(d,w)->callback.finish(false,"Action refusée sur le téléphone."))
            .setPositiveButton("Autoriser",(d,w)->runAgentCommand(action,parameters,callback))
            .setNeutralButton("Autoriser le travail autonome",(d,w)->{
                agentClient.setAutonomous(true);AgentService.refresh(this);
                runAgentCommand(action,parameters,callback);
            })
            .setOnCancelListener(d->callback.finish(false,"Action annulée sur le téléphone."))
            .show();
    }
    private void runAgentCommand(String action,JSONObject args,AgentClient.ResultCallback callback){
        if(!agentClient.isEnabled()){callback.finish(false,"MCP désactivé par le propriétaire.");return;}
        if(current==null){callback.finish(false,"Aucun onglet.");return;}
        try{
            switch(action){
                case "preview":case "screenshot":{
                    captureVisiblePreview(callback);return;
                }
                case "tabs":{
                    JSONArray list=new JSONArray();
                    for(WebView web:tabs){
                        JSONObject entry=new JSONObject();
                        entry.put("title",web.getTitle()==null?"":web.getTitle());
                        entry.put("url",web.getUrl()==null?"":web.getUrl());
                        list.put(entry);
                    }
                    callback.finish(true,list.toString());return;
                }
                case "read_page":{
                    final WebView web=current;
                    final String page=web.getUrl(),title=web.getTitle();
                    web.evaluateJavascript(BrowserScripts.readPage(),raw->{
                        String content=fromJavascript(raw);
                        try{
                            JSONObject out=new JSONObject();
                            out.put("url",page==null?"":page.substring(0,Math.min(500,page.length())));
                            out.put("title",title==null?"":title.substring(0,Math.min(150,title.length())));
                            JSONObject details=new JSONObject(content);
                            out.put("text",details.optString("text"));
                            out.put("elements",details.optJSONArray("elements"));
                            callback.finish(true,out.toString());
                        }catch(Exception ex){callback.finish(false,"Lecture impossible");}
                    });return;
                }
                case "open_url":{
                    String url=args.optString("url","");
                    Uri uri=Uri.parse(url);
                    String hostname=uri.getHost();
                    if(!"https".equalsIgnoreCase(uri.getScheme())||hostname==null||url.length()>2000
                        ||hostname.equalsIgnoreCase("localhost")||hostname.endsWith(".local")
                        ||hostname.startsWith("127.")||hostname.startsWith("192.168.")||hostname.startsWith("10.")){
                        callback.finish(false,"Adresse HTTPS invalide ou réseau local interdit.");return;
                    }
                    current.loadUrl(url);
                    callback.finish(true,"Navigation demandée vers "+url);return;
                }
                case "scroll":{
                    int offset=(int)(current.getHeight()*0.7f);
                    if("up".equals(args.optString("direction")))offset=-offset;
                    current.scrollBy(0,offset);
                    callback.finish(true,"Défilement effectué.");return;
                }
                case "click":{
                    String selector=args.optString("selector","");
                    current.evaluateJavascript(BrowserScripts.click(JSONObject.quote(selector)),raw->{
                        String result=fromJavascript(raw);
                        callback.finish("Clic effectué".equals(result),result);
                    });return;
                }
                case "type":{
                    String selector=args.optString("selector","");
                    String content=args.optString("text","");
                    if(content.length()>8000){callback.finish(false,"Texte trop long");return;}
                    String js=BrowserScripts.type(JSONObject.quote(selector),JSONObject.quote(content));
                    current.evaluateJavascript(js,raw->{
                        String result=fromJavascript(raw);
                        callback.finish("Saisie effectuée".equals(result),result);
                    });return;
                }
                case "select_option":{
                    String selector=args.optString("selector","");
                    String value=args.optString("value","");
                    String label=args.optString("label","");
                    int index=args.has("index")?args.optInt("index",-1):-1;
                    current.evaluateJavascript(BrowserScripts.select(JSONObject.quote(selector),JSONObject.quote(value),JSONObject.quote(label),index),raw->{
                        String result=fromJavascript(raw);
                        callback.finish(result.startsWith("Option sélectionnée"),result);
                    });return;
                }
                case "check":{
                    String selector=args.optString("selector","");
                    current.evaluateJavascript(BrowserScripts.check(JSONObject.quote(selector),args.optBoolean("checked",true)),raw->{
                        String result=fromJavascript(raw);
                        callback.finish(result.startsWith("État"),result);
                    });return;
                }
                case "wait_for_element":{
                    waitForElement(current,args.optString("selector",""),Math.min(30000,Math.max(500,args.optInt("timeout_ms",10000))),callback);return;
                }
                case "get_form":{
                    current.evaluateJavascript(BrowserScripts.getForm(),raw->callback.finish(true,fromJavascript(raw)));return;
                }
                case "get_page_status":{
                    current.evaluateJavascript(BrowserScripts.pageStatus(),raw->callback.finish(true,fromJavascript(raw)));return;
                }
                case "switch_tab":{
                    int index=args.optInt("index",-1);
                    String contains=args.optString("url_contains","");
                    WebView target=null;
                    if(index>=0&&index<tabs.size())target=tabs.get(index);
                    if(target==null&&!contains.isEmpty())for(WebView tab:tabs){String u=tab.getUrl();if(u!=null&&u.contains(contains)){target=tab;break;}}
                    if(target==null){callback.finish(false,"Onglet introuvable.");return;}
                    select(target);callback.finish(true,"Onglet actif : "+(target.getUrl()==null?"":target.getUrl()));return;
                }
                case "close_popup":{
                    if(tabs.size()<=1){callback.finish(false,"Aucun onglet secondaire à fermer.");return;}
                    WebView closing=current;close(closing);callback.finish(true,"Fenêtre secondaire fermée.");return;
                }
                case "upload_file":{
                    startMcpUpload(current,args,callback);return;
                }
                case "download_file":{
                    startMcpDownload(current,args,callback);return;
                }
                default:callback.finish(false,"Action inconnue");
            }
        }catch(Exception e){callback.finish(false,"Échec de l'action : "+e.getClass().getSimpleName());}
    }
    private boolean deliverPendingUpload(WebView web,ValueCallback<Uri[]> chooser){
        if(pendingUploadCallback==null||pendingUploadUris==null||pendingUploadWeb!=web)return false;
        Uri[] staged=pendingUploadUris;
        String selector=pendingUploadSelector;
        AgentClient.ResultCallback result=pendingUploadCallback;
        pendingUploadUris=null;pendingUploadCallback=null;pendingUploadWeb=null;pendingUploadSelector="";
        if(Build.VERSION.SDK_INT>=26){
            android.content.pm.PackageInfo pkg=WebView.getCurrentWebViewPackage();
            if(pkg!=null)for(Uri uri:staged)grantUriPermission(pkg.packageName,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        chooser.onReceiveValue(staged);
        transferHandler.postDelayed(()->web.evaluateJavascript(BrowserScripts.fileInfo(JSONObject.quote(selector)),raw->{
            try{
                JSONObject info=new JSONObject(fromJavascript(raw));
                boolean ok=info.optBoolean("ok")&&info.optInt("count")==staged.length;
                result.finish(ok,ok?"Importation confirmée par la page : "+info.toString():"Le site n'a pas confirmé tous les fichiers : "+info.toString());
            }catch(Exception e){result.finish(false,"Impossible de confirmer les fichiers reçus par le site.");}
        }),700L);
        return true;
    }
    private void startMcpUpload(WebView web,JSONObject args,AgentClient.ResultCallback callback){
        String selector=args.optString("selector","");
        JSONArray files=args.optJSONArray("files");
        if(selector.isEmpty()||selector.length()>=350){callback.finish(false,"Sélecteur de fichier invalide.");return;}
        String expected=args.optString("expected_host","");
        String host=web.getUrl()==null?null:Uri.parse(web.getUrl()).getHost();
        if(host==null||(!expected.isEmpty()&&!expected.equalsIgnoreCase(host))){
            callback.finish(false,"Destination d'importation différente de celle autorisée.");return;
        }
        BrowserTransferManager.prepareUploads(this,files,agentClient.deviceTokenForTransfers(),(ok,uris,message)->{
            if(!ok){callback.finish(false,message);return;}
            if(pendingUploadCallback!=null){callback.finish(false,"Une autre importation est déjà en cours.");return;}
            pendingUploadUris=uris;pendingUploadCallback=callback;pendingUploadSelector=selector;pendingUploadWeb=web;
            web.evaluateJavascript(BrowserScripts.fileClick(JSONObject.quote(selector)),raw->{
                String result=fromJavascript(raw);
                if(!"Sélecteur fichier ouvert".equals(result)&&pendingUploadCallback==callback){
                    pendingUploadUris=null;pendingUploadCallback=null;pendingUploadWeb=null;pendingUploadSelector="";
                    callback.finish(false,result);
                }
            });
            transferHandler.postDelayed(()->{
                if(pendingUploadCallback==callback){
                    pendingUploadUris=null;pendingUploadCallback=null;pendingUploadWeb=null;pendingUploadSelector="";
                    callback.finish(false,"Le site n'a pas ouvert son sélecteur de fichiers.");
                }
            },7000L);
        });
    }
    private void startMcpDownload(WebView web,JSONObject args,AgentClient.ResultCallback callback){
        String expected=args.optString("expected_host","");
        String host=web.getUrl()==null?null:Uri.parse(web.getUrl()).getHost();
        if(host==null||(!expected.isEmpty()&&!expected.equalsIgnoreCase(host))){
            callback.finish(false,"Destination de téléchargement différente de celle autorisée.");return;
        }
        String direct=args.optString("url","");
        if(!direct.isEmpty()){downloadResolved(web,direct,args.optString("mime_type",""),callback);return;}
        String selector=args.optString("selector","");
        if(selector.isEmpty()){callback.finish(false,"URL ou sélecteur de téléchargement requis.");return;}
        web.evaluateJavascript(BrowserScripts.linkUrl(JSONObject.quote(selector)),raw->{
            String url=fromJavascript(raw);
            if(url==null||url.isEmpty()){callback.finish(false,"Lien de téléchargement introuvable.");return;}
            downloadResolved(web,url,args.optString("mime_type",""),callback);
        });
    }
    private void downloadResolved(WebView web,String url,String mime,AgentClient.ResultCallback callback){
        if(!url.startsWith("https://")){callback.finish(false,"Téléchargement HTTPS uniquement.");return;}
        BrowserTransferManager.downloadToApp(this,url,web.getSettings().getUserAgentString(),mime,(ok,message)->callback.finish(ok,message));
    }
    private void waitForElement(WebView web,String selector,int timeout,AgentClient.ResultCallback callback){
        if(selector.isEmpty()||selector.length()>=350){callback.finish(false,"Sélecteur invalide.");return;}
        long start=android.os.SystemClock.elapsedRealtime();
        Runnable[] poll=new Runnable[1];
        poll[0]=()->web.evaluateJavascript(BrowserScripts.exists(JSONObject.quote(selector)),raw->{
            if("true".equals(raw)){callback.finish(true,"Élément disponible.");return;}
            if(android.os.SystemClock.elapsedRealtime()-start>=timeout){callback.finish(false,"Élément absent après "+timeout+" ms.");return;}
            transferHandler.postDelayed(poll[0],350L);
        });
        poll[0].run();
    }

    private void captureVisiblePreview(AgentClient.ResultCallback callback) {
        if(!agentClient.isPreviewAllowed()){
            callback.finish(false,"Active le partage des captures dans Réglages.");
            return;
        }
        if(current==null||current.getWidth()<=0||current.getHeight()<=0){
            callback.finish(false,"Le navigateur n'est pas encore prêt.");return;
        }
        try {
            int w=current.getWidth(),h=current.getHeight();
            float scale=Math.min(1f,540f/w);
            // Limit height to avoid excessive memory use or traffic on cellular networks.
            scale=Math.min(scale,960f/h);
            int width=Math.max(1,Math.round(w*scale)),height=Math.max(1,Math.round(h*scale));
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(
                width,height,android.graphics.Bitmap.Config.RGB_565);
            android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            canvas.scale(scale,scale);
            current.draw(canvas);
            byte[] bytes=null;
            int quality=52;
            for(int attempt=0;attempt<4;attempt++){
                java.io.ByteArrayOutputStream stream=new java.io.ByteArrayOutputStream();
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,quality,stream);
                bytes=stream.toByteArray();
                if(bytes.length<=150000)break;
                quality-=12;
            }
            bitmap.recycle();
            if(bytes==null||bytes.length>150000){
                callback.finish(false,"Capture trop volumineuse.");return;
            }
            JSONObject result=new JSONObject();
            result.put("url",current.getUrl()==null?"":current.getUrl());
            result.put("jpeg_base64",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP));
            callback.finish(true,result.toString());
        }catch(Exception e){
            callback.finish(false,"Capture impossible : "+e.getClass().getSimpleName());
        }
    }
    private static String fromJavascript(String raw){
        if(raw==null)return "";
        try{return new JSONArray("["+raw+"]").getString(0);}
        catch(Exception e){return "Résultat illisible";}
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==FILE_REQUEST&&fileCallback!=null){
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data));fileCallback=null;
        }
    }
    @Override public void onBackPressed(){
        if(current!=null&&current.canGoBack())current.goBack();else super.onBackPressed();
    }
    @Override protected void onDestroy(){
        super.onDestroy();
        for(WebView v:tabs){if(v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);v.destroy();}
        tabs.clear();
        if(fileCallback!=null)fileCallback.onReceiveValue(null);
        if(pendingUploadCallback!=null)pendingUploadCallback.finish(false,"Navigateur fermé pendant l'importation.");
        pendingUploadUris=null;pendingUploadCallback=null;pendingUploadWeb=null;
        transferHandler.removeCallbacksAndMessages(null);
    }
}
