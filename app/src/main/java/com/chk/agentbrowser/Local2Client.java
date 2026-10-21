package com.chk.agentbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Independent Local 2 transport. Never contacts Supabase or Render. */
public final class Local2Client {
    private static final String PREF = "chk_local2_v1";
    private static Local2Client instance;
    private final Context app;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile int generation;
    private volatile long lastPoll;
    private volatile String serverVersion = "non vérifié", serverId = "non disponible";
    private volatile String commandState = "aucune commande reçue", route = "standard";
    private volatile String executionState = "aucune", displayState = "non vérifié";
    private String verifiedAddress = "", bridgeAddress = "";
    private final java.util.concurrent.atomic.AtomicBoolean commandInFlight = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile String status = "Non connecté";
    private final Runnable loop = this::poll;

    private Local2Client(Context context) {
        app = context.getApplicationContext();
        preferences = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
    public static synchronized Local2Client get(Context c) {
        if (instance == null) instance = new Local2Client(c);
        return instance;
    }
    public boolean enabled() { return preferences.getBoolean("enabled", false); }
    int sessionGeneration() { return generation; }
    public String url() { return preferences.getString("url", ""); }
    public String status() {
        if (lastPoll > 0 && android.os.SystemClock.elapsedRealtime() - lastPoll > 45000L
            && status.startsWith("Connecté")) return "Connexion PC à revérifier";
        return status;
    }
    public String diagnostic() {
        String host = "non configuré";
        try { URL target = new URL(url()); host = target.getHost() + (target.getPort() < 0 ? "" : ":" + target.getPort()); }
        catch (Exception ignored) {}
        long age = lastPoll == 0 ? -1 : (android.os.SystemClock.elapsedRealtime() - lastPoll) / 1000;
        return "Local 2 : " + (enabled() ? "activé" : "désactivé") + " · " + status()
            + "\nServeur contacté : " + host + "\nVersion PC : " + serverVersion
            + "\nIdentité PC : " + serverId + "\nRoute Android : " + route
            + "\nDernier échange Android : " + (age < 0 ? "aucun" : age + " s")
            + "\nMode autonome : " + (AgentClient.get(app).isAutonomous() ? "actif" : "désactivé")
            + "\nNavigateur Local 2 : " + AgentClient.get(app).local2BrowserSummary()
            + "\nRéception / PC : " + commandState
            + "\nExécution Android : " + executionState + "\nAffichage : " + displayState;
    }
    /** Import the existing ChatGPT gateway address locally; never poll Supabase. */
    public static String resolveMcpAddress(String entered) {
        String value = entered == null ? "" : entered.trim();
        if (value.isEmpty()) return "";
        try {
            URL parsed = new URL(value);
            String hostname = parsed.getHost().toLowerCase(java.util.Locale.ROOT);
            if (!"https".equals(parsed.getProtocol()) ||
                parsed.getAuthority().contains("@") || parsed.getQuery() != null ||
                parsed.getRef() != null)
                throw new IllegalArgumentException("Adresse MCP HTTPS invalide.");
            String path = parsed.getPath();
            if ("gflnvlolwqnvzxyqsrir.supabase.co".equals(hostname)
                && parsed.getPort() == -1
                && path.matches("/functions/v1/chk-home-mcp-gateway/mcp/[a-f0-9]{64}/?")) {
                // The gateway's Windows upstream is a fixed, public hostname.
                // The capability stays only in app-private preferences.
                String capability = path.replaceFirst("^.*/mcp/", "").replace("/", "");
                return "https://pc-salon.taild9e4de.ts.net:8443/mcp/" + capability;
            }
            if (hostname.endsWith(".ts.net") &&
                path.matches("/mcp/[a-f0-9]{64}/?") &&
                (parsed.getPort() == 8443 || parsed.getPort() == -1))
                return value;
            throw new IllegalArgumentException("Utilise le lien MCP ChatGPT Supabase ou le lien privé du PC.");
        } catch (java.net.MalformedURLException invalid) {
            throw new IllegalArgumentException("Adresse HTTPS MCP invalide.");
        }
    }
    /** URL is the private HTTPS MCP URL configured on the Windows host. */
    public void configure(String url, boolean on) {
        String value = url == null ? "" : url.trim();
        value = resolveMcpAddress(value);
        boolean changed = !value.equals(url());
        if (changed) stop();
        preferences.edit().putString("url", value).putBoolean("enabled", on).apply();
        if (changed) { lastPoll = 0; serverId = "non disponible"; serverVersion = "non vérifié"; }
        if (!on) stop();
        else if (value.isEmpty()) status = "Activé · URL PC à configurer (pas Supabase)";
        else if (!running) status = "Activé · vérification du canal Android";
    }
    public void start() {
        if (!enabled() || url().isEmpty() || running) return;
        running = true;
        main.post(loop);
    }
    public void stop() {
        running = false;
        generation++;
        commandInFlight.set(false);
        main.removeCallbacks(loop);
        status = "Désactivé";
    }
    private JSONObject request(String address, String method, JSONObject parameters) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(address).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(25000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            JSONObject body = new JSONObject().put("jsonrpc", "2.0")
                .put("id", System.nanoTime()).put("method", method).put("params", parameters);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            if (connection.getResponseCode() != 200)
                throw new java.io.IOException("PC HTTP " + connection.getResponseCode());
            try (InputStream in = connection.getInputStream()) {
                java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int count;
                while ((count = in.read(buf)) != -1) {
                    result.write(buf, 0, count);
                    if (result.size() > 65536) throw new java.io.IOException("Réponse trop grande");
                }
                return new JSONObject(new String(result.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }
    private void verifyServer(String address) throws Exception {
        JSONObject initialized = request(address, "initialize", new JSONObject()
            .put("protocolVersion", "2025-06-18").put("capabilities", new JSONObject())
            .put("clientInfo", new JSONObject().put("name", "CHK Android Local 2").put("version", "1.1")));
        Local2Protocol.checkError(initialized);
        JSONObject result = initialized.optJSONObject("result");
        JSONObject server = result == null ? null : result.optJSONObject("serverInfo");
        if (server == null || !"CHK Home MCP".equals(server.optString("name")))
            throw new java.io.IOException("Serveur CHK non reconnu");
        serverVersion = server.optString("version", "inconnue");
        bridgeAddress = address;
        route = "standard";
        verifiedAddress = address;
    }
    private JSONObject nextCommand(String address) throws Exception {
        JSONObject reply = request(bridgeAddress, "android/next", new JSONObject());
        try { Local2Protocol.checkError(reply); }
        catch (Local2Protocol.RpcFailure failure) {
            if (failure.code != -32601 || !bridgeAddress.equals(address)) throw failure;
            // Retry only an explicit unknown-method response on the recognized
            // server. No commands have been delivered or executed by this poll.
            String compatible = Local2Protocol.proxyCompatibilityAddress(address);
            reply = request(compatible, "android/next", new JSONObject());
            Local2Protocol.command(reply);
            bridgeAddress = compatible;
            route = "compatibilité Funnel 1.3.0";
        }
        Local2Protocol.command(reply);
        String identity = reply.optString("server_id");
        if (!identity.isEmpty() && !identity.equals(Local2Protocol.fingerprint(address)))
            throw new java.io.IOException("Identité du serveur différente");
        serverId = identity.isEmpty() ? "non fournie par le PC " + serverVersion : identity;
        return reply;
    }
    private void poll() {
        if (!running || !enabled()) return;
        if (commandInFlight.get()) {
            main.postDelayed(loop, 1200L);
            return;
        }
        final int session = generation;
        final String address = url();
        worker.execute(() -> {
            long delay = 2000L;
            try {
                if (session != generation || !running || !enabled()) return;
                if (!address.equals(verifiedAddress)) verifyServer(address);
                JSONObject command = Local2Protocol.command(nextCommand(address));
                if (session != generation || !running || !enabled()) return;
                lastPoll = android.os.SystemClock.elapsedRealtime();
                status = "Connecté au PC · canal Android vérifié";
                if (command != null) {
                    commandInFlight.set(true);
                    final String ident = command.getString("id");
                    final String action = command.getString("action");
                    final String replyAddress = bridgeAddress;
                    commandState = "commande reçue · exécution en cours";
                    executionState = "en cours";
                    displayState = "à vérifier";
                    final JSONObject args = command.optJSONObject("args") == null ?
                        new JSONObject() : command.getJSONObject("args");
                    main.post(() -> {
                        if (session != generation || !running || !enabled()) {
                            if(session==generation)commandInFlight.set(false); return;
                        }
                        AgentClient.get(app).executeLocal2(action, args, (ok, message) ->
                        worker.execute(() -> {
                            if(session==generation){
                                executionState = ok ? "réussie" : "échec · " + safeFailure(message);
                                displayState = "action sans preuve d’affichage";
                                try{
                                    JSONObject proof=new JSONObject(message);
                                    if(proof.has("visible"))displayState=proof.optBoolean("visible")
                                        ? "confirmé dans CHK" : "CHK en arrière-plan · page prête à son retour";
                                }catch(Exception ignored){}
                            }
                            try {
                                String transportMessage;boolean reportedSuccess=ok;
                                try{transportMessage=Local2Results.prepare(app,message);}
                                catch(Exception packaging){
                                    reportedSuccess=false;
                                    transportMessage="Action exécutée mais résultat non transportable : vérifier son état avant de relancer. Erreur : "+packaging.getClass().getSimpleName();
                                }
                                JSONObject params = new JSONObject().put("id", ident)
                                    .put("success", reportedSuccess).put("message", transportMessage);
                                Local2Protocol.completion(request(replyAddress, "android/complete", params));
                                if (session == generation) commandState = reportedSuccess ? "résultat reçu par le PC" : "échec transmis au PC";
                            } catch (Exception failure) {
                                if (session == generation) {
                                    commandState = "résultat non acquitté par le PC";
                                    status = "Résultat non transmis : " + failure.getClass().getSimpleName();
                                }
                            } finally {
                                if(session==generation){
                                    commandInFlight.set(false);
                                    main.post(()->{if(session==generation&&running&&enabled()){main.removeCallbacks(loop);main.post(loop);}});
                                }
                            }
                        }));
                    });
                }
            } catch (Exception error) {
                if (session != generation) return;
                verifiedAddress = "";
                status = error instanceof Local2Protocol.RpcFailure
                    ? error.getMessage() + " · canal Android non connecté"
                    : "Canal Android non connecté : " + error.getClass().getSimpleName();
                delay = 6000L;
            }
            final long next = delay;
            main.post(() -> { if (session == generation && running && enabled()) main.postDelayed(loop, next); });
        });
    }
    private static String safeFailure(String message){
        if(message==null)return "résultat non confirmé";
        // Diagnostics never copy private URLs, file contents or account details.
        if(message.startsWith("Mode autonome"))return "mode autonome désactivé";
        if(message.startsWith("Local 2 désactivé"))return "Local 2 désactivé";
        if(message.startsWith("Navigateur Android fermé"))return "navigateur Android fermé";
        if(message.startsWith("Page inaccessible"))return "page inaccessible";
        if(message.startsWith("Chargement non confirmé"))return "chargement non confirmé";
        return "consulter le résultat de commande";
    }
}
