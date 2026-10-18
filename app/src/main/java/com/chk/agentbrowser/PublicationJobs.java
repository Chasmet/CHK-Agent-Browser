package com.chk.agentbrowser;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/**
 * Private publication manifests: no platform passwords, OAuth tokens, ad spend,
 * synthetic success reports, or blind retries of a final Publish click.
 * The existing MCP task tool can call publication_prepare/status/list/submit.
 */
final class PublicationJobs {
    private PublicationJobs(){}
    static final String ACTION_PREPARE="publication_prepare";
    static final String ACTION_STATUS="publication_status";
    static final String ACTION_LIST="publication_list";
    static final String ACTION_CANCEL="publication_cancel";
    static final String ACTION_SUBMIT="publication_submit";

    static boolean metadataAction(String action){
        return Arrays.asList(ACTION_PREPARE,ACTION_STATUS,ACTION_LIST,ACTION_CANCEL).contains(action);
    }
    private static File file(Context app,String id)throws Exception{
        return AgentFiles.journal(app,"publications",id);
    }
    private static boolean allowedHost(String platform,String host){
        if(host==null)return false;
        String base;
        switch(platform){
            case "youtube":base="youtube.com";break;
            case "tiktok":base="tiktok.com";break;
            case "instagram":base="instagram.com";break;
            case "facebook":base="facebook.com";break;
            case "threads":base="threads.net";break;
            case "x":base="x.com";break;
            case "linkedin":base="linkedin.com";break;
            default:return false;
        }
        return host.equals(base)||host.endsWith("."+base)
            ||("x".equals(platform)&&(host.equals("twitter.com")||host.endsWith(".twitter.com")));
    }
    static boolean validDestination(String platform,String address){
        Uri uri=Uri.parse(address==null?"":address);
        return "https".equalsIgnoreCase(uri.getScheme())&&uri.getUserInfo()==null
            &&allowedHost(platform,uri.getHost()==null?null:uri.getHost().toLowerCase(Locale.ROOT));
    }
    private static JSONObject view(JSONObject job)throws Exception{
        JSONObject safe=new JSONObject(job.toString());safe.remove("fingerprint");return safe;
    }
    static synchronized JSONObject command(Context context,String action,JSONObject args)throws Exception{
        if(ACTION_LIST.equals(action)){
            File dir=file(context,"list").getParentFile();
            File[] files=dir.listFiles();
            JSONArray items=new JSONArray();
            if(files!=null){
                Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
                for(File f:files){
                    if(items.length()>=50)break;
                    if(f.isFile()&&f.getName().endsWith(".json")&&f.length()<100000){
                        try{JSONObject j=AgentFiles.read(f);
                            if(j.has("platform"))items.put(new JSONObject()
                                .put("id",j.optString("id")).put("platform",j.optString("platform"))
                                .put("status",j.optString("status")).put("title",j.optString("title"))
                                .put("updated",j.optLong("updated")));
                        }catch(Exception ignored){}
                    }
                }
            }
            return new JSONObject().put("ok",true).put("items",items);
        }
        String id=args.getString("id");
        File journal=file(context,id);
        if(ACTION_STATUS.equals(action))
            return view(AgentFiles.read(journal));
        if(ACTION_CANCEL.equals(action)){
            JSONObject job=AgentFiles.read(journal);
            if(!"prepared".equals(job.optString("status")))
                throw new Exception("Publication déjà lancée, confirmée ou incertaine : annulation automatique refusée.");
            job.put("status","cancelled").put("updated",System.currentTimeMillis());
            AgentFiles.save(journal,job);
            return view(job);
        }
        if(!ACTION_PREPARE.equals(action))throw new Exception("Commande Publication inconnue.");
        String platform=args.getString("platform").toLowerCase(Locale.ROOT);
        String dest=args.getString("destination_url");
        if(!validDestination(platform,dest))throw new Exception("Adresse HTTPS hors de la plateforme autorisée.");
        String title=args.optString("title",""),caption=args.optString("caption","");
        String account=args.optString("account","");
        if(title.length()>150||caption.length()>8000||account.length()>100)
            throw new Exception("Titre, légende ou identifiant de compte trop long.");
        if(title.trim().isEmpty()&&caption.trim().isEmpty())
            throw new Exception("Ajoute un titre ou une légende.");
        String mode=args.optString("promotion_mode","organic").toLowerCase(Locale.ROOT);
        if(!"organic".equals(mode)&&!"paid".equals(mode))
            throw new Exception("promotion_mode : organic ou paid.");
        String schedule=args.optString("schedule_at","");
        if(!schedule.isEmpty()&&!schedule.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?(Z|[+-]\\d{2}:\\d{2})"))
            throw new Exception("schedule_at doit indiquer une date ISO avec fuseau (ex. 2026-11-01T19:00:00+01:00).");
        JSONArray paths=args.optJSONArray("media_paths");if(paths==null)paths=new JSONArray();
        if(paths.length()>8)throw new Exception("Maximum huit médias par publication.");
        JSONArray checked=new JSONArray();
        WorkspaceStore store=new WorkspaceStore(context);long total=0;
        java.util.Set<String> unique=new java.util.HashSet<>();
        for(int i=0;i<paths.length();i++){
            String path=paths.getString(i);
            if(!unique.add(path))throw new Exception("Média répété : "+path);
            File f=store.file(path);if(!f.isFile()||f.length()<1)
                throw new Exception("Média inexistant ou vide : "+path);
            String mime=store.mime(path);
            if(!(mime.startsWith("video/")||mime.startsWith("image/")||mime.startsWith("audio/")))
                throw new Exception("Le fichier n'est pas un média autorisé : "+path);
            total+=f.length();if(total>WorkspaceStore.MAX_IMPORT)
                throw new Exception("Sélection de médias supérieure à 512 Mo.");
            checked.put(new JSONObject().put("path",path).put("size",f.length())
                .put("updated",f.lastModified()).put("mime",mime));
        }
        JSONArray tags=args.optJSONArray("hashtags");if(tags==null)tags=new JSONArray();
        if(tags.length()>30)throw new Exception("Maximum trente hashtags.");
        JSONArray validTags=new JSONArray();
        for(int i=0;i<tags.length();i++){
            String tag=tags.getString(i).trim();
            if(!tag.matches("#?[\\p{L}\\p{N}_]{1,60}"))
                throw new Exception("Hashtag incorrect : "+tag);
            validTags.put(tag.startsWith("#")?tag:"#"+tag);
        }
        JSONObject input=new JSONObject()
            .put("platform",platform).put("destination_url",dest)
            .put("title",title).put("caption",caption).put("account",account)
            .put("promotion_mode",mode).put("schedule_at",schedule)
            .put("media",checked).put("hashtags",validTags);
        String fingerprint=AgentVision.digest(input.toString());
        if(journal.exists()){
            JSONObject old=AgentFiles.read(journal);
            if(!fingerprint.equals(old.optString("fingerprint")))
                throw new Exception("Cet identifiant appartient déjà à une autre publication.");
            return view(old);
        }
        JSONObject job=new JSONObject(input.toString()).put("id",id).put("fingerprint",fingerprint)
            .put("status","prepared").put("created",System.currentTimeMillis())
            .put("updated",System.currentTimeMillis())
            .put("remote_verified",false)
            .put("next","Ouvre la plateforme, remplis le formulaire avec le MCP, importe les médias, vérifie l'aperçu ; puis lance publication_submit avec un résultat observable.");
        if("paid".equals(mode))
            job.put("next","Promotion payante : préparation uniquement. Budget et achat exigent une validation distincte du propriétaire ; aucun paiement MCP automatique.");
        AgentFiles.save(journal,job);
        return view(job);
    }
    static synchronized JSONObject begin(Context context,JSONObject args,String address)throws Exception{
        File journal=file(context,args.getString("id"));
        JSONObject job=AgentFiles.read(journal);
        if(!"prepared".equals(job.optString("status")))
            throw new Exception("Publication déjà lancée, annulée ou incertaine : ne pas cliquer deux fois.");
        if("paid".equals(job.optString("promotion_mode")))
            throw new Exception("Campagne payante : le budget et le paiement nécessitent l'accord explicite du propriétaire.");
        String platform=job.getString("platform");
        if(!validDestination(platform,address)||!validDestination(platform,job.optString("destination_url")))
            throw new Exception("Tu n'es pas sur le site de publication prévu.");
        Uri active=Uri.parse(address);
        String expected=args.getString("expected_host");
        if(active.getHost()==null||!expected.equalsIgnoreCase(active.getHost()))
            throw new Exception("Le nom de domaine de l'onglet ne correspond pas à celui attendu.");
        String selector=args.optString("selector","");
        String proofUrl=args.optString("expected_url_contains","");
        String proofText=args.optString("expected_text","");
        if(selector.length()<2||selector.length()>340||
            (proofText.trim().length()<6&&proofUrl.trim().length()<10))
            throw new Exception("Cible et preuve de publication ou de programmation trop faibles.");
        if(!proofUrl.isEmpty()&&!proofUrl.startsWith("https://"))
            throw new Exception("La preuve par URL doit être HTTPS complète.");
        if(!proofUrl.isEmpty()&&!validDestination(platform,proofUrl))
            throw new Exception("URL de preuve hors de la plateforme.");
        boolean schedule=!job.optString("schedule_at").isEmpty();
        String request=args.optString("mode","publish");
        if(!request.equals(schedule?"schedule":"publish"))
            throw new Exception(schedule?"Cette publication est programmée, mode schedule obligatoire.":"Ce brouillon est une publication immédiate.");
        WorkspaceStore store=new WorkspaceStore(context);
        JSONArray media=job.getJSONArray("media");
        for(int i=0;i<media.length();i++){
            JSONObject entry=media.getJSONObject(i);File f=store.file(entry.getString("path"));
            if(!f.isFile()||f.length()!=entry.getLong("size")||f.lastModified()!=entry.getLong("updated"))
                throw new Exception("Média modifié depuis la préparation : "+entry.getString("path"));
        }
        job.put("status","click_pending").put("attempted",System.currentTimeMillis())
            .put("proof_expected",new JSONObject().put("host",expected)
                .put("text",proofText).put("url",proofUrl))
            .put("updated",System.currentTimeMillis());
        AgentFiles.save(journal,job); // durable before one irreversible click
        return job;
    }
    static synchronized JSONObject finish(Context context,String id,boolean ok,String feedback,String page)throws Exception{
        File f=file(context,id);JSONObject job=AgentFiles.read(f);
        if(!"click_pending".equals(job.optString("status")))
            throw new Exception("Journal Publication incohérent.");
        boolean clicked=ok&&feedback!=null&&feedback.startsWith("Clic confirmé");
        job.put("status",clicked?
            (job.optString("schedule_at").isEmpty()?"browser_confirmed":"schedule_browser_confirmed"):"uncertain");
        job.put("remote_verified",clicked);
        job.put("proof_page",page==null?"":page);
        job.put("proof_message",feedback==null?"":feedback.substring(0,Math.min(250,feedback.length())));
        job.put("updated",System.currentTimeMillis());
        AgentFiles.save(f,job);
        return view(job);
    }
}
