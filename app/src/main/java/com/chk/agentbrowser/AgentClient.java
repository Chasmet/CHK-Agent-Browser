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
 * WebView commands always run on a visible Activity after owner confirmation.
 * The local installation token and user's MCP URL are preserved during APK updates.
 */
public final class AgentClient {
    public static final String BASE = "https://modeliseur-trellis-mcp.onrender.com";
    private static final String PREF = "browser_mcp_v1";
    private static final long COMMAND_TIMEOUT_MS = 42000L;
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
    private JSONObject pending;
    private long receivedAt;
    private boolean presenting;
    private int networkErrors;
    private final Runnable tick = this::poll;
    private final Runnable expiry = () -> finishPending(false, "Commande expirée. Vérifie la connexion et le mode autonome.");

    public interface ResultCallback { void finish(boolean success, String result); }
    public interface CommandHandler {
        void onCommand(JSONObject command, ResultCallback callback);
        String pageUrl();
        String pageTitle();
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
    public String mcpUrl() { return settings.getString("mcp_url", ""); }
    public boolean isAutonomous(){return settings.getBoolean("autonomous_mode",false);}
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
        io.execute(() -> {
            try {
                JSONObject request = new JSONObject(); request.put("device_token", token());
                JSONObject response = request("POST", "/agentbrowser/api/register", request, false);
                if(!response.optBoolean("ok")) throw new Exception(response.optString("error","Association refusée"));
                String url = response.optString("mcp_url", "");
                if(!url.startsWith(BASE + "/agentbrowser/mcp/")) throw new Exception("URL MCP inattendue");
                settings.edit().putString("mcp_url", url).putBoolean("enabled", true).apply();
                ui.post(() -> callback.completed(true, "MCP connecté : contrôle maintenu par notification Android."));
            } catch(Exception e) {
                ui.post(() -> callback.completed(false, "Association impossible : " + e.getMessage()));
            }
        });
    }
    public void disable() {
        settings.edit().putBoolean("enabled", false).apply();
        running = false;
        ui.removeCallbacks(tick);
        finishPending(false, "MCP désactivé par le propriétaire.");
    }
    /** Called only from the Android foreground Service. */
    public void startServiceMode(IncomingListener listener) {
        incoming = listener;
        if(!isEnabled()) return;
        running = true;
        ui.removeCallbacks(tick);
        ui.post(tick);
        deliverPending();
    }
    public void stopServiceMode() {
        running = false;
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
            presenting = false;
            deliverPending();
        }
    }
    private void poll() {
        if(!running || !isEnabled()) return;
        if(inFlight) { ui.postDelayed(tick, 1200L); return; }
        inFlight = true;
        final boolean mustHeartbeat = pending != null;
        final CommandHandler currentHandler=activeHandler();
        final String page=currentHandler==null?"":currentHandler.pageUrl();
        final String title=currentHandler==null?"":currentHandler.pageTitle();
        io.execute(() -> {
            JSONObject reply = null;
            try {
                if(mustHeartbeat) {
                    JSONObject info = new JSONObject();
                    info.put("url", limit(page, 500)); info.put("title", limit(title, 150));
                    request("POST", "/agentbrowser/api/heartbeat", info, true);
                } else {
                    reply = request("GET", "/agentbrowser/api/poll", null, true);
                }
                networkErrors = 0;
            } catch(Exception ignored) { networkErrors++; }
            final JSONObject result = reply;
            ui.post(() -> {
                inFlight = false;
                if(!running) return;
                JSONObject command = result == null ? null : result.optJSONObject("command");
                if(command != null && pending == null && command.has("id")) {
                    pending = command; receivedAt = SystemClock.elapsedRealtime(); presenting = false;
                    ui.postDelayed(expiry, COMMAND_TIMEOUT_MS);
                    if(incoming != null && !isAutonomous()) incoming.waitingForApproval(command.optString("action","Action"));
                    deliverPending();
                }
                ui.postDelayed(tick, networkErrors > 3 ? 7500L : (pending == null ? 3000L : 10000L));
            });
        });
    }
    private void deliverPending() {
        CommandHandler receiver=activeHandler();
        if(pending == null || presenting || receiver == null ||
            (screen != null && screen.isFinishing()))return;
        if(SystemClock.elapsedRealtime() - receivedAt >= COMMAND_TIMEOUT_MS) {
            finishPending(false, "Commande expirée."); return;
        }
        presenting = true;
        final String originalId = pending.optString("id", "");
        final JSONObject command = pending;
        receiver.onCommand(command, (approved, text) -> ui.post(() -> {
            if(pending != null && originalId.equals(pending.optString("id"))) finishPending(approved, text);
        }));
    }
    private void finishPending(boolean ok, String message) {
        if(pending == null) return;
        String id = pending.optString("id", "");
        String action = pending.optString("action","");
        pending = null;
        presenting = false;
        ui.removeCallbacks(expiry);
        if(incoming != null) incoming.approvalFinished();
        sendResult(id, action, ok, message);
        if(running) { ui.removeCallbacks(tick); ui.postDelayed(tick, 200L); }
    }
    private void sendResult(String id, String action, boolean success, String message) {
        io.execute(() -> {
            try {
                JSONObject result = new JSONObject();
                result.put("id", id); result.put("ok", success);
                if(success) result.put("result", limit(message, "preview".equals(action)?230000:11000));
                else result.put("error", limit(message, 750));
                request("POST", "/agentbrowser/api/result", result, true);
            } catch(Exception ignored) {}
        });
    }
    private static String limit(String text, int max) {
        if(text == null) return "";
        return text.length() > max ? text.substring(0,max) : text;
    }
    private JSONObject request(String method, String path, JSONObject body, boolean auth) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection)new URL(BASE+path).openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(10000);
            c.setRequestMethod(method); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept","application/json");
            c.setRequestProperty("User-Agent","CHK-Agent-Browser/2.0");
            if(auth) c.setRequestProperty("Authorization", "Bearer "+token());
            if(body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                if(bytes.length>(path.equals("/agentbrowser/api/result")?250000:24000)) throw new Exception("Message trop long");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json");
                try(OutputStream stream=c.getOutputStream()) { stream.write(bytes); }
            }
            int code=c.getResponseCode();
            if(code<200 || code>=300) throw new Exception("HTTP "+code);
            try(InputStream input=c.getInputStream()) {
                ByteArrayOutputStream output=new ByteArrayOutputStream();
                byte[] buf=new byte[4096]; int n;
                while((n=input.read(buf))!=-1) {
                    output.write(buf,0,n);
                    if(output.size()>48000) throw new Exception("Réponse trop longue");
                }
                return new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));
            }
        } finally { if(c!=null)c.disconnect(); }
    }
}
