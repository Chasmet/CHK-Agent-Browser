package com.chk.agentbrowser;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import java.net.URLEncoder;
import android.net.http.SslError;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.util.Collections;
import org.json.JSONObject;

/** A script-free Tor WebView in the :tor process, created only after proxy and circuit proofs. */
@androidx.annotation.RequiresApi(29)
public final class TorWebActivity extends Activity {
    private static final String FRENCH_HOME="https://check.torproject.org/?lang=fr";
    private TorRuntime runtime;
    private WebView web;
    private TextView state;
    private EditText address;
    private TextView pageInfo, translatedText;
    private ScrollView startPage, translatedPage;
    private ProgressBar loading;
    private boolean lightweight;
    private TextView lightweightButton, autoFrenchButton;
    private boolean autoFrench, resumeFrenchAfterInstall;
    private static final String AHMIA="http://juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion/";
    private static final String DDG="http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/";
    boolean resumed;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        runtime=TorRuntime.get(this);
        if(!runtime.policy.usable(SystemClock.elapsedRealtime())){finish();return;}

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0c1427);
        root.setPadding(dp(10),dp(6),dp(10),0);

        TextView heading=label("◉  Tor  ·  Navigateur indépendant",18,0xfff2f0ff);
        heading.setTypeface(null,android.graphics.Typeface.BOLD);
        root.addView(heading);
        state=label("Tor connecté · JS désactivé",12,0xff80e7ab);
        state.setSingleLine(true);state.setEllipsize(android.text.TextUtils.TruncateAt.END);root.addView(state);

        LinearLayout translationRow=new LinearLayout(this);
        translationRow.setOrientation(LinearLayout.HORIZONTAL);
        translationRow.setPadding(0,dp(6),0,dp(5));
        TextView translate=control("Traduire FR");translate.setOnClickListener(v->translatePage());
        translationRow.addView(translate,new LinearLayout.LayoutParams(0,dp(43),1));
        autoFrench=getPreferences(MODE_PRIVATE).getBoolean("automatic_french",false);
        autoFrenchButton=control(autoFrench?"FR auto ON":"FR auto OFF");
        autoFrenchButton.setOnClickListener(v->{
            autoFrench=!autoFrench;
            getPreferences(MODE_PRIVATE).edit().putBoolean("automatic_french",autoFrench).apply();
            autoFrenchButton.setText(autoFrench?"FR auto ON":"FR auto OFF");
            if(autoFrench&&web!=null&&web.getUrl()!=null&&web.getUrl().startsWith("http"))translatePage();
        });
        LinearLayout.LayoutParams autoParams=new LinearLayout.LayoutParams(dp(107),dp(43));
        autoParams.leftMargin=dp(5);
        translationRow.addView(autoFrenchButton,autoParams);
        TextView homeButton=control("Accueil");homeButton.setOnClickListener(v->showHome());
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(70),dp(43));hp.leftMargin=dp(7);
        translationRow.addView(homeButton,hp);root.addView(translationRow);

        LinearLayout bar=new LinearLayout(this);bar.setOrientation(LinearLayout.HORIZONTAL);
        address=new EditText(this);address.setSingleLine();address.setSelectAllOnFocus(true);
        address.setTextColor(0xfff4f4ff);address.setHintTextColor(0xff9ba9c5);
        address.setTextSize(14);address.setHint("Adresse .onion ou HTTPS");address.setPadding(dp(12),0,dp(8),0);
        address.setBackground(round(0xff1d2a44,0xff61759a));
        address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setOnEditorActionListener((v,id,event)->{openAddress();return true;});
        bar.addView(address,new LinearLayout.LayoutParams(0,dp(48),1));
        TextView go=control("Aller");go.setBackground(round(0xff6638dc,0xff6638dc));
        go.setOnClickListener(v->openAddress());
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(dp(78),dp(48));gp.leftMargin=dp(6);
        bar.addView(go,gp);root.addView(bar);

        HorizontalScrollView scroller=new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout tools=new LinearLayout(this);tools.setOrientation(LinearLayout.HORIZONTAL);
        small(tools,"← Retour",()->action("back",null));
        small(tools,"→ Suivant",()->action("forward",null));
        small(tools,"↻ Actualiser",()->action("reload",null));
        small(tools,"Annuler",()->action("cancel_navigation",null));
        lightweightButton=small(tools,"Images ON",()->{
            lightweight=!lightweight;
            web.getSettings().setLoadsImagesAutomatically(!lightweight);
            web.getSettings().setBlockNetworkImage(lightweight);
            lightweightButton.setText(lightweight?"Images OFF · rapide":"Images ON");
            pageInfo.setText(lightweight?"Images bloquées pour alléger les pages.":"Images autorisées.");
        });
        small(tools,"Stop Tor",()->startActivity(new android.content.Intent(this,TorEntryActivity.class)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("disconnect_tor",true)));
        small(tools,"CHK",this::finish);
        scroller.addView(tools);root.addView(scroller);
        loading=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        loading.setMax(100);loading.setVisibility(View.GONE);root.addView(loading,new LinearLayout.LayoutParams(-1,dp(3)));
        pageInfo=label("Accès rapides · les liens .onion passent uniquement par Tor",12,0xffb6c3dd);
        pageInfo.setMaxLines(2);pageInfo.setPadding(dp(4),dp(4),dp(2),dp(5));root.addView(pageInfo);

        startPage=new ScrollView(this);
        startPage.setFillViewport(false);
        LinearLayout home=new LinearLayout(this);home.setOrientation(LinearLayout.VERTICAL);home.setPadding(dp(6),dp(4),dp(6),dp(10));
        TextView intro=label("Recherche .onion",24,0xffe8dfff);intro.setTypeface(null,android.graphics.Typeface.BOLD);home.addView(intro);
        home.addView(label("Découvrir le réseau Tor et ses services publics.",13,0xffb5c1de));
        LinearLayout searchRow=new LinearLayout(this);
        EditText search=new EditText(this);search.setSingleLine();search.setTextColor(0xfff8f7ff);search.setHintTextColor(0xffadb2c3);
        search.setHint("Rechercher sur Ahmia…");search.setTextSize(15);search.setBackground(round(0xff202c46,0xff586c91));
        search.setPadding(dp(12),0,dp(12),0);
        searchRow.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));
        TextView searchButton=control("Chercher");searchButton.setBackground(round(0xff6638dc,0xff6638dc));
        searchButton.setOnClickListener(v->searchAhmia(search.getText().toString()));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(102),dp(48));sp.leftMargin=dp(6);
        searchRow.addView(searchButton,sp);
        LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(-1,-2);searchParams.topMargin=dp(14);home.addView(searchRow,searchParams);

        TextView quick=label("Accès rapides",19,0xfff8f5ff);quick.setTypeface(null,android.graphics.Typeface.BOLD);
        quick.setPadding(0,dp(18),0,dp(6));home.addView(quick);
        shortcut(home,"Ahmia Search","Recherche de services .onion",AHMIA);
        shortcut(home,"DuckDuckGo Onion","Recherche privée",""+DDG);
        shortcut(home,"Tor Project","Répertoire de services vérifiés","https://onion.torproject.org/");
        shortcut(home,"ProPublica SecureDrop","Contact sécurisé pour les sources","http://33xu4yhum2eiisxm6fntaslayop76fvaqgt3ak5dakdm3t7cub25cead.onion/");
        shortcut(home,"Guide Tor en français","Documentation et réglages","https://support.torproject.org/fr/");
        shortcut(home,"Vérifier Tor","Test officiel de la connexion",FRENCH_HOME);
        TextView tips=label("Confidentialité : vérifiez les adresses .onion. Le JavaScript est désactivé. Évitez les téléchargements inconnus. Le mode léger retire les images mais ne peut pas accélérer les relais Tor.",12,0xffa9b6d3);
        tips.setPadding(0,dp(15),0,dp(8));home.addView(tips);
        TextView prepareFrench=control("Installer le traducteur FR (une seule fois)");
        prepareFrench.setOnClickListener(v->installFrenchModel());
        home.addView(prepareFrench);
        startPage.addView(home);
        root.addView(startPage,new LinearLayout.LayoutParams(-1,0,1));

        translatedPage=new ScrollView(this);translatedPage.setVisibility(View.GONE);
        LinearLayout reader=new LinearLayout(this);reader.setOrientation(LinearLayout.VERTICAL);
        TextView close=control("Afficher l'original");close.setOnClickListener(v->showBrowser());
        reader.addView(close);
        translatedText=label("",16,0xffedf1ff);
        translatedText.setLineSpacing(dp(4),1.05f);
        translatedText.setTextIsSelectable(true);
        translatedText.setPadding(dp(10),dp(12),dp(10),dp(20));
        reader.addView(translatedText);translatedPage.addView(reader);
        root.addView(translatedPage,new LinearLayout.LayoutParams(-1,0,1));

        web=new WebView(this);
        web.setTag("chk_tor_webview");
        WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(false);settings.setOffscreenPreRaster(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Avoid image downloads over slow Tor relays until the user enables them.
        lightweight=true;settings.setLoadsImagesAutomatically(false);
        settings.setBlockNetworkImage(true);lightweightButton.setText("Images OFF · rapide");
        web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        settings.setDomStorageEnabled(false);settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);settings.setSafeBrowsingEnabled(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
            @Override public void onProgressChanged(WebView view,int progress){
                loading.setProgress(progress);
                loading.setVisibility(progress>=100?View.GONE:View.VISIBLE);
            }
        });
        web.setDownloadListener((url,agent,disposition,type,size)->Toast.makeText(this,"Téléchargement Tor non pris en charge ; aucune requête directe envoyée",Toast.LENGTH_LONG).show());
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                if(!request.isForMainFrame())return !allowed(request.getUrl().toString());
                try{
                    String url=TorUrlPolicy.checked(request.getUrl().toString());
                    if(!runtime.policy.usable(SystemClock.elapsedRealtime()))throw new Exception("TOR_NOT_CONNECTED");
                    if(request.isRedirect())return false;
                    action("navigate",url);return true;
                }catch(Exception e){runtime.pageFailed(e.getMessage());return true;}
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                if(!allowed(request.getUrl().toString()))return new WebResourceResponse("text/plain","UTF-8",403,"Tor required",Collections.emptyMap(),new ByteArrayInputStream("TOR_REQUEST_BLOCKED".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                return null;
            }
            @Override public void onPageStarted(WebView view,String url,Bitmap favicon){runtime.pageStarted(url);}
            @Override public void onPageFinished(WebView view,String url){
                runtime.pageFinished(url,view.getTitle());address.setText(url);
                pageInfo.setText("Page : "+(view.getTitle()==null?url:view.getTitle()));
                if(autoFrench&&url!=null&&url.startsWith("http")){
                    final String loadedUrl=url;
                    runtime.main.postDelayed(()->{
                        if(!isFinishing()&&!isDestroyed()&&web!=null
                            &&autoFrench&&loadedUrl.equals(web.getUrl())
                            &&web.getVisibility()==View.VISIBLE)translatePage();
                    },350);
                }
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){
                if(request.isForMainFrame()){runtime.pageFailed("TOR_PAGE_NETWORK_ERROR: "+error.getErrorCode());pageInfo.setText("Échec réseau Tor : "+error.getDescription());}
            }
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){
                if(request.isForMainFrame()){runtime.pageFailed("TOR_PAGE_HTTP_ERROR: "+response.getStatusCode());pageInfo.setText("Site indisponible ou protection anti-Tor (HTTP "+response.getStatusCode()+")");}
            }
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){
                handler.cancel();runtime.pageFailed("TOR_PAGE_TLS_REJECTED");pageInfo.setText("Certificat HTTPS rejeté.");
            }
        });
        web.setVisibility(View.GONE);
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);runtime.attach(this,web);
        // Always open the local dashboard first, even on direct launch.
        showHome();
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable round(int color,int stroke){
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(color);bg.setCornerRadius(dp(13));bg.setStroke(dp(1),stroke);return bg;
    }
    private TextView label(String text,int size,int color){
        TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);return t;
    }
    private TextView control(String caption){
        TextView text=label(caption,14,android.graphics.Color.WHITE);
        text.setGravity(Gravity.CENTER);
        text.setTypeface(null,android.graphics.Typeface.BOLD);
        text.setClickable(true);text.setFocusable(true);
        text.setMinWidth(0);text.setMinHeight(0);
        text.setPadding(dp(10),0,dp(10),0);
        text.setBackground(round(0xff202a43,0xff405273));
        return text;
    }
    // Always-visible labels remain readable while the WebView repaints.
    private TextView small(LinearLayout row,String title,Runnable click){
        TextView b=label(title,14,android.graphics.Color.WHITE);
        b.setGravity(Gravity.CENTER);b.setTypeface(null,android.graphics.Typeface.BOLD);
        b.setPadding(dp(12),0,dp(12),0);b.setBackground(round(0xff202a43,0xff405273));
        b.setOnClickListener(v->click.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(42));p.rightMargin=dp(5);
        row.addView(b,p);return b;
    }
    private void shortcut(LinearLayout home,String title,String note,String url){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(0xff192640,0xff304560));card.setPadding(dp(15),dp(10),dp(8),dp(10));
        TextView name=label(title+"  ›",15,0xfffbf7ff);name.setTypeface(null,android.graphics.Typeface.BOLD);
        card.addView(name);card.addView(label(note,12,0xffa9bbd5));
        card.setOnClickListener(v->action("navigate",url));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(7);home.addView(card,params);
    }
    private void searchAhmia(String query){
        String q=query==null?"":query.trim();
        if(q.isEmpty()){Toast.makeText(this,"Écris un mot pour chercher.",Toast.LENGTH_SHORT).show();return;}
        if(q.length()>200)q=q.substring(0,200);
        try{action("navigate",AHMIA+"search/?q="+URLEncoder.encode(q,"UTF-8"));}
        catch(Exception e){pageInfo.setText("Recherche invalide : "+e.getMessage());}
    }
    private void openAddress(){
        String raw=address.getText().toString().trim();
        if(raw.isEmpty()){showHome();return;}
        if(raw.contains(" ")&&!raw.startsWith("http")){searchAhmia(raw);return;}
        if(!raw.contains("://"))raw=(raw.contains(".onion")?"http://":"https://")+raw;
        action("navigate",raw);
    }
    private void showHome(){translationToken++;startPage.setVisibility(View.VISIBLE);web.setVisibility(View.GONE);translatedPage.setVisibility(View.GONE);}
    private void showBrowser(){showBrowserForNavigation();}
    /** Called by TorRuntime on the UI thread for MCP navigations, including background use. */
    void showBrowserForNavigation(){translationToken++;startPage.setVisibility(View.GONE);web.setVisibility(View.VISIBLE);translatedPage.setVisibility(View.GONE);}

    private void installFrenchModel(){
        resumeFrenchAfterInstall=true;
        // Download public language models in CHK's main process, not in :tor.
        startActivity(new android.content.Intent(this,TorEntryActivity.class).putExtra("download_translate",true));
    }
    private void showTranslation(){
        startPage.setVisibility(View.GONE);web.setVisibility(View.GONE);
        translatedPage.setVisibility(View.VISIBLE);translatedPage.scrollTo(0,0);
    }
    private int translationToken;
    private String translatedUrl="",translatedBody="";
    private void translatePage(){
        if(web==null||web.getUrl()==null||web.getUrl().isEmpty()){
            Toast.makeText(this,"Ouvre d'abord une page.",Toast.LENGTH_SHORT).show();return;
        }
        final int token=++translationToken;
        final String url=web.getUrl();
        pageInfo.setText("FR · vérification du traducteur local");
        // Fast model check BEFORE any additional Tor request.
        TorFrenchTranslator.isReady(this,(ready,detail)->runOnUiThread(()->{
            if(token!=translationToken||isFinishing()||isDestroyed())return;
            if(!ready){
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Traducteur français de Tor")
                    .setMessage(detail+" Installer le module FR dans CHK ? Les pages Tor ne sont pas envoyées à Google.")
                    .setNegativeButton("Annuler",(dialog,which)->pageInfo.setText("Page originale affichée"))
                    .setPositiveButton("Installer FR",(dialog,which)->installFrenchModel())
                    .show();
                return;
            }
            // Obtain native accessibility nodes while WebView is still visible.
            // Hiding the WebView first makes its virtual children inaccessible.
            String nativeText=url.equals(translatedUrl)?"":readAccessibleWebText();
            showTranslation();
            if(url.equals(translatedUrl)&&!translatedBody.isEmpty()){
                translatedText.setText(translatedBody);
                pageInfo.setText("FR · traduction locale conservée");return;
            }
            translatedText.setText("Lecture du texte visible et traduction française…");
            if(nativeText.length()>=60){translateLocalText(token,url,nativeText,true);return;}
            translatedText.setText("Lecture de la page par Tor… L'original reste disponible.");
            try{runtime.command(new JSONObject().put("operation","read").put("url",url),(ok,value)->runOnUiThread(()->{
                if(token!=translationToken||isFinishing()||isDestroyed())return;
                if(!ok){translatedText.setText("Lecture impossible : "+value.optString("error","Site indisponible"));return;}
                translateLocalText(token,url,value.optString("text",""),false);
            }));}catch(Exception error){translatedText.setText("Lecture Tor impossible : "+error.getMessage());}
        }));
    }
    private void translateLocalText(int token,String url,String text,boolean visible){
        if(text==null||text.trim().isEmpty()){translatedText.setText("Aucun texte lisible.");return;}
        final String header="Français · "+(visible?"texte affiché":"mode lecture")+"\n\n";
        translatedText.setText(header+"Traduction en cours…");
        TorFrenchTranslator.translate(this,text,
            (partial,n,total)->runOnUiThread(()->{
                if(token!=translationToken||isFinishing()||isDestroyed())return;
                translatedText.setText(header+partial+"\n\n["+n+"/"+total+" passages]");
                pageInfo.setText("FR · "+n+"/"+total+" passages traduits");
            }),
            (ok,translated)->runOnUiThread(()->{
                if(token!=translationToken||isFinishing()||isDestroyed())return;
                translatedUrl=ok?url:"";
                translatedBody=header+translated;
                translatedText.setText(ok?translatedBody:"Échec : "+translated);
                pageInfo.setText(ok?"FR · texte traduit localement":"Traduction indisponible");
            }));
    }
    private String readAccessibleWebText(){
        StringBuilder text=new StringBuilder();
        try{
            android.view.accessibility.AccessibilityNodeInfo root=web.createAccessibilityNodeInfo();
            if(root!=null){
                collectText(root,text,new java.util.HashSet<>(),0,new int[]{0});
                root.recycle();
            }
        }catch(Exception e){android.util.Log.w("CHK-Tor","Lecture native indisponible",e);}
        return text.toString();
    }
    private void collectText(android.view.accessibility.AccessibilityNodeInfo node,
            StringBuilder text,java.util.HashSet<String> seen,int depth,int[] count){
        if(node==null||depth>20||count[0]++>500||text.length()>12000)return;
        CharSequence cs=node.getText();
        if(cs!=null){
            String line=cs.toString().trim();
            if(!line.isEmpty()&&line.length()<2500&&seen.add(line))text.append(line).append('\n');
        }
        for(int i=0;i<node.getChildCount()&&count[0]<500&&text.length()<12000;i++){
            android.view.accessibility.AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null){collectText(child,text,seen,depth+1,count);child.recycle();}
        }
    }
    private boolean allowed(String url){if(url.equals("about:blank")||url.startsWith("data:"))return true;try{TorUrlPolicy.checked(url);return runtime.policy.usable(SystemClock.elapsedRealtime());}catch(Exception e){return false;}}
    private void action(String op,String url){
        try{
            if(!op.equals("cancel_navigation")){showBrowser();pageInfo.setText("Navigation via Tor…");loading.setVisibility(View.VISIBLE);loading.setProgress(5);}
            JSONObject args=runtime.uiArgs(op);if(url!=null)args.put("url",url);
            runtime.command(args,(ok,value)->{
                if(!ok){
                    String reason=value.optString("error","site indisponible");
                    if("TOR_NAVIGATION_REPLACED".equals(reason))return;
                    if("TOR_HISTORY_BOUNDARY".equals(reason))reason="Aucune page précédente ou suivante.";
                    pageInfo.setText("Navigation Tor : "+reason);
                    loading.setVisibility(View.GONE);
                    Toast.makeText(this,reason,Toast.LENGTH_LONG).show();
                }
                else if(op.equals("cancel_navigation")){loading.setVisibility(View.GONE);pageInfo.setText("Chargement arrêté.");}
            });
        }catch(Exception e){loading.setVisibility(View.GONE);pageInfo.setText(e.getMessage());Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private static String frenchState(String value){
        if("connected".equals(value))return "Connecté";
        if("connecting".equals(value))return "Connexion en cours";
        if("disconnected".equals(value))return "Déconnecté";
        return "Erreur";
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(!resumed)return;
        try{
            JSONObject info=runtime.status();
            String failure=info.optString("error","");
            state.setText("Tor · "+frenchState(info.optString("connection_state"))+
                " · JS désactivé"+(failure.isEmpty()?"":" · "+failure));
        }catch(Exception ignored){}
        runtime.main.postDelayed(this,1500);
    }};
    @Override protected void onResume(){
        super.onResume();resumed=true;
        if(runtime!=null)runtime.main.post(tick);
        if(resumeFrenchAfterInstall){
            resumeFrenchAfterInstall=false;
            TorFrenchTranslator.isReady(this,(ready,detail)->runOnUiThread(()->{
                if(ready&&!isFinishing()&&web!=null&&web.getUrl()!=null&&web.getUrl().startsWith("http"))translatePage();
                else if(!ready)pageInfo.setText("Installation du français non terminée. Vous pouvez réessayer.");
            }));
        }
    }
    @Override protected void onPause(){resumed=false;if(runtime!=null)runtime.main.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){if(runtime!=null)runtime.detach(this);if(web!=null){web.stopLoading();web.destroy();web=null;}super.onDestroy();}
    /**
     * Screenshot from the actual Tor window. Some emulator and GPU surfaces return
     * PixelCopy.ERROR_SOURCE_NO_DATA (3) despite a completed WebView navigation.
     * Retry the native surface first. If no surface exists, render the real WebView
     * on the UI thread and label the evidence distinctly; never claim PixelCopy.
     */
    void capture(TorRuntime.Result cb){
        if(web==null||isFinishing()||isDestroyed()){cb.done(false,TorBridge.error("TOR_WEBVIEW_UNAVAILABLE_OPEN_ONCE"));return;}
        // The native start page has no visible WebView. Render only this Tor Activity,
        // with an explicit non-PixelCopy provenance instead of returning TOR_VIEW_EMPTY.
        if(web.getVisibility()!=View.VISIBLE){captureDashboard(cb);return;}
        // A native PixelCopy requires a visible window. When ChatGPT is foregrounded
        // preserve the existing isolated WebView and render it, never the phone UI.
        if(!resumed||!hasWindowFocus()){captureOffscreen(cb);return;}
        int[] at=new int[2];web.getLocationInWindow(at);
        int w=web.getWidth(),h=web.getHeight();
        if(w<1||h<1){cb.done(false,TorBridge.error("TOR_VIEW_EMPTY"));return;}
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        capturePixels(cb,bitmap,new Rect(at[0],at[1],at[0]+w,at[1]+h),0);
    }
    private void captureDashboard(TorRuntime.Result cb){
        View root=getWindow().getDecorView();
        int w=root.getWidth(),h=root.getHeight();
        if(w<1||h<1){cb.done(false,TorBridge.error("TOR_DASHBOARD_NOT_LAID_OUT"));return;}
        float scale=Math.min(1f,720f/Math.max(w,h));
        Bitmap bitmap=null;
        try{
            bitmap=Bitmap.createBitmap(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)),Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(bitmap);canvas.scale(scale,scale);root.draw(canvas);
            completeCapture(cb,bitmap,"tor_activity_draw_background","Rendered native Tor controls; not live PixelCopy pixels.");
        }catch(Exception|OutOfMemoryError e){
            if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();
            cb.done(false,TorBridge.error("TOR_DASHBOARD_CAPTURE_FAILED: "+e.getMessage()));
        }
    }
    private void captureOffscreen(TorRuntime.Result cb){
        if(web==null||isFinishing()||isDestroyed()){cb.done(false,TorBridge.error("TOR_WEBVIEW_UNAVAILABLE_OPEN_ONCE"));return;}
        int w=web.getWidth(),h=web.getHeight();
        if(w<1||h<1){cb.done(false,TorBridge.error("TOR_VIEW_EMPTY"));return;}
        // Cap allocation before drawing; background WebViews may retain large layouts.
        float factor=Math.min(1f,720f/Math.max(w,h));
        Bitmap bitmap=null;
        try{
            bitmap=Bitmap.createBitmap(Math.max(1,Math.round(w*factor)),Math.max(1,Math.round(h*factor)),Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(bitmap);canvas.scale(factor,factor);web.draw(canvas);
            completeCapture(cb,bitmap,"webview_draw_background","Retained Tor WebView only, not a live native screenshot; GPU/composited surfaces may be missing.");
        }catch(Exception|OutOfMemoryError e){
            if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();
            cb.done(false,TorBridge.error("TOR_BACKGROUND_CAPTURE_FAILED: "+e.getMessage()));
        }
    }
    private void capturePixels(TorRuntime.Result cb,Bitmap bitmap,Rect rect,int tries){
        if(web==null||isFinishing()||isDestroyed()){bitmap.recycle();cb.done(false,TorBridge.error("TOR_WEBVIEW_UNAVAILABLE_OPEN_ONCE"));return;}
        if(!resumed||!hasWindowFocus()){bitmap.recycle();captureOffscreen(cb);return;}
        try{
            PixelCopy.request(getWindow(),rect,bitmap,result->{
                if(result==PixelCopy.SUCCESS){
                    completeCapture(cb,bitmap,"pixelcopy_real_tor_window",null);
                }else if(tries<5){
                    runtime.main.postDelayed(()->capturePixels(cb,bitmap,rect,tries+1),300);
                }else{
                    // Explicitly marked WebView-only fallback: no system overlays included.
                    try{
                        Bitmap rendered=Bitmap.createBitmap(bitmap.getWidth(),bitmap.getHeight(),Bitmap.Config.ARGB_8888);
                        try{web.draw(new Canvas(rendered));
                            completeCapture(cb,rendered,"webview_draw_main_thread","PIXEL_COPY_UNAVAILABLE_"+result);
                        }catch(Exception e){rendered.recycle();throw e;}
                    }catch(Exception e){cb.done(false,TorBridge.error("TOR_SCREENSHOT_RENDER_FAILED: "+e.getMessage()));}
                    finally{bitmap.recycle();}
                }
            },runtime.main);
        }catch(Exception e){bitmap.recycle();cb.done(false,TorBridge.error("TOR_PIXEL_COPY_REQUEST_FAILED: "+e.getMessage()));}
    }
    private void completeCapture(TorRuntime.Result cb,Bitmap bitmap,String source,String note){
        Bitmap small=bitmap;
        try{
            float scale=Math.min(1f,720f/Math.max(bitmap.getWidth(),bitmap.getHeight()));
            if(scale<1f)small=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(!small.compress(Bitmap.CompressFormat.JPEG,80,bytes)||bytes.size()==0)throw new IOException("TOR_SCREENSHOT_ENCODING_FAILED");
            if(bytes.size()>500000)throw new IOException("TOR_SCREENSHOT_TOO_LARGE");
            JSONObject value=runtime.status().put("capture_source",source).put("jpeg_base64",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP));
            if(note!=null)value.put("capture_limitation",note);
            cb.done(true,value);
        }catch(Exception e){cb.done(false,TorBridge.error(e.getMessage()));}
        finally{if(small!=bitmap)small.recycle();bitmap.recycle();}
    }
}
