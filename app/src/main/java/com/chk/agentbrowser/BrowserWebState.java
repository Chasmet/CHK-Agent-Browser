package com.chk.agentbrowser;

import android.webkit.WebView;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Main-thread WebView lifetime tracking; never call a destroyed renderer. */
final class BrowserWebState {
    private static final Map<WebView,String> URLS=new WeakHashMap<>();
    private static final Map<WebView,Long> DOCUMENTS=new WeakHashMap<>();
    private static final Map<WebView,Long> COMMITTED=new WeakHashMap<>();
    private static final Map<WebView,Long> FINISHED=new WeakHashMap<>();
    private static final Map<WebView,Long> FINISHED_AT=new WeakHashMap<>();
    private static final Set<WebView> CLOSED=Collections.newSetFromMap(new WeakHashMap<WebView,Boolean>());
    private BrowserWebState(){}
    /** Called on each main-frame page start, including reloads of the same URL. */
    static void remember(WebView web,String url){
        BrowserStreamUploader.revoke(web);
        DOCUMENTS.put(web,documentVersion(web)+1);
        if(url!=null&&url.startsWith("https://"))URLS.put(web,url);
    }
    static long documentVersion(WebView web){Long version=DOCUMENTS.get(web);return version==null?0:version;}
    /** Page-start alone may still expose the previous document on same-URL reloads. */
    static void committed(WebView web,String url){
        if(alive(web)&&url!=null&&url.equals(web.getUrl()))COMMITTED.put(web,documentVersion(web));
    }
    static boolean committed(WebView web){Long version=COMMITTED.get(web);return version!=null&&version==documentVersion(web);}
    static void finished(WebView web,String url){
        if(alive(web)&&url!=null&&url.equals(web.getUrl())){
            committed(web,url);
            FINISHED.put(web,documentVersion(web));FINISHED_AT.put(web,android.os.SystemClock.elapsedRealtime());
        }
    }
    static boolean loaded(WebView web){Long done=FINISHED.get(web);return done!=null&&done==documentVersion(web);}
    static long loadedAt(WebView web){Long at=FINISHED_AT.get(web);return at==null?0:at;}
    static String url(WebView web){String url=URLS.get(web);return url==null?"https://www.google.com":url;}
    static boolean alive(WebView web){return web!=null&&!CLOSED.contains(web);}
    static void close(WebView web){BrowserStreamUploader.revoke(web);CLOSED.add(web);URLS.remove(web);DOCUMENTS.remove(web);COMMITTED.remove(web);FINISHED.remove(web);FINISHED_AT.remove(web);}
}
