package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The MCP poller belongs to AgentService, never to an Activity.
 * Autonomous consent is persisted; commands run on the service or visible browser.
 * The local installation token and user's MCP URL are preserved during APK updates.
 */
public final class AgentClient {
    public static final String BASE = "https://modeliseur-trellis-mcp.onrender.com";
    private static final String PREF = "browser_mcp_v1";
    private static final long COMMAND_TIMEOUT_MS = 180000L;
    private static AgentClient instance;
    private final Context app;
    private final SharedPreferences settings;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile boolean inFlight;
    private Activity screen;
    private CommandHandler handler;
    private CommandHandler backgroundHandler;
    private IncomingListener incoming;
    private volatile JSONObject pending;
    private long receivedAt;
    private boolean presenting;
    private int networkErrors;
    private int generation;
    private volatile long lastContact;
    private long lastHeartbeat;
    private volatile String lastError = "";
    private volatile long lastRelayLatencyMs = -1L;
    private volatile long meanRelayLatencyMs = -1L;
    private volatile long lastCommandDurationMs = -1L;
    private volatile int successfulRequests;
    private volatile int failedRequests;
    private long lastReconnectRequest;
    private final Runnable tick = this::poll;
    private final Runnable expiry = () -> finishPending(false, "Commande expirée. Vérifie la connexion et le mode autonome.");

    public interface ResultCallback {
        void finish(boolean success, String result);
        default boolean isActive(){return true;}
    }
    public interface CommandHandler {
        void onCommand(JSONObject command, ResultCallback callback);
        String pageUrl();
        String pageTitle();
        default String sessionSource() { return "background"; }
    }
    public interface RegistrationCallback {void completed(boolean ok, String message); }
    public interface IncomingListener {
        void waitingForApproval(String action);
        void approvalFinished();
    }

