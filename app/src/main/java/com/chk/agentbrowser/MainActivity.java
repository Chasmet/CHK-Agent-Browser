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
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);setContentView(R.layout.activity_main);
        store=new BrowserStore(this);
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
                    try{new UpdateManager(this).download(release);toast("Téléchargement lancé. Installation dans Réglages.");}
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
