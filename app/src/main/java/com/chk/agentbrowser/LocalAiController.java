package com.chk.agentbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in local helper for the remote ChatGPT agent. It only proposes text:
 * it cannot execute Android actions, grant permissions or visit a website.
 * GGUF is stored in no-backup private storage; model + context are released
 * at the end of each native inference (no resident model in idle state).
 */
public final class LocalAiController {
    public static final String MODEL_NAME = "SmolLM2-135M-Instruct · Q4_K_M";
    // Pin both the upstream revision and the exact binary checksum.
    private static final String MODEL_URL =
        "https://huggingface.co/QuantFactory/SmolLM2-135M-Instruct-GGUF/resolve/"
        + "476854d00ede130660aba430d15f9347ad2e7d0e/SmolLM2-135M-Instruct.Q4_K_M.gguf";
    private static final String MODEL_SHA256 =
        "8030f04528538d47bda434f6f0bdf3952c40a58123e4d5e755332f23731a8684";
    private static final long MAX_BYTES = 150L * 1024L * 1024L;
    private static final String PREF = "chk_local_ai";
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static volatile LocalAiController instance;
    private static volatile Boolean nativeReady;
    private final Context app;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chk-local-ai"); t.setPriority(Thread.MIN_PRIORITY + 1); return t;
    });
    private final AtomicBoolean downloading = new AtomicBoolean(false);
    private final AtomicBoolean inferring = new AtomicBoolean(false);
    private volatile long downloadedBytes;
    private volatile String lastError = "";

    public interface Done { void finished(boolean ok, String message); }
    private LocalAiController(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
    public static LocalAiController get(Context context) {
        if (instance == null) synchronized (LocalAiController.class) {
            if (instance == null) instance = new LocalAiController(context);
        }
        return instance;
    }
    public boolean enabled() { return prefs.getBoolean("enabled", false); }
    /** Only the Settings UI opts the user in; an MCP command cannot enable itself. */
    public void setEnabled(boolean enabled) { prefs.edit().putBoolean("enabled", enabled).apply(); }
    public boolean busy() { return inferring.get() || downloading.get(); }
    public File modelFile() {
        return new File(new File(app.getNoBackupFilesDir(), "local-ai"),
            "SmolLM2-135M-Instruct.Q4_K_M.gguf");
    }
    public static boolean nativeAvailable() {
        Boolean cached = nativeReady;
        if (cached != null) return cached;
        synchronized (LocalAiController.class) {
            if (nativeReady == null) {
                try { System.loadLibrary("chk_local_ai"); nativeReady = true; }
                catch (LinkageError error) { nativeReady = false; }
            }
            return nativeReady;
        }
    }
    public JSONObject status() {
        File f = modelFile();
        JSONObject info = new JSONObject();
        try {
            info.put("model", MODEL_NAME).put("engine", "llama.cpp / CPU / GGUF")
                .put("enabled", enabled()).put("model_installed", f.isFile() && f.length() > 90000000L)
                .put("model_bytes", f.isFile() ? f.length() : 0L)
                .put("native_available", nativeAvailable())
                .put("download_in_progress", downloading.get())
                .put("downloaded_bytes", downloadedBytes)
                .put("inference_in_progress", inferring.get())
                .put("max_context_tokens", 1024)
                .put("max_generated_tokens", 160)
                .put("cpu_threads", 2)
                .put("resident_when_idle", false)
                .put("training", false)
                .put("model_network_calls_during_inference", false)
                .put("can_execute_actions", false)
                .put("playbook_version", LocalAiPlaybook.VERSION)
                .put("supported_commands", "status, plan, check, quality, ask, install, delete, disable")
                .put("weights_edited", false)
                .put("last_error", lastError);
        } catch (Exception ignored) { }
        return info;
    }
    public String statusText() {
        JSONObject s = status();
        return MODEL_NAME + "\n"
            + (enabled() ? "Activée" : "Désactivée") + " · "
            + (s.optBoolean("native_available") ? "moteur disponible" : "moteur natif absent")
            + "\nModèle : " + (s.optBoolean("model_installed") ? "installé" : "non installé")
            + (downloading.get() ? " · téléchargement " + (downloadedBytes / 1048576) + " Mo" : "")
            + (inferring.get() ? " · génération en cours" : "")
            + (lastError.isEmpty() ? "" : "\nDernière erreur : " + lastError);
    }
    public void command(JSONObject p, AgentClient.ResultCallback callback) {
        String op = p.optString("operation", "status");
        if ("status".equals(op)) {
            callback.finish(true, status().toString());
            return;
        }
        if ("disable".equals(op)) {
            setEnabled(false);
            callback.finish(true, status().toString());
            return;
        }
        if (!enabled()) {
            callback.finish(false, "IA locale désactivée. Activer explicitement dans Réglages CHK.");
            return;
        }
        if ("install".equals(op)) {
            if (!nativeAvailable()) {
                callback.finish(false, "Bibliothèque llama.cpp absente pour cet appareil.");
                return;
            }
            boolean accepted = install((ok, msg) -> AgentLog.add(app, "local-ai",
                ok ? "Modèle local prêt" : "Installation du modèle échouée"));
            callback.finish(accepted, accepted
                ? "Téléchargement lancé en tâche de fond. Suivre avec local_ai/status ; environ 105 Mo."
                : "Téléchargement déjà en cours, modèle installé ou génération active.");
            return;
        }
        if ("delete".equals(op)) {
            boolean ok = !busy() && (!modelFile().exists() || modelFile().delete());
            callback.finish(ok, ok ? "Modèle local supprimé." : "Modèle occupé ou suppression impossible.");
            return;
        }
        if ("plan".equals(op)) {
            callback.finish(true, LocalAiPlaybook.plan(p.optString("workflow", ""),
                p.optString("prompt", "")).toString());
            return;
        }
        if ("check".equals(op)) {
            callback.finish(true, LocalAiPlaybook.verifyReport(p).toString());
            return;
        }
        if ("quality".equals(op)) {
            callback.finish(true, LocalAiPlaybook.quality(
                p.optString("prompt", ""), p.optString("answer", "")).toString());
            return;
        }
        if (!"ask".equals(op)) {
            callback.finish(false, "Opération inconnue. Utiliser status, plan, check, quality, ask, install, delete ou disable.");
            return;
        }
        String prompt = p.optString("prompt", "").trim();
        if (prompt.isEmpty() || prompt.length() > 2800) {
            callback.finish(false, "Prompt requis, 1 à 2800 caractères.");
            return;
        }
        int tokens = Math.max(16, Math.min(160, p.optInt("max_tokens", 96)));
        if (!nativeAvailable() || !modelFile().isFile()) {
            callback.finish(false, "Moteur ou modèle absent ; installer depuis Réglages CHK.");
            return;
        }
        if (downloading.get() || !inferring.compareAndSet(false, true)) {
            callback.finish(false, "IA occupée ; attendre le résultat précédent.");
            return;
        }
        worker.execute(() -> {
            boolean ok = false;
            String answer;
            try {
                if (!enabled()) throw new IllegalStateException("IA locale désactivée.");
                String instruction = "<|im_start|>system\n"
                    + "You are a tiny offline text assistant. Answer the question; do not repeat it. "
                    + "Be direct. If unsure say 'Je ne sais pas'. Never claim actions occurred. "
                    + "No website access. Do not invent dates, selectors, files or results. "
                    + "Maximum three short sentences.\n<|im_end|>\n"
                    + "<|im_start|>user\nCombien font 2 + 3 ?<|im_end|>\n"
                    + "<|im_start|>assistant\n5<|im_end|>\n"
                    + "<|im_start|>user\n" + prompt + "\n<|im_end|>\n"
                    + "<|im_start|>assistant\n";
                answer = nativeAsk(modelFile().getAbsolutePath(), instruction, tokens, 2);
                ok = answer != null && !answer.trim().isEmpty();
                if (!ok) answer = "Le modèle n'a pas produit de réponse.";
                lastError = "";
            } catch (Exception | LinkageError e) {
                answer = e.getMessage() == null ? "Erreur du moteur IA local." : e.getMessage();
                lastError = answer;
            } finally {
                inferring.set(false);
            }
            final boolean success = ok && enabled();
            final String result = answer;
            UI.post(() -> {
                if (callback.isActive()) callback.finish(success,
                    success ? new JSONObjectBuilder(prompt, result).toString()
                        : "IA locale : " + result);
            });
        });
    }
    private static final class JSONObjectBuilder {
        private final String question, answer;
        JSONObjectBuilder(String question, String answer) {
            this.question=question; this.answer=answer;
        }
        @Override public String toString() {
            try {
                JSONObject quality=LocalAiPlaybook.quality(question, answer);
                String category=LocalAiPlaybook.category("",question);
                JSONObject value=new JSONObject()
                    .put("ok",true).put("source","local_model_unverified_text")
                    .put("model",MODEL_NAME).put("text",answer)
                    .put("quality",quality)
                    .put("actions_executed",false);
                // The deterministic fallback does not claim to be the model output.
                if (!quality.optBoolean("passed",false))
                    value.put("fallback_plan",LocalAiPlaybook.plan(category,question));
                return value.toString();
            } catch(Exception error) {
                return "{\"ok\":false,\"actions_executed\":false}";
            }
        }
    }
    /** Explicit one-time download, streaming + SHA-256 verification; no data uploaded. */
    public boolean install(Done callback) {
        if (!enabled() || busy() || modelFile().isFile()) return false;
        if (!downloading.compareAndSet(false, true)) return false;
        worker.execute(() -> {
            File target = modelFile();
            File temporary = new File(target.getParentFile(), target.getName() + ".part");
            boolean ok = false;
            String message = "Modèle installé.";
            HttpURLConnection connection = null;
            try {
                if (!target.getParentFile().exists() && !target.getParentFile().mkdirs())
                    throw new IllegalStateException("Dossier privé inaccessible.");
                connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(45000);
                connection.setInstanceFollowRedirects(true);
                if (connection.getResponseCode() != 200)
                    throw new IllegalStateException("Téléchargement HTTP " + connection.getResponseCode());
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                downloadedBytes = 0L;
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[64 * 1024]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (!enabled()) throw new IllegalStateException("Téléchargement annulé : IA désactivée.");
                        downloadedBytes += n;
                        if (downloadedBytes > MAX_BYTES)
                            throw new IllegalStateException("Fichier modèle trop volumineux.");
                        output.write(buffer, 0, n);
                        digest.update(buffer, 0, n);
                    }
                    output.getFD().sync();
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : digest.digest())
                    hex.append(String.format(Locale.ROOT, "%02x", b & 255));
                if (!MODEL_SHA256.equals(hex.toString()))
                    throw new SecurityException("Empreinte SHA-256 incorrecte.");
                try (FileInputStream in = new FileInputStream(temporary)) {
                    if (in.read() != 'G' || in.read() != 'G' || in.read() != 'U' || in.read() != 'F')
                        throw new SecurityException("Format GGUF invalide.");
                }
                if (!temporary.renameTo(target))
                    throw new IllegalStateException("Impossible d'enregistrer le modèle.");
                ok = true;
                lastError = "";
            } catch (Exception error) {
                message = error.getMessage() == null ? "Téléchargement impossible." : error.getMessage();
                lastError = message;
            } finally {
                if (connection != null) connection.disconnect();
                if (!ok) temporary.delete();
                downloading.set(false);
            }
            if (callback != null) {
                final boolean success = ok; final String result = message;
                UI.post(() -> callback.finished(success, result));
            }
        });
        return true;
    }
    private static native String nativeAsk(String modelPath, String formattedPrompt,
                                           int maxTokens, int threads);
}
