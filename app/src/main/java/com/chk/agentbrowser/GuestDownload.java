package com.chk.agentbrowser;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;

import java.io.File;
import java.io.IOException;

/** DownloadManager owns the image download so it can resume when CHK is closed. */
final class GuestDownload {
    private static final String NAME = "chk-android-lineage-23.2-arm64.zip";
    private final Context app;
    private final SharedPreferences preferences;
    private final DownloadManager manager;

    GuestDownload(Context context) {
        app = context.getApplicationContext();
        preferences = app.getSharedPreferences("android_guest_download", Context.MODE_PRIVATE);
        manager = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    private long id() {
        return preferences.getLong("id", -1);
    }

    void start() throws IOException {
        if (id() >= 0) return;
        if (manager == null) throw new IOException("Gestionnaire de téléchargements Android indisponible.");
        File root = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (root == null || new StatFs(root.getPath()).getAvailableBytes() < 6L * 1024 * 1024 * 1024) {
            throw new IOException("Libère au moins 6 Gio pour télécharger et installer l’image Android.");
        }
        File stale = new File(root, NAME);
        if (stale.exists() && !stale.delete()) throw new IOException("Impossible de remplacer l’ancien téléchargement Android.");
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(GuestImage.URL))
                .setTitle("CHK Browser · image Android")
                .setDescription("Environnement Android intégré à CHK Browser")
                .setMimeType("application/zip")
                .setAllowedOverMetered(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, NAME);
        long added = manager.enqueue(request);
        preferences.edit().putLong("id", added).apply();
    }

    Status status() {
        long current = id();
        if (current < 0) return null;
        if (manager == null) return new Status(DownloadManager.STATUS_FAILED, 0, 0, null);
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(current))) {
            if (cursor == null || !cursor.moveToFirst()) {
                return new Status(DownloadManager.STATUS_FAILED, 0, 0, null);
            }
            int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long received = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            Uri uri = state == DownloadManager.STATUS_SUCCESSFUL ? manager.getUriForDownloadedFile(current) : null;
            return new Status(state, received, total, uri);
        } catch (RuntimeException error) {
            return new Status(DownloadManager.STATUS_FAILED, 0, 0, null);
        }
    }

    void clear() {
        long current = id();
        preferences.edit().remove("id").apply();
        if (manager != null && current >= 0) {
            try { manager.remove(current); }
            catch (RuntimeException ignored) { }
        }
    }

    static final class Status {
        final int state;
        final long received, total;
        final Uri uri;

        Status(int state, long received, long total, Uri uri) {
            this.state = state;
            this.received = received;
            this.total = total;
            this.uri = uri;
        }
    }
}
