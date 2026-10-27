package com.chk.agentbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Isolated CHK Universal Control channel (Local 3). Does NOT use Render or
 * Supabase, and never embeds owner credentials in the public APK.
 * App secret is provisioned once with the Universal Control invitation.
 */
public final class Local3Client {
    // Real owner-provided HTTPS endpoint, never a fabricated hostname.
    private static final String PREF="chk_local3_v1";
    private static Local3Client instance;
    private final Context app;
    private final SharedPreferences p;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private volatile boolean running, busy;
    private volatile int generation, errors;
    private volatile String status="Désactivé";
    private volatile long lastContact, nextCapabilitySync, nextConsentSync;
    private volatile String capabilityStatus="À synchroniser", lastAction="aucune", lastOutcome="aucun";
    private volatile boolean executing;
    private volatile String sentConsent="", permissionStatus="À synchroniser";
    private final Runnable loop=this::poll;

    public interface PairCallback { void complete(boolean ok,String message); }
    private Local3Client(Context c){
        app=c.getApplicationContext();
        p=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }
    public static synchronized Local3Client get(Context c){
        if(instance==null)instance=new Local3Client(c);return instance;
    }
    public boolean enabled(){return p.getBoolean("enabled",false);}
    public boolean isExecuting(){return executing || !p.getString("outbox","").isEmpty();}
    public int sessionGeneration(){return generation;}
    public boolean paired(){return !p.getString("app_id","").isEmpty()&&!p.getString("secret","").isEmpty();}
    public String serverUrl(){return p.getString("server_url","");}
    /** Accept a secure server base URL or its /mcp endpoint; refuse tokenized links. */
    public static String normalizeUrl(String raw){
        try{
            java.net.URI uri=new java.net.URI(raw==null?"":raw.trim());
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null
                ||uri.getHost().trim().isEmpty()||uri.getRawUserInfo()!=null
                ||uri.getRawQuery()!=null||uri.getRawFragment()!=null)
                throw new IllegalArgumentException("Adresse HTTPS MCP invalide.");
            String path=uri.getPath();
            if(!("".equals(path)||"/".equals(path)||"/mcp".equals(path)||"/mcp/".equals(path)))
                throw new IllegalArgumentException("Colle l'adresse racine du serveur ou son lien /mcp.");
            int port=uri.getPort();
            if(port==0||port>65535||port < -1)
                throw new IllegalArgumentException("Port HTTPS invalide.");
            return "https://"+uri.getRawAuthority();
        }catch(java.net.URISyntaxException e){
            throw new IllegalArgumentException("Adresse HTTPS MCP invalide.");
        }
    }
    public void configureBase(String provided){
        String normalized=normalizeUrl(provided);
        if(normalized.equals(serverUrl()))return;
        stop();
        // Never send a previous server's credentials or unacknowledged results elsewhere.
        if(!p.edit().putString("server_url",normalized).remove("app_id").remove("secret")
            .remove("outbox").remove("leased_task").putBoolean("enabled",false).commit())
            throw new IllegalStateException("Enregistrement sécurisé impossible.");
        lastContact=0;
        status="Serveur configuré · association nécessaire";
    }
    public String status(){
        if(!enabled())return "Désactivé";
        if(!paired())return "Activé · association sécurisée nécessaire";
        if(executing)return "Commande Android en cours";
        if(lastContact>0 && android.os.SystemClock.elapsedRealtime()-lastContact>60000L && status.startsWith("Connecté"))
            return "Liaison Local 3 à revérifier";
        return status;
    }
    public String diagnostic(){
        String id=p.getString("app_id","");
        return "Local 3 : "+status()+"\nHôte : "+(serverUrl().isEmpty()?"non configuré":serverUrl().replace("https://",""))
            +"\nIdentifiant : "+(id.isEmpty()?"non associé":id)
            +"\nAppels Render / Supabase par ce canal : aucun"
            +"\nÉchecs consécutifs : "+errors
            +"\nDernier contact : "+(lastContact==0?"jamais":((android.os.SystemClock.elapsedRealtime()-lastContact)/1000)+" s")
            +"\nAutorisations : "+permissionStatus+" · nombre de demandes sans quota"
            +"\nOutils : "+capabilityStatus
            +"\nDernière commande : "+lastAction+" · "+lastOutcome
            +"\nRésultat en attente : "+(!p.getString("outbox","").isEmpty()?"oui":"non");
    }
    public boolean persistentPermissions(){return p.getBoolean("persistent_permissions",true);}
    public boolean autonomousAllowed(){return persistentPermissions()&&AgentClient.get(app).isAutonomous();}
    public void setPersistentPermissions(boolean on){
        p.edit().putBoolean("persistent_permissions",on).apply();
        consentChanged();
    }
    public void consentChanged(){
        sentConsent="";nextConsentSync=0;
        if(!paired()||serverUrl().isEmpty())return;
        io.execute(()->{try{syncConsent(p.getString("app_id",""),p.getString("secret",""));}
            catch(Exception e){permissionStatus="Serveur à mettre à jour : "+safe(e);}});
        reconnect();
    }
    private void syncConsent(String id,String secret)throws Exception{
        boolean allowed=enabled()&&autonomousAllowed();
        String desired=Boolean.toString(allowed);
        if(desired.equals(sentConsent))return;
        long clock=android.os.SystemClock.elapsedRealtime();
        if(clock<nextConsentSync)return;
        nextConsentSync=clock+30000L;
        String version=app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionName;
        request("PUT","/api/agent/"+id+"/permissions",new JSONObject().put("enabled",allowed).put("version",version),secret);
        sentConsent=desired;nextConsentSync=0;permissionStatus=allowed?"Autorisations persistantes actives":"Autorisations révoquées";
    }
    public void setEnabled(boolean on){
        if(!on)stop();
        p.edit().putBoolean("enabled",on).apply();
        consentChanged();
        if(on){status=paired()?"Connexion à CHK Universal Control":"Association nécessaire";start();}
    }
    public void start(){
        if(!enabled()||!paired()||serverUrl().isEmpty()||running)return;
        running=true;nextCapabilitySync=0;sentConsent="";main.post(loop);
    }
    public void stop(){
        running=false;generation++;main.removeCallbacks(loop);
        status="Désactivé";
    }
    public JSONObject snapshot() throws Exception{
        String version=app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionName;
        return new JSONObject().put("protocol_version",BrowserAutomation.VERSION).put("version",version)
            .put("enabled",enabled()).put("paired",paired()).put("status",status())
            .put("autonomous",autonomousAllowed()).put("persistent_permissions",persistentPermissions())
            .put("permission_sync",permissionStatus).put("request_quota",JSONObject.NULL)
            .put("max_file_bytes",TransferLimits.MAX_FILE_BYTES).put("max_tabs",BrowserPolicy.MAX_TABS)
            .put("agent_control",AgentControl.capabilities())
            .put("previews_allowed",AgentClient.get(app).isPreviewAllowed())
            .put("capability_sync",capabilityStatus).put("last_action",lastAction)
            .put("last_outcome",lastOutcome).put("result_pending",!p.getString("outbox","").isEmpty());
    }
    public void reconnect(){
        nextCapabilitySync=0;
        main.post(()->{
            if(!enabled()||!paired())return;
            if(!running)start();
            else if(!busy&&!executing){main.removeCallbacks(loop);main.post(loop);}
        });
    }
    /** Credentials are generated by the PC; they are never part of the app source. */
    public void pair(String invitation,PairCallback callback){
        String code=invitation==null?"":invitation.trim();
        if(!code.matches("chkinvite_[A-Za-z0-9_-]{20,}")){
            callback.complete(false,"Invitation invalide. Crée une invitation avec app_invite dans Local 3.");return;
        }
        if(serverUrl().isEmpty()){
            callback.complete(false,"Configure d’abord le lien HTTPS du serveur Local 3.");return;
        }
        io.execute(()->{
            try{
                JSONObject input=new JSONObject().put("name","CHK Agent Browser Android · Local 3")
                    .put("invite",code).put("capabilities",capabilities());
                JSONObject response=request("POST","/api/onboard",input,null);
                String id=response.getString("id"),secret=response.getString("secret");
                if(!id.matches("[a-f0-9-]{36}")||!secret.startsWith("chkapp_"))
                    throw new IllegalStateException("Identifiants de l'application invalides");
                // Commit both credentials atomically before starting the channel.
                if(!p.edit().putString("app_id",id).putString("secret",secret).putBoolean("enabled",true).commit())
                    throw new IllegalStateException("Enregistrement local des identifiants impossible");
                status="Association réussie · connexion en cours";
                main.post(()->{start();AgentService.ensureRunning(app);callback.complete(true,"Local 3 associé et activé");});
            }catch(Exception e){
                main.post(()->callback.complete(false,"Association impossible : "+safe(e)));
            }
        });
    }
    static JSONArray capabilities() throws Exception{
        JSONArray a=new JSONArray();
        String[] read={
            "browser_upload_status","browser_get_page_status","browser_verify_fields","workflow_status",
            "local3_status","browser_inspect","browser_capture","browser_status","browser_read_page","browser_get_form","files_list",
            "files_read_text","files_read_bytes","files_transfer_read","files_stat","files_hash","files_catalog",
            "files_downloads","files_preview","files_detect_format","files_capabilities",
            "files_archive_list","files_archive_read","files_media_inspect",
            "document_read","document_page","media_info","media_frame","media_codecs",
            "workspace_status","notes_list","notes_read","video_editor_capabilities",
            "video_editor_queue_status","video_editor_status","video_editor_wait",
            "video_editor_project_read","video_editor_verify_output","publication_list",
            "publication_status"
        };
        String[] write={
            "browser_upload_file","browser_download_file","browser_click_when_enabled","browser_fill_fields","workflow_run","workflow_resume","workflow_cancel",
            "android_action","browser_tabs","browser_open_url","browser_click",
            "browser_type","browser_scroll","browser_batch","files_write_text",
            "files_edit_text","files_move","files_copy","files_mkdir","files_trash",
            "files_restore","files_zip","files_unzip","files_import_download",
            "files_external","files_transfer_begin","files_transfer_write",
            "files_transfer_commit","files_transfer_abort","files_task",
            "files_batch","files_convert","files_extract_archive","document_create",
            "notes_save","notes_trash","notes_restore","notes_export",
            "video_editor_project_create","video_editor_project_update",
            "video_editor_render","publication_prepare","publication_cancel",
            "keyboard","desktop"
        };
        for(String name:read)a.put(new JSONObject().put("name",name).put("readOnly",true)
            .put("description","Lire ou inspecter les données CHK Android avec autorisation du propriétaire"));
        for(String name:write)a.put(new JSONObject().put("name",name).put("readOnly",false)
            .put("description","Contrôler CHK Android selon les autorisations persistantes du propriétaire"));
        return a;
    }
    /** Exact protocol-2 manifest accepted by Universal Control 1.0.4.
     * The two newer operations remain callable through the existing desktop tool.
     * Never downgrade readOnly policies or alter the pairing/permissions.
     */
    static JSONArray compatibleCapabilities() throws Exception{
        JSONArray compatible=new JSONArray(),all=capabilities();
        for(int i=0;i<all.length();i++){
            JSONObject entry=all.getJSONObject(i);String name=entry.getString("name");
            if(!name.equals("browser_download_file")&&!name.equals("browser_click_when_enabled"))compatible.put(entry);
        }
        return compatible;
    }
    private void syncCapabilities(String id,String secret)throws Exception{
        JSONArray manifest=capabilities();boolean compatible=false;
        try{
            request("PUT","/api/agent/"+id+"/capabilities",new JSONObject()
                .put("capabilities",manifest).put("diagnostic",snapshot()),secret);
        }catch(java.io.IOException e){
            // Only a manifest rejection can trigger negotiation. Auth/network
            // errors must remain visible, with no second state-changing request.
            if(!"HTTP 400".equals(e.getMessage()))throw e;
            manifest=compatibleCapabilities();
            request("PUT","/api/agent/"+id+"/capabilities",new JSONObject()
                .put("capabilities",manifest).put("diagnostic",snapshot()),secret);
            compatible=true;
        }
        capabilityStatus="Synchronisés · "+manifest.length()+" outils"+
            (compatible?" · compatibilité serveur 1.0.4 (opérations avancées via desktop)":"");
    }
    private JSONObject request(String method,String route,JSONObject payload,String secret)throws Exception{
        String base=serverUrl();
        if(base.isEmpty())throw new java.io.IOException("Serveur Local 3 non configuré");
        URL u=new URL(base+route);
        HttpURLConnection c=(HttpURLConnection)u.openConnection();
        try{
            c.setRequestMethod(method);c.setConnectTimeout(9000);c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");
            if(secret!=null)c.setRequestProperty("Authorization","Bearer "+secret);
            if(payload!=null){
                byte[] bytes=payload.toString().getBytes(StandardCharsets.UTF_8);
                if(bytes.length>340000)throw new IllegalArgumentException("Message trop volumineux");
                c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);
                try(OutputStream out=c.getOutputStream()){out.write(bytes);}
            }
            int http=c.getResponseCode();
            if(http!=200 && http!=201)throw new java.io.IOException("HTTP "+http);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[4096];int len;
                while((len=in.read(buf))!=-1){
                    if(out.size()+len>350000)throw new java.io.IOException("Réponse trop volumineuse");
                    out.write(buf,0,len);
                }
                return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            }
        }finally{c.disconnect();}
    }
    /** Large text, MP3/MP4/PDF metadata and previews remain in Android workspace. */
    static String compactResult(Context app,String result)throws Exception{
        if(result==null)return "";
        if(result.getBytes(StandardCharsets.UTF_8).length<=240000
            &&new JSONObject().put("message",result).toString().getBytes(StandardCharsets.UTF_8).length<=330000)return result;
        return resultFile(app,result);
    }
    static String compactWorkflowResult(Context app,String result)throws Exception{
        return result.getBytes(StandardCharsets.UTF_8).length<=26000?result:resultFile(app,result);
    }
    private static String resultFile(Context app,String result)throws Exception{
        byte[] bytes=result.getBytes(StandardCharsets.UTF_8);
        WorkspaceStore store=new WorkspaceStore(app);
        java.io.File directory=store.file("AgentResults");
        if(!directory.exists())store.mkdir("AgentResults");
        String name="Local3-"+java.util.UUID.randomUUID()+".json";
        String path="AgentResults/"+name;
        java.io.File target=store.file(path);
        java.io.File stage=new java.io.File(directory,".stage-"+java.util.UUID.randomUUID());
        try{
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(stage)){
                out.write(bytes);out.getFD().sync();
            }
            if(!stage.renameTo(target))throw new java.io.IOException("Enregistrement du résultat impossible");
            return new JSONObject().put("result_in_file",true).put("result_file",path)
                .put("size",target.length()).put("updated",target.lastModified()).put("sha256",WorkspaceTransfer.hash(target))
                .put("read_action","files_transfer_read").put("truncated",false).toString();
        }finally{stage.delete();}
    }
    private static String safe(Exception e){
        if(e instanceof java.net.SocketTimeoutException)return "délai réseau dépassé";
        if(e instanceof java.net.UnknownHostException)return "serveur PC introuvable";
        if(e instanceof javax.net.ssl.SSLException)return "certificat HTTPS non reconnu";
        String m=e.getMessage();
        if(m!=null && m.matches("HTTP [0-9]{3}"))return m;
        return e.getClass().getSimpleName();
    }
    private void schedule(int session,long delay){
        main.post(()->{
            if(session==generation&&running&&enabled()){
                main.removeCallbacks(loop);main.postDelayed(loop,delay);
            }
        });
    }
    private void poll(){
        if(!running||!enabled()||!paired()||busy||executing)return;
        busy=true;
        final int session=generation, idLength=p.getString("app_id","").length();
        final String id=p.getString("app_id",""),secret=p.getString("secret","");
        io.execute(()->{
            long delay=2500L;
            try{
                if(session!=generation||!running||idLength!=36)return;
                try{syncConsent(id,secret);}catch(Exception e){permissionStatus="Serveur à mettre à jour : "+safe(e);}
                // A process interruption after leasing must never silently repeat an effect.
                String interrupted=p.getString("leased_task","");
                if(!interrupted.isEmpty()&&p.getString("outbox","").isEmpty()){
                    JSONObject unknown=new JSONObject().put("task_id",interrupted).put("result",new JSONObject()
                        .put("ok",false).put("execution_state","unknown").put("retry_command",false)
                        .put("message","Android a été interrompu pendant cette commande. Vérifier son effet avant de la relancer."));
                    if(!p.edit().putString("outbox",unknown.toString()).remove("leased_task").commit())
                        throw new java.io.IOException("Journal d’interruption non enregistré");
                }
                // An already executed action is acknowledged first. Never replay it.
                String queued=p.getString("outbox","");
                if(!queued.isEmpty()){
                    JSONObject o=new JSONObject(queued);
                    request("POST","/api/agent/"+id+"/result",o,secret);
                    if(queued.equals(p.getString("outbox","")))p.edit().remove("outbox").commit();
                }
                // Refresh this paired worker's fixed tool manifest after every APK update.
                // Older servers may reject this route; keep polling existing tools.
                long clock=android.os.SystemClock.elapsedRealtime();
                if(clock>=nextCapabilitySync){
                    nextCapabilitySync=clock+30000L;
                    try{
                        syncCapabilities(id,secret);
                        nextCapabilitySync=clock+21600000L;
                    }catch(Exception e){capabilityStatus="Serveur à mettre à jour : "+safe(e);}
                }
                JSONObject reply=request("GET","/api/agent/"+id+"/poll",null,secret);
                if(session!=generation||!running||!enabled())return;
                lastContact=android.os.SystemClock.elapsedRealtime();
                status="Connecté au serveur local · Android disponible";errors=0;
                JSONObject task=reply.optJSONObject("task");
                if(task!=null){
                    String taskId=task.getString("id");
                    if(!taskId.matches("[a-f0-9-]{36}"))throw new java.io.IOException("Identifiant de tâche incorrect");
                    executing=true;
                    // Mark as leased on Android before attempting any side effect.
                    if(!p.edit().putString("leased_task",taskId).commit())
                        throw new java.io.IOException("Journal de tâche non enregistré");
                    String action=task.getString("action");
                    lastAction=action;lastOutcome="en cours";
                    JSONObject args=task.optJSONObject("args");
                    final JSONObject parameters=args==null?new JSONObject():args;
                    main.post(()->{
                        if(session!=generation||!running||!enabled()){executing=false;return;}
                        AgentClient.get(app).executeLocal3(action,parameters,(ok,message)->{
                            io.execute(()->{
                                try{
                                    String bounded=compactResult(app,message);
                                    lastOutcome=ok?"réussie":"échec";
                                    JSONObject result=new JSONObject().put("ok",ok).put("message",bounded);
                                    JSONObject outbound=new JSONObject().put("task_id",taskId)
                                        .put("result",result);
                                    String packet=outbound.toString();
                                    if(!p.edit().putString("outbox",packet).remove("leased_task").commit())
                                        throw new java.io.IOException("Résultat non enregistré");
                                    try{
                                        request("POST","/api/agent/"+id+"/result",outbound,secret);
                                        if(packet.equals(p.getString("outbox","")))p.edit().remove("outbox").commit();
                                    }catch(Exception e){status="Résultat sauvegardé · transmission en attente";}
                                }catch(Exception e){status="Résultat Android non confirmé : "+safe(e);}
                                finally{executing=false;schedule(session,0);}
                            });
                        });
                    });
                }
            }catch(Exception e){
                errors=Math.min(8,errors+1);
                status="Connexion Local 3 indisponible : "+safe(e);
                delay=Math.min(45000L,3500L << Math.min(3,errors));
            }finally{
                busy=false;
                if(!executing)schedule(session,delay);
            }
        });
    }
}
