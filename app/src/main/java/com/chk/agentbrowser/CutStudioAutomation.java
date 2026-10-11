package com.chk.agentbrowser;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Decoupage a la demande dans CHK Agent Browser, jamais dans Cut Video externe.
 * Point d'entree MCP existant : files_write_text(path=CutVideo/cut_inbox.json).
 * Les MP4 restent sur le telephone. L'accuse de reception signifie "en cours",
 * pas "export termine". Lire CutVideo/cut_status.json pour la preuve.
 */
@OptIn(markerClass = UnstableApi.class)
public final class CutStudioAutomation {
    public static final String COMMAND_PATH = "CutVideo/cut_inbox.json";
    public static final String STATUS_PATH = "CutVideo/cut_status.json";
    public static final String CANCEL_PATH = "CutVideo/cut_cancel.json";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CutStudioAutomation INSTANCE = new CutStudioAutomation();
    private static final String SOURCE_PREFS = "chk_cut_last_authorized_source";
    private static final String SOURCE_URI = "uri";
    private static final String SOURCE_NAME = "name";

    /** Autorise uniquement la derniere source effectivement choisie dans l'onglet Cut. */
    public static void rememberSelected(Context context, Uri uri, String name) {
        if (uri == null) return;
        context.getApplicationContext().getSharedPreferences(SOURCE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(SOURCE_URI, uri.toString())
                .putString(SOURCE_NAME, name == null ? "clip.mp4" : name).apply();
    }
    private Job current;
    private Transformer transformer;
    private File temp;
    private int completed;

    private CutStudioAutomation() {}

    public static JSONObject submit(Context context, String text) throws Exception {
        final JSONObject args = new JSONObject(text);
        final String id = args.optString("request_id", "").trim();
        if (!id.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("request_id obligatoire (1-80 lettres, chiffres, _ ou -).");
        String path = args.optString("source_path", args.optString("path", "")).trim();
        boolean lastSelected = "@last_selected".equals(path);
        if (!lastSelected && (path.isEmpty() || !path.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mov|mkv|webm|m4v)$")))
            throw new IllegalArgumentException("source_path : chemin Fichiers CHK ou @last_selected.");
        int seconds = args.optInt("seconds", 0);
        if (seconds < 1 || seconds > 600)
            throw new IllegalArgumentException("seconds doit etre compris entre 1 et 600.");

        Context app = context.getApplicationContext();
        WorkspaceStore store = new WorkspaceStore(app);
        File source = null;
        Uri sourceUri;
        String sourceName;
        if (lastSelected) {
            SharedPreferences selected = app.getSharedPreferences(SOURCE_PREFS, Context.MODE_PRIVATE);
            String rawUri = selected.getString(SOURCE_URI, "");
            sourceName = selected.getString(SOURCE_NAME, "clip.mp4");
            if (rawUri == null || rawUri.isEmpty())
                throw new IOException("Aucune video importee depuis l'onglet Cut.");
            sourceUri = Uri.parse(rawUri);
            if (!"content".equals(sourceUri.getScheme()) && !"file".equals(sourceUri.getScheme()))
                throw new IllegalArgumentException("Source non autorisee.");
            try (android.os.ParcelFileDescriptor descriptor =
                         app.getContentResolver().openFileDescriptor(sourceUri, "r")) {
                if (descriptor == null) throw new IOException("Video selectionnee inaccessible.");
            }
        } else {
            source = store.file(path);
            if (!source.isFile() || source.length() < 1024)
                throw new IOException("Video absente ou vide : " + path);
            if (source.getName().startsWith("temp_"))
                throw new IllegalArgumentException("Fichier temporaire refuse.");
            sourceUri = Uri.fromFile(source);
            sourceName = source.getName();
        }
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        long duration;
        try {
            reader.setDataSource(app, sourceUri);
            String raw = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            duration = Long.parseLong(raw == null ? "0" : raw);
        } finally { reader.release(); }
        if (duration < 1) throw new IOException("Duree video indisponible.");
        long start = args.has("start_ms") ? args.getLong("start_ms") : 0L;
        long end = args.has("end_ms") && !args.isNull("end_ms") ? args.getLong("end_ms") : duration;
        if (start < 0 || end <= start || end > duration + 50L)
            throw new IllegalArgumentException("Plage start_ms/end_ms invalide.");
        end = Math.min(end, duration);
        long countLong = (end - start + seconds * 1000L - 1L) / (seconds * 1000L);
        if (countLong < 1 || countLong > 100)
            throw new IllegalArgumentException("Limite de 100 morceaux par demande.");

        synchronized (INSTANCE) {
            if (INSTANCE.current != null) {
                if (id.equals(INSTANCE.current.id))
                    return INSTANCE.snapshot("running");
                throw new IllegalStateException("Un autre decoupage est en cours : "
                        + INSTANCE.current.id + ". Verifie cut_status.json.");
            }
            ensureFolder(store);
            JSONObject previous = readStatus(store);
            if ("running".equals(previous.optString("state"))) {
                previous.put("state", "interrupted");
                previous.put("error", "Application redemarree avant la confirmation de fin d'export.");
                store.writeText(STATUS_PATH, previous.toString(), true);
            }
            if (id.equals(previous.optString("request_id"))) {
                String state = previous.optString("state");
                if ("completed".equals(state) || "cancelled".equals(state))
                    return new JSONObject(previous.toString());
                if ("interrupted".equals(state))
                    throw new IllegalStateException("Export precedent interrompu : demande un nouveau request_id apres verification des fichiers.");
            }
            String folder = "lot_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + "_" + UUID.randomUUID().toString().substring(0, 8);
            String output = CutStudioStore.FOLDER + "/" + folder;
            store.mkdir(output);
            List<long[]> segments = new ArrayList<>();
            for (long t = start; t < end; t += seconds * 1000L)
                segments.add(new long[]{t, Math.min(end, t + seconds * 1000L)});
            Job job = new Job(app, store, id, path, output, sourceUri, sourceName, segments);
            INSTANCE.current = job;
            INSTANCE.completed = 0;
            INSTANCE.saveStatus(job, "running", "");
            MAIN.post(() -> INSTANCE.exportNext(job));
            return INSTANCE.snapshot("running");
        }
    }

    public static JSONObject cancel(Context context, String payload) throws Exception {
        JSONObject args = new JSONObject(payload);
        String id = args.optString("request_id", "");
        synchronized (INSTANCE) {
            if (INSTANCE.current == null)
                return new JSONObject().put("state", "idle").put("cancelled", false);
            if (!id.equals(INSTANCE.current.id))
                throw new IllegalArgumentException("request_id ne correspond pas a l'export actif.");
            final Job job = INSTANCE.current;
            MAIN.post(() -> INSTANCE.fail(job, "cancelled", "Annule sur demande."));
            return new JSONObject().put("request_id", id).put("state", "cancelling")
                    .put("output_folder", job.outputFolder);
        }
    }

    public static String statusText(Context context) {
        synchronized (INSTANCE) {
            try {
                if (INSTANCE.current != null)
                    return INSTANCE.snapshot("running").toString();
                WorkspaceStore store = new WorkspaceStore(context);
                return readStatus(store).toString();
            } catch (Exception e) { return "{}"; }
        }
    }

    private static void ensureFolder(WorkspaceStore store) throws Exception {
        File dir = store.file(CutStudioStore.FOLDER);
        if (!dir.exists()) store.mkdir(CutStudioStore.FOLDER);
        else if (!dir.isDirectory()) throw new IOException("CutVideo doit etre un dossier.");
    }

    private static JSONObject readStatus(WorkspaceStore store) {
        try {
            return new JSONObject(store.readText(STATUS_PATH, 0).getString("text"));
        } catch (Exception ignored) { return new JSONObject(); }
    }

    private JSONObject snapshot(String state) throws Exception {
        Job job = current;
        if (job == null) return new JSONObject().put("state", "idle");
        return new JSONObject().put("request_id", job.id).put("source_path", job.sourcePath)
                .put("output_folder", job.outputFolder).put("state", state)
                .put("completed", completed).put("total", job.segments.size())
                .put("source_deleted", false)
                .put("platform_publication_confirmed", false);
    }

    private void saveStatus(Job job, String state, String error) {
        try {
            JSONObject record = new JSONObject()
                    .put("request_id", job.id).put("source_path", job.sourcePath)
                    .put("output_folder", job.outputFolder).put("state", state)
                    .put("completed", completed).put("total", job.segments.size())
                    .put("updated_at_millis", System.currentTimeMillis())
                    .put("source_deleted", false)
                    .put("platform_publication_confirmed", false);
            if (!error.isEmpty()) record.put("error", error);
            job.store.writeText(STATUS_PATH, record.toString(), true);
        } catch (Exception e) {
            AgentLog.add(job.app, "cut", "Statut non ecrit : " + e.getMessage());
        }
    }

    private void exportNext(Job job) {
        synchronized (this) {
            if (current != job) return;
            if (completed >= job.segments.size()) {
                saveStatus(job, "completed", "");
                AgentLog.add(job.app, "cut", "Export termine : " + job.outputFolder);
                current = null;
                transformer = null;
                return;
            }
            long[] interval = job.segments.get(completed);
            String base = job.sourceName.replaceFirst("\\.[^.]+$", "")
                    .replaceAll("[^A-Za-z0-9_-]", "_");
            if (base.isEmpty()) base = "cut";
            if (base.length() > 35) base = base.substring(0, 35);
            String name = String.format(Locale.US, "%s_%02d.mp4", base, completed + 1);
            File destination = new File(job.output, name);
            File temporary = new File(job.output, "temp_" + UUID.randomUUID() + ".mp4");
            temp = temporary;
            try {
                MediaItem item = new MediaItem.Builder().setUri(job.sourceUri)
                        .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(interval[0])
                                .setEndPositionMs(interval[1]).build()).build();
                EditedMediaItem media = new EditedMediaItem.Builder(item).build();
                transformer = new Transformer.Builder(job.app)
                        .addListener(new Transformer.Listener() {
                            @Override public void onCompleted(Composition c, ExportResult result) {
                                synchronized (CutStudioAutomation.this) {
                                    if (current != job) { temporary.delete(); return; }
                                    transformer = null;
                                    if (destination.exists() || !temporary.renameTo(destination)) {
                                        temporary.delete();
                                        fail(job, "failed", "Echec enregistrement " + name);
                                        return;
                                    }
                                    temp = null;
                                    completed++;
                                    saveStatus(job, "running", "");
                                    exportNext(job);
                                }
                            }

                            @Override public void onError(Composition c, ExportResult result,
                                                          ExportException error) {
                                fail(job, "failed", "Erreur codec : " + error.getMessage());
                            }
                        }).build();
                transformer.start(media, temporary.getAbsolutePath());
            } catch (Exception error) {
                fail(job, "failed", error.toString());
            }
        }
    }

    private synchronized void fail(Job job, String state, String reason) {
        if (current != job) return;
        if (transformer != null) {
            transformer.cancel();
            transformer = null;
        }
        if (temp != null) { temp.delete(); temp = null; }
        saveStatus(job, state, reason);
        AgentLog.add(job.app, "cut", state + " : " + reason);
        current = null;
    }

    private static final class Job {
        final Context app;
        final WorkspaceStore store;
        final String id;
        final String sourcePath;
        final String outputFolder;
        final Uri sourceUri;
        final String sourceName;
        final File output;
        final List<long[]> segments;

        Job(Context app, WorkspaceStore store, String id, String sourcePath, String outputFolder,
            Uri sourceUri, String sourceName, List<long[]> segments) throws Exception {
            this.app = app;
            this.store = store;
            this.id = id;
            this.sourcePath = sourcePath;
            this.outputFolder = outputFolder;
            this.sourceUri = sourceUri;
            this.sourceName = sourceName;
            this.output = store.file(outputFolder);
            this.segments = segments;
        }
    }
}
