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
    /**
     * Entrée MCP sans nouvel endpoint Render : browser_files_write_text sur
     * CutVideo/schedule_inbox.json déclenche une importation transactionnelle.
     * Aucun réseau social n'est publié automatiquement.
     */
    public static synchronized JSONObject importFromAgent(Context context, String payload) throws Exception {
        if(payload==null||payload.length()>65536)
            throw new IllegalArgumentException("Planning JSON limité à 64 Ko");
        String input=payload.trim();
        JSONArray requests=input.startsWith("[")?new JSONArray(input):
            new JSONObject(input).getJSONArray("entries");
        if(requests.length()==0||requests.length()>25)
            throw new IllegalArgumentException("Entre 1 et 25 programmations par envoi");
        JSONArray incoming=new JSONArray(), identifiers=new JSONArray();
        java.util.HashSet<String> ids=new java.util.HashSet<>();
        JSONArray previous=all(context);
        for(int i=0;i<requests.length();i++){
            JSONObject request=requests.getJSONObject(i);
            if(request.optBoolean("published",false))
                throw new IllegalArgumentException("Une publication ne peut pas être validée par import distant");
            String path=request.getString("path");
            String platform=request.getString("platform");
            String account=request.optString("account","chknoirshadow");
            long at="x".equals(platform)?0L:request.getLong("at");
            String requestId=request.optString("request_id","");
            if(requestId.length()>128)throw new IllegalArgumentException("request_id trop long");
            String unique=requestId.isEmpty()
                ?path+"|"+platform+"|"+account+"|"+at
                :"request:"+requestId;
            String id=UUID.nameUUIDFromBytes(("chkcut:"+unique)
                .getBytes(StandardCharsets.UTF_8)).toString();
            if(!ids.add(id))throw new IllegalArgumentException("Identifiant dupliqué dans l'envoi");
            JSONObject row=new JSONObject()
                .put("id",id)
                .put("path",path)
                .put("platform",platform)
                .put("account",account)
                .put("visibility",request.optString("visibility","public"))
                .put("title",request.getString("title").trim())
                .put("description",request.optString("description","").trim())
                .put("hashtags",request.optString("hashtags","").trim())
                .put("at",at)
                .put("published",false);
            for(int p=0;p<previous.length();p++){
                JSONObject prior=previous.optJSONObject(p);
                if(prior!=null&&id.equals(prior.optString("id"))&&prior.optBoolean("published"))
                    throw new IllegalArgumentException("Publication déjà validée : modification distante interdite");
            }
            validate(context,row);
            incoming.put(row);identifiers.put(id);
        }
        // Tous les éléments sont validés AVANT d'écrire : aucun lot partiel.
        JSONArray merged=new JSONArray();
        for(int i=0;i<previous.length();i++){
            JSONObject row=previous.optJSONObject(i);
            if(row!=null&&!ids.contains(row.optString("id")))merged.put(row);
        }
        for(int i=0;i<incoming.length();i++)merged.put(incoming.getJSONObject(i));
        persist(context,merged);
        for(int i=0;i<incoming.length();i++)alarm(context,incoming.getJSONObject(i));
        return new JSONObject()
            .put("imported",incoming.length())
            .put("ids",identifiers)
            .put("state","scheduled_local_reminder")
            .put("platform_publication_confirmed",false)
            .put("planning_file",FOLDER+"/publications.json");
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
        if(!"x".equals(platform)&&entry.optLong("at",0)<=System.currentTimeMillis()&&!entry.optBoolean("published",false))
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
        if(j.optBoolean("published")||"x".equals(j.optString("platform"))||at<=System.currentTimeMillis())return;
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
