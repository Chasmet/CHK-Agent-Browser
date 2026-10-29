package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Separate official Tor Browser launcher. Never routes CHK WebView over a false proxy. */
public final class TorAccessActivity extends Activity {
    private static final String OFFICIAL_PACKAGE="org.torproject.torbrowser";
    private TextView state;
    private boolean launched;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32,48,32,24);
        layout.setBackgroundColor(0xff17181c);
        state=new TextView(this);
        state.setTextColor(0xfff1f5ff);
        state.setTextSize(18);
        state.setGravity(Gravity.CENTER_VERTICAL);
        layout.addView(state);
        TextView message=new TextView(this);
        message.setTextColor(0xffabb5cb);
        message.setTextSize(15);
        message.setPadding(0,28,0,28);
        message.setText("Navigation Tor séparée : seule l'application officielle Tor Browser peut ouvrir les liens. CHK ne reçoit ni cookies, ni historique, ni requêtes Tor. L'ouverture de Tor Browser ne prouve pas que son circuit est connecté. Vérifie la connexion dans Tor Browser avant de visiter un site .onion.");
        layout.addView(message);
        Button open=new Button(this);open.setText("Ouvrir Tor Browser officiel");
        open.setOnClickListener(v->launchTor());
        layout.addView(open);
        Button disconnect=new Button(this);disconnect.setText("Déconnexion / fermer la session");
        disconnect.setOnClickListener(v->{
            state.setText("État Tor inconnu : ferme la connexion et les onglets dans Tor Browser.");
            new AgentDialog.Builder(this).setTitle("Déconnexion Tor")
                .setMessage("La session appartient à Tor Browser. Android interdit à CHK de couper ou de prétendre couper les circuits de cette autre application. Ferme la session directement dans Tor Browser.")
                .setPositiveButton("Compris",null).show();
        });
        layout.addView(disconnect);
        Button back=new Button(this);back.setText("Retour au Web normal");
        back.setOnClickListener(v->finish());
        layout.addView(back);
        setContentView(layout);
        updateStatus();
    }
    @Override protected void onResume(){super.onResume();if(state!=null)updateStatus();}
    private void updateStatus(){
        boolean available=getPackageManager().getLaunchIntentForPackage(OFFICIAL_PACKAGE)!=null;
        state.setText(!available?"Tor : Déconnecté · Tor Browser non installé":
            launched?"Tor : état non vérifié · connexion à confirmer dans Tor Browser":
                     "Tor : Déconnecté dans CHK · moteur externe disponible");
    }
    private void launchTor(){
        Intent launch=getPackageManager().getLaunchIntentForPackage(OFFICIAL_PACKAGE);
        if(launch==null){
            new AgentDialog.Builder(this).setTitle("Installer Tor Browser")
                .setMessage("Tor Browser officiel est nécessaire. CHK ne simule pas un réseau Tor.")
                .setNegativeButton("Annuler",null)
                .setPositiveButton("Google Play",(d,w)->{
                    try{startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id="+OFFICIAL_PACKAGE)));}
                    catch(Exception e){startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id="+OFFICIAL_PACKAGE)));}
                }).show();
            return;
        }
        // Only the official package is opened; never fall back to CHK's normal WebView.
        launch.setPackage(OFFICIAL_PACKAGE);
        try{startActivity(launch);launched=true;updateStatus();}
        catch(Exception e){state.setText("Tor : Erreur d'ouverture de l'application officielle");}
    }
}
