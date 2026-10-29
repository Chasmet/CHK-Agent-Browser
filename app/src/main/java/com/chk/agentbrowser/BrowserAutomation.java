package com.chk.agentbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Platform-independent browser operations carried by Windows 1.3's existing desktop action. */
final class BrowserAutomation {
    static final int VERSION=2;
    private static final Handler UI=new Handler(Looper.getMainLooper());
    private static final String PREF="browser_upload_jobs_v1";
    private static final String PROCESS=UUID.randomUUID().toString();
    private BrowserAutomation(){}

    static JSONObject capabilities(){
        try{return new JSONObject().put("version",VERSION)
            .put("transport",new JSONObject().put("action","android_action")
                .put("args",new JSONObject().put("action","desktop")
                    .put("args",new JSONObject().put("tool","browser").put("operation","capabilities"))))
            .put("operations",new JSONArray().put("capabilities").put("upload_file").put("upload_status")
                .put("get_form").put("fill_form").put("verify_form").put("get_page_status").put("read_pdf")
                .put("click_verified").put("click_when_enabled").put("download_file").put("download_status").put("publication_submit"))
            .put("upload",new JSONObject().put("required",new JSONArray().put("id").put("expected_host").put("selector").put("files"))
                .put("files","[{workspace_path: chemin relatif CHK}]")
                .put("max_files",8).put("max_local_bytes",WorkspaceStore.MAX_IMPORT)
                .put("confirmation","selected signifie reçu par le champ du site ; pas téléversé sur son serveur"))
            .put("download",new JSONObject().put("start","download_file").put("status","download_status").put("id","identifiant stable requis dans un workflow").put("workspace_path","destination facultative : vérification SHA-256 et import sans écrasement").put("size_limit",TransferLimits.MAX_FILE_BYTES).put("completion","state=completed et verified=true ; downloading signifie encore en cours"))
            .put("forms",new JSONObject().put("required",new JSONArray().put("expected_host").put("fields"))
                .put("fields","[{selector,kind:text|check|select,value:string|boolean}]")
                .put("max_fields",24).put("readback",true))
            .put("publication","Préparer publication_prepare puis utiliser publication_submit avec compte, domaine et preuve finale ; aucun clic final rejoué automatiquement.")
            .put("session","Les mêmes onglets restent disponibles en mode autonome tant que le processus Android vit ; arrêt forcé, manque de mémoire ou arrêt du propriétaire peuvent interrompre le travail.");
        }catch(Exception e){return new JSONObject();}
    }
    private static String host(WebView web){
        return web==null||web.getUrl()==null?"":Uri.parse(web.getUrl()).getHost();
    }
    private static void destination(WebView web,JSONObject args)throws Exception{
        String expected=args.optString("expected_host","");
        if(!BrowserWebState.alive(web)||web.getUrl()==null||!BrowserPolicy.webUrl(web.getUrl())
                ||expected.isEmpty()||expected.length()>253||!expected.equalsIgnoreCase(host(web)))
            throw new Exception("Page HTTP/HTTPS et expected_host correspondant exactement au domaine requis.");
    }
    static void execute(Context context,WebView web,JSONObject args,AgentClient.ResultCallback cb){
        if(Looper.myLooper()!=Looper.getMainLooper()){UI.post(()->execute(context,web,args,cb));return;}
        if(!cb.isActive())return;
        try{
            String operation=args.optString("operation","");
            if(operation.equals("capabilities")){cb.finish(true,capabilities().toString());return;}
            if(operation.equals("upload_status")){cb.finish(true,uploadStatus(context,args.getString("id")).toString());return;}
            if(operation.equals("download_status")){BrowserDownloads.inspect(context,args,cb);return;}
            destination(web,args);
            switch(operation){
                case "upload_file":if(!args.has("id"))throw new Exception("id stable obligatoire pour une importation MCP.");upload(context,web,args,cb);return;
                case "get_form":web.evaluateJavascript(BrowserScripts.getForm(),raw->cb.finish(true,decode(raw)));return;
                case "get_page_status":web.evaluateJavascript(BrowserScripts.pageStatus(),raw->cb.finish(true,decode(raw)));return;
                case "read_pdf":{
                    String url=web.getUrl(),cookie=android.webkit.CookieManager.getInstance().getCookie(url);
                    WorkspaceCommands.IO.execute(()->{try{JSONObject document=DocumentReader.readWebPdf(context,url,cookie);WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(true,document.toString());});}
                        catch(Exception e){WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(false,e.getMessage());});}});return;
                }
                case "fill_form":case "verify_form":form(web,args,operation.equals("fill_form"),cb);return;
                case "click_verified":VerifiedClick.run(web,args,UI,cb);return;
                case "download_file":
                    AgentClient.get(context).executeTaskStep(new JSONObject().put("action","download_file")
                        .put("args",new JSONObject(args.toString())),cb);return;
                case "click_when_enabled":{
                    JSONObject guarded=new JSONObject(args.toString());
                    guarded.put("wait_until_enabled",true);
                    VerifiedClick.run(web,guarded,UI,cb);return;
                }
                case "publication_submit":{
                    String page=web.getUrl();long version=BrowserWebState.documentVersion(web);
                    form(web,args,false,new AgentClient.ResultCallback(){
                        public boolean isLocal2(){return cb.isLocal2();}
                        public boolean isLocal3(){return cb.isLocal3();}
                        public boolean isActive(){return cb.isActive();}
                        public void finish(boolean ok,String message){
                            if(!ok){cb.finish(false,"Formulaire non confirmé. Aucun clic final : "+message);return;}
                            if(!same(web,page,version)){cb.finish(false,"Page remplacée avant la programmation.");return;}
                            PublicationBrowserCommands.submit(context,web,args,UI,cb);
                        }
                    });return;
                }
                default:cb.finish(false,"Opération navigateur inconnue. Lire capabilities.");
            }
        }catch(Exception e){cb.finish(false,e.getMessage()==null?"Commande navigateur invalide.":e.getMessage());}
    }
    private static String decode(String raw){
        try{return new JSONArray("["+raw+"]").getString(0);}catch(Exception e){return "{}";}
    }
    private static boolean same(WebView web,String page,long version){
        return BrowserWebState.alive(web)&&page.equals(web.getUrl())&&version==BrowserWebState.documentVersion(web);
    }
    private static void form(WebView web,JSONObject args,boolean write,AgentClient.ResultCallback cb)throws Exception{
        JSONArray fields=args.getJSONArray("fields");
        if(fields.length()<1||fields.length()>24)throw new Exception("Entre 1 et 24 champs requis.");
        for(int i=0;i<fields.length();i++){
            JSONObject f=fields.getJSONObject(i);
            String selector=f.getString("selector"),kind=f.optString("kind","text");
            if(selector.isEmpty()||selector.length()>340||!java.util.Arrays.asList("text","check","select").contains(kind))
                throw new Exception("Sélecteur ou type de champ invalide.");
            if(!f.has("value")||(kind.equals("check")?!(f.get("value") instanceof Boolean):!(f.get("value") instanceof String)))
                throw new Exception("Valeur de champ incorrecte.");
            if(!kind.equals("check")&&f.getString("value").length()>8000)throw new Exception("Valeur trop longue.");
        }
        String page=web.getUrl();long version=BrowserWebState.documentVersion(web);
        web.evaluateJavascript(BrowserScripts.formFields(fields.toString(),write),raw->{
            if(!cb.isActive())return;
            if(!same(web,page,version)){cb.finish(false,"Page modifiée durant la saisie : relire avant de continuer.");return;}
            try{
                JSONObject initial=new JSONObject(decode(raw));
                if(!initial.optBoolean("ok")&&(!write||!"Valeur refusée par la page".equals(initial.optString("error")))){if(write)initial.put("effect_uncertain",true).put("retry_command",false).put("guidance","Des champs peuvent avoir changé : relire le formulaire avant toute reprise.");cb.finish(false,initial.toString());return;}
                // Rich editors may settle after DOM input; their immediate text can differ
                // while React/ProseMirror completes the update. Confirm via a fresh read.
                UI.postDelayed(()->{
                    if(!cb.isActive())return;
                    if(!same(web,page,version)){cb.finish(false,"Page remplacée avant la relecture du formulaire.");return;}
                    web.evaluateJavascript(BrowserScripts.formFields(fields.toString(),false),proof->{
                        if(!cb.isActive())return;
                        if(!same(web,page,version)){cb.finish(false,"Formulaire remplacé avant sa vérification.");return;}
                        try{JSONObject result=new JSONObject(decode(proof));if(write&&!result.optBoolean("ok"))result.put("effect_uncertain",true).put("retry_command",false).put("guidance","Saisie effectuée, relecture incomplète : inspecter les valeurs avant toute reprise.");cb.finish(result.optBoolean("ok"),result.toString());}
                        catch(Exception e){cb.finish(false,write?"Relecture impossible : des champs peuvent avoir changé. Inspecter avant toute reprise.":"Relecture du formulaire impossible.");}
                    });
                },write?500L:0L);
            }catch(Exception e){cb.finish(false,"Formulaire non vérifiable.");}
        });
    }
    private static SharedPreferences jobs(Context c){return c.getApplicationContext().getSharedPreferences(PREF,0);}
    private static String jobKey(String id)throws Exception{
        if(!id.matches("[A-Za-z0-9_-]{1,100}"))throw new Exception("id stable : 1..100 lettres, chiffres, tirets ou soulignés.");
        return "job_"+id;
    }
    static JSONObject uploadStatus(Context c,String id)throws Exception{
        String raw=jobs(c).getString(jobKey(id),"");
        if(raw.isEmpty())throw new Exception("Importation inconnue.");
        JSONObject state=new JSONObject(raw);
        if("preparing".equals(state.optString("state"))&&(!PROCESS.equals(state.optString("process"))
                ||state.optBoolean("local2")&&(!(state.optBoolean("local3")?Local3Client.get(c).autonomousAllowed():AgentClient.get(c).isAutonomous())
                    ||(state.optBoolean("local3")?!Local3Client.get(c).enabled()||state.optInt("generation",-1)!=Local3Client.get(c).sessionGeneration():!Local2Client.get(c).enabled()||state.optInt("generation",-1)!=Local2Client.get(c).sessionGeneration())))){
            state.put("state","interrupted").put("message","Session Android interrompue : réception inconnue. Inspecter le site avant une nouvelle sélection.");
            save(c,id,state);
        }
        state.remove("process");state.remove("fingerprint");return state;
    }
    private static void save(Context c,String id,JSONObject state)throws Exception{
        SharedPreferences prefs=jobs(c);
        if(!prefs.contains(jobKey(id))&&prefs.getAll().size()>=32){
            String oldest=null;long time=Long.MAX_VALUE;
            for(java.util.Map.Entry<String,?> e:prefs.getAll().entrySet())try{
                JSONObject previous=new JSONObject(String.valueOf(e.getValue()));
                if((!previous.optString("state").equals("preparing")||!PROCESS.equals(previous.optString("process")))&&previous.optLong("updated")<time){oldest=e.getKey();time=previous.optLong("updated");}
            }catch(Exception ignored){}
            if(oldest==null)throw new Exception("32 importations encore actives. Lire leur état avant de continuer.");
            if(!prefs.edit().remove(oldest).commit())throw new Exception("Journal d'importation indisponible.");
        }
        state.put("updated",System.currentTimeMillis());
        if(!prefs.edit().putString(jobKey(id),state.toString()).commit())throw new Exception("Journal d'importation non enregistré. Aucun nouvel envoi.");
    }
    static void upload(Context context,WebView web,JSONObject args,AgentClient.ResultCallback cb){
        if(Looper.myLooper()!=Looper.getMainLooper()){UI.post(()->upload(context,web,args,cb));return;}
        if(!cb.isActive())return;
        Context app=context.getApplicationContext();
        try{
            destination(web,args);
            // Older Render callers may omit id. Local 2's documented operation requires a stable one.
            String id=args.optString("id",UUID.randomUUID().toString());jobKey(id);
            String selector=args.getString("selector");JSONArray files=args.getJSONArray("files");
            if(selector.isEmpty()||selector.length()>340||files.length()<1||files.length()>8)
                throw new Exception("Champ fichier et 1..8 fichiers requis.");
            String fingerprint=AgentVision.digest(new JSONObject().put("host",host(web)).put("selector",selector).put("files",files).toString());
            String old=jobs(app).getString(jobKey(id),"");
            if(!old.isEmpty()){
                if(!fingerprint.equals(new JSONObject(old).optString("fingerprint")))throw new Exception("Cet id décrit une autre importation. Aucun envoi.");
                JSONObject receipt=uploadStatus(app,id);receipt.put("replayed",true);
                cb.finish(receipt.optString("state").equals("selected"),receipt.toString());return;
            }
            JSONObject state=new JSONObject().put("id",id).put("state","preparing").put("host",host(web))
                .put("fingerprint",fingerprint).put("process",PROCESS).put("local2",cb.isLocal2())
                .put("local3",cb.isLocal3()).put("generation",cb.isLocal3()?Local3Client.get(app).sessionGeneration():Local2Client.get(app).sessionGeneration()).put("server_upload_verified",false);
            save(app,id,state);
            String page=web.getUrl();long version=BrowserWebState.documentVersion(web);
            AgentClient.ResultCallback receipt=new AgentClient.ResultCallback(){
                @Override public boolean isLocal2(){return cb.isLocal2();}
                        public boolean isLocal3(){return cb.isLocal3();}
                @Override public boolean isActive(){return BrowserWebState.alive(web)&&AgentClient.get(app).commandEnabled(cb)
                    &&(!cb.isLocal2()||(cb.isLocal3()?Local3Client.get(app).autonomousAllowed():AgentClient.get(app).isAutonomous()));}
                @Override public void finish(boolean ok,String message){
                    try{
                        state.put("state",ok?"selected":"unconfirmed").put("message",(message==null?"":message.substring(0,Math.min(1800,message.length()))))
                            .put("server_upload_verified",false);
                        save(app,id,state);cb.finish(ok,uploadStatus(app,id).toString());
                    }catch(Exception e){cb.finish(false,"Réception possible mais journal non confirmé. Vérifier le site avant de renvoyer.");}
                }
            };
            BrowserTransferManager.prepareUploads(app,files,AgentClient.get(app).deviceTokenForTransfers(),(ok,uris,message)->{
                if(!receipt.isActive()){receipt.finish(false,"Canal arrêté pendant la préparation. Aucun nouvel envoi.");return;}
                if(!same(web,page,version)){receipt.finish(false,"Page modifiée pendant la préparation. Aucun fichier envoyé.");return;}
                if(!ok){receipt.finish(false,message);return;}
                // Grok Imagine can hold the synthetic same-origin response stream open
                // indefinitely in a background WebView. Owner-selected images are
                // small enough for the existing bounded, serial JS file injection.
                // Large media use native content URIs; bounded stream/JS fallbacks stay small.
                if("grok.com".equalsIgnoreCase(host(web))
                        && files.length()==1 && files.optJSONObject(0)!=null
                        && files.optJSONObject(0).optString("workspace_path","").toLowerCase(java.util.Locale.ROOT)
                            .matches(".*\\.(png|jpg|jpeg|webp)"))
                    BrowserJsUploader.upload(app,web,selector,uris,receipt);
                else BrowserNativeUploader.upload(app,web,selector,uris,receipt);
            });
        }catch(Exception e){cb.finish(false,e.getMessage()==null?"Importation refusée.":e.getMessage());}
    }
}
