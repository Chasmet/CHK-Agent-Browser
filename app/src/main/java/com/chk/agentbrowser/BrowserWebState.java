package com.chk.agentbrowser;

import android.webkit.WebView;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Main-thread WebView lifetime tracking; never call a destroyed renderer. */
final class BrowserWebState {
    private static final Map<WebView,String> URLS=new WeakHashMap<>();
    private static final Set<WebView> CLOSED=Collections.newSetFromMap(new WeakHashMap<WebView,Boolean>());
    private BrowserWebState(){}
    static void remember(WebView web,String url){if(url!=null&&url.startsWith("https://"))URLS.put(web,url);}
    static String url(WebView web){String url=URLS.get(web);return url==null?"https://www.google.com":url;}
    static boolean alive(WebView web){return web!=null&&!CLOSED.contains(web);}
    static void close(WebView web){CLOSED.add(web);URLS.remove(web);}
}