    private AgentClient(Context context) {
        app = context.getApplicationContext();
        settings = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
    public static synchronized AgentClient get(Context c) {
        if(instance == null) instance = new AgentClient(c);
        return instance;
    }
    public boolean isEnabled() { return settings.getBoolean("enabled", false); }
    public boolean isDirectMode() { return settings.getBoolean("direct_mode", false); }
    public String mcpUrl() {
        return isDirectMode() ? DirectMcpServer.privateUrl(app) : settings.getString("mcp_url", "");
    }
    /** No registration, polling, heartbeat, or file transfer to Render in direct mode. */
    public void enableDirect() {
        DirectMcpServer.secret(app);
        running = false;
        generation++;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(expiry);
        pending = null;
        presenting = false;
        settings.edit().remove("result_outbox").remove("executing_id")
            .putBoolean("direct_mode", true).putBoolean("enabled", true).commit();
        lastError = "";
        AgentLog.add(app,"mcp","mode serveur local Tailscale activé ; aucun relais Render");
    }
    String deviceTokenForTransfers(){ return token(); }
    public String lastCommandStatus(){
        String action=settings.getString("last_command_action","");
        String result=settings.getString("last_command_result","");
        if(action.isEmpty())return "Aucune commande exécutée";
        return action+" · "+(result.isEmpty()?"en cours":result);
    }
    public String lastErrorSummary(){return lastError.isEmpty()?"aucune":lastError;}
    public String telemetrySummary(){
        return "Latence relais : "+(lastRelayLatencyMs<0?"non mesurée":lastRelayLatencyMs+" ms")+
            "\nMoyenne mobile : "+(meanRelayLatencyMs<0?"non mesurée":meanRelayLatencyMs+" ms")+
            "\nÉchanges réussis / échecs : "+successfulRequests+" / "+failedRequests+
            "\nDernière commande : "+(lastCommandDurationMs<0?"non mesurée":lastCommandDurationMs+" ms");
    }
    public String shortConnectionStatus(){
        if(!isEnabled())return "MCP désactivé";
        if(isDirectMode())return AgentService.directRunning()
            ? "MCP direct Tailscale actif · sans Render" : "Serveur direct en attente";
        if(!running)return "Connexion arrêtée · ouvrir Réglages";
        if(!lastError.isEmpty()||(lastContact>0&&SystemClock.elapsedRealtime()-lastContact>45000L))
            return "Reconnexion à ChatGPT en cours…";
        if(lastContact==0)return "Connexion à ChatGPT en cours…";
        return "● ChatGPT connecté · "+(isAutonomous()?"autonome":"manuel");
    }
    public String connectionStatus(){
        if(!isEnabled())return "MCP désactivé";
        if(isDirectMode())return AgentService.directRunning()
            ? "Serveur local Android actif sur le port 8765 · HTTPS public non configuré · 0 appel Render"
            : "Démarrage du serveur local · aucune connexion Render";
        if(!running)return "Service arrêté : ouvre le navigateur pour reprendre";
        if(!lastError.isEmpty())return "Reconnexion automatique · " + lastError;
        if(lastContact==0)return "Connexion au relais en cours…";
        if(SystemClock.elapsedRealtime()-lastContact>45000L)return "Relais sans réponse récente · reconnexion en cours";
        return "Relais connecté · dernier contact il y a " +
            ((SystemClock.elapsedRealtime()-lastContact)/1000) + " s";
    }
    public void reconnect(){
        if(isDirectMode()) return;
        ui.post(()->{
            if(isDirectMode())return;
            if(!running||!isEnabled())return;
            long now=SystemClock.elapsedRealtime();
            if(now-lastReconnectRequest<1200L)return;
            lastReconnectRequest=now;
            ui.removeCallbacks(tick);ui.post(tick);
        });
    }
    void browserInterrupted(){
        if(pending!=null&&!WorkspaceCommands.handles(pending.optString("action",""))&&!KeyboardCommands.nativeTarget(pending)&&!DesktopCommands.nativeTarget(pending))
            finishPending(false,"Page interrompue par Android. Résultat inconnu : relis la page avant de recommencer l'action.");
    }
    public boolean isAutonomous(){return settings.getBoolean("autonomous_mode",false);}
    /** Optional reduced background wake usage, never enabled silently. */
    public boolean isEcoMode(){return settings.getBoolean("eco_mode",false);}
    public boolean isExecuting(){return pending!=null;}
    public void setEcoMode(boolean enabled){settings.edit().putBoolean("eco_mode",enabled).apply();}
    public void setAutonomous(boolean allow){
        settings.edit().putBoolean("autonomous_mode",allow).apply();
        deliverPending();
    }
    public boolean isPreviewAllowed(){return settings.getBoolean("preview_allowed",false);}
    public void setPreviewAllowed(boolean allow){settings.edit().putBoolean("preview_allowed",allow).apply();}
    public void setBackgroundHandler(CommandHandler listener){
        backgroundHandler=listener;
        deliverPending();
    }
    public void removeBackgroundHandler(CommandHandler listener){
        if(backgroundHandler==listener)backgroundHandler=null;
    }
    void nativeScreenChanged(){ui.post(this::deliverPending);}
    private CommandHandler activeHandler() {
        return screen!=null && handler!=null ? handler :
            (isAutonomous()?backgroundHandler:null);
    }
    private synchronized String token() {
        String key = settings.getString("device_token", "");
        if(key.matches("[a-f0-9]{64}")) return key;
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for(byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b & 255));
        key = sb.toString();
        settings.edit().putString("device_token", key).apply();
        return key;
    }
    public void enable(RegistrationCallback callback) {
        settings.edit().putBoolean("direct_mode", false).apply();
        io.execute(() -> {
            try {
                JSONObject request = new JSONObject(); request.put("device_token", token());
                JSONObject response = request("POST", "/agentbrowser/api/register", request, false);
                if(!response.optBoolean("ok")) throw new Exception(response.optString("error","Association refusée"));
                String url = response.optString("mcp_url", "");
                if(!url.startsWith(BASE + "/agentbrowser/mcp/")) throw new Exception("URL MCP inattendue");
                settings.edit().putString("mcp_url", url).putBoolean("enabled", true).apply();
                AgentLog.add(app,"mcp","association au relais réussie");
                ui.post(() -> callback.completed(true, "MCP connecté : contrôle maintenu par notification Android."));
            } catch(Exception e) {
                AgentLog.add(app,"mcp","association refusée : "+e.getClass().getSimpleName());
                ui.post(() -> callback.completed(false, "Association impossible : " + e.getMessage()));
            }
        });
    }
    public void disable() {
        AgentLog.add(app,"mcp","désactivation demandée");
        settings.edit().putBoolean("enabled", false).apply();
        running = false;generation++;
        ui.removeCallbacks(tick);
        finishPending(false, "MCP désactivé par le propriétaire.");
    }
    /** Called only from the Android foreground Service. */
    public void startServiceMode(IncomingListener listener) {
        if(isDirectMode()) {
            running = false;
            ui.removeCallbacks(tick);
            incoming = null;
            return;
        }
        incoming = listener;
        if(!isEnabled()) return;
        running = true;
        ui.removeCallbacks(tick);
        ui.post(tick);
        deliverPending();
    }
    public void stopServiceMode() {
        running = false;generation++;
        ui.removeCallbacks(tick);
        incoming = null;
        finishPending(false, "Service MCP arrêté.");
    }
    /** MainActivity overrides the service engine while the visible browser is active. */
    public void attach(Activity activity, CommandHandler h) {
        screen = activity; handler = h;
        deliverPending();
    }
    public void detach(Activity activity) {
        if(screen == activity) {
            screen = null; handler = null;
            // An executing command retains ownership until its callback/timeout.
            deliverPending();
        }
    }
    /** Dispatch local Tailscale MCP requests through the exact same Android handlers as the UI. */
    void executeDirect(String action, JSONObject args, DirectMcpServer.Reply reply) {
        ui.post(() -> {
            if (!isEnabled() || !isDirectMode()) {
                reply.finish(false, "MCP direct désactivé."); return;
            }
            if (!isAutonomous()) {
                reply.finish(false, "Autorise le mode autonome dans les réglages CHK."); return;
            }
            CommandHandler receiver = activeHandler();
            if (receiver == null) {
                reply.finish(false, "Aucun navigateur CHK actif."); return;
            }
            JSONObject command = new JSONObject();
            try {
                command.put("id", "direct-local");
                command.put("action", action);
                command.put("args", args);
            } catch (org.json.JSONException fail) {
                reply.finish(false, "Commande invalide."); return;
            }
            try {
                receiver.onCommand(command, (ok, message) -> reply.finish(ok, message));
            } catch (RuntimeException fail) {
                reply.finish(false, "Erreur locale : " + fail.getClass().getSimpleName());
            }
        });
    }

