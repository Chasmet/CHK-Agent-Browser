package com.chk.agentbrowser;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.util.Locale;

/**
 * User-triggered publication handoff: native Android video share plus caption clipboard.
 * Does NOT publish, edit schedule status, store account tokens or access a social API.
 */
final class CutPublicationShare {
    private CutPublicationShare() {}

    static String targetPackage(String platform) {
        if("youtube".equals(platform))return "com.google.android.youtube";
        if("tiktok".equals(platform))return "com.zhiliaoapp.musically";
        if("instagram".equals(platform))return "com.instagram.android";
        if("x".equals(platform))return "com.twitter.android";
        return null;
    }

    static Intent buildIntent(Context context, WorkspaceStore files, JSONObject entry) throws Exception {
        String path=entry.optString("path","");
        if(!path.startsWith(CutStudioStore.FOLDER+"/")||
                !path.toLowerCase(Locale.ROOT).endsWith(".mp4"))
            throw new IllegalArgumentException("Chemin MP4 Cut incorrect");
        File target=files.file(path);
        if(!target.isFile())throw new IllegalArgumentException("MP4 introuvable : "+path);
        Uri uri=files.uri(path);
        Intent intent=new Intent(Intent.ACTION_SEND).setType("video/mp4");
        intent.putExtra(Intent.EXTRA_STREAM,uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(context.getContentResolver(),"Vidéo Cut",uri));
        String title=entry.optString("title","").trim();
        if(!title.isEmpty())intent.putExtra(Intent.EXTRA_SUBJECT,title);
        String caption=CutStudioStore.text(entry);
        if(!caption.isEmpty())intent.putExtra(Intent.EXTRA_TEXT,caption);
        return intent;
    }

    static void publish(Activity activity, WorkspaceStore files, JSONObject entry) {
        NativeSocialSession.offer(activity,files,entry);
    }

    /** Existing Android share unchanged, available as the owner-selected fallback. */
    static void publishClassic(Activity activity, WorkspaceStore files, JSONObject entry) {
        final Intent intent;
        try {
            intent=buildIntent(activity,files,entry);
        }catch(Exception error){
            Toast.makeText(activity,"Partage impossible : "+error.getMessage(),Toast.LENGTH_LONG).show();
            return;
        }
        String caption=CutStudioStore.text(entry);
        if(!caption.isEmpty()){
            ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard!=null)
                clipboard.setPrimaryClip(ClipData.newPlainText("Métadonnées Cut Vidéo",caption));
        }
        String platform=entry.optString("platform","");
        String preferred=targetPackage(platform);
        if(preferred!=null){
            try{
                activity.startActivity(new Intent(intent).setPackage(preferred));
                Toast.makeText(activity,
                    "MP4 transmis à l'application. Colle la légende, puis valide la publication.",
                    Toast.LENGTH_LONG).show();
                return;
            }catch(ActivityNotFoundException | SecurityException notAvailable){
                // The network app may not be installed or may reject ACTION_SEND video/mp4.
                // Fallback to Android chooser, never claim remote publication.
            }
        }
        try{
            activity.startActivity(Intent.createChooser(intent,
                "Partager la vidéo"+(platform.isEmpty()?"":" sur "+platform.toUpperCase(Locale.ROOT))));
            Toast.makeText(activity,
                "Vidéo prête à partager. Les métadonnées sont copiées.",
                Toast.LENGTH_LONG).show();
        }catch(ActivityNotFoundException | SecurityException error){
            Toast.makeText(activity,"Aucune application ne peut recevoir cette vidéo.",Toast.LENGTH_LONG).show();
        }
    }
}
