package com.chk.agentbrowser;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import org.json.JSONObject;

/** Separate launch space; all actual Tor browsing remains in the official browser. */
public final class TorSpaceActivity extends Activity {
    private TextView state;
    @Override public void onCreate(Bundle b){super.onCreate(b);LinearLayout column=MobileUi.column(this);column.setPadding(24,24,24,24);column.setBackgroundColor(0xff17181c);setContentView(column);
        TextView title=MobileUi.text(this,"◉  Tor · espace séparé",23,MobileUi.TEXT);column.addView(title);state=MobileUi.text(this,"",16,0xffc5bfff);state.setPadding(0,24,0,24);state.setTag("tor_state");column.addView(state);
        column.addView(MobileUi.text(this,"La navigation Tor s’ouvre dans Tor Browser. CHK ne partage avec lui ni cookies, ni historique, ni stockage Web.\n\nOuvrir le navigateur ne prouve pas une connexion Tor. La page de diagnostic doit être vérifiée dans Tor Browser avant de saisir une adresse .onion.\n\nPour arrêter le circuit, déconnecte et quitte Tor Browser. Le bouton ci-dessous ferme uniquement l’espace CHK.",16,MobileUi.MUTED));
        button(column,"Ouvrir Tor et son diagnostic","check");button(column,"Installer Tor Browser officiel","download");button(column,"Déconnexion de l’espace CHK","disconnect");Button close=new Button(this);close.setText("Retour au Web classique");column.addView(close);close.setOnClickListener(v->finish());refresh();
    }
    private void button(LinearLayout box,String label,String operation){Button button=new Button(this);button.setText(label);button.setContentDescription(label);button.setTag("tor_"+operation);box.addView(button);button.setOnClickListener(v->TorSpace.command(this,operation,new JSONObject(),(ok,text)->{if(!ok)Toast.makeText(this,text,Toast.LENGTH_LONG).show();refresh();}));}
    private void refresh(){try{JSONObject s=TorSpace.status(this);String value=s.getString("state");String label=value.equals("disconnected")?"Déconnecté":value.equals("connecting")?"Connexion en cours":value.equals("error")?"Erreur : "+s.optString("error"):"Connexion externe non vérifiée";state.setText(label+"\nTor Browser : "+(s.getBoolean("browser_installed")?"détecté · "+s.optString("browser_version"):"absent"));}catch(Exception e){state.setText("Erreur : "+e.getMessage());}}
    @Override protected void onResume(){super.onResume();if(state!=null)refresh();}
}
