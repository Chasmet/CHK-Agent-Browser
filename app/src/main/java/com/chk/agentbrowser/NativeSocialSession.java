package com.chk.agentbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.UUID;

/**
 * Explicit, short-lived handoff to the genuine Android social application.
 * This is NOT activity embedding. Accessibility permission is granted only
 * by the owner in system Settings; no automatic enrollment.
 */
final class NativeSocialSession {
    private static final String PREFS="chk_native_social_session_v1";
    private static final String RECORD="record";
    private static final long MAX_AGE_MS=20L*60L*1000L;
    private NativeSocialSession(){}

    static String packageFor(String network){
        return CutPublicationShare.targetPackage(network);
    }
    static boolean allowed(String pkg){
        return "com.zhiliaoapp.musically".equals(pkg) ||
            "com.instagram.android".equals(pkg) ||
            "com.google.android.youtube".equals(pkg) ||
            "com.twitter.android".equals(pkg);
    }
    static JSONObject current(Context context){
        try{
            String raw=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(RECORD,"");
            if(raw.isEmpty())return new JSONObject();
            JSONObject state=new JSONObject(raw);
            if(state.optLong("expires_at",0L)<=System.currentTimeMillis() ||
                    !allowed(state.optString("target_package","")))return new JSONObject();
            return state;
        }catch(Exception ignored){return new JSONObject();}
    }
    static boolean isActive(Context context,String packageName){
        return packageName!=null&&packageName.equals(current(context).optString("target_package",""));
    }
    static void stop(Context context){
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(RECORD).commit();
        try {
            new WorkspaceStore(context).writeText("CutVideo/native_handoff.json",
                new JSONObject().put("state","stopped").put("published",false)
                    .put("updated_at",System.currentTimeMillis()).toString(),true);
        }catch(Exception ignored){}
    }
    static void offer(Activity activity,WorkspaceStore workspace,JSONObject row){
        try{
            String network=row.optString("platform","");
            if(packageFor(network)==null){CutPublicationShare.publishClassic(activity,workspace,row);return;}
            if(NativeSocialControlService.activeService()==null){
                new AlertDialog.Builder(activity)
                    .setTitle("Pilotage natif CHK désactivé")
                    .setMessage("Android exige ton activation explicite du service d'accessibilité CHK. " +
                        "Il permettra de lire et d'interagir UNIQUEMENT avec le réseau choisi pendant " +
                        "20 minutes. Aucune publication ne sera déclarée réussie sans vérification. " +
                        "Les écrans sensibles sont exclus.")
                    .setPositiveButton("Activer dans Android",(d,w)->{
                        try{activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
                        catch(Exception e){Toast.makeText(activity,"Paramètres Android indisponibles",Toast.LENGTH_LONG).show();}
                    })
                    .setNeutralButton("Partager sans pilotage",(d,w)->CutPublicationShare.publishClassic(activity,workspace,row))
                    .setNegativeButton("Annuler",null).show();
                return;
            }
            new AlertDialog.Builder(activity).setTitle("Lancer le partage Android assisté ?")
                .setMessage("La vraie application Android va s'ouvrir en dehors de la fenêtre CHK. " +
                    "Un petit panneau CHK permettra de revenir et d'arrêter la session. " +
                    "Le service est limité à ce réseau pendant 20 minutes. " +
                    "ChatGPT ne reçoit que les observations demandées. " +
                    "Tu gardes la validation finale de publication.")
                .setPositiveButton("Lancer",(d,w)->launch(activity,workspace,row))
                .setNeutralButton("Partage classique",(d,w)->CutPublicationShare.publishClassic(activity,workspace,row))
                .setNegativeButton("Annuler",null).show();
        }catch(Exception e){
            Toast.makeText(activity,"Préparation : "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
    private static void launch(Activity activity,WorkspaceStore workspace,JSONObject row){
        try{
            String platform=row.getString("platform");
            String target=packageFor(platform);
            String path=row.getString("path");
            Intent nativeIntent=CutPublicationShare.buildIntent(activity,workspace,row).setPackage(target);
            JSONObject session=new JSONObject()
                .put("id",UUID.randomUUID().toString())
                .put("platform",platform).put("target_package",target)
                .put("video_path",path).put("title",row.optString("title",""))
                .put("description",row.optString("description",""))
                .put("hashtags",row.optString("hashtags",""))
                .put("state","handoff_prepared")
                .put("published",false).put("platform_publication_confirmed",false)
                .put("started_at",System.currentTimeMillis())
                .put("expires_at",System.currentTimeMillis()+MAX_AGE_MS);
            if(!activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                    .putString(RECORD,session.toString()).commit())
                throw new IllegalStateException("Session non enregistrée");
            workspace.writeText("CutVideo/native_handoff.json",session.toString(2),true);
            String caption=CutStudioStore.text(row);
            ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard!=null&&!caption.isEmpty())
                clipboard.setPrimaryClip(ClipData.newPlainText("CHK métadonnées",caption));
            try{
                activity.startActivity(nativeIntent);
            }catch(android.content.ActivityNotFoundException | SecurityException failure){
                stop(activity);
                Toast.makeText(activity,"Ce réseau refuse le partage direct. Mode classique disponible.",Toast.LENGTH_LONG).show();
                CutPublicationShare.publishClassic(activity,workspace,row);
                return;
            }
            Toast.makeText(activity,"CHK : partage envoyé, publication non confirmée.",Toast.LENGTH_LONG).show();
        }catch(Exception e){
            stop(activity);
            Toast.makeText(activity,"Partage natif : "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
}
