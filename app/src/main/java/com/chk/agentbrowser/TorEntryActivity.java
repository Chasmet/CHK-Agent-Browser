package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import org.json.JSONObject;

/** Main-process controls; the embedded engine and Tor WebView live in a separate process. */
public final class TorEntryActivity extends Activity {
    static final String PACKAGE="org.torproject.torbrowser";
    static volatile boolean visible;
    private TextView status;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean resumed;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(20,20,20,16);layout.setBackgroundColor(0xff16141e);
        TextView title=new TextView(this);title.setText("Tor · Espace indépendant");title.setTextSize(24);title.setTextColor(0xffeee6ff);layout.addView(title);
        status=new TextView(this);status.setTextSize(15);status.setTextColor(0xffdfd1ee);status.setPadding(0,16,0,16);layout.addView(status);
        if(Build.VERSION.SDK_INT>=29){
            add(layout,"Connecter Tor",()->command("connect"));add(layout,"Ouvrir la navigation Tor",()->command("open_browser"));add(layout,"Vérifier le circuit Tor",()->command("diagnostic"));add(layout,"Déconnecter Tor",()->command("disconnect"));
            TextView note=new TextView(this);note.setTextColor(0xffc9bdd7);note.setText("Navigation isolée, JavaScript désactivé. CHK ne fournit pas les protections complètes contre l’empreinte de Tor Browser officiel.");layout.addView(note);
        }
        add(layout,"Tor Browser externe",this::openExternal);
        add(layout,"Installer depuis torproject.org",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.torproject.org/download/#android"))));
        add(layout,"Retour à CHK Web",this::finish);ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
    }
    private void add(LinearLayout layout,String name,Runnable action){Button b=new Button(this);b.setAllCaps(false);b.setText(name);b.setOnClickListener(v->action.run());layout.addView(b,new LinearLayout.LayoutParams(-1,-2));}
    private void command(String op){try{status.setText(op.equals("disconnect")?"Déconnexion en cours · vérification de l’arrêt…":"Demande Tor en cours…");TorBridge.request(this,new JSONObject().put("operation",op),(ok,value)->{if(!resumed)return;show(value);if(!ok)Toast.makeText(this,value.optString("error","Opération Tor non confirmée"),Toast.LENGTH_LONG).show();});}catch(Exception e){status.setText("Erreur : "+e.getMessage());}}
    private void show(JSONObject state){String value=state.optString("connection_state","error"),label=value.equals("connected")?"Connecté":value.equals("connecting")?"Connexion en cours":value.equals("disconnected")?"Déconnecté":"Erreur";status.setText(label+" · "+state.optInt("bootstrap_percent")+" %\n"+state.optString("error")+(state.optBoolean("tor_verified")?"\nCircuit Tor et diagnostic HTTPS vérifiés.":"\nAucune navigation .onion autorisée sans preuve Tor."));}
    private final Runnable tick=new Runnable(){public void run(){if(!resumed)return;if(Build.VERSION.SDK_INT>=29)TorBridge.request(TorEntryActivity.this,new JSONObject(),(ok,value)->{if(resumed)show(value);});else status.setText("Le moteur intégré demande Android 10 ou plus. Utiliser Tor Browser externe ; son état réseau n’est pas observable par CHK.");main.postDelayed(this,1500);}};
    private void openExternal(){Intent launch=getPackageManager().getLaunchIntentForPackage(PACKAGE);if(launch==null){status.setText("Tor Browser externe non installé.");return;}try{startActivity(launch);status.setText("Application externe lancée · signature et circuit non vérifiés par CHK.");}catch(Exception e){status.setText("Erreur de lancement : "+e.getMessage());}}
    @Override protected void onResume(){super.onResume();resumed=true;visible=true;main.post(tick);if(getIntent().getBooleanExtra("disconnect_tor",false)){getIntent().removeExtra("disconnect_tor");command("disconnect");}}
    @Override protected void onPause(){resumed=false;visible=false;main.removeCallbacks(tick);super.onPause();}
}
