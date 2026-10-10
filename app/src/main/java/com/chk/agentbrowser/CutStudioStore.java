package com.chk.agentbrowser;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Planning indépendant de Cut Vidéo : aucune table ni préférence d'une autre app n'est modifiée. */
public final class CutStudioStore {
    static final String FOLDER = "CutVideo";
    static final String PREFS = "cut_studio_planning_v1";
    static final String KEY = "entries";
    private CutStudioStore() {}

    public static synchronized JSONArray all(Context context) {
        try {
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
            return new JSONArray(raw);
        } catch(Exception e) { return new JSONArray(); }
    }
    public static synchronized void save(Context context, JSONObject entry) throws Exception {
        if (!entry.has("id")) entry.put("id", UUID.randomUUID().toString());
        validate(context, entry);
        JSONArray old = all(context), next = new JSONArray();
        for (int i=0;i<old.length();i++) {
            JSONObject item=old.optJSONObject(i);
            if(item!=null&&!entry.optString("id").equals(item.optString("id"))) next.put(item);
        }
        next.put(entry);
        persist(context,next);
        alarm(context,entry);
    }
    public static synchronized void remove(Context context,String id) throws Exception {
        JSONArray old=all(context),next=new JSONArray();
        for(int i=0;i<old.length();i++){
            JSONObject item=old.optJSONObject(i);
            if(item==null)continue;
            if(id.equals(item.optString("id"))) cancel(context,id);
            else next.put(item);
        }
        persist(context,next);
    }
    public static synchronized void published(Context context,String id,boolean done) throws Exception {
        JSONArray entries=all(context);
        for(int i=0;i<entries.length();i++){
            JSONObject item=entries.optJSONObject(i);
            if(item!=null&&id.equals(item.optString("id"))){
                item.put("published",done);
                persist(context,entries);
                if(done)cancel(context,id); else alarm(context,item);
                return;
            }
        }
    }
    public static synchronized void reschedule(Context context) {
        JSONArray entries=all(context);
        for(int i=0;i<entries.length();i++){
            JSONObject item=entries.optJSONObject(i);
            if(item!=null)alarm(context,item);
        }
    }
    private static void validate(Context context,JSONObject entry) throws Exception {
        String path=entry.optString("path","");
        if(!path.startsWith(FOLDER+"/")||!path.endsWith(".mp4")||
            !new WorkspaceStore(context).file(path).isFile())
            throw new IllegalArgumentException("Morceau MP4 CHK introuvable");
        String platform=entry.optString("platform","");
        if(!("youtube".equals(platform)||"tiktok".equals(platform)||
             "instagram".equals(platform)||"x".equals(platform)))
            throw new IllegalArgumentException("Réseau non pris en charge");
        String account=entry.optString("account","chknoirshadow");
        if(!("chknoirshadow".equals(account)||"qg".equals(account)))
            throw new IllegalArgumentException("Compte inconnu");
        if("qg".equals(account)&&!("youtube".equals(platform)||"tiktok".equals(platform)))
            throw new IllegalArgumentException("Le compte QG ne gère que YouTube et TikTok");
        if(entry.optString("title","").trim().isEmpty()||entry.optString("title").length()>70)
            throw new IllegalArgumentException("Titre requis (70 caractères maximum)");
        if(entry.optString("description","").length()>90)
            throw new IllegalArgumentException("Description trop longue");
        String hashtags=entry.optString("hashtags","");
        if(hashtags.trim().split("\\s+").length>5)
            throw new IllegalArgumentException("5 hashtags maximum");
        if(text(entry).length()>100)
            throw new IllegalArgumentException("Titre, description et hashtags : 100 caractères maximum");
        if(entry.optLong("at",0)<=System.currentTimeMillis()&&!entry.optBoolean("published",false))
            throw new IllegalArgumentException("Choisir une date de publication future");
    }
    public static String text(JSONObject j){
        StringBuilder b=new StringBuilder();
        for(String k:new String[]{"title","description","hashtags"}){
            String value=j.optString(k,"").trim();
            if(!value.isEmpty()){if(b.length()>0)b.append("\n");b.append(value);}
        }
        return b.toString();
    }
    public static List<JSONObject> list(Context context) {
        JSONArray a=all(context);List<JSONObject> entries=new ArrayList<>();
        for(int i=0;i<a.length();i++)if(a.optJSONObject(i)!=null)entries.add(a.optJSONObject(i));
        Collections.sort(entries,(x,y)->Long.compare(x.optLong("at"),y.optLong("at")));
        return entries;
    }
    private static void persist(Context context,JSONArray entries) throws Exception {
        if(!context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putString(KEY,entries.toString()).commit())
            throw new IllegalStateException("Échec de l'enregistrement");
        WorkspaceStore store=new WorkspaceStore(context);
        try{store.mkdir(FOLDER);}catch(Exception e){
            if(!store.file(FOLDER).isDirectory())throw e;
        }
        // Copie de lecture exploitable par l'agent MCP Fichiers et sauvegardée hors de l'UI.
        store.writeText(FOLDER+"/publications.json",entries.toString(2),true);
    }
    private static PendingIntent intent(Context ctx,String id,int flags){
        Intent i=new Intent(ctx,CutStudioReminderReceiver.class)
            .setAction("com.chk.agentbrowser.CUT_REMINDER")
            .setData(android.net.Uri.parse("cutstudio://reminder/"+id));
        return PendingIntent.getBroadcast(ctx,0,i,flags|PendingIntent.FLAG_IMMUTABLE);
    }
    private static void alarm(Context context,JSONObject j){
        String id=j.optString("id","");
        if(id.isEmpty())return;
        cancel(context,id);
        long at=j.optLong("at",0);
        if(j.optBoolean("published")||at<=System.currentTimeMillis())return;
        AlarmManager manager=(AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
        if(manager==null)return;
        PendingIntent pi=intent(context,id,PendingIntent.FLAG_UPDATE_CURRENT);
        try{
            if(Build.VERSION.SDK_INT>=23)
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi);
            else manager.set(AlarmManager.RTC_WAKEUP,at,pi);
        }catch(SecurityException ignored){}
    }
    private static void cancel(Context ctx,String id){
        AlarmManager manager=(AlarmManager)ctx.getSystemService(Context.ALARM_SERVICE);
        if(manager==null)return;
        PendingIntent pi=intent(ctx,id,PendingIntent.FLAG_NO_CREATE);
        if(pi!=null){manager.cancel(pi);pi.cancel();}
    }
}