    private void poll() {
        if(isDirectMode() || !running || !isEnabled()) return;
        if(inFlight) { ui.removeCallbacks(tick);ui.postDelayed(tick, 1200L); return; }
        inFlight = true;
        final int requestGeneration=generation;
        final boolean mustHeartbeat = pending != null;
        final CommandHandler currentHandler=activeHandler();
        final String page=currentHandler==null?"":currentHandler.pageUrl();
        final String title=currentHandler==null?"":currentHandler.pageTitle();
        io.execute(() -> {
            JSONObject reply = null;
            try {
                flushResult();
                if(mustHeartbeat || SystemClock.elapsedRealtime()-lastHeartbeat>=10000L) {
                    JSONObject info = new JSONObject();
                    info.put("url", limit(page, 500)); info.put("title", limit(title, 150));
                    info.put("autonomous",isAutonomous());
                    info.put("workspace_version",3);
                    info.put("keyboard_version",1);
                    info.put("desktop_version",1);
                    info.put("publication_version",1);
                    info.put("session_source",currentHandler==null?"none":currentHandler.sessionSource());
                    info.put("executing_id",settings.getString("executing_id",""));
                    request("POST", "/agentbrowser/api/heartbeat", info, true);
                    lastHeartbeat=SystemClock.elapsedRealtime();
                }
                if(!mustHeartbeat) {
                    reply = request("GET", "/agentbrowser/api/poll", null, true);
                }
                networkErrors = 0;lastError="";
                lastContact=SystemClock.elapsedRealtime();
            } catch(Exception failure) {
                networkErrors++;lastError=failure.getMessage()==null?"Réseau indisponible":failure.getMessage();
                AgentLog.add(app,"réseau","échec relais #"+networkErrors+" : "+failure.getClass().getSimpleName());
            }
            final JSONObject result = reply;
            ui.post(() -> {
                inFlight = false;
                if(!running||requestGeneration!=generation) {
                    if(running){ui.removeCallbacks(tick);ui.post(tick);}return;
                }
                JSONObject command = result == null ? null : result.optJSONObject("command");
                if(command != null && pending == null && command.has("id")) {
                    String id=command.optString("id");
                    if(id.equals(settings.getString("last_result_id",""))) {
                        settings.edit().putString("result_outbox",settings.getString("last_result","" )).commit();
                        ui.postDelayed(tick,200L);return;
                    }
                    pending = command; receivedAt = SystemClock.elapsedRealtime(); presenting = false;
                    AgentLog.add(app,"commande","reçue : "+command.optString("action","commande"));
                    settings.edit().putString("last_command_action",command.optString("action","commande"))
                        .putString("last_command_result","en cours").apply();
                    ui.postDelayed(expiry, COMMAND_TIMEOUT_MS);
                    if(incoming != null && !isAutonomous()) incoming.waitingForApproval(command.optString("action","Action"));
                    deliverPending();
                }
                ui.removeCallbacks(tick);
                ui.postDelayed(tick, RelayPolicy.pollDelay(networkErrors,pending!=null,screen!=null));
            });
        });
    }
    void executeTaskStep(JSONObject command,ResultCallback cb){
        if(!cb.isActive())return;
        CommandHandler receiver=DesktopCommands.nativeTarget(command)?DesktopCommands.handler(app):KeyboardCommands.nativeTarget(command)?NativeKeyboard.handler(app):activeHandler();
        if(receiver==null){cb.finish(false,"Aucun écran CHK ou moteur web disponible.");return;}
        receiver.onCommand(command,cb);
    }
    private void deliverPending() {
        CommandHandler receiver=pending!=null&&DesktopCommands.nativeTarget(pending)?DesktopCommands.handler(app):pending!=null&&KeyboardCommands.nativeTarget(pending)?NativeKeyboard.handler(app):activeHandler();
        if(pending == null || presenting || receiver == null ||
            (screen != null && screen.isFinishing()))return;
        if(SystemClock.elapsedRealtime() - receivedAt >= COMMAND_TIMEOUT_MS) {
            finishPending(false, "Commande expirée."); return;
        }
        presenting = true;
        final String originalId = pending.optString("id", "");
        final JSONObject command = pending;
        if(originalId.equals(settings.getString("executing_id",""))) {
            finishPending(false,"Exécution interrompue : résultat inconnu. Vérifie la page avant une nouvelle action.");
            return;
        }
        if(!settings.edit().putString("executing_id",originalId).commit()) {
            finishPending(false,"Impossible de mémoriser la commande : aucune action exécutée.");return;
        }
        ResultCallback callback=new ResultCallback(){
            @Override public boolean isActive(){
                JSONObject active=pending;
                return isEnabled()&&active==command;
            }
            @Override public void finish(boolean approved,String text){ui.post(()->{
                if(pending==command)finishPending(approved,text);
            });}
        };
        try{receiver.onCommand(command,callback);}
        catch(RuntimeException failure){finishPending(false,"Commande interrompue : "+failure.getClass().getSimpleName());}
    }
    private void finishPending(boolean ok, String message) {
        if(pending == null) return;
        lastCommandDurationMs=Math.max(0L,SystemClock.elapsedRealtime()-receivedAt);
        String id = pending.optString("id", "");
        String action = pending.optString("action","");
        pending = null;
        presenting = false;
        ui.removeCallbacks(expiry);
        if(incoming != null) incoming.approvalFinished();
        settings.edit().putString("last_command_result",ok?"réussie":"échec : "+limit(message,180)).apply();
        AgentLog.add(app,"commande",action+" : "+(ok?"réussie":"échec"));
        sendResult(id, action, ok, message);
        if(running) { ui.removeCallbacks(tick); ui.postDelayed(tick, 200L); }
    }
    private void sendResult(String id, String action, boolean success, String message) {
        try {
            JSONObject result=CommandResult.envelope(id,action,success,message);
            settings.edit().putString("result_outbox",result.toString())
                .putString("last_result",result.toString()).putString("last_result_id",id)
                .remove("executing_id").commit();
            io.execute(()->{try{flushResult();}catch(Exception ignored){
                lastError="Résultat en attente d'envoi : reconnexion automatique";
            }});
        }catch(Exception ignored){lastError="Impossible de préparer le résultat";}
    }
    private void flushResult() throws Exception {
        String saved=settings.getString("result_outbox","");
        if(saved.isEmpty())return;
        try{
            request("POST","/agentbrowser/api/result",new JSONObject(saved),true);
            AgentLog.add(app,"mcp","résultat confirmé par le relais");
        }catch(HttpFailure failure){
            if(!RelayPolicy.isTerminalResultStatus(failure.status))throw failure;
            // Expired/unknown commands cannot be acknowledged after relay restart.
            // Preserve the local receipt, but unblock polling for the next command.
            lastError="Résultat clos par le relais (HTTP "+failure.status+")";
        }
        // Never discard a newer result written by the UI during this request.
        synchronized(settings) {
            if(saved.equals(settings.getString("result_outbox","")))
                settings.edit().remove("result_outbox").commit();
        }
    }
    private static String limit(String text, int max) {
        if(text == null) return "";
        return text.length() > max ? text.substring(0,max) : text;
    }
    private static final class HttpFailure extends java.io.IOException {
        final int status;
        HttpFailure(int code){super("HTTP "+code);status=code;}
    }
    private JSONObject request(String method, String path, JSONObject body, boolean auth) throws Exception {
        HttpURLConnection c = null;
        final long requestStarted=SystemClock.elapsedRealtime();
        boolean completed=false;
        try {
            c = (HttpURLConnection)new URL(BASE+path).openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(10000);
            c.setRequestMethod(method); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept","application/json");
            c.setRequestProperty("User-Agent","CHK-Agent-Browser/2.1");
            if(auth) c.setRequestProperty("Authorization", "Bearer "+token());
            if(body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                if(bytes.length>(path.equals("/agentbrowser/api/result")?250000:24000)) throw new Exception("Message trop long");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json");
                try(OutputStream stream=c.getOutputStream()) { stream.write(bytes); }
            }
            int code=c.getResponseCode();
            if(code<200 || code>=300) throw new HttpFailure(code);
            try(InputStream input=c.getInputStream()) {
                ByteArrayOutputStream output=new ByteArrayOutputStream();
                byte[] buf=new byte[4096]; int n;
                while((n=input.read(buf))!=-1) {
                    output.write(buf,0,n);
                    if(output.size()>(path.equals("/agentbrowser/api/poll")?230000:48000)) throw new Exception("Réponse trop longue");
                }
                JSONObject parsed=new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));
                completed=true;
                return parsed;
            }
        } finally {
            long elapsed=Math.max(0L,SystemClock.elapsedRealtime()-requestStarted);
            lastRelayLatencyMs=elapsed;
            if(completed){
                successfulRequests++;
                meanRelayLatencyMs=meanRelayLatencyMs<0?elapsed:(meanRelayLatencyMs*3+elapsed)/4;
            }else failedRequests++;
            if(c!=null)c.disconnect();
        }
    }
}
