package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;

/**
 * Isolated launcher for the official Tor Browser. No CHK WebView, cookies,
 * history, DNS, or proxy settings are shared with the ordinary browser.
 * An external launch NEVER claims the Tor circuit is connected.
 */
public final class TorGatewayActivity extends Activity {
    public static final String PACKAGE="org.torproject.torbrowser";
    public static final String CHECK="https://check.torproject.org/";
    private TextView state,detail;
    private boolean dispatched;
    static boolean isOnion(String address){
        if(address==null)return false;
        try{
            String host=Uri.parse(address.trim()).getHost();
            return host!=null&&(host.equalsIgnoreCase("onion")||host.toLowerCase(java.util.Locale.ROOT).endsWith(".onion"));
        }catch(Exception ignored){return false;}
    }
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(0xff0b1220);
        LinearLayout root=MobileUi.column(this);
        root.setPadding(22,24,22,20);
        root.setBackgroundColor(0xff0d1423);
        TextView title=MobileUi.text(this,"Tor · Navigation séparée",24,MobileUi.TEXT);
        root.addView(title);
        root.addView(MobileUi.text(this,"Le navigateur classique et son historique ne sont pas connectés à Tor.",15,MobileUi.MUTED));
        state=MobileUi.text(this,"Déconnecté · non vérifié",18,0xffedc078);
        state.setTag("tor_connection_state");root.addView(state);
        detail=MobileUi.text(this,"CHK utilise uniquement l'application officielle Tor Browser, isolée du WebView CHK. Une ouverture réussie n'est pas une preuve de connexion Tor.",14,MobileUi.TEXT);
        root.addView(detail);
        TextView launch=MobileUi.action(this,"Ouvrir Tor Browser · diagnostic");
        launch.setTag("tor_launch");root.addView(launch);
        launch.setOnClickListener(v->launch());
        TextView disconnect=MobileUi.action(this,"Déconnecter / fermer l'espace Tor CHK");
        disconnect.setTag("tor_disconnect");root.addView(disconnect);
        disconnect.setOnClickListener(v->{
            dispatched=false;state.setText("Déconnecté · espace CHK fermé");
            detail.setText("Cette commande ferme l'espace CHK, PAS Tor Browser. Pour arrêter réellement Tor, quitte Tor Browser dans Android.");
            finish();
        });
        TextView install=MobileUi.action(this,"Installer Tor Browser officiel");
        root.addView(install);install.setOnClickListener(v->{
            Intent store=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.torproject.org/download/#android"));
            try{startActivity(store);}catch(Exception ex){detail.setText("Téléchargement indisponible : "+ex.getClass().getSimpleName());}
        });
        setContentView(root);
    }
    private void launch(){
        state.setText("Connexion en cours · diagnostic externe");
        try{
            // Explicit package, no generic ACTION_VIEW fallback that could
            // leak an .onion destination to a non-Tor browser.
            Intent tor=new Intent(Intent.ACTION_VIEW,Uri.parse(CHECK));
            tor.setPackage(PACKAGE);
            tor.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(tor);
            dispatched=true;
            state.setText("Non vérifié · contrôle dans Tor Browser");
            detail.setText("Dans Tor Browser, attends « Connecté », puis vérifie le résultat de check.torproject.org. CHK ne peut pas lire la session externe et ne prétend pas être connecté.");
        }catch(Exception ex){
            dispatched=false;
            state.setText("Erreur · Tor Browser indisponible");
            detail.setText("Installe ou ouvre le Tor Browser officiel. Aucun accès .onion n'a été lancé depuis CHK.");
        }
    }
    @Override protected void onResume(){
        super.onResume();
        if(dispatched)detail.setText("Retour dans CHK : état réel de Tor toujours non vérifiable. La fermeture de cette fenêtre ne ferme pas Tor Browser.");
    }
}