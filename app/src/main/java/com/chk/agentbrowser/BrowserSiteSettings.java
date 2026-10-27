package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Owner-controlled compatibility per host; never disables certificate validation. */
final class BrowserSiteSettings {
    private static final String PREF="browser_site_settings";
    private static final java.util.WeakHashMap<WebView,Boolean> APPLIED=new java.util.WeakHashMap<>();
    private static final String DESKTOP="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private BrowserSiteSettings(){}
    static void apply(Context app,WebView web,String url){
        String host=Uri.parse(url==null?"":url).getHost();if(host==null)return;
        host=host.toLowerCase(java.util.Locale.ROOT);
        android.content.SharedPreferences prefs=app.getSharedPreferences(PREF,0);
        boolean google=host.equals("google.com")||host.endsWith(".google.com")||host.endsWith(".googleusercontent.com")||host.endsWith(".gstatic.com");
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,prefs.getBoolean(host+":cookies",google));
        if(prefs.contains(host+":desktop")||APPLIED.containsKey(web)){
            boolean desktop=prefs.getBoolean(host+":desktop",false);
            String agent=desktop?DESKTOP:WebSettings.getDefaultUserAgent(app);
            if(!agent.equals(web.getSettings().getUserAgentString()))web.getSettings().setUserAgentString(agent);
            if(desktop)APPLIED.put(web,true);else APPLIED.remove(web);
        }
    }
    static void show(Activity app,WebView web){
        if(web==null||!BrowserPolicy.webUrl(web.getUrl()))return;
        String host=Uri.parse(web.getUrl()).getHost().toLowerCase(java.util.Locale.ROOT);
        android.content.SharedPreferences prefs=app.getSharedPreferences(PREF,0);
        LinearLayout panel=new LinearLayout(app);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(28,12,28,12);
        TextView title=new TextView(app);title.setText(host+(web.getUrl().startsWith("http:")?"\nConnexion HTTP non chiffrée":""));panel.addView(title);
        Switch desktop=new Switch(app);desktop.setText("Afficher la version ordinateur");desktop.setChecked(prefs.getBoolean(host+":desktop",false));panel.addView(desktop);
        Switch cookies=new Switch(app);cookies.setText("Autoriser les cookies tiers pour ce site");cookies.setChecked(CookieManager.getInstance().acceptThirdPartyCookies(web));panel.addView(cookies);
        new AgentDialog.Builder(app).setTitle("Compatibilité du site").setView(panel)
            .setPositiveButton("Appliquer et recharger",(d,w)->{prefs.edit().putBoolean(host+":desktop",desktop.isChecked()).putBoolean(host+":cookies",cookies.isChecked()).apply();apply(app,web,web.getUrl());web.reload();})
            .setNegativeButton("Annuler",null).show();
    }
    static boolean external(Activity app,String value){
        try{
            Uri uri=Uri.parse(value);String scheme=uri.getScheme();Intent intent;
            if("mailto".equalsIgnoreCase(scheme))intent=new Intent(Intent.ACTION_SENDTO,uri);
            else if("tel".equalsIgnoreCase(scheme))intent=new Intent(Intent.ACTION_DIAL,uri);
            else if(BrowserPolicy.webUrl(value))intent=new Intent(Intent.ACTION_VIEW,uri);
            else if("intent".equalsIgnoreCase(scheme)){
                Intent parsed=Intent.parseUri(value,Intent.URI_INTENT_SCHEME);String fallback=parsed.getStringExtra("browser_fallback_url");
                if(!BrowserPolicy.webUrl(fallback))return false;intent=new Intent(Intent.ACTION_VIEW,Uri.parse(fallback));
            }else return false;
            intent.addCategory(Intent.CATEGORY_BROWSABLE);app.startActivity(intent);return true;
        }catch(Exception e){return false;}
    }
}
