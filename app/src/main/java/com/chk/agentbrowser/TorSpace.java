package com.chk.agentbrowser;

import android.content.*;
import android.content.pm.PackageInfo;
import android.net.Uri;
import java.io.IOException;
import org.json.*;

/** External Tor Browser only. No WebView proxy, shared cookie jar or fabricated connection proof. */
final class TorSpace {
    static final String PACKAGE="org.torproject.torbrowser",CHECK="https://check.torproject.org/",DOWNLOAD="https://www.torproject.org/download/#android";
    private TorSpace(){}
    static JSONObject status(Context app)throws Exception{
        boolean installed=false;String version="";try{PackageInfo p=app.getPackageManager().getPackageInfo(PACKAGE,0);installed=true;version=p.versionName;}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
        String state=app.getSharedPreferences("tor_space",0).getString("state","disconnected");if(!installed&&!state.equals("disconnected"))state="error";
        return new JSONObject().put("state",state).put("browser_installed",installed).put("browser_package",PACKAGE).put("browser_version",version).put("connection_verified",false).put("connection_observable",false).put("onion_verified",false).put("normal_web_uses_tor",false).put("storage_isolation","external Android application sandbox").put("error",app.getSharedPreferences("tor_space",0).getString("error","")).put("limitation","CHK cannot observe or disconnect the external Tor process. Verify the circuit and exit inside Tor Browser. No connected state is inferred from an opened activity.");
    }
    static void command(Context app,String operation,JSONObject args,AgentClient.ResultCallback cb){WorkspaceCommands.UI.post(()->{try{
        if(!cb.isActive())return;String action=operation.startsWith("tor_")?operation.substring(4):operation;
        if(action.equals("status")){cb.finish(true,status(app).toString());return;}
        if(action.equals("space")){app.startActivity(new Intent(app,TorSpaceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));cb.finish(true,status(app).put("space_opened",true).toString());return;}
        if(action.equals("disconnect")){
            app.getSharedPreferences("tor_space",0).edit().putString("state","disconnected").apply();
            cb.finish(true,status(app).put("local_space_disconnected",true).put("external_disconnect_confirmed",false).put("instruction","Déconnecter et quitter Tor depuis Tor Browser pour arrêter son circuit.").toString());return;
        }
        if(action.equals("download")){app.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(DOWNLOAD)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));cb.finish(true,status(app).put("download_page_opened",true).put("browser_installed_by_command",false).toString());return;}
        if(!action.equals("open")&&!action.equals("check"))throw new IOException("Commande Tor inconnue");
        if(args.has("url")&&!CHECK.equals(args.getString("url")))throw new IOException("Connexion Tor non observable : ouvrir et vérifier cette adresse directement dans Tor Browser. Aucun domaine .onion transmis au réseau ordinaire.");
        if(!status(app).getBoolean("browser_installed"))throw new IOException("Tor Browser absent. Installer la version officielle avant de continuer.");
        app.getSharedPreferences("tor_space",0).edit().putString("state","connecting").remove("error").apply();
        app.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(CHECK)).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        app.getSharedPreferences("tor_space",0).edit().putString("state","external_unverified").apply();
        cb.finish(true,status(app).put("external_activity_opened",true).put("diagnostic_url",CHECK).put("diagnostic_result_verified",false).toString());
    }catch(Exception e){app.getSharedPreferences("tor_space",0).edit().putString("state","error").putString("error",e.getMessage()).apply();cb.finish(false,e.getMessage());}});}
}
