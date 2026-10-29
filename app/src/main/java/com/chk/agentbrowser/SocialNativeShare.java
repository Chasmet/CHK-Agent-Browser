package com.chk.agentbrowser;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Locale;

/**
 * Native bridge: share an EXISTING private CHK video with an installed social app.
 * Does not embed another APK, publish a post, schedule it or record remote success.
 * TikTok/YouTube/Instagram remain the authorities for actual publication.
 */
final class SocialNativeShare {
    private SocialNativeShare() {}

    static String targetPackage(String platform) {
        if ("tiktok".equals(platform)) return "com.zhiliaoapp.musically";
        if ("youtube".equals(platform)) return "com.google.android.youtube";
        if ("instagram".equals(platform)) return "com.instagram.android";
        return null;
    }

    static Intent buildShare(Context context, JSONObject job) throws Exception {
        String platform = job.optString("platform", "");
        if (targetPackage(platform) == null)
            throw new IllegalArgumentException("Application sociale non prise en charge.");
        if ("paid".equals(job.optString("promotion_mode", "organic")))
            throw new IllegalArgumentException("La promotion payante exige une validation distincte.");
        JSONArray media = job.optJSONArray("media");
        if (media == null || media.length() != 1)
            throw new IllegalArgumentException("Sélectionne une seule vidéo MP4 pour le partage direct.");
        JSONObject source = media.getJSONObject(0);
        String path = source.getString("path");
        if (!path.toLowerCase(Locale.ROOT).endsWith(".mp4"))
            throw new IllegalArgumentException("Le partage direct exige un MP4.");
        WorkspaceStore store = new WorkspaceStore(context);
        File f = store.file(path);
        if (!f.isFile() || f.length() < 1 || f.length() != source.optLong("size", -1))
            throw new IllegalArgumentException("Le fichier a disparu ou changé : "+path);
        if (source.has("updated") && source.optLong("updated") != f.lastModified())
            throw new IllegalArgumentException("Le MP4 a changé depuis la préparation : refaire la fiche.");
        if (!"video/mp4".equals(store.mime(path)))
            throw new IllegalArgumentException("Type de média incorrect.");
        Uri uri = store.uri(path);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("video/mp4");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_SUBJECT, job.optString("title", ""));
        intent.putExtra(Intent.EXTRA_TEXT, job.optString("caption", ""));
        intent.setClipData(ClipData.newUri(context.getContentResolver(), "CHK MP4", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    /**
     * Handoff to the installed official social app. Android accepting an Intent
     * NEVER means upload, publication or schedule was accepted by that service.
     * Never change the prepared publication's journal status here.
     */
    static JSONObject handoff(Activity activity, JSONObject job) throws Exception {
        if (!"prepared".equals(job.optString("status")))
            throw new IllegalArgumentException("Fiche non préparée : partage direct refusé pour éviter un doublon.");
        Intent video = buildShare(activity, job);
        String caption = job.optString("caption", "");
        if (!caption.isEmpty()) {
            ClipboardManager clipboard=(ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null)
                clipboard.setPrimaryClip(ClipData.newPlainText("CHK description", caption));
        }
        boolean official = true;
        try {
            activity.startActivity(new Intent(video).setPackage(targetPackage(job.optString("platform"))));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            official = false;
            activity.startActivity(Intent.createChooser(video,"Transmettre la vidéo CHK"));
        }
        return new JSONObject()
            .put("ok",true)
            .put("state", official ? "official_app_opened" : "android_share_chooser_opened")
            .put("platform",job.optString("platform"))
            .put("publication_id",job.optString("id"))
            .put("schedule_requested",job.optString("schedule_at"))
            .put("remote_verified",false)
            .put("upload_confirmed",false)
            .put("schedule_confirmed",false)
            .put("message","Le MP4 a ete remis a Android. Verifier le compte, l'import et la date dans l'application sociale.");
    }

    static void launch(Activity activity, JSONObject job) {
        try {
            JSONObject result = handoff(activity,job);
            Toast.makeText(activity,
                "Application ouverte : programmation NON confirmée. Vérifier dans le réseau social.",
                Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(activity, "Transmission impossible : "+error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
