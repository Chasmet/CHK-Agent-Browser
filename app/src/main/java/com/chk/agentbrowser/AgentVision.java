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
    private static String script;
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
        if(root.getWidth()<=0||root.getHeight()<=0)throw new Exception("Écran non mesuré : attends son affichage.");
        JSONArray rows=new JSONArray();StringBuilder revision=new StringBuilder(context(root));collect(root,root,rows,revision,new int[]{0});
        float scale=Math.min(1f,Math.min(720f/root.getWidth(),960f/root.getHeight()));
        JSONObject out=new JSONObject().put("ok",true).put("context",context(root)).put("revision",digest(revision.toString())).put("target",root instanceof WebView?"web":"native")
            .put("width",root.getWidth()).put("height",root.getHeight()).put("image_width",Math.max(1,Math.round(root.getWidth()*scale))).put("image_height",Math.max(1,Math.round(root.getHeight()*scale)))
            .put("coordinate_space","view_pixels").put("controls",rows).put("controls_truncated",rows.length()==160).put("foreground",NativeKeyboard.foreground());
        if(root instanceof WebView)out.put("url",((WebView)root).getUrl());else out.put("activity",NativeKeyboard.activity().getClass().getSimpleName());
        return out;
    }
    static void guard(View root,JSONObject p)throws Exception{
        if(!context(root).equals(p.optString("expected_context")))throw new Exception("Écran absent ou remplacé : observe l’écran avant le geste.");
        String revision=root instanceof WebView?webRevisions.get((WebView)root):inspect(root).getString("revision");
        if(!p.optString("expected_revision").isEmpty()&&!p.optString("expected_revision").equals(revision))throw new Exception("L’interface a changé : observe à nouveau avant le geste.");
    }
    interface Observation{void ready(JSONObject info);void failed(String error);}
    static void observeWeb(Context app,WebView web,Observation cb){
        try{
            JSONObject initial=inspect(web);String expected=initial.getString("context");
            if(script==null){try(java.io.InputStream in=app.getAssets().open("agent_vision.js");ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);script=new String(out.toByteArray(),StandardCharsets.UTF_8);}}
            web.evaluateJavascript(script,raw->{try{
                if(!BrowserWebState.alive(web)||!expected.equals(context(web)))throw new Exception("La page a changé pendant l’observation.");
                JSONObject dom=new JSONObject(new JSONArray("["+raw+"]").getString(0));JSONObject info=inspect(web);
                float sx=web.getWidth()/(float)Math.max(1,dom.getDouble("css_width")),sy=sx;JSONArray controls=dom.getJSONArray("controls");
                for(int i=0;i<controls.length();i++){JSONObject bounds=controls.getJSONObject(i).getJSONObject("bounds");bounds.put("x",bounds.getDouble("x")*sx).put("y",bounds.getDouble("y")*sy).put("width",bounds.getDouble("width")*sx).put("height",bounds.getDouble("height")*sy);}
                String revision=digest(info.getString("revision")+dom.toString());webRevisions.put(web,revision);
                info.put("controls",controls).put("text",dom.getString("text")).put("controls_truncated",dom.getBoolean("controls_truncated")).put("revision",revision);
                cb.ready(info);
            }catch(Exception e){cb.failed(e.getMessage()==null?"Observation web indisponible.":e.getMessage());}});
        }catch(Exception e){cb.failed(e.getMessage());}
    }
    static void execute(Context app,WebView web,JSONObject p,AgentClient.ResultCallback cb){
        if(!cb.isActive())return;
        try{
            View root=target(p,web);JSONObject info=inspect(root);
            if(root instanceof WebView){observeWeb(app,(WebView)root,new Observation(){public void ready(JSONObject info){if(cb.isActive())capture(app,web,root,p,info,cb);}public void failed(String error){if(cb.isActive())cb.finish(false,error);}});return;}
            capture(app,web,root,p,info,cb);
        }catch(Exception e){cb.finish(false,e.getMessage()==null?"Vision indisponible.":e.getMessage());}
    }
    private static void capture(Context app,WebView web,View root,JSONObject p,JSONObject info,AgentClient.ResultCallback cb){
        try{
            if(!p.optBoolean("capture",false)){cb.finish(true,info.toString());return;}
            if(!AgentClient.get(app).isPreviewAllowed())throw new Exception("Active le partage d’aperçus dans Réglages.");
            // A retained Android WebView can expose a complete DOM while its GPU
            // content is absent from View.draw(). Never present skeleton placeholders
            // as a verified visual preview.
            final boolean isWeb=root instanceof WebView;
            final Window window=NativeKeyboard.window();
            final boolean foreground=NativeKeyboard.foreground();
            if(isWeb&&!foreground){
                info.put("capture_source","unavailable_background_webview")
                    .put("visual_verified",false)
                    .put("reason","WebView en arrière-plan : ouvrir CHK > Web pour afficher et vérifier les vrais pixels.");
                cb.finish(true,info.toString());
                return;
            }
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
                                finish(bitmap,info,isWeb?"webview_window_pixels":"window_pixels",cb);
                            }else if(isWeb){
                                bitmap.recycle();
                                cb.finish(false,"PixelCopy WebView indisponible ("+status+"). Revenir dans CHK > Web et réessayer la capture.");
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
                        cb.finish(false,"WebView hors zone capturable : ouvrir CHK > Web et relancer la capture.");return;}
                }
            }
            if(isWeb){bitmap.recycle();
                cb.finish(false,"Capture WebView réelle indisponible : CHK doit être visible au premier plan.");return;}
            draw(root,bitmap,info,cb);
        }catch(Exception e){cb.finish(false,e.getMessage()==null?"Vision indisponible.":e.getMessage());}
    }
    private static void draw(View root,Bitmap bitmap,JSONObject info,AgentClient.ResultCallback cb)throws Exception{
        Canvas canvas=new Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE);canvas.scale(bitmap.getWidth()/(float)root.getWidth(),bitmap.getHeight()/(float)root.getHeight());root.draw(canvas);
        finish(bitmap,info,"retained_view_render",cb);
    }
    private static void finish(Bitmap bitmap,JSONObject info,String source,AgentClient.ResultCallback cb)throws Exception{
        byte[] bytes=null;try{for(int quality=65;quality>=17;quality-=12){ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,quality,out);bytes=out.toByteArray();if(bytes.length<=140000)break;}}finally{bitmap.recycle();}
        if(bytes==null||bytes.length>140000)throw new Exception("Capture trop volumineuse.");
        info.put("capture_source",source).put("jpeg_base64",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP));cb.finish(true,info.toString());
    }
}
