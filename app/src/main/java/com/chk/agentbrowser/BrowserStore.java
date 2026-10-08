package com.chk.agentbrowser;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
public final class BrowserStore {
    private final SharedPreferences preferences;
    public BrowserStore(Context context) { preferences=context.getSharedPreferences("browser_data_v1",Context.MODE_PRIVATE); }
    public JSONArray get(String key) {
        try { return new JSONArray(preferences.getString(key,"[]")); }
        catch(Exception e){ return new JSONArray(); }
    }
    public boolean hasFavorite(String url) {
        JSONArray a=get("favorites");
        for(int i=0;i<a.length();i++){
            JSONObject o=a.optJSONObject(i);
            if(o!=null && url!=null && url.equals(o.optString("url"))) return true;
        }
        return false;
    }
    public void removeFavorite(String url) {
        JSONArray a=get("favorites"), b=new JSONArray();
        for(int i=0;i<a.length();i++) { JSONObject o=a.optJSONObject(i); if(o!=null && !url.equals(o.optString("url"))) b.put(o); }
        preferences.edit().putString("favorites",b.toString()).apply();
    }
    public void add(String key,String title,String url) {
        if(url==null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        JSONArray a=get(key), b=new JSONArray();
        try {
            JSONObject item=new JSONObject(); item.put("title",title==null||title.isEmpty()?url:title); item.put("url",url);
            b.put(item);
            int max=key.equals("favorites")?100:300;
            for(int i=0;i<a.length()&&b.length()<max;i++){
                JSONObject old=a.optJSONObject(i);
                if(old!=null&&!url.equals(old.optString("url")))b.put(old);
            }
            preferences.edit().putString(key,b.toString()).apply();
        } catch(Exception ignored){}
    }
    public void clearHistory(){ preferences.edit().remove("history").apply(); }
}
