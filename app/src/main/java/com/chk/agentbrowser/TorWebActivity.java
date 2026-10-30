package com.chk.agentbrowser;

import android.app.Activity;
import android.graphics.*;
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
    private TorRuntime runtime;
    private WebView web;
    private TextView state;
    private EditText address;
    boolean resumed;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);runtime=TorRuntime.get(this);
        if(!runtime.policy.usable(SystemClock.elapsedRealtime())){finish();return;}
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setBackgroundColor(0xff16141e);
        state=new TextView(this);state.setTextColor(0xffe4ceff);state.setPadding(14,10,14,10);box.addView(state);
        LinearLayout bar=new LinearLayout(this);address=new EditText(this);address.setSingleLine();address.setTextColor(0xffeeeeee);address.setText("https://check.torproject.org/?lang=fr");address.setHint("Adresse HTTP(S) via Tor");address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);bar.addView(address,new LinearLayout.LayoutParams(0,-2,1));
        Button go=new Button(this);go.setText("Aller");go.setOnClickListener(v->action("navigate",address.getText().toString()));bar.addView(go);box.addView(bar);
        LinearLayout tools=new LinearLayout(this);button(tools,"←",()->action("back",null));button(tools,"→",()->action("forward",null));button(tools,"↻",()->action("reload",null));button(tools,"Stop Tor",()->startActivity(new android.content.Intent(this,TorEntryActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("disconnect_tor",true)));button(tools,"Retour CHK",this::finish);box.addView(tools);
        web=new WebView(this);web.setTag("chk_tor_webview");WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(false);settings.setOffscreenPreRaster(true);settings.setDomStorageEnabled(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setGeolocationEnabled(false);settings.setSaveFormData(false);settings.setSafeBrowsingEnabled(false);settings.setSupportMultipleWindows(false);settings.setMediaPlaybackRequiresUserGesture(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest request){request.deny();}});
        web.setDownloadListener((url,agent,disposition,type,size)->Toast.makeText(this,"Téléchargement Tor non pris en charge ; aucune requête directe envoyée",Toast.LENGTH_LONG).show());
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                if(!request.isForMainFrame())return !allowed(request.getUrl().toString());
                try{String url=TorUrlPolicy.checked(request.getUrl().toString());if(!runtime.policy.usable(SystemClock.elapsedRealtime()))throw new Exception("TOR_NOT_CONNECTED");
                    if(request.isRedirect())return false;action("navigate",url);return true;
                }catch(Exception e){runtime.pageFailed(e.getMessage());return true;}
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                if(!allowed(request.getUrl().toString()))return new WebResourceResponse("text/plain","UTF-8",403,"Tor required",Collections.emptyMap(),new ByteArrayInputStream("TOR_REQUEST_BLOCKED".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                return null;
            }
            @Override public void onPageStarted(WebView view,String url,Bitmap favicon){runtime.pageStarted(url);}
            @Override public void onPageFinished(WebView view,String url){runtime.pageFinished(url,view.getTitle());address.setText(url);}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())runtime.pageFailed("TOR_PAGE_NETWORK_ERROR: "+error.getErrorCode());}
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){if(request.isForMainFrame())runtime.pageFailed("TOR_PAGE_HTTP_ERROR: "+response.getStatusCode());}
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){handler.cancel();runtime.pageFailed("TOR_PAGE_TLS_REJECTED");}
        });
        box.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(box);runtime.attach(this,web);
    }
    private boolean allowed(String url){if(url.equals("about:blank")||url.startsWith("data:"))return true;try{TorUrlPolicy.checked(url);return runtime.policy.usable(SystemClock.elapsedRealtime());}catch(Exception e){return false;}}
    private void button(LinearLayout row,String title,Runnable action){Button b=new Button(this);b.setAllCaps(false);b.setText(title);b.setOnClickListener(v->action.run());row.addView(b,new LinearLayout.LayoutParams(0,-2,1));}
    private void action(String op,String url){try{JSONObject args=runtime.uiArgs(op);if(url!=null)args.put("url",url);runtime.command(args,(ok,value)->{if(!ok)Toast.makeText(this,value.optString("error","Navigation non confirmée"),Toast.LENGTH_LONG).show();});}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}
    private final Runnable tick=new Runnable(){public void run(){if(!resumed)return;try{JSONObject s=runtime.status();state.setText("Tor · "+s.optString("connection_state")+" · "+s.optString("error")+"\nJavaScript désactivé · stockage et réseau séparés de CHK Web");}catch(Exception ignored){}new Handler(Looper.getMainLooper()).postDelayed(this,1000);}};
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
        // A native PixelCopy requires a visible window. When ChatGPT is foregrounded
        // preserve the existing isolated WebView and render it, never the phone UI.
        if(!resumed||!hasWindowFocus()){captureOffscreen(cb);return;}
        int[] at=new int[2];web.getLocationInWindow(at);
        int w=web.getWidth(),h=web.getHeight();
        if(w<1||h<1){cb.done(false,TorBridge.error("TOR_VIEW_EMPTY"));return;}
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        capturePixels(cb,bitmap,new Rect(at[0],at[1],at[0]+w,at[1]+h),0);
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
