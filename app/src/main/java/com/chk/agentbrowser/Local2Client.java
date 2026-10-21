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
    private boolean running;
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
    public String url() { return preferences.getString("url", ""); }
    public String status() { return status; }
    /** URL is the private HTTPS MCP URL configured on the Windows host. */
    public void configure(String url, boolean on) {
        String value = url == null ? "" : url.trim();
        if (on && (!value.startsWith("https://") || !value.matches("https://[^ ]+/mcp/[a-f0-9]{64}/?")))
            throw new IllegalArgumentException("URL HTTPS MCP privée attendue.");
        preferences.edit().putString("url", value).putBoolean("enabled", on).apply();
        if (!on) stop();
    }
    public void start() {
        if (!enabled() || url().isEmpty() || running) return;
        running = true;
        main.post(loop);
    }
    public void stop() {
        running = false;
        main.removeCallbacks(loop);
        status = "Désactivé";
    }
    private JSONObject request(String method, JSONObject parameters) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(url()).openConnection();
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
    private void poll() {
        if (!running || !enabled()) return;
        worker.execute(() -> {
            long delay = 2000L;
            try {
                JSONObject command = request("android/next", new JSONObject()).optJSONObject("command");
                status = "Connecté au PC · sans Render/Supabase";
                if (command != null) {
                    final String ident = command.getString("id");
                    final String action = command.getString("action");
                    final JSONObject args = command.optJSONObject("args") == null ?
                        new JSONObject() : command.getJSONObject("args");
                    main.post(() -> AgentClient.get(app).executeLocal2(action, args, (ok, message) ->
                        worker.execute(() -> {
                            try {
                                JSONObject params = new JSONObject().put("id", ident)
                                    .put("success", ok).put("message", message);
                                request("android/complete", params);
                            } catch (Exception failure) {
                                status = "Résultat non transmis : " + failure.getClass().getSimpleName();
                            }
                        })));
                }
            } catch (Exception error) {
                status = "PC indisponible : " + error.getClass().getSimpleName();
                delay = 6000L;
            }
            final long next = delay;
            main.post(() -> { if (running && enabled()) main.postDelayed(loop, next); });
        });
    }
}
