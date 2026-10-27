package com.chk.agentbrowser;

import android.webkit.WebView;
import org.json.JSONObject;
import java.util.List;
import java.util.WeakHashMap;
import java.util.UUID;

/** Stable for the lifetime of the shared WebView, including Activity handoffs. */
final class BrowserTabIds {
    private static final WeakHashMap<WebView,String> IDS=new WeakHashMap<>();
    private BrowserTabIds(){}
    static String id(WebView web){String id=IDS.get(web);if(id==null){id="tab-"+UUID.randomUUID();IDS.put(web,id);}return id;}
    static int index(List<WebView> tabs,JSONObject args,int fallback){
        if(args.has("id")){
            String id=args.optString("id","");
            for(int i=0;i<tabs.size();i++)if(id(tabs.get(i)).equals(id))return i;
            try{int i=Integer.parseInt(id);return i>=0&&i<tabs.size()?i:-1;}catch(Exception ignored){return -1;}
        }
        if(args.has("index"))return args.optInt("index",-1);
        String contains=args.optString("url_contains","");
        if(!contains.isEmpty()){for(int i=0;i<tabs.size();i++){String url=tabs.get(i).getUrl();if(url!=null&&url.contains(contains))return i;}return -1;}
        return fallback;
    }
}
