package com.chk.agentbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small local technical ring log. Never pass URLs, cookies, tokens, passwords or page text here. */
public final class AgentLog {
    private static final String PREF="browser_diag_log_v1";
    private static final String KEY="events";
    private static final int MAX_CHARS=12000;
    private AgentLog(){}

    public static synchronized void add(Context context,String category,String message){
        if(context==null)return;
        String safe=(message==null?"":message)
            .replaceAll("(?i)bearer\\s+[a-z0-9._-]+","Bearer [masqué]")
            .replaceAll("https?://\\S+","[url masquée]")
            .replaceAll("[a-f0-9]{48,}","[jeton masqué]");
        if(safe.length()>240)safe=safe.substring(0,240);
        String time=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT).format(new Date());
        String line=time+" · "+category+" · "+safe+"\n";
        SharedPreferences p=context.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
        String old=p.getString(KEY,"");
        String joined=old+line;
        if(joined.length()>MAX_CHARS)joined=joined.substring(joined.length()-MAX_CHARS);
        p.edit().putString(KEY,joined).apply();
    }

    public static String dump(Context context){
        String value=context.getApplicationContext()
            .getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,"");
        return value.isEmpty()?"Aucun événement technique enregistré.":value;
    }

    public static void clear(Context context){
        context.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .edit().remove(KEY).apply();
    }
}
