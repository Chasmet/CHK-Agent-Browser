package com.chk.agentbrowser;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.accessibility.AccessibilityNodeInfo;
import android.content.Intent;
import java.util.HashSet;
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
    private boolean lightweight=true;
    private TextView lightweightButton,autoTranslateButton;
    private boolean translateAutomatically;
    private boolean translationSetupPending;
    private int translationRequest=0;
    private static final int TRANSLATION_SETUP_REQUEST=2094;
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
        state=label("Connexion Tor vérifiée",12,0xff80e7ab);root.addView(state);

        LinearLayout translationRow=new LinearLayout(this);
        translationRow.setOrientation(LinearLayout.HORIZONTAL);
        translationRow.setPadding(0,dp(6),0,dp(5));
        TextView translate=control("Traduire FR");translate.setOnClickListener(v->translatePage());
        translationRow.addView(translate,new LinearLayout.LayoutParams(0,dp(43),1));
        autoTranslateButton=control("FR auto OFF");
        autoTranslateButton.setOnClickListener(v->{
            translateAutomatically=!translateAutomatically;
            getPreferences(MODE_PRIVATE).edit().putBoolean("automatic_french",translateAutomatically).apply();
            autoTranslateButton.setText(translateAutomatically?"FR auto ON":"FR auto OFF");
            if(translateAutomatically)translatePage();
        });
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(dp(103),dp(43));ap.leftMargin=dp(5);
        translationRow.addView(autoTranslateButton,ap);
        TextView homeButton=control("Accueil");homeButton.setOnClickListener(v->showHome());
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(72),dp(43));hp.leftMargin=dp(7);
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
        small(tools,"←",()->action("back",null));
        small(tools,"→",()->action("forward",null));
        small(tools,"↻",()->action("reload",null));
        small(tools,"Annuler",()->action("cancel_navigation",null));
        lightweightButton=small(tools,"Mode léger ON",()->{
            lightweight=!lightweight;
            getPreferences(MODE_PRIVATE).edit().putBoolean("lightweight",lightweight).apply();
            web.getSettings().setLoadsImagesAutomatically(!lightweight);
            web.getSettings().setBlockNetworkImage(lightweight);
            lightweightButton.setText(lightweight?"Mode léger ON":"Mode léger OFF");
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
        TextView prepareFrench=control("Activer le traducteur français intégré");
        prepareFrench.setOnClickListener(v->translatePage());
        home.addView(prepareFrench);
        startPage.addView(home);
        root.addView(startPage,new LinearLayout.LayoutParams(-1,0,1));

        translatedPage=new ScrollView(this);translatedPage.setVisibility(View.GONE);
        LinearLayout reader=new LinearLayout(this);reader.setOrientation(LinearLayout.VERTICAL);
        TextView close=control("Retour à la page");close.setOnClickListener(v->showBrowser());
        reader.addView(close);
        translatedText=label("",15,0xffedf1ff);
        translatedText.setTextIsSelectable(true);
        translatedText.setPadding(dp(10),dp(12),dp(10),dp(20));
        reader.addView(translatedText);translatedPage.addView(reader);
        root.addView(translatedPage,new LinearLayout.LayoutParams(-1,0,1));

        web=new WebView(this);
        web.setTag("chk_tor_webview");
        lightweight=getPreferences(MODE_PRIVATE).getBoolean("lightweight",true);
        lightweightButton.setText(lightweight?"Mode léger ON":"Mode léger OFF");
        translateAutomatically=getPreferences(MODE_PRIVATE).getBoolean("automatic_french",false);
        autoTranslateButton.setText(translateAutomatically?"FR auto ON":"FR auto OFF");
        WebSettings settings=web.getSettings();
        settings.setLoadsImagesAutomatically(!lightweight);
        settings.setBlockNetworkImage(lightweight);
        settings.setJavaScriptEnabled(false);settings.setOffscreenPreRaster(false);
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
            @Override public void onPageStarted(WebView view,String url,Bitmap favicon){
                translationRequest++;
                runtime.pageStarted(url);
                pageInfo.setText("Chargement Tor : "+(url==null?"":url));
            }
            @Override public void onPageFinished(WebView view,String url){
                runtime.pageFinished(url,view.getTitle());address.setText(url);
                pageInfo.setText("Page : "+(view.getTitle()==null?url:view.getTitle()));
                if(translateAutomatically&&url!=null&&url.startsWith("http")){
                    final String finalUrl=url;
                    runtime.main.postDelayed(()->{
                        if(!isFinishing()&&web!=null&&finalUrl.equals(web.getUrl())&&translateAutomatically)translatePage();
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
    private TextView control(String text){
        TextView b=new TextView(this);
        b.setText(text);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(11),0,dp(11),0);
        b.setTextColor(0xfff5f2ff);
        b.setBackground(round(0xff202a43,0xff405273));
        b.setClickable(true);
        return b;
    }
    private TextView small(LinearLayout row,String title,Runnable click){
        TextView b=control(title);b.setOnClickListener(v->click.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(42));p.rightMargin=dp(5);row.addView(b,p);return b;
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
    private void showHome(){startPage.setVisibility(View.VISIBLE);web.setVisibility(View.GONE);translatedPage.setVisibility(View.GONE);}
    private void showBrowser(){showBrowserForNavigation();}
    /** Called by TorRuntime on the UI thread for MCP navigations, including background use. */
    void showBrowserForNavigation(){startPage.setVisibility(View.GONE);web.setVisibility(View.VISIBLE);translatedPage.setVisibility(View.GONE);}
    /** Native reader within Tor: no JavaScript, no external translation web service. */
    private void translatePage(){
        if(web==null||web.getUrl()==null||!web.getUrl().startsWith("http")){
            pageInfo.setText("Ouvrez d'abord une page via Tor, puis utilisez Traduire FR.");
            return;
        }
        final String url=web.getUrl();
        final int token=++translationRequest;
        TorFrenchTranslator.modelsReady(installed->runOnUiThread(()->{
            if(isFinishing()||isDestroyed()||token!=translationRequest||web==null||!url.equals(web.getUrl()))return;
            if(!installed){
                if(translationSetupPending)return;
                translationSetupPending=true;
                pageInfo.setText("Préparation du français hors ligne dans CHK…");
                try{
                    startActivityForResult(new Intent(this,TorEntryActivity.class)
                        .putExtra("translate_setup",true),TRANSLATION_SETUP_REQUEST);
                }catch(Exception e){
                    translationSetupPending=false;
                    pageInfo.setText("Installation de traduction impossible : "+e.getMessage());
                }
                return;
            }
            translateCurrentPage(token,url);
        }));
    }
    private void translateCurrentPage(int token,String url){
        // Read text from the already-rendered WebView accessibility tree first.
        // This avoids a second expensive Tor download on supported pages.
        String rendered=readVisibleText();
        startPage.setVisibility(View.GONE);
        web.setVisibility(View.GONE);
        translatedPage.setVisibility(View.VISIBLE);
        translatedPage.scrollTo(0,0);
        translatedText.setText("Traduction française locale en cours…\nLe texte reste dans CHK.");
        if(rendered.length()>=100){
            translateText(token,url,rendered,"Texte accessible de la page");
        }else{
            translatedText.setText("Extraction du texte via Tor en cours…\nCertains sites .onion répondent lentement.");
            try{
                runtime.command(new JSONObject().put("operation","read").put("url",url),(ok,value)->{
                    if(token!=translationRequest||isFinishing()||isDestroyed())return;
                    if(!ok){
                        translatedText.setText("Impossible d'obtenir le texte de cette page via Tor : "+
                            value.optString("error","page indisponible")+
                            "\nRevenez à la page originale.");
                        return;
                    }
                    translateText(token,url,value.optString("text",""),"Lecture simplifiée via Tor");
                });
            }catch(Exception e){
                translatedText.setText("Lecture Tor indisponible : "+e.getMessage());
            }
        }
    }
    private void translateText(int token,String url,String original,String source){
        TorFrenchTranslator.translate(this,original,(success,result)->runOnUiThread(()->{
            if(isFinishing()||isDestroyed()||web==null||token!=translationRequest)return;
            if(!success){
                translatedText.setText("Traduction locale indisponible : "+result+
                    "\nRetournez à la page originale pour continuer.");
                return;
            }
            translatedText.setText("Français · "+source+
                "\nMode lecture simplifié, sans traduction des images ou des formulaires.\n\n"+result);
        }));
    }
    /** Text extraction never enables JS or sends user data to an external service. */
    private String readVisibleText(){
        if(web==null)return "";
        AccessibilityNodeInfo root=null;
        try{
            root=web.createAccessibilityNodeInfo();
            if(root==null)return "";
            StringBuilder out=new StringBuilder();
            HashSet<String> known=new HashSet<>();
            collectText(root,out,known,0);
            return out.toString().trim();
        }catch(Exception e){return "";}
        finally{if(root!=null)root.recycle();}
    }
    private void collectText(AccessibilityNodeInfo node,StringBuilder out,HashSet<String> known,int depth){
        if(node==null||depth>18||out.length()>8000||known.size()>650)return;
        if(!node.isPassword()&&!node.isEditable()){
            CharSequence value=node.getText();
            if(value!=null){
                String text=value.toString().trim();
                if(text.length()>1&&text.length()<1800&&known.add(text)){
                    if(out.length()>0)out.append('\n');
                    out.append(text);
                }
            }
        }
        for(int i=0;i<node.getChildCount()&&out.length()<=8000;i++){
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null){
                try{collectText(child,out,known,depth+1);}finally{child.recycle();}
            }
        }
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==TRANSLATION_SETUP_REQUEST){
            translationSetupPending=false;
            if(resultCode==Activity.RESULT_OK)translatePage();
            else pageInfo.setText("La traduction française hors ligne n'est pas encore installée.");
        }
    }
    @Override public void onBackPressed(){
        if(translatedPage!=null&&translatedPage.getVisibility()==View.VISIBLE){showBrowser();return;}
        if(web!=null&&web.getVisibility()==View.VISIBLE&&web.canGoBack()){action("back",null);return;}
        if(startPage!=null&&startPage.getVisibility()!=View.VISIBLE){showHome();return;}
        super.onBackPressed();
    }
    private boolean allowed(String url){if(url.equals("about:blank")||url.startsWith("data:"))return true;try{TorUrlPolicy.checked(url);return runtime.policy.usable(SystemClock.elapsedRealtime());}catch(Exception e){return false;}}
    private void action(String op,String url){
        try{
            if(!op.equals("cancel_navigation")){showBrowser();pageInfo.setText("Navigation via Tor…");loading.setVisibility(View.VISIBLE);loading.setProgress(5);}
            JSONObject args=runtime.uiArgs(op);if(url!=null)args.put("url",url);
            runtime.command(args,(ok,value)->{
                if(!ok){pageInfo.setText("Navigation interrompue : "+value.optString("error","site indisponible"));loading.setVisibility(View.GONE);Toast.makeText(this,value.optString("error","Navigation non confirmée"),Toast.LENGTH_LONG).show();}
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
    private final Runnable tick=new Runnable(){public void run(){if(!resumed)return;try{JSONObject s=runtime.status();state.setText("Tor · "+frenchState(s.optString("connection_state"))+" · "+s.optString("error")+"\nJavaScript désactivé · stockage et réseau séparés de CHK Web");}catch(Exception ignored){}new Handler(Looper.getMainLooper()).postDelayed(this,1000);}};
    @Override protected void onResume(){super.onResume();resumed=true;if(runtime!=null)runtime.main.post(tick);}
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
