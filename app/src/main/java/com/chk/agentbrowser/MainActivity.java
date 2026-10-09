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
    private EditText address;
    private ProgressBar progress;
    private WorkspacePanel workspace;
    private String activeSpace="browser";
    private boolean focusMode;
    private View fullScreenVideo;
    private WebChromeClient.CustomViewCallback fullScreenCallback;
    private final Runnable connectionTick=new Runnable(){public void run(){if(!isFinishing()){((TextView)findViewById(R.id.connection_badge)).setText(agentClient.isEnabled()?(agentClient.isAutonomous()?"● Agent actif · autonome":"● Agent actif · manuel"):"Ton espace de travail");transferHandler.postDelayed(this,3000);}}};
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
        container=findViewById(R.id.web_container);
        address=findViewById(R.id.address);progress=findViewById(R.id.progress);
        findViewById(R.id.back).setOnClickListener(v->{if(current!=null&&current.canGoBack())current.goBack();});
        findViewById(R.id.go).setOnClickListener(v->navigate(address.getText().toString()));
        findViewById(R.id.new_tab).setOnClickListener(v->{showSpace("browser");newTab(HOME);});
        address.setOnEditorActionListener((v,id,event)->{
            if(id==EditorInfo.IME_ACTION_GO || (event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER)){
                navigate(address.getText().toString());return true;
            }return false;
        });
        workspace=new WorkspacePanel(this,findViewById(R.id.workspace_container));
        findViewById(R.id.nav_browser).setOnClickListener(v->showSpace("browser"));
        findViewById(R.id.nav_files).setOnClickListener(v->showSpace("files"));
        findViewById(R.id.nav_notes).setOnClickListener(v->showSpace("notes"));
        findViewById(R.id.nav_video).setOnClickListener(v->startActivity(new Intent(this,VideoEditorActivity.class)));
        findViewById(R.id.menu).setOnClickListener(v->browserMenu());
        findViewById(R.id.tab_count).setOnClickListener(v->tabOverview());
        restoreTabs(state);
        showSpace(state==null?"browser":state.getString("space","browser"));
        String launch=getIntent().getStringExtra("open_url");if(launch!=null)navigate(launch);
        autoUpdate();
    }
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_SHORT).show();}
    private int dp(float p){return (int)(getResources().getDisplayMetrics().density*p+0.5f);}
    private void autoUpdate(){
        android.content.SharedPreferences pref=getSharedPreferences("update_monitor",MODE_PRIVATE);
        if(!pref.getBoolean("enabled",true))return;
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
        showSpace("browser");
        String url=normalize(text);
        if(url.isEmpty()){toast("Adresse non autorisée");return;}
        if(current!=null)current.loadUrl(url);
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);
        address.clearFocus();
    }
    private void newTab(String url){
        if(tabs.size()>=12){toast("Maximum 12 onglets : ferme un onglet pour en ouvrir un autre");return;}
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
        s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);
        s.setTextZoom(getSharedPreferences("mobile_ui",MODE_PRIVATE).getInt("text_zoom",100));
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
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap favicon){
                applyCookiePolicy(v,url);
            }
            @Override public void onPageFinished(WebView v,String url){
                applyCookiePolicy(v,url);
                if(url!=null)store.add("history",v.getTitle(),url);
                if(v==current){if(!address.hasFocus())address.setText(url);refreshTabs();saveTabs();}
            }
        });
        web.setOnLongClickListener(v->{WebView.HitTestResult hit=web.getHitTestResult();if(hit==null||hit.getExtra()==null||!(hit.getType()==WebView.HitTestResult.SRC_ANCHOR_TYPE||hit.getType()==WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE))return false;String link=hit.getExtra();if(!link.startsWith("https://"))return false;new AlertDialog.Builder(this).setTitle("Lien").setMessage(link).setItems(new String[]{"Ouvrir dans un nouvel onglet","Copier le lien","Partager"},(d,i)->{if(i==0)newTab(link);else if(i==1){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Lien",link));toast("Lien copié");}else startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,link),"Partager le lien"));}).show();return true;});
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onShowCustomView(View view,CustomViewCallback callback){
                if(fullScreenVideo!=null){callback.onCustomViewHidden();return;}
                fullScreenVideo=view;fullScreenCallback=callback;((ViewGroup)getWindow().getDecorView()).addView(view,new ViewGroup.LayoutParams(-1,-1));getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
            @Override public void onHideCustomView(){hideFullScreenVideo();}
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
        progress.setVisibility(View.GONE);refreshTabs();
    }
    private void close(WebView web){
        int i=tabs.indexOf(web);if(i<0)return;
        tabs.remove(i);
        if(current==web){
            container.removeView(web);current=null;
            if(!tabs.isEmpty())select(tabs.get(Math.max(0,i-1)));
        }
        web.destroy();if(tabs.isEmpty())newTab(HOME);refreshTabs();saveTabs();
    }
    private void refreshTabs(){
        ((TextView)findViewById(R.id.tab_count)).setText(String.valueOf(tabs.size()));
        findViewById(R.id.tab_count).setContentDescription("Voir les "+tabs.size()+" onglets");
        if(activeSpace.equals("browser"))((TextView)findViewById(R.id.space_title)).setText("CHK Browser");
    }
    private void toggleBookmark(){
        if(current==null||current.getUrl()==null)return;
        String url=current.getUrl();
        if(store.hasFavorite(url)){store.removeFavorite(url);toast("Favori retiré");}
        else{store.add("favorites",current.getTitle(),url);toast("Favori ajouté");}

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
        transferHandler.removeCallbacks(connectionTick);transferHandler.post(connectionTick);
        if(workspace!=null&&!activeSpace.equals("browser"))workspace.refresh();
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
                @Override public String sessionSource(){return "visible";}
                @Override public void onCommand(JSONObject command,AgentClient.ResultCallback callback){
                    confirmAgentCommand(command,callback);
                }
            });
        }
    }
    @Override protected void onPause(){
        saveTabs();transferHandler.removeCallbacks(connectionTick);
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
        if(WorkspaceCommands.handles(action))extra="Utiliser l’espace Fichiers et Notes : "+action;
        else switch(action){
            case "read_page":extra="Lire le texte actuellement visible dans la page";break;
            case "tabs":extra="Lister les onglets et leurs adresses";break;
            case "open_url":extra="Ouvrir : "+args.optString("url","");break;
            case "click_verified":extra="Cliquer une seule fois et vérifier le résultat";break;
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
        if(WorkspaceCommands.handles(action)){
            WorkspaceCommands.run(this,action,args,(ok,result)->{if(workspace!=null&&!activeSpace.equals("browser"))workspace.refresh();callback.finish(ok,result);});return;
        }
        showSpace("browser");
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
                            out.put("document_url",details.optString("document_url"));
                            out.put("session_source","visible");
                            out.put("visibility",details.optString("visibility"));
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
                case "click_verified":{
                    VerifiedClick.run(current,args,transferHandler,callback);
                    return;
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
    private void hideFullScreenVideo(){if(fullScreenVideo==null)return;((ViewGroup)getWindow().getDecorView()).removeView(fullScreenVideo);fullScreenVideo=null;getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);WebChromeClient.CustomViewCallback cb=fullScreenCallback;fullScreenCallback=null;if(cb!=null)cb.onCustomViewHidden();}
    private void showSpace(String space){
        boolean browser=space.equals("browser");
        findViewById(R.id.web_container).setVisibility(browser?View.VISIBLE:View.GONE);
        findViewById(R.id.workspace_container).setVisibility(browser?View.GONE:View.VISIBLE);
        findViewById(R.id.address_bar).setVisibility(browser?View.VISIBLE:View.GONE);
        findViewById(R.id.new_tab).setVisibility(browser?View.VISIBLE:View.GONE);
        findViewById(R.id.tab_count).setVisibility(browser?View.VISIBLE:View.GONE);
        ((TextView)findViewById(R.id.space_title)).setText(browser?"CHK Browser":space.equals("files")?"Fichiers":"Notes");
        String[] spaces={"browser","files","notes"};int[] ids={R.id.nav_browser,R.id.nav_files,R.id.nav_notes};
        for(int i=0;i<ids.length;i++){TextView v=findViewById(ids[i]);boolean selected=space.equals(spaces[i]);v.setTextColor(selected?MobileUi.ACCENT:MobileUi.MUTED);v.setBackground(selected?MobileUi.bg(this,MobileUi.CARD):null);v.setSelected(selected);v.setFocusable(true);}
        if(!browser&&workspace!=null&&!space.equals(activeSpace))workspace.show(space);
        activeSpace=space;
    }
    private void browserMenu(){
        if(!activeSpace.equals("browser")){new AlertDialog.Builder(this).setTitle("Espace de travail").setItems(new String[]{"Réglages","Revenir au navigateur"},(d,i)->{if(i==0)startActivity(new Intent(this,SettingsActivity.class));else showSpace("browser");}).show();return;}
        new AlertDialog.Builder(this).setTitle("Actions de la page").setItems(new String[]{"Actualiser","Page suivante",store.hasFavorite(current==null?"":current.getUrl())?"Retirer le favori":"Ajouter aux favoris","Favoris et historique","Rechercher dans la page","Partager le lien","Enregistrer la page dans Notes","Lire / copier le texte","Taille du texte","Mode lecture plein écran","Réglages"},(d,i)->{
            switch(i){case 0:if(current!=null)current.reload();break;case 1:if(current!=null&&current.canGoForward())current.goForward();break;case 2:toggleBookmark();break;case 3:library();break;case 4:findInPage();break;
                case 5:if(current!=null)startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,current.getUrl()),"Partager la page"));break;
                case 6:savePageNote();break;case 7:readPage();break;case 8:textSize();break;case 9:setFocusMode(true);break;case 10:startActivity(new Intent(this,SettingsActivity.class));break;}
        }).setNegativeButton("Fermer",null).show();
    }
    private void textSize(){new AlertDialog.Builder(this).setTitle("Taille du texte des pages").setItems(new String[]{"100 % · Standard","115 % · Confort","130 % · Grand","150 % · Très grand"},(d,i)->{int zoom=new int[]{100,115,130,150}[i];getSharedPreferences("mobile_ui",MODE_PRIVATE).edit().putInt("text_zoom",zoom).apply();for(WebView web:tabs)web.getSettings().setTextZoom(zoom);}).show();}
    private void findInPage(){if(current==null)return;WebView web=current;EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Mot à rechercher");LinearLayout box=MobileUi.column(this);box.setPadding(dp(16),dp(8),dp(16),dp(8));box.addView(input);TextView count=MobileUi.text(this,"Saisis un mot",14,MobileUi.MUTED);box.addView(count);web.setFindListener((index,total,done)->{if(done)count.setText(total==0?"Aucun résultat":(index+1)+" / "+total);});input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int aft){}public void onTextChanged(CharSequence s,int st,int b,int c){web.findAllAsync(s.toString());}public void afterTextChanged(android.text.Editable e){}});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Rechercher dans la page").setView(box).setPositiveButton("Suivant",null).setNeutralButton("Précédent",null).setNegativeButton("Fermer",null).create();dialog.setOnDismissListener(d->{web.clearMatches();web.setFindListener(null);});dialog.setOnShowListener(d->{dialog.getButton(-1).setOnClickListener(v->web.findNext(true));dialog.getButton(-3).setOnClickListener(v->web.findNext(false));});dialog.show();
    }
    private void setFocusMode(boolean enabled){focusMode=enabled;findViewById(R.id.space_nav).setVisibility(enabled?View.GONE:View.VISIBLE);findViewById(R.id.address_bar).setVisibility(enabled?View.GONE:View.VISIBLE);if(enabled)toast("Lecture agrandie · Retour pour retrouver les commandes");}
    private void savePageNote(){if(current==null)return;WebView web=current;String title=web.getTitle(),url=web.getUrl();web.evaluateJavascript("(document.body && document.body.innerText || '').slice(0,12000)",raw->{if(isFinishing())return;startActivity(new Intent(this,NoteEditorActivity.class).putExtra("title",title).putExtra("body",fromJavascript(raw)).putExtra("source",url));});}
    private void tabOverview(){
        android.app.Dialog dialog=new android.app.Dialog(this);LinearLayout box=MobileUi.column(this);box.setPadding(dp(16),dp(16),dp(16),dp(16));box.setBackgroundColor(Color.rgb(13,20,35));box.addView(MobileUi.text(this,"Tes onglets · "+tabs.size()+" / 12",22,MobileUi.TEXT));
        android.widget.ScrollView sc=new android.widget.ScrollView(this);box.addView(sc,new LinearLayout.LayoutParams(-1,0,1));LinearLayout rows=MobileUi.column(this);sc.addView(rows);
        for(WebView web:new ArrayList<>(tabs)){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);String title=web.getTitle()==null?"Nouvel onglet":web.getTitle();TextView label=MobileUi.action(this,(web==current?"●  ":"")+title+"\n"+(web.getUrl()==null?"":web.getUrl()));label.setMaxLines(3);label.setTextColor(web==current?MobileUi.ACCENT:MobileUi.TEXT);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));label.setOnClickListener(v->{select(web);showSpace("browser");dialog.dismiss();});TextView x=MobileUi.action(this,"×");x.setTextSize(24);x.setContentDescription("Fermer l’onglet "+title);row.addView(x,new LinearLayout.LayoutParams(dp(48),dp(64)));x.setOnClickListener(v->{close(web);dialog.dismiss();tabOverview();});rows.addView(row);}
        TextView add=MobileUi.action(this,"＋ Nouvel onglet");box.addView(add);add.setOnClickListener(v->{showSpace("browser");newTab(HOME);dialog.dismiss();address.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(address,InputMethodManager.SHOW_IMPLICIT);});TextView done=MobileUi.action(this,"Revenir à la page");box.addView(done);done.setOnClickListener(v->dialog.dismiss());dialog.setContentView(box);android.view.Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout(-1,Math.round(getResources().getDisplayMetrics().heightPixels*0.80f));w.setGravity(Gravity.BOTTOM);}dialog.show();if(w!=null)w.setLayout(-1,Math.round(getResources().getDisplayMetrics().heightPixels*0.80f));
    }
    private void saveTabs(){if(tabs.isEmpty())return;JSONArray urls=new JSONArray();for(WebView web:tabs){String url=web.getUrl();urls.put(url!=null&&url.startsWith("https://")?url:HOME);}getSharedPreferences("mobile_ui",MODE_PRIVATE).edit().putString("tabs",urls.toString()).putInt("active_tab",Math.max(0,tabs.indexOf(current))).apply();}
    private void restoreTabs(Bundle state){
        try{JSONArray urls=new JSONArray(state==null?getSharedPreferences("mobile_ui",MODE_PRIVATE).getString("tabs","[]"):state.getString("tabs","[]"));int selected=state==null?getSharedPreferences("mobile_ui",MODE_PRIVATE).getInt("active_tab",0):state.getInt("active_tab",0);
            for(int i=0;i<Math.min(12,urls.length());i++){String url=urls.optString(i,HOME);newTab(url.startsWith("https://")?url:HOME);}if(!tabs.isEmpty())select(tabs.get(Math.min(tabs.size()-1,Math.max(0,selected))));
        }catch(Exception ignored){}if(tabs.isEmpty())newTab(HOME);
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);saveTabs();out.putString("tabs",getSharedPreferences("mobile_ui",MODE_PRIVATE).getString("tabs","[]"));out.putInt("active_tab",Math.max(0,tabs.indexOf(current)));out.putString("space",activeSpace);}
    private static String fromJavascript(String raw){
        if(raw==null)return "";
        try{return new JSONArray("["+raw+"]").getString(0);}
        catch(Exception e){return "Résultat illisible";}
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(workspace!=null&&workspace.result(request,result,data))return;
        if(request==FILE_REQUEST&&fileCallback!=null){
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data));fileCallback=null;
        }
    }
    @Override public void onBackPressed(){
        if(fullScreenVideo!=null){hideFullScreenVideo();return;}
        if(focusMode){setFocusMode(false);return;}
        if(!activeSpace.equals("browser")){if(workspace.back())return;showSpace("browser");return;}
        if(current!=null&&current.canGoBack())current.goBack();else super.onBackPressed();
    }
    @Override protected void onDestroy(){
        super.onDestroy();
        if(workspace!=null)workspace.destroy();hideFullScreenVideo();
        for(WebView v:tabs){if(v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);v.destroy();}
        tabs.clear();
        if(fileCallback!=null)fileCallback.onReceiveValue(null);
        if(pendingUploadCallback!=null)pendingUploadCallback.finish(false,"Navigateur fermé pendant l'importation.");
        pendingUploadUris=null;pendingUploadCallback=null;pendingUploadWeb=null;
        transferHandler.removeCallbacksAndMessages(null);
    }
}
