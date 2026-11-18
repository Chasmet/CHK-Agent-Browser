package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class EmulatorMcp {
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final ExecutorService TRANSFERS = Executors.newSingleThreadExecutor();
    private static final Map<String, Transfer> transfers = new LinkedHashMap<>();

    private EmulatorMcp() { }

    static void execute(Context context, JSONObject args, AgentClient.ResultCallback callback) {
        if (!callback.isLocal3()) {
            callback.finish(false, "L’émulateur utilise le connecteur CHK Browser Local 3.");
            return;
        }
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                if (!callback.isActive()) return;
                JSONObject result = command(app, args);
                WorkspaceCommands.UI.post(() -> { if (callback.isActive()) callback.finish(true, result.toString()); });
            } catch (Exception error) {
                String message = error.getMessage() == null ? "Émulateur Android indisponible." : error.getMessage();
                WorkspaceCommands.UI.post(() -> { if (callback.isActive()) callback.finish(false, message); });
            }
        });
    }

    private static JSONObject command(Context app, JSONObject args) throws Exception {
        String operation = args.optString("operation", "status");
        if ("status".equals(operation)) {
            JSONObject result = new JSONObject().put("source", "android_emulator").put("authorized", EmulatorConnection.agentAllowed())
                    .put("preview_allowed", AgentClient.get(app).isPreviewAllowed());
            if (!EmulatorConnection.agentAllowed()) return result.put("execution", "on_device").put("ready", false);
            return EmulatorConnection.load(app).status().put("source", "android_emulator")
                    .put("authorized", true).put("preview_allowed", AgentClient.get(app).isPreviewAllowed());
        }
        if ("stop".equals(operation)) {
            EmulatorConnection.stopAgent();
            return new JSONObject().put("authorized", false);
        }
        requireAccess();
        if ("diagnostics".equals(operation)) return EmulatorConnection.load(app).diagnostics(app);
        if ("media_status".equals(operation)) return transferStatus(args.getString("id"));
        EmulatorConnection connection = EmulatorConnection.load(app);
        switch (operation) {
            case "observe":
                if (!AgentClient.get(app).isPreviewAllowed()) throw new IOException("Active le partage d’aperçus dans Réglages CHK.");
                return screenshot(connection);
            case "tap":
            case "swipe":
            case "key":
            case "text":
                requireAccess();
                return connection.input(args);
            case "send_media":
                return queueTransfer(app, connection, args.getString("id"), args.getString("workspace_path"));
            default:
                throw new IOException("Opération de l’émulateur inconnue.");
        }
    }

    private static void requireAccess() throws IOException {
        if (!EmulatorConnection.agentAllowed()) throw new IOException("Autorise Local 3 depuis le menu Navigateur Android.");
    }

    private static JSONObject screenshot(EmulatorConnection connection) throws Exception {
        EmulatorConnection.Frame frame = connection.screen();
        Bitmap original = frame.bitmap;
        float scale = Math.min(1f, 800f / Math.max(original.getWidth(), original.getHeight()));
        Bitmap preview = Bitmap.createScaledBitmap(original, Math.max(1, Math.round(original.getWidth() * scale)),
                Math.max(1, Math.round(original.getHeight() * scale)), true);
        try {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            preview.compress(Bitmap.CompressFormat.JPEG, 62, data);
            if (data.size() > 40_000) {
                data.reset();
                preview.compress(Bitmap.CompressFormat.JPEG, 35, data);
            }
            if (data.size() > 42_000) throw new IOException("Aperçu Android trop volumineux.");
            requireAccess();
            return new JSONObject().put("source", "android_emulator").put("frame_id", frame.id)
                    .put("width", original.getWidth()).put("height", original.getHeight())
                    .put("preview_width", preview.getWidth()).put("preview_height", preview.getHeight())
                    .put("mime_type", "image/jpeg").put("image_base64", Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP));
        } finally {
            if (preview != original) preview.recycle();
            original.recycle();
        }
    }

    private static synchronized JSONObject queueTransfer(Context context, EmulatorConnection connection, String id, String path) throws Exception {
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw new IOException("Identifiant de transfert Android invalide.");
        Transfer previous = transfers.get(id);
        if (previous != null) {
            if (!previous.path.equals(path)) throw new IOException("Identifiant déjà utilisé pour un autre fichier.");
            return previous.result;
        }
        File source = new WorkspaceStore(context).file(path);
        if (!source.isFile() || !source.canRead()) throw new IOException("Média CHK inaccessible.");
        for (Transfer transfer : transfers.values()) {
            if ("queued".equals(transfer.result.optString("state"))) throw new IOException("Un transfert Android est déjà en cours.");
        }
        if (transfers.size() >= 24) transfers.remove(transfers.keySet().iterator().next());
        Transfer transfer = new Transfer(path, new JSONObject().put("id", id).put("state", "queued"));
        transfers.put(id, transfer);
        TRANSFERS.execute(() -> {
            JSONObject outcome = new JSONObject();
            try {
                requireAccess();
                try (FilterInputStream stream = new FilterInputStream(new FileInputStream(source)) {
                    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                        requireAccess();
                        return super.read(buffer, offset, length);
                    }
                }) {
                    outcome = connection.sendMedia(stream, source.getName(), source.length());
                }
                outcome.put("id", id).put("state", outcome.optBoolean("verified") ? "staged" : "failed");
            } catch (Exception e) {
                try { outcome.put("id", id).put("state", "failed").put("error", e.getMessage()); }
                catch (org.json.JSONException ignored) { }
            }
            transfer.result = outcome;
        });
        return transfer.result;
    }

    private static synchronized JSONObject transferStatus(String id) throws Exception {
        Transfer transfer = transfers.get(id);
        return transfer == null ? new JSONObject().put("id", id).put("state", "unknown") : transfer.result;
    }

    private static final class Transfer {
        final String path;
        volatile JSONObject result;

        Transfer(String path, JSONObject result) {
            this.path = path;
            this.result = result;
        }
    }
}
