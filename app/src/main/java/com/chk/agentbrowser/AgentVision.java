package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.PixelCopy;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Coordinates and pixels refer to CHK's own view, never the display of another app. */
final class AgentVision {
    private static final java.util.WeakHashMap<WebView,String> webRevisions=new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<WebView,JSONObject> webFrames=new java.util.WeakHashMap<>();
    private static String script;
    private static final java.util.WeakHashMap<WebView,String> settledContexts=new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<WebView,Long> settledSince=new java.util.WeakHashMap<>();
    private AgentVision(){}
    static String digest(String s)throws Exception{
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    static View target(JSONObject p,WebView web)throws Exception{
        String target=p.optString("target","native");
        if(target.equals("native"))return NativeKeyboard.window().getDecorView();
        if(!target.equals("web")||!BrowserWebState.alive(web))throw new Exception("Écran CHK ou onglet web indisponible.");
        String host=p.optString("expected_host","");
        if(!host.isEmpty()&&!host.equalsIgnoreCase(android.net.Uri.parse(web.getUrl()==null?"":web.getUrl()).getHost()))throw new Exception("Le site a changé.");
        return web;
    }
    static String context(View root){
        return (root instanceof WebView?"webview:":"screen:")+NativeKeyboard.identity(root)+":"+root.getWidth()+"x"+root.getHeight()+
            (root instanceof WebView?":"+BrowserWebState.documentVersion((WebView)root):"");
    }
    private static String clip(String s,int n){return s.length()>n?s.substring(0,n):s;}
    private static void collect(View v,View root,JSONArray out,StringBuilder revision,int[] visits)throws Exception{
        if(v.getVisibility()!=View.VISIBLE||++visits[0]>3000)return;
        int[] at=new int[2],base=new int[2];v.getLocationOnScreen(at);root.getLocationOnScreen(base);
        int x=at[0]-base[0],y=at[1]-base[1],w=v.getWidth(),h=v.getHeight();
        boolean protectedView=v instanceof EditText&&NativeKeyboard.protectedField((EditText)v);
        String text=protectedView?"":v instanceof TextView?((TextView)v).getText().toString():"";
        String label=v.getContentDescription()==null?"":v.getContentDescription().toString();
        revision.append(v.getClass().getSimpleName()).append(':').append(x).append(',').append(y).append(',').append(w).append(',').append(h).append(':').append(v.getScrollX()).append(':').append(v.getScrollY()).append(':').append(v.isEnabled()).append(':').append(clip(text,400));
        if(v instanceof WebView)revision.append(BrowserWebState.documentVersion((WebView)v)).append(((WebView)v).getUrl());
        if(!protectedView&&out.length()<160&&w>0&&h>0&&x+w>0&&y+h>0&&x<root.getWidth()&&y<root.getHeight()&&(!text.isEmpty()||!label.isEmpty()||v.isClickable()||v.isLongClickable())){
            String id="";try{id=v.getResources().getResourceEntryName(v.getId());}catch(Exception ignored){}
            out.put(new JSONObject().put("id",id).put("selector","view:"+NativeKeyboard.identity(v)).put("role",v.getClass().getSimpleName())
                .put("text",clip(text,180)).put("label",clip(label,120)).put("enabled",v.isEnabled()).put("clickable",v.isClickable()).put("long_clickable",v.isLongClickable())
                .put("bounds",new JSONObject().put("x",Math.max(0,x)).put("y",Math.max(0,y)).put("width",Math.max(0,Math.min(x+w,root.getWidth())-Math.max(0,x))).put("height",Math.max(0,Math.min(y+h,root.getHeight())-Math.max(0,y)))));
        }
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),root,out,revision,visits);
    }
    static JSONObject inspect(View root)throws Exception{
        if(root instanceof WebView)ensureMeasured((WebView)root);
        if(root.getWidth()<=0||root.getHeight()<=0)throw new Exception("Écran non mesuré : attends son affichage.");
        JSONArray rows=new JSONArray();StringBuilder revision=new StringBuilder(context(root));collect(root,root,rows,revision,new int[]{0});
        float scale=Math.min(1f,Math.min(720f/root.getWidth(),960f/root.getHeight()));
        JSONObject out=new JSONObject().put("ok",true).put("context",context(root)).put("revision",digest(revision.toString())).put("target",root instanceof WebView?"web":"native")
            .put("width",root.getWidth()).put("height",root.getHeight()).put("image_width",Math.max(1,Math.round(root.getWidth()*scale))).put("image_height",Math.max(1,Math.round(root.getHeight()*scale)))
            .put("coordinate_space","view_pixels").put("controls",rows).put("controls_truncated",rows.length()==160).put("foreground",NativeKeyboard.foreground());
        if(root instanceof WebView)out.put("url",((WebView)root).getUrl());else out.put("activity",NativeKeyboard.activity().getClass().getSimpleName());
        return out;
    }
    /** An inactive Activity can create a tab without a layout pass. DOM and
     * native file selectors still need a real viewport; this is not a screenshot. */
    static void ensureMeasured(WebView web){
        if(web.getWidth()>0&&web.getHeight()>0)return;
        android.util.DisplayMetrics metrics=web.getResources().getDisplayMetrics();
        int width=Math.max(320,metrics.widthPixels),height=Math.max(480,metrics.heightPixels);
        if(web.getParent() instanceof View){View parent=(View)web.getParent();if(parent.getWidth()>0)width=parent.getWidth();if(parent.getHeight()>0)height=parent.getHeight();}
        web.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        web.layout(0,0,width,height);
    }
    static void guard(View root,JSONObject p)throws Exception{
        if(!context(root).equals(p.optString("expected_context")))throw new Exception("Écran absent ou remplacé : observe l’écran avant le geste. Attendu="+p.optString("expected_context")+" ; actuel="+context(root));
        String revision=root instanceof WebView?webRevisions.get((WebView)root):inspect(root).getString("revision");
        if(!p.optString("expected_revision").isEmpty()&&!p.optString("expected_revision").equals(revision))throw new Exception("L’interface a changé : observe à nouveau avant le geste.");
    }
    interface Observation{void ready(JSONObject info);void failed(String error);}
    static void observeWeb(Context app,WebView web,Observation cb){
        java.util.concurrent.atomic.AtomicBoolean ended=new java.util.concurrent.atomic.AtomicBoolean();
        Runnable timeout=()->{if(ended.compareAndSet(false,true))cb.failed("Observation DOM expirée après 5 secondes ; aucun geste envoyé.");};
        WorkspaceCommands.UI.postDelayed(timeout,5000L);
        settleWeb(app,web,new Observation(){
            public void ready(JSONObject info){if(ended.compareAndSet(false,true)){WorkspaceCommands.UI.removeCallbacks(timeout);cb.ready(info);}}
            public void failed(String error){if(ended.compareAndSet(false,true)){WorkspaceCommands.UI.removeCallbacks(timeout);cb.failed(error);}}
        },0);
    }
    private static void settleWeb(Context app,WebView web,Observation cb,int attempt){
        if(!BrowserWebState.alive(web)){cb.failed("Onglet fermé pendant l'observation.");return;}
        ensureMeasured(web);String current=context(web);long now=android.os.SystemClock.elapsedRealtime();
        if(!current.equals(settledContexts.get(web))){settledContexts.put(web,current);settledSince.put(web,now);}
        if(now-settledSince.get(web)<90L){
            if(attempt>=40){cb.failed("La taille de l'onglet change encore : attendre la fin du clavier ou de l'animation.");return;}
            WorkspaceCommands.UI.postDelayed(()->settleWeb(app,web,cb,attempt+1),20L);return;
        }
        observeWebNow(app,web,cb);
    }
    private static void observeWebNow(Context app,WebView web,Observation cb){
        try{
            JSONObject initial=inspect(web);String expected=initial.getString("context");
            if(script==null){try(java.io.InputStream in=app.getAssets().open("agent_vision.js");ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);script=new String(out.toByteArray(),StandardCharsets.UTF_8);}}
            web.evaluateJavascript(script,raw->{try{
                if(!BrowserWebState.alive(web)||!expected.equals(context(web)))throw new Exception("La page a changé pendant l’observation.");
                JSONObject dom=new JSONObject(new JSONArray("["+raw+"]").getString(0));JSONObject info=inspect(web);
                float sx=web.getWidth()/(float)Math.max(1,dom.getDouble("css_width"));
                float sy=web.getHeight()/(float)Math.max(1,dom.getDouble("css_height"));
                JSONArray controls=dom.getJSONArray("controls"),cards=dom.optJSONArray("cards"),scrollables=dom.optJSONArray("scrollables");
                scaleBounds(controls,sx,sy);scaleBounds(cards,sx,sy);scaleBounds(scrollables,sx,sy);
                // Execution timing is telemetry, NOT page state: excluding it prevents false stale revisions.
                 JSONObject stableDom=new JSONObject(dom.toString());stableDom.remove("observed_duration_ms");
                 String revision=digest(info.getString("revision")+stableDom.toString());webRevisions.put(web,revision);
                info.put("controls",controls).put("text",dom.getString("text")).put("controls_truncated",dom.getBoolean("controls_truncated")).put("revision",revision);
                info.put("cards",cards==null?new JSONArray():cards).put("scrollables",scrollables==null?new JSONArray():scrollables)
                    .put("cards_truncated",dom.optBoolean("cards_truncated",false))
                    .put("dom_scan_ms",dom.optInt("observed_duration_ms",-1));
                info.put("observation_source","live_dom").put("observed_at",System.currentTimeMillis());
                cb.ready(info);
            }catch(Exception e){cb.failed(e.getMessage()==null?"Observation web indisponible.":e.getMessage());}});
        }catch(Exception e){cb.failed(e.getMessage());}
    }
    /** Maps CSS viewport coordinates to the Android WebView coordinates used by the pointer. */
    private static void scaleBounds(JSONArray items,float sx,float sy)throws Exception{
        if(items==null)return;
        for(int i=0;i<items.length();i++){
            JSONObject row=items.optJSONObject(i);
            if(row==null)continue;
            JSONObject rect=row.optJSONObject("bounds");
            if(rect==null)continue;
            rect.put("x",rect.optDouble("x")*sx).put("y",rect.optDouble("y")*sy)
                .put("width",rect.optDouble("width")*sx).put("height",rect.optDouble("height")*sy);
        }
    }
    static void execute(Context app,WebView web,JSONObject p,AgentClient.ResultCallback cb){
        if(!cb.isActive())return;
        try{
            View root=target(p,web);JSONObject info=inspect(root);
            if(root instanceof WebView){observeWeb(app,(WebView)root,new Observation(){public void ready(JSONObject info){if(cb.isActive())capture(app,web,root,p,info,cb);}public void failed(String error){if(cb.isActive())cb.finish(false,error);}});return;}
            capture(app,web,root,p,info,cb);
        }catch(Exception e){cb.finish(false,e.getMessage()==null?"Vision indisponible.":e.getMessage());}
    }
    static boolean visibleWindow(View root){
        try{return NativeKeyboard.foreground()&&root.isShown()&&root.isAttachedToWindow()&&root.getRootView()==NativeKeyboard.window().getDecorView();}
        catch(Exception e){return false;}
    }
    private static void capture(Context app,WebView web,View root,JSONObject p,JSONObject info,AgentClient.ResultCallback cb){
        try{
            if(!p.optBoolean("capture",false)){cb.finish(true,info.toString());return;}
            if(!AgentClient.get(app).isPreviewAllowed())throw new Exception("Active le partage d’aperçus dans Réglages.");
            // A retained Android WebView can expose a complete DOM while its GPU
            // content is absent from View.draw(). Never present skeleton placeholders
            // as a verified visual preview.
            final boolean isWeb=root instanceof WebView;
            final boolean foreground=NativeKeyboard.foreground();
            if(isWeb&&!visibleWindow(root)){
                AgentClient.ResultCallback captureOwner=AgentControl.lease(web,false,cb);
                PrivateWebCapture.capture(app,web,info,captureOwner,new PrivateWebCapture.Result(){
                    public void ready(Bitmap bitmap){try{finish(bitmap,info,"private_webview_pixels",captureOwner,web);}catch(Exception e){captureOwner.finish(false,e.getMessage());}}
                    public void unavailable(String reason){try{AgentVision.unavailable(web,info,reason,captureOwner);}catch(Exception e){captureOwner.finish(false,e.getMessage());}}
                });return;
            }
            final Window window=NativeKeyboard.window();
            if(window!=null&&(window.getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0)
                throw new Exception("Cette fenêtre protège sa capture.");
            Bitmap bitmap=Bitmap.createBitmap(info.getInt("image_width"),info.getInt("image_height"),Bitmap.Config.ARGB_8888);
            if(Build.VERSION.SDK_INT>=26&&window!=null&&foreground&&root.isAttachedToWindow()){
                try{
                    PixelCopy.OnPixelCopyFinishedListener listener=status->{
                        if(!cb.isActive()){bitmap.recycle();return;}
                        try{
                            if(target(p,web)!=root||!context(root).equals(info.getString("context")))
                                throw new Exception("Écran remplacé pendant la capture.");
                            if(status==PixelCopy.SUCCESS){
                                info.put("visual_verified",true);
                                finish(bitmap,info,isWeb?"webview_window_pixels":"window_pixels",cb,isWeb?web:null);
                            }else if(isWeb){
                                bitmap.recycle();
                                unavailable(web,info,"PixelCopy indisponible ("+status+") ; contrôles DOM actuels disponibles.",cb);
                            }else draw(root,bitmap,info,cb);
                        }catch(Exception e){if(!bitmap.isRecycled())bitmap.recycle();cb.finish(false,e.getMessage());}
                    };
                    if(isWeb){
                        int[] location=new int[2];root.getLocationInWindow(location);
                        Rect bounds=new Rect(location[0],location[1],
                            location[0]+root.getWidth(),location[1]+root.getHeight());
                        if(bounds.left<0||bounds.top<0||bounds.isEmpty())
                            throw new IllegalArgumentException("WebView partiellement hors écran");
                        PixelCopy.request(window,bounds,bitmap,listener,WorkspaceCommands.UI);
                    }else PixelCopy.request(window,bitmap,listener,WorkspaceCommands.UI);
                    return;
                }catch(IllegalArgumentException ignored){
                    if(isWeb){bitmap.recycle();
                        unavailable(web,info,"WebView hors zone capturable ; contrôles DOM actuels disponibles.",cb);return;}
                }
            }
            if(isWeb){bitmap.recycle();
                unavailable(web,info,"Pixels WebView actuels indisponibles ; contrôles DOM actuels disponibles.",cb);return;}
            draw(root,bitmap,info,cb);
        }catch(Exception e){cb.finish(false,e.getMessage()==null?"Vision indisponible.":e.getMessage());}
    }
    private static void unavailable(WebView web,JSONObject info,String reason,AgentClient.ResultCallback cb)throws Exception{
        info.put("capture_source","unavailable_background_webview").put("visual_verified",false)
            .put("capture_available",false).put("capture_current",false).put("reason",reason);
        JSONObject frame=webFrames.get(web);
        if(frame!=null&&frame.optString("context").equals(info.optString("context"))
            &&frame.optString("url").equals(info.optString("url"))){
            long age=android.os.SystemClock.elapsedRealtime()-frame.optLong("elapsed_at");
            if(age>=0&&age<=3000L){
                info.put("capture_source","cached_window_pixels").put("capture_available",true)
                    .put("capture_age_ms",age).put("captured_at",frame.getLong("captured_at"))
                    .put("capture_revision",frame.getString("revision")).put("jpeg_base64",frame.getString("jpeg_base64"));
            }
        }
        cb.finish(true,info.toString());
    }
    private static void draw(View root,Bitmap bitmap,JSONObject info,AgentClient.ResultCallback cb)throws Exception{
        Canvas canvas=new Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE);canvas.scale(bitmap.getWidth()/(float)root.getWidth(),bitmap.getHeight()/(float)root.getHeight());root.draw(canvas);
        finish(bitmap,info,"retained_view_render",cb);
    }
    private static void finish(Bitmap bitmap,JSONObject info,String source,AgentClient.ResultCallback cb)throws Exception{
        finish(bitmap,info,source,cb,null);
    }
    private static void finish(Bitmap bitmap,JSONObject info,String source,AgentClient.ResultCallback cb,WebView web)throws Exception{
        byte[] bytes=null;try{for(int quality=65;quality>=17;quality-=12){ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,quality,out);bytes=out.toByteArray();if(bytes.length<=140000)break;}}finally{bitmap.recycle();}
        if(bytes==null||bytes.length>140000)throw new Exception("Capture trop volumineuse.");
        info.put("capture_source",source).put("capture_available",true)
            .put("capture_current",!source.equals("retained_view_render"))
            .put("visual_verified",!source.equals("retained_view_render"))
            .put("captured_at",System.currentTimeMillis()).put("capture_age_ms",0)
            .put("jpeg_base64",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP));
        if(web!=null){
            if(webFrames.size()>=2)webFrames.clear();
            webFrames.put(web,new JSONObject(info.toString()).put("elapsed_at",android.os.SystemClock.elapsedRealtime()));
        }
        cb.finish(true,info.toString());
    }
}
