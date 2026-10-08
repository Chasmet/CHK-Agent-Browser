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
        s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSupportMultipleWindows(false);
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
            @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> callback,FileChooserParams params){
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
            case "preview":extra="Afficher un aperçu de la page dans ChatGPT";break;
            default:callback.finish(false,"Commande MCP inconnue.");return;
        }
        if(agentClient.isAutonomous()){
            if("preview".equals(action)&&!agentClient.isPreviewAllowed()) {
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
            .setOnCancelListener(d->callback.finish(false,"Action annulée sur le téléphone."))
            .show();
    }
    private void runAgentCommand(String action,JSONObject args,AgentClient.ResultCallback callback){
        if(current==null){callback.finish(false,"Aucun onglet.");return;}
        try{
            switch(action){
                case "preview":{
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
                    web.evaluateJavascript("(document.body && document.body.innerText || '').slice(0,10000)",raw->{
                        String content=fromJavascript(raw);
                        try{
                            JSONObject out=new JSONObject();
                            out.put("url",page==null?"":page);out.put("title",title==null?"":title);
                            out.put("text",content);
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
                    String js="(function(){try{var el=document.querySelector("+JSONObject.quote(selector)+");"
                        +"if(!el)return 'Élément introuvable';el.click();return 'Clic effectué';}"
                        +"catch(e){return 'Erreur de sélection : '+e.message;}})()";
                    current.evaluateJavascript(js,raw->{
                        String result=fromJavascript(raw);
                        callback.finish("Clic effectué".equals(result),result);
                    });return;
                }
                case "type":{
                    String selector=args.optString("selector","");
                    String content=args.optString("text","");
                    if(content.length()>500){callback.finish(false,"Texte trop long");return;}
                    String js="(function(){try{var el=document.querySelector("+JSONObject.quote(selector)+");"
                        +"if(!el)return 'Champ introuvable';"
                        +"if(!('value' in el))return 'Champ non saisissable';"
                        +"if(['password','file','hidden'].indexOf((el.type||'').toLowerCase())>=0)"
                        +"return 'Champ sensible bloqué';"
                        +"el.focus();el.value="+JSONObject.quote(content)+";"
                        +"el.dispatchEvent(new Event('input',{bubbles:true}));"
                        +"el.dispatchEvent(new Event('change',{bubbles:true}));"
                        +"return 'Saisie effectuée';}catch(e){return 'Erreur de saisie : '+e.message;}})()";
                    current.evaluateJavascript(js,raw->{
                        String result=fromJavascript(raw);
                        callback.finish("Saisie effectuée".equals(result),result);
                    });return;
                }
                default:callback.finish(false,"Action inconnue");
            }
        }catch(Exception e){callback.finish(false,"Échec de l'action : "+e.getClass().getSimpleName());}
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
    }
}
