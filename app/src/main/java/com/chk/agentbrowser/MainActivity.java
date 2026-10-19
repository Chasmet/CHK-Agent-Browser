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
import android.widget.ImageView;
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
    private final java.util.Map<WebView,String> pageErrors=new java.util.WeakHashMap<>();
    private BrowserStore store;
    private FrameLayout container;
    private EditText address;
    private ProgressBar progress;
    private WorkspacePanel workspace;
    private String activeSpace="browser";
    private boolean focusMode;
    private boolean bottomNavCollapsed;
    private boolean explicitPageOnResume;
    private BrowserHome home;
    private View fullScreenVideo;
    private WebChromeClient.CustomViewCallback fullScreenCallback;
    private final Runnable connectionTick=new Runnable(){public void run(){if(!isFinishing()&&!isDestroyed()){TextView badge=findViewById(R.id.connection_badge);badge.setText(agentClient.shortConnectionStatus());badge.setVisibility(agentClient.isEnabled()?View.VISIBLE:View.GONE);transferHandler.postDelayed(this,3000);}}};
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
        home=new BrowserHome(this,address,store,url->navigate(url));
        findViewById(R.id.back).setOnClickListener(v->{home.newStart();navigate(HOME);});
        findViewById(R.id.voice_search).setOnClickListener(v->{try{startActivityForResult(new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT,"Rechercher sur Google"),812);}catch(Exception e){toast("Recherche vocale indisponible sur ce téléphone");}});
        findViewById(R.id.go).setOnClickListener(v->navigate(address.getText().toString()));
        findViewById(R.id.new_tab).setOnClickListener(v->{showSpace("browser");newTab(HOME);});
        address.setOnEditorActionListener((v,id,event)->{
            if(id==EditorInfo.IME_ACTION_GO || (event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER)){
                navigate(address.getText().toString());return true;
            }return false;
        });
        workspace=new WorkspacePanel(this,findViewById(R.id.workspace_container));
        workspace.restoreState(state);
        findViewById(R.id.browser_error).setOnClickListener(v->{
            if(BrowserWebState.alive(current))current.loadUrl(BrowserWebState.url(current));
        });
        findViewById(R.id.nav_browser).setOnClickListener(v->showSpace("browser"));
        findViewById(R.id.nav_files).setOnClickListener(v->showSpace("files"));
        findViewById(R.id.nav_notes).setOnClickListener(v->showSpace("notes"));
        findViewById(R.id.nav_video).setOnClickListener(v->startActivity(new Intent(this,StudioProjectsActivity.class)));
         findViewById(R.id.nav_cut).setOnClickListener(v->startActivity(new Intent(this,CutStudioHomeActivity.class)));
        findViewById(R.id.menu).setOnClickListener(v->browserMenu());
        findViewById(R.id.translate_page).setOnClickListener(v->translateMenu());
        findViewById(R.id.tab_count).setOnClickListener(v->tabOverview());
        findViewById(R.id.tab_count).setOnLongClickListener(v->{newTab(HOME);return true;});
        findViewById(R.id.connection_badge).setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class)));
        restoreTabs(state);
        showSpace(state==null?"browser":state.getString("space","browser"));
        if(state==null)openIntent(getIntent());
        autoUpdate();
    }
    private void openIntent(Intent intent){
        String launch=intent.getStringExtra("open_url"),cut=intent.getStringExtra("cutstudio_url");
        intent.removeExtra("open_url");intent.removeExtra("cutstudio_url");
        if(launch!=null&&launch.startsWith("https://")){explicitPageOnResume=true;navigate(launch);}
        if(cut!=null&&cut.startsWith("https://")){
            explicitPageOnResume=true;showSpace("browser");
            if(tabs.size()<12)newTab(cut);else navigate(cut);
        }
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);openIntent(intent);}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_SHORT).show();}
    private int dp(float p){return (int)(getResources().getDisplayMetrics().density*p+0.5f);}
    private void autoUpdate(){
        android.content.SharedPreferences pref=getSharedPreferences("update_monitor",MODE_PRIVATE);
        if(!pref.getBoolean("enabled",true))return;
        long now=System.currentTimeMillis();
        if(now-pref.getLong("last_check",0)<86400000L||now-pref.getLong("last_attempt",0)<600000L)return;
        pref.edit().putLong("last_attempt",now).apply();
        new UpdateManager(this).check((message,release)->{
            if(isFinishing()||isDestroyed()||release==null)return;
            new AgentDialog.Builder(this).setTitle("Nouvelle version "+release.version)
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
        if(home!=null&&HOME.equals(url))home.newStart();
        WebView web=new WebView(this);web.setBackgroundColor(Color.WHITE);configure(web);
        BrowserWebState.remember(web,url);
        tabs.add(web);select(web);
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
                BrowserWebState.remember(v,url);pageErrors.remove(v);updatePageError();
                applyCookiePolicy(v,url);if(v==current&&home!=null)home.page(url);
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,android.webkit.WebResourceError error){
                if(request.isForMainFrame())pageError(view,"Page inaccessible. Vérifie la connexion. Toucher pour réessayer.");
            }
            @Override public boolean onRenderProcessGone(WebView view,android.webkit.RenderProcessGoneDetail detail){
                recoverRenderer(view);return true;
            }
            @Override public void onPageFinished(WebView v,String url){
                if(!BrowserWebState.alive(v))return;
                applyCookiePolicy(v,url);
                if(url!=null)store.add("history",v.getTitle(),url);
                if(v==current){if(home!=null)home.page(url);if(!address.hasFocus())address.setText(home!=null&&home.isHome(url)?"google.com":url);refreshTabs();saveTabs();}
            }
        });
        web.setOnScrollChangeListener((view,x,y,oldX,oldY)->{
            if(web!=current||focusMode||!"browser".equals(activeSpace))return;
            int delta=y-oldY;
            if(delta>8&&y>260)setBottomNavCollapsed(true);
            else if(delta< -8||y<48)setBottomNavCollapsed(false);
        });
        web.setOnLongClickListener(v->{WebView.HitTestResult hit=web.getHitTestResult();if(hit==null||hit.getExtra()==null||!(hit.getType()==WebView.HitTestResult.SRC_ANCHOR_TYPE||hit.getType()==WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE))return false;String link=hit.getExtra();if(!link.startsWith("https://"))return false;new AgentDialog.Builder(this).setTitle("Lien").setMessage(link).setItems(new String[]{"Ouvrir dans un nouvel onglet","Copier le lien","Partager"},(d,i)->{if(i==0)newTab(link);else if(i==1){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Lien",link));toast("Lien copié");}else startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,link),"Partager le lien"));}).show();return true;});
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onJsAlert(WebView web,String url,String message,android.webkit.JsResult result){
                new AgentDialog.Builder(MainActivity.this).setTitle("Message du site").setMessage(message)
                    .setPositiveButton("OK",(d,w)->result.confirm()).setOnCancelListener(d->result.cancel()).show();return true;
            }
            @Override public boolean onJsConfirm(WebView web,String url,String message,android.webkit.JsResult result){
                new AgentDialog.Builder(MainActivity.this).setTitle("Confirmation du site").setMessage(message)
                    .setPositiveButton("Confirmer",(d,w)->result.confirm()).setNegativeButton("Annuler",(d,w)->result.cancel()).setOnCancelListener(d->result.cancel()).show();return true;
            }
            @Override public boolean onJsPrompt(WebView web,String url,String message,String value,android.webkit.JsPromptResult result){
                EditText input=new EditText(MainActivity.this);input.setText(value);input.setHint(message);
                new AgentDialog.Builder(MainActivity.this).setTitle("Saisie du site").setMessage(message).setView(input)
                    .setPositiveButton("Confirmer",(d,w)->result.confirm(input.getText().toString())).setNegativeButton("Annuler",(d,w)->result.cancel()).setOnCancelListener(d->result.cancel()).show();return true;
            }
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
                if(BrowserNativeUploader.offer(v,callback,params))return true;
                if(deliverPendingUpload(v,callback))return true;
                if(fileCallback!=null)fileCallback.onReceiveValue(null);
                fileCallback=callback;
                Runnable androidPicker=()->{
                    try{startActivityForResult(params.createIntent(),FILE_REQUEST);}
                    catch(Exception e){
                        if(fileCallback==callback){fileCallback=null;callback.onReceiveValue(null);}
                        toast("Sélection Android indisponible");
                    }
                };
                try{
                    WorkspaceMediaChooser.show(MainActivity.this,params,uris->{
                        if(fileCallback==callback){fileCallback=null;callback.onReceiveValue(uris);}
                    },androidPicker);
                    return true;
                }catch(Exception e){androidPicker.run();return true;}
            }
        });
        web.setDownloadListener((url,userAgent,contentDisposition,mimeType,length)->{
            if(!url.startsWith("https://")){toast("Téléchargement HTTPS uniquement");return;}
            new AgentDialog.Builder(this).setTitle("Télécharger ce fichier ?")
                .setMessage("Enregistrer dans les téléchargements de l'application ?")
                .setNegativeButton("Annuler",null)
                .setPositiveButton("Télécharger",(d,w)->startDownload(url,userAgent,contentDisposition,mimeType)).show();
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
    private void startDownload(String url,String userAgent,String disposition,String mime){
        BrowserTransferManager.downloadToApp(this,url,userAgent,disposition,mime,(ok,message)->{
            if(!isFinishing()&&!isDestroyed())toast(message);
        });
        toast("Téléchargement lancé · disponible dans Fichiers > Téléchargements");
    }
    private void pageError(WebView web,String message){
        pageErrors.put(web,message);
        if(web==current){progress.setVisibility(View.GONE);if(home!=null)home.revealWeb();updatePageError();}
    }
    private void updatePageError(){
        TextView banner=findViewById(R.id.browser_error);String error=pageErrors.get(current);
        banner.setText(error==null?"":error);
        banner.setVisibility(error!=null&&"browser".equals(activeSpace)?View.VISIBLE:View.GONE);
    }
    void recoverRenderer(WebView dead){
        int index=tabs.indexOf(dead);if(index<0)return;
        String url=BrowserWebState.url(dead);boolean selected=current==dead;
        agentClient.browserInterrupted();
        if(fileCallback!=null){fileCallback.onReceiveValue(null);fileCallback=null;}
        if(pendingUploadWeb==dead){pendingUploadUris=null;pendingUploadCallback=null;pendingUploadWeb=null;}
        if(dead.getParent() instanceof ViewGroup)((ViewGroup)dead.getParent()).removeView(dead);
        BrowserWebState.close(dead);dead.destroy();pageErrors.remove(dead);
        WebView replacement=new WebView(this);configure(replacement);BrowserWebState.remember(replacement,url);
        tabs.set(index,replacement);if(selected){current=null;select(replacement);}
        pageError(replacement,"Page interrompue par Android. Toucher pour la rouvrir.");
        refreshTabs();saveTabs();
    }
    private void select(WebView web){
        if(!tabs.contains(web))return;
        if(current!=null)container.removeView(current);
        current=web;
        if(web.getParent() instanceof ViewGroup)((ViewGroup)web.getParent()).removeView(web);
        container.addView(web,new FrameLayout.LayoutParams(-1,-1));
        String page=web.getUrl()==null?BrowserWebState.url(web):web.getUrl();
        address.setText(page);
        if(home!=null)home.page(page);
        if(bottomNavCollapsed){
            bottomNavCollapsed=false;
            findViewById(R.id.space_nav).setVisibility(focusMode?View.GONE:View.VISIBLE);
        }
        progress.setVisibility(View.GONE);refreshTabs();updatePageError();
        // Restored background tabs are lazy: only the selected tab loads over the network.
        if(web.getUrl()==null&&BrowserWebState.alive(web))web.loadUrl(page);
    }
    private void close(WebView web){
        int i=tabs.indexOf(web);if(i<0)return;
        tabs.remove(i);
        if(current==web){
            container.removeView(web);current=null;
            if(!tabs.isEmpty())select(tabs.get(Math.max(0,i-1)));
        }
        BrowserWebState.close(web);pageErrors.remove(web);web.destroy();if(tabs.isEmpty())newTab(HOME);refreshTabs();saveTabs();
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
        new AgentDialog.Builder(this).setTitle("Bibliothèque")
        .setItems(new String[]{"Favoris","Historique"},(d,i)->showList(i==0?"favorites":"history"))
        .setNegativeButton("Fermer",null).show();
    }
    private void showList(String key){
        JSONArray rows=store.get(key);
        if(rows.length()==0){toast("Aucun élément");return;}
        String[] names=new String[rows.length()];
        for(int i=0;i<rows.length();i++){JSONObject o=rows.optJSONObject(i);names[i]=o==null?"":o.optString("title",o.optString("url"));}
        new AgentDialog.Builder(this).setTitle(key.equals("favorites")?"Favoris":"Historique")
        .setItems(names,(d,i)->{JSONObject o=rows.optJSONObject(i);if(o!=null&&current!=null)current.loadUrl(o.optString("url"));})
        .setNegativeButton("Fermer",null).show();
    }
    private void readPage(){
        if(current==null)return;
        new AgentDialog.Builder(this).setTitle("Lire la page")
        .setMessage("Extraire le texte de la page sur ce téléphone ? Aucun texte ne sera envoyé automatiquement.")
        .setNegativeButton("Annuler",null).setPositiveButton("Lire",(d,w)->
            current.evaluateJavascript("(document.body && document.body.innerText || '').slice(0,12000)",result->{
                String content;
                try{content=new JSONArray("["+result+"]").getString(0);}catch(Exception e){content="Lecture impossible";}
                final String copied=content;
                new AgentDialog.Builder(this).setTitle("Texte de la page").setMessage(content.isEmpty()?"Page vide":content)
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
            // Do not overwrite the user's visible tab with the autonomous engine's URL.
            // A separate, explicit "Reprendre la page MCP" action is available in the menu.
            agentClient.attach(this,new AgentClient.CommandHandler(){
                @Override public String pageUrl(){return current==null||current.getUrl()==null?"":current.getUrl();}
                @Override public String pageTitle(){return current==null||current.getTitle()==null?"":current.getTitle();}
                @Override public String sessionSource(){return "visible";}
                @Override public void onCommand(JSONObject command,AgentClient.ResultCallback callback){
                    confirmAgentCommand(command,callback);
                }
            });
        }
        explicitPageOnResume=false;
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
        else if(action.equals("publication_submit"))extra="Publier ou programmer UNE fois sur la plateforme prévue, avec contrôle du résultat et journal anti-doublon";
        else if(DesktopCommands.handles(action,args))extra="Utiliser les outils IA CHK : souris, vision, fichiers, tâches ou scripts";
        else if(KeyboardCommands.handles(action,args))extra="Utiliser le clavier agent pour inspecter ou remplir les champs";
        else switch(action){
            case "read_page":extra="Lire le texte actuellement visible dans la page";break;
            case "tabs":case "list_tabs":extra="Lister les onglets, leurs indices et leurs adresses";break;
            case "new_tab":case "open_tab":extra="Ouvrir un nouvel onglet : "+args.optString("url",HOME);break;
            case "close_tab":extra="Fermer l’onglet demandé";break;
            case "translate_page":extra="Traduire le contenu de la page (anglais / français)";break;
            case "open_url":extra="Ouvrir : "+args.optString("url","");break;
            case "resume_visible_page":extra="Reprendre l'adresse visible dans un nouvel onglet MCP, sans recopier les données du formulaire";break;
            case "click_verified":extra="Cliquer une seule fois et vérifier le résultat";break;
            case "click":extra="Cliquer sur : "+args.optString("selector","");break;
            case "type":
                extra="Saisir dans "+args.optString("selector","")+
                    "\nTexte : "+args.optString("text","");break;
            case "collect_public_comments":extra="Collecter les commentaires publics X/TikTok, anonymiser, dedoublonner et enregistrer un rapport";break;
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
        new AgentDialog.Builder(this)
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
    private void runAgentCommand(String action,JSONObject args,AgentClient.ResultCallback originalCallback){
        if(!originalCallback.isActive())return;
        final AgentClient.ResultCallback callback=new AgentClient.ResultCallback(){
            public boolean isActive(){return originalCallback.isActive()&&!isFinishing()&&!isDestroyed();}
            public void finish(boolean ok,String result){transferHandler.post(()->{if(!isDestroyed())finishAgentAction(ok);});originalCallback.finish(ok,result);}
        };
        if(!agentClient.isEnabled()){callback.finish(false,"MCP désactivé par le propriétaire.");return;}
        if(DesktopCommands.handles(action,args)){
            if(args.optString("target","native").equals("web")){showSpace("browser");if(home!=null)home.revealWeb();}
            DesktopCommands.execute(this,current,action,args,callback);return;
        }
        if(KeyboardCommands.handles(action,args)){
            boolean nativeTarget;try{String target=KeyboardCommands.parameters(action,args).optString("target","web");nativeTarget=target.equals("native")||(target.equals("auto")&&NativeKeyboard.prefersNative());}catch(Exception e){callback.finish(false,e.getMessage());return;}
            if(!nativeTarget){showSpace("browser");if(home!=null)home.revealWeb();showAgentAction("keyboard");}
            KeyboardCommands.run(this,current,action,args,callback);return;
        }
        if(WorkspaceCommands.handles(action)){
            WorkspaceCommands.run(this,action,args,new AgentClient.ResultCallback(){
                public boolean isActive(){return callback.isActive();}
                public void finish(boolean ok,String result){if(!isDestroyed()&&workspace!=null&&!activeSpace.equals("browser"))workspace.refresh();callback.finish(ok,result);}
            });return;
        }
        showSpace("browser");
        if(home!=null)home.revealWeb();
        showAgentAction(action);
        if(current==null){callback.finish(false,"Aucun onglet.");return;}
        if(PublicationJobs.ACTION_SUBMIT.equals(action)){
            PublicationBrowserCommands.submit(this,current,args,transferHandler,callback);
            return;
        }
        try{
            switch(action){
                case "preview":case "screenshot":{
                    captureVisiblePreview(callback);return;
                }
                case "tabs":case "list_tabs":{
                    JSONArray list=new JSONArray();
                    for(int index=0;index<tabs.size();index++){
                        WebView web=tabs.get(index);
                        JSONObject entry=new JSONObject();
                        entry.put("index",index);entry.put("active",web==current);
                        entry.put("title",web.getTitle()==null?"":web.getTitle());
                        entry.put("url",web.getUrl()==null?"":web.getUrl());
                        list.put(entry);
                    }
                    callback.finish(true,list.toString());return;
                }
                case "resume_visible_page":{
                    // The current Android Activity already owns the visible page.
                    // The background engine can explicitly recreate its URL after pause.
                    callback.finish(false,"Reprise non exécutée : la page est encore visible. Passe CHK en arrière-plan puis relance la reprise MCP.");return;
                }
                case "new_tab":case "open_tab":{
                    String url=args.optString("url",HOME);
                    if(url.isEmpty())url=HOME;
                    if(url.length()>2000||!BackgroundBrowser.isPublicHttps(Uri.parse(url))){
                        callback.finish(false,"Une URL HTTPS publique est requise.");return;
                    }
                    if(tabs.size()>=12){callback.finish(false,"Maximum de 12 onglets atteint.");return;}
                    newTab(url);
                    callback.finish(true,"Onglet "+(tabs.size()-1)+" ouvert : "+url);return;
                }
                case "close_tab":{
                    int index=args.has("index")?args.optInt("index",-1):tabs.indexOf(current);
                    String contains=args.optString("url_contains","");
                    if(!contains.isEmpty()&&!args.has("index")){
                        index=-1;
                        for(int i=0;i<tabs.size();i++){String url=tabs.get(i).getUrl();
                            if(url!=null&&url.contains(contains)){index=i;break;}}
                    }
                    if(index<0||index>=tabs.size()){callback.finish(false,"Onglet introuvable.");return;}
                    close(tabs.get(index));
                    callback.finish(true,"Onglet fermé. "+tabs.size()+" onglet(s) restant(s).");return;
                }
                case "translate_page":{
                    String direction=args.optString("direction","en-fr");
                    BrowserTranslator.translate(current,direction,(ok,message)->callback.finish(ok,message));return;
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
                            out.put("media",details.optJSONArray("media"));
                            out.put("document_url",details.optString("document_url"));
                            out.put("session_source","visible");
                            out.put("visibility",details.optString("visibility"));
                            WebPdfReader.enrich(this,page,out,callback);
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
                case "collect_public_comments":{
                    PublicCommentCollector.start(this,current,args,transferHandler,callback);return;
                }
                case "scroll":{
                    int offset=(int)(current.getHeight()*0.7f);
                    if("up".equals(args.optString("direction")))offset=-offset;
                    final WebView target=current;final int from=target.getScrollY(),to=Math.max(0,from+offset);
                    android.animation.ValueAnimator animation=android.animation.ValueAnimator.ofInt(from,to);animation.setDuration(280);animation.addUpdateListener(v->target.scrollTo(target.getScrollX(),(int)v.getAnimatedValue()));animation.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){callback.finish(true,"Défilement effectué.");}});animation.start();return;
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
        if(selector.isEmpty()||selector.length()>=350){callback.finish(false,"Sélecteur fichier invalide.");return;}
        String expected=args.optString("expected_host","");
        String host=web.getUrl()==null?null:Uri.parse(web.getUrl()).getHost();
        if(host==null||(!expected.isEmpty()&&!expected.equalsIgnoreCase(host))){
            callback.finish(false,"Destination d'importation différente de celle autorisée.");return;
        }
        final String document=web.getUrl();
        BrowserTransferManager.prepareUploads(this,files,agentClient.deviceTokenForTransfers(),(ok,uris,message)->{
            if(!callback.isActive())return;
            if(!BrowserWebState.alive(web)||!document.equals(web.getUrl())){callback.finish(false,"La page a changé pendant la préparation. Relance l’importation sur la bonne page.");return;}
            if(!ok){callback.finish(false,message);return;}
            // Select the locally stored URI through the WebView's native file chooser.
            // This avoids base64 relay buffers and supports original large videos.
            BrowserNativeUploader.upload(this,web,selector,uris,callback);
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
        if(!callback.isActive()||!BrowserWebState.alive(web))return;
        if(!url.startsWith("https://")){callback.finish(false,"Téléchargement HTTPS uniquement.");return;}
        BrowserTransferManager.downloadToApp(this,url,web.getSettings().getUserAgentString(),mime,(ok,message)->callback.finish(ok,message));
    }
    private void waitForElement(WebView web,String selector,int timeout,AgentClient.ResultCallback callback){
        if(selector.isEmpty()||selector.length()>=350){callback.finish(false,"Sélecteur invalide.");return;}
        long start=android.os.SystemClock.elapsedRealtime();
        Runnable[] poll=new Runnable[1];
        poll[0]=()->{if(!callback.isActive()||!BrowserWebState.alive(web))return;
            web.evaluateJavascript(BrowserScripts.exists(JSONObject.quote(selector)),raw->{
            if("true".equals(raw)){callback.finish(true,"Élément disponible.");return;}
            if(android.os.SystemClock.elapsedRealtime()-start>=timeout){callback.finish(false,"Élément absent après "+timeout+" ms.");return;}
            transferHandler.postDelayed(poll[0],350L);
        });};
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
    private int agentActionGeneration;
    private void showAgentAction(String action){
        agentActionGeneration++;TextView banner=findViewById(R.id.agent_action);String label;
        switch(action){case "open_url":label="Navigation";break;case "click":case "click_verified":label="Clic sur la page";break;case "type":case "keyboard":label="Saisie dans un formulaire";break;case "scroll":label="Défilement";break;case "read_page":label="Lecture de la page";break;case "upload_file":label="Importation du fichier";break;case "download_file":label="Téléchargement";break;default:label="Vérification de la page";}
        banner.setText("●  ChatGPT · "+label);banner.setVisibility(View.VISIBLE);banner.setOnClickListener(v->new AgentDialog.Builder(this).setTitle("Navigation visible").setMessage("Les actions du connecteur s’exécutent dans cet onglet. Tu peux arrêter le connecteur depuis Réglages.").setNegativeButton("Fermer",null).setPositiveButton("Réglages",(d,n)->startActivity(new Intent(this,SettingsActivity.class))).show());
    }
    private void finishAgentAction(boolean ok){int ticket=agentActionGeneration;TextView banner=findViewById(R.id.agent_action);if(banner.getVisibility()!=View.VISIBLE)return;banner.setText(ok?"✓  ChatGPT · action effectuée":"!  ChatGPT · action à vérifier");transferHandler.postDelayed(()->{if(ticket==agentActionGeneration)banner.setVisibility(View.GONE);},2200);}
    private void setBottomNavCollapsed(boolean collapsed){
        if(focusMode||!"browser".equals(activeSpace)||bottomNavCollapsed==collapsed)return;
        bottomNavCollapsed=collapsed;
        findViewById(R.id.space_nav).setVisibility(collapsed?View.GONE:View.VISIBLE);
    }
    private void showSpace(String space){
        boolean browser=space.equals("browser");
        if(!browser||!space.equals(activeSpace)){
            bottomNavCollapsed=false;
            findViewById(R.id.space_nav).setVisibility(focusMode?View.GONE:View.VISIBLE);
        }
        findViewById(R.id.space_header).setVisibility(View.GONE);
        if(home!=null)home.space(browser,current==null?HOME:current.getUrl());
        findViewById(R.id.web_container).setVisibility(browser?View.VISIBLE:View.GONE);
        findViewById(R.id.workspace_container).setVisibility(browser?View.GONE:View.VISIBLE);
        findViewById(R.id.address_bar).setVisibility(browser?View.VISIBLE:View.GONE);
        findViewById(R.id.new_tab).setVisibility(browser?View.VISIBLE:View.GONE);
        findViewById(R.id.tab_count).setVisibility(browser?View.VISIBLE:View.GONE);
        findViewById(R.id.translate_page).setVisibility(browser?View.VISIBLE:View.GONE);
        ((TextView)findViewById(R.id.space_title)).setText(browser?"CHK Browser":space.equals("files")?"Fichiers":"Notes");
        boolean light=space.equals("files");findViewById(R.id.space_nav).setBackgroundColor(light?0xfff4f6fb:0xff202124);
        getWindow().setStatusBarColor(0xff17181c);getWindow().setNavigationBarColor(light?0xfff4f6fb:0xff202124);
        if(Build.VERSION.SDK_INT>=26){int flags=getWindow().getDecorView().getSystemUiVisibility();getWindow().getDecorView().setSystemUiVisibility(light?flags|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:flags&~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);}
        String[] spaces={"browser","files","video","cut","notes"},icons={"globe","folder","video","scissors","document"};int[] ids={R.id.nav_browser,R.id.nav_files,R.id.nav_video,R.id.nav_cut,R.id.nav_notes};
        for(int i=0;i<ids.length;i++){TextView v=findViewById(ids[i]);boolean selected=space.equals(spaces[i]);int color=selected?(light?0xff2468cb:0xffc5bfff):(light?0xff687080:0xffbfc2cd);v.setTextSize(11);v.setTextColor(color);v.setCompoundDrawablesWithIntrinsicBounds(null,UiIcons.icon(this,icons[i],color,23),null,null);v.setBackground(selected?MobileUi.bg(this,light?0xffdceaff:0xff393548):null);v.setSelected(selected);v.setFocusable(true);}
        if(!browser&&workspace!=null&&!space.equals(activeSpace))workspace.show(space);
        activeSpace=space;updatePageError();
    }
    private void translateMenu(){
        if(current==null){toast("Aucune page ouverte");return;}
        new AgentDialog.Builder(this).setTitle("Traduire la page")
            .setItems(new String[]{"Anglais → français","Français → anglais","Afficher le texte original"},(dialog,index)->{
                String mode=new String[]{"en-fr","fr-en","original"}[index];
                WebView requested=current;
                toast(index==2?"Restauration de la page":"Traduction en cours (premier usage : téléchargement du modèle)");
                BrowserTranslator.translate(requested,mode,(ok,message)->{
                    if(!isFinishing()&&!isDestroyed())toast(message);
                });
            }).setNegativeButton("Annuler",null).show();
    }
    private void browserMenu(){
        if(!activeSpace.equals("browser")){new AgentDialog.Builder(this).setTitle("Espace de travail").setItems(new String[]{"Réglages","Revenir au navigateur"},(d,i)->{if(i==0)startActivity(new Intent(this,SettingsActivity.class));else showSpace("browser");}).show();return;}
        new AgentDialog.Builder(this).setTitle("Menu navigateur").setItems(new String[]{
            "＋ Nouvel onglet","▣ Mes onglets","Traduire la page (EN / FR)",
            "Actualiser","Page suivante","Fermer cet onglet",
            store.hasFavorite(current==null?"":current.getUrl())?"Retirer le favori":"Ajouter aux favoris",
            "Favoris et historique","Rechercher dans la page","Partager le lien",
            "Enregistrer dans Notes","Lire / copier le texte","Taille du texte",
            "Mode lecture plein écran","Reprendre la page MCP","Réglages"},(d,i)->{
            switch(i){
                case 0:newTab(HOME);break;
                case 1:tabOverview();break;
                case 2:translateMenu();break;
                case 3:if(current!=null)current.reload();break;
                case 4:if(current!=null&&current.canGoForward())current.goForward();break;
                case 5:if(current!=null)close(current);break;
                case 6:toggleBookmark();break;
                case 7:library();break;
                case 8:findInPage();break;
                case 9:if(current!=null)startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,current.getUrl()),"Partager la page"));break;
                case 10:savePageNote();break;
                case 11:readPage();break;
                case 12:textSize();break;
                case 13:setFocusMode(true);break;
                case 14:openAgentPage();break;
                case 15:startActivity(new Intent(this,SettingsActivity.class));break;
            }
        }).setNegativeButton("Fermer",null).show();
    }
    private void openAgentPage(){
        String url=AgentService.latestBackgroundUrl();
        if(url==null||!url.startsWith("https://")){
            toast("Aucune page MCP récente à reprendre");return;
        }
        for(WebView tab:tabs){
            String known=tab.getUrl()==null?BrowserWebState.url(tab):tab.getUrl();
            if(url.equals(known)){select(tab);showSpace("browser");return;}
        }
        if(tabs.size()<12){newTab(url);showSpace("browser");return;}
        final String destination=url;
        new AgentDialog.Builder(this).setTitle("Ouvrir la page MCP")
            .setMessage("Les 12 onglets sont utilisés. Remplacer la page actuelle ? Les formulaires non enregistrés seront perdus.")
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Remplacer",(dialog,which)->{if(current!=null)current.loadUrl(destination);})
            .show();
    }
    private void textSize(){new AgentDialog.Builder(this).setTitle("Taille du texte des pages").setItems(new String[]{"100 % · Standard","115 % · Confort","130 % · Grand","150 % · Très grand"},(d,i)->{int zoom=new int[]{100,115,130,150}[i];getSharedPreferences("mobile_ui",MODE_PRIVATE).edit().putInt("text_zoom",zoom).apply();for(WebView web:tabs)web.getSettings().setTextZoom(zoom);}).show();}
    private void findInPage(){if(current==null)return;WebView web=current;EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Mot à rechercher");LinearLayout box=MobileUi.column(this);box.setPadding(dp(16),dp(8),dp(16),dp(8));box.addView(input);TextView count=MobileUi.text(this,"Saisis un mot",14,MobileUi.MUTED);box.addView(count);web.setFindListener((index,total,done)->{if(done)count.setText(total==0?"Aucun résultat":(index+1)+" / "+total);});input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int aft){}public void onTextChanged(CharSequence s,int st,int b,int c){web.findAllAsync(s.toString());}public void afterTextChanged(android.text.Editable e){}});
        AlertDialog dialog=new AgentDialog.Builder(this).setTitle("Rechercher dans la page").setView(box).setPositiveButton("Suivant",null).setNeutralButton("Précédent",null).setNegativeButton("Fermer",null).create();dialog.setOnDismissListener(d->{web.clearMatches();web.setFindListener(null);});dialog.setOnShowListener(d->{dialog.getButton(-1).setOnClickListener(v->web.findNext(true));dialog.getButton(-3).setOnClickListener(v->web.findNext(false));});dialog.show();
    }
    private void setFocusMode(boolean enabled){focusMode=enabled;bottomNavCollapsed=false;findViewById(R.id.space_nav).setVisibility(enabled?View.GONE:View.VISIBLE);findViewById(R.id.address_bar).setVisibility(enabled?View.GONE:View.VISIBLE);if(enabled)toast("Lecture agrandie · Retour pour retrouver les commandes");}
    private void savePageNote(){if(current==null)return;WebView web=current;String title=web.getTitle(),url=web.getUrl();web.evaluateJavascript("(document.body && document.body.innerText || '').slice(0,12000)",raw->{if(isFinishing())return;startActivity(new Intent(this,NoteEditorActivity.class).putExtra("title",title).putExtra("body",fromJavascript(raw)).putExtra("source",url));});}
    private void tabOverview(){
        final android.app.Dialog dialog=AgentDialog.watch(new android.app.Dialog(this));
        LinearLayout root=MobileUi.column(this);
        root.setBackgroundColor(0xff202124);
        root.setPadding(dp(12),dp(14),dp(12),dp(12));
        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header,new LinearLayout.LayoutParams(-1,dp(58)));
        TextView heading=MobileUi.text(this,"Onglets · "+tabs.size(),21,MobileUi.TEXT);
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        TextView add=MobileUi.action(this,"＋");
        add.setTextSize(28);add.setContentDescription("Nouvel onglet");
        header.addView(add,new LinearLayout.LayoutParams(dp(52),dp(50)));
        add.setOnClickListener(v->{if(tabs.size()<12){dialog.dismiss();newTab(HOME);address.requestFocus();
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(address,InputMethodManager.SHOW_IMPLICIT);}
            else toast("Maximum 12 onglets");});
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        android.widget.GridLayout grid=new android.widget.GridLayout(this);
        grid.setColumnCount(2);
        scroll.addView(grid);
        int cardWidth=Math.max(dp(130),(getResources().getDisplayMetrics().widthPixels-dp(46))/2);
        for(WebView web:new ArrayList<>(tabs)){
            LinearLayout card=MobileUi.column(this);
            card.setPadding(dp(12),dp(8),dp(12),dp(12));
            card.setBackground(MobileUi.bg(this,web==current?0xff343449:0xff303136));
            android.widget.GridLayout.LayoutParams cell=new android.widget.GridLayout.LayoutParams();
            cell.width=cardWidth;cell.height=dp(158);
            cell.setMargins(dp(4),dp(5),dp(4),dp(5));
            grid.addView(card,cell);
            LinearLayout first=new LinearLayout(this);first.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(first,new LinearLayout.LayoutParams(-1,dp(42)));
            TextView marker=MobileUi.text(this,web==current?"●  Onglet actif":"Onglet "+(tabs.indexOf(web)+1),12,MobileUi.MUTED);
            first.addView(marker,new LinearLayout.LayoutParams(0,-2,1));
            TextView x=MobileUi.action(this,"×");x.setTextSize(26);x.setContentDescription("Fermer l’onglet");
            first.addView(x,new LinearLayout.LayoutParams(dp(40),dp(40)));
            String title=web.getTitle();if(title==null||title.isEmpty())title="Nouvel onglet";
            TextView name=MobileUi.text(this,title,16,MobileUi.TEXT);
            name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(name);
            android.graphics.Bitmap favicon=web.getFavicon();
            if(favicon!=null){
                ImageView icon=new ImageView(this);
                icon.setImageBitmap(favicon);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setContentDescription("Icône de la page");
                card.addView(icon,new LinearLayout.LayoutParams(dp(26),dp(26)));
            }
            Uri uri=Uri.parse(web.getUrl()==null?BrowserWebState.url(web):web.getUrl());
            String host=uri.getHost()==null?"Page en chargement":uri.getHost();
            TextView domain=MobileUi.text(this,host,12,MobileUi.MUTED);
            domain.setMaxLines(2);card.addView(domain);
            card.setContentDescription(title+" · "+host);
            card.setOnClickListener(v->{select(web);showSpace("browser");dialog.dismiss();});
            x.setOnClickListener(v->{close(web);dialog.dismiss();tabOverview();});
        }
        TextView done=MobileUi.action(this,"Retour à la page");
        root.addView(done,new LinearLayout.LayoutParams(-1,dp(50)));
        done.setOnClickListener(v->dialog.dismiss());
        dialog.setContentView(root);
        dialog.show();
        android.view.Window window=dialog.getWindow();
        if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(-1,Math.round(getResources().getDisplayMetrics().heightPixels*0.92f));
            window.setGravity(Gravity.BOTTOM);}
    }
    private void saveTabs(){if(tabs.isEmpty())return;JSONArray urls=new JSONArray();for(WebView web:tabs){String url=web.getUrl();urls.put(url!=null&&url.startsWith("https://")?url:BrowserWebState.url(web));}getSharedPreferences("mobile_ui",MODE_PRIVATE).edit().putString("tabs",urls.toString()).putInt("active_tab",Math.max(0,tabs.indexOf(current))).apply();}
    private void restoreTabs(Bundle state){
        try{JSONArray urls=new JSONArray(state==null?getSharedPreferences("mobile_ui",MODE_PRIVATE).getString("tabs","[]"):state.getString("tabs","[]"));int selected=state==null?getSharedPreferences("mobile_ui",MODE_PRIVATE).getInt("active_tab",0):state.getInt("active_tab",0);
            for(int i=0;i<Math.min(12,urls.length());i++){
                String url=urls.optString(i,HOME);
                if(!url.startsWith("https://"))url=HOME;
                WebView restored=new WebView(this);
                restored.setBackgroundColor(Color.WHITE);
                configure(restored);
                BrowserWebState.remember(restored,url);
                tabs.add(restored);
            }
            if(!tabs.isEmpty())select(tabs.get(Math.min(tabs.size()-1,Math.max(0,selected))));
        }catch(Exception ignored){}if(tabs.isEmpty())newTab(HOME);
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);saveTabs();out.putString("tabs",getSharedPreferences("mobile_ui",MODE_PRIVATE).getString("tabs","[]"));out.putInt("active_tab",Math.max(0,tabs.indexOf(current)));out.putString("space",activeSpace);if(workspace!=null)workspace.saveState(out);}
    private static String fromJavascript(String raw){
        if(raw==null)return "";
        try{return new JSONArray("["+raw+"]").getString(0);}
        catch(Exception e){return "Résultat illisible";}
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==812&&result==RESULT_OK&&data!=null){java.util.ArrayList<String> words=data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);if(words!=null&&!words.isEmpty())navigate(words.get(0));return;}
        if(workspace!=null&&workspace.result(request,result,data))return;
        if(request==FILE_REQUEST&&fileCallback!=null){
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data));fileCallback=null;
        }
    }
    @Override public void onBackPressed(){
        if(home!=null&&home.closeSuggestions()){address.clearFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);return;}
        if(fullScreenVideo!=null){hideFullScreenVideo();return;}
        if(focusMode){setFocusMode(false);return;}
        if(!activeSpace.equals("browser")){if(workspace.back())return;showSpace("browser");return;}
        if(current!=null&&current.canGoBack())current.goBack();else super.onBackPressed();
    }
    @Override protected void onDestroy(){
        super.onDestroy();
        if(home!=null)home.destroy();if(workspace!=null)workspace.destroy();hideFullScreenVideo();
        for(WebView v:tabs){if(v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);BrowserWebState.close(v);v.destroy();}
        tabs.clear();
        if(fileCallback!=null)fileCallback.onReceiveValue(null);
        if(pendingUploadCallback!=null)pendingUploadCallback.finish(false,"Navigateur fermé pendant l'importation.");
        pendingUploadUris=null;pendingUploadCallback=null;pendingUploadWeb=null;
        transferHandler.removeCallbacksAndMessages(null);
    }
}
