package com.chk.agentbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;

/** A desktop User-Agent alone cannot unlock responsive desktop CSS. */
final class BrowserViewport {
    private static final String PREF="browser_site_settings";
    private BrowserViewport(){}
    static String host(String url){
        try {
            if(!BrowserPolicy.webUrl(url))return "";
            String host=Uri.parse(url).getHost();
            return host==null?"":host.toLowerCase(java.util.Locale.ROOT);
        }catch(Exception e){return "";}
    }
    static int dp(Context app,float dp){
        return Math.round(dp*app.getResources().getDisplayMetrics().density);
    }
    static boolean desktop(Context app,String url){
        String h=host(url);
        return !h.isEmpty()&&app.getSharedPreferences(PREF,0).getBoolean(h+":desktop",false);
    }
    static int width(Context app,String url){
        int w=app.getSharedPreferences(PREF,0).getInt(host(url)+":desktop_width",1366);
        return w==1920?1920:1366;
    }
    static void change(Context app,String url,String mode,int requestedWidth){
        String h=host(url);
        if(h.isEmpty())throw new IllegalArgumentException("Page HTTP/HTTPS requise.");
        if(!"mobile".equals(mode)&&!"desktop".equals(mode)&&!"auto".equals(mode))
            throw new IllegalArgumentException("Choisir mobile, desktop ou auto.");
        if(requestedWidth!=1366&&requestedWidth!=1920)
            throw new IllegalArgumentException("Largeur PC : 1366 ou 1920.");
        boolean wide="desktop".equals(mode)||"auto".equals(mode)&&("github.com".equals(h)||"play.google.com".equals(h));
        app.getSharedPreferences(PREF,0).edit().putBoolean(h+":desktop",wide)
            .putInt(h+":desktop_width",requestedWidth).apply();
    }
    static void apply(Context app,WebView web,String url){
        if(web==null)return;
        boolean wide=desktop(app,url);
        ViewGroup parent=web.getParent() instanceof ViewGroup?(ViewGroup)web.getParent():null;
        if(parent!=null&&parent.getId()==R.id.web_container){
            if(parent.getWidth()<=0||parent.getHeight()<=0)return;
            int actualWidth=wide?dp(app,width(app,url)):parent.getWidth();
            float scale=wide?Math.min(1f,parent.getWidth()/(float)actualWidth):1f;
            int actualHeight=wide?Math.max(dp(app,width(app,url)==1920?1080:768),
                Math.min(dp(app,2800),Math.round(parent.getHeight()/scale))):parent.getHeight();
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(
                wide?actualWidth:ViewGroup.LayoutParams.MATCH_PARENT,
                wide?actualHeight:ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP|Gravity.LEFT);
            ViewGroup.LayoutParams old=web.getLayoutParams();
            if(old==null||old.width!=lp.width||old.height!=lp.height)web.setLayoutParams(lp);
            web.setPivotX(0f);web.setPivotY(0f);
            if(Math.abs(web.getScaleX()-scale)>0.001f)web.setScaleX(scale);
            if(Math.abs(web.getScaleY()-scale)>0.001f)web.setScaleY(scale);
            return;
        }
        // Autonomous WebViews without a parent still receive a genuine desktop viewport.
        int w=dp(app,wide?width(app,url):540);
        int h=dp(app,wide?(width(app,url)==1920?1080:768):960);
        if(web.getWidth()!=w||web.getHeight()!=h){
            web.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));
            web.layout(0,0,w,h);
        }
        web.setPivotX(0f);web.setPivotY(0f);web.setScaleX(1f);web.setScaleY(1f);
    }
    static JSONObject info(Context app,WebView web)throws Exception {
        String url=web.getUrl()==null?BrowserWebState.url(web):web.getUrl();
        return new JSONObject().put("mode",desktop(app,url)?"desktop":"mobile")
            .put("requested_width_css",desktop(app,url)?width(app,url):0)
            .put("view_width_pixels",web.getWidth()).put("view_height_pixels",web.getHeight())
            .put("visual_scale",web.getScaleX()).put("host",host(url))
            .put("desktop_user_agent",web.getSettings().getUserAgentString().contains("X11"));
    }
    static void verify(Context app,WebView web,AgentClient.ResultCallback cb){
        if(!cb.isActive())return;
        try{
            JSONObject result=info(app,web);
            boolean wide="desktop".equals(result.optString("mode"));
            web.evaluateJavascript("(function(){return JSON.stringify({css_width:innerWidth,css_height:innerHeight,client_width:document.documentElement.clientWidth,ready:document.readyState,url:location.href})})()",raw->{
                if(!cb.isActive())return;
                try{
                    JSONObject measured=new JSONObject(new JSONArray("["+raw+"]").getString(0));
                    result.put("dom",measured);
                    int css=measured.optInt("css_width",0);
                    boolean verified=css>0&&(!wide||css>=1200);
                    result.put("verified",verified).put("state",verified?"SUCCESS":"INDETERMINATE");
                    result.put("reload_performed",false);
                    cb.finish(verified,result.toString());
                }catch(Exception e){cb.finish(false,"Viewport non confirmé : "+e.getMessage());}
            });
        }catch(Exception e){cb.finish(false,"Viewport inaccessible : "+e.getMessage());}
    }
    static void command(Context app,WebView web,JSONObject args,boolean write,AgentClient.ResultCallback cb){
        if(!write){verify(app,web,cb);return;}
        try{
            String url=web.getUrl();
            change(app,url,args.getString("mode"),args.optInt("width",1366));
            BrowserSiteSettings.apply(app,web,url);
            apply(app,web,url);
            if(args.optBoolean("reload",false)){
                web.reload();
                cb.finish(true,new JSONObject().put("state","INDETERMINATE")
                    .put("reloaded",true).put("verified",false)
                    .put("message","Rechargement lancé ; inspecter avant de poursuivre.").toString());
                return;
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->verify(app,web,cb),450L);
        }catch(Exception e){cb.finish(false,"Mode PC non appliqué : "+e.getMessage());}
    }
}
