package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Real Tor stays in Tor Browser's isolated application, never a CHK WebView. */
public final class TorEntryActivity extends Activity {
    static final String PACKAGE="org.torproject.torbrowser";
    private TextView status;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28,32,28,24);
        layout.setBackgroundColor(0xff16141e);
        TextView title=new TextView(this);
        title.setText("Tor · Espace indépendant");
        title.setTextSize(24);
        title.setTextColor(0xffeee6ff);
        layout.addView(title);
        status=new TextView(this);
        status.setTextSize(16);
        status.setTextColor(0xffdfd1ee);
        status.setPadding(0,24,0,28);
        layout.addView(status);
        add(layout,"Ouvrir Tor Browser officiel",this::openOfficial);
        add(layout,"Vérifier l'état",this::updateState);
        add(layout,"Diagnostic réseau dans Tor Browser",()->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://check.torproject.org/")).setPackage(PACKAGE));status.setText("Diagnostic envoyé à Tor Browser · résultat non observable depuis CHK");}catch(Exception e){status.setText("Erreur : Tor Browser indisponible");}});
        add(layout,"Déconnexion",()->status.setText(
            "Déconnexion non effectuée par CHK : ferme les onglets et la session dans Tor Browser. CHK ne peut pas arrêter ou confirmer une connexion d'une autre application."));
        add(layout,"Installer Tor Browser officiel",()->{
            Intent link=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.torproject.org/download/#android"));
            startActivity(link);
        });
        add(layout,"Retour à CHK Web",this::finish);
        setContentView(layout);
        updateState();
    }
    private void add(LinearLayout layout,String name,Runnable action){
        Button button=new Button(this);
        button.setAllCaps(false);button.setText(name);
        layout.addView(button,new LinearLayout.LayoutParams(-1,-2));
        button.setOnClickListener(v->action.run());
    }
    private void updateState(){
        Intent launch=getPackageManager().getLaunchIntentForPackage(PACKAGE);
        status.setText(launch==null?
            "Déconnecté · Tor Browser officiel non installé. CHK Web conserve son réseau et ses données. Aucun domaine .onion n'est ouvert dans CHK.":
            "Tor Browser détecté · connexion au réseau Tor non vérifiée. Ouvre-le, attends son état Connecté puis consulte check.torproject.org dans Tor Browser.");
    }
    private void openOfficial(){
        Intent launch=getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if(launch==null){updateState();return;}
        status.setText("Connexion en cours (non vérifiée). Tor Browser seul gère Tor, le DNS et les cookies.");
        try{startActivity(launch);}
        catch(Exception e){status.setText("Erreur de lancement : "+e.getClass().getSimpleName());}
    }
    @Override protected void onResume(){super.onResume();if(status!=null)updateState();}
}
