package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import org.json.JSONObject;
import com.google.mlkit.nl.translate.*;

/** Main-process controls; the embedded engine and Tor WebView live in a separate process. */
public final class TorEntryActivity extends Activity {
    static final String PACKAGE="org.torproject.torbrowser";
    static volatile boolean visible;
    private TextView status;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean resumed;
    private boolean modelSetupStarted, modelDownloading;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(20,20,20,16);layout.setBackgroundColor(0xff16141e);
        TextView title=new TextView(this);title.setText("Tor · Espace indépendant");title.setTextSize(24);title.setTextColor(0xffeee6ff);layout.addView(title);
        status=new TextView(this);status.setTextSize(15);status.setTextColor(0xffdfd1ee);status.setPadding(0,16,0,16);layout.addView(status);
        if(Build.VERSION.SDK_INT>=29){
            add(layout,"Connecter Tor",()->command("connect"));add(layout,"Ouvrir la navigation Tor",()->command("open_browser"));add(layout,"Vérifier le circuit Tor",()->command("diagnostic"));add(layout,"Déconnecter Tor",()->command("disconnect"));
            TextView note=new TextView(this);note.setTextColor(0xffc9bdd7);note.setText("Navigation isolée, JavaScript désactivé. CHK ne fournit pas les protections complètes contre l’empreinte de Tor Browser officiel.");layout.addView(note);
        }
        add(layout,"Préparer traduction EN → FR hors ligne",this::downloadTranslationModel);
        add(layout,"Tor Browser externe",this::openExternal);
        add(layout,"Installer depuis torproject.org",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.torproject.org/download/#android"))));
        add(layout,"Retour à CHK Web",this::finish);ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
    }
    private void add(LinearLayout layout,String name,Runnable action){Button b=new Button(this);b.setAllCaps(false);b.setText(name);b.setOnClickListener(v->action.run());layout.addView(b,new LinearLayout.LayoutParams(-1,-2));}
    private void command(String op){try{status.setText(op.equals("disconnect")?"Déconnexion en cours · vérification de l’arrêt…":"Demande Tor en cours…");TorBridge.request(this,new JSONObject().put("operation",op),(ok,value)->{if(!resumed)return;show(value);if(!ok)Toast.makeText(this,value.optString("error","Opération Tor non confirmée"),Toast.LENGTH_LONG).show();});}catch(Exception e){status.setText("Erreur : "+e.getMessage());}}
    /** A normal tap on CHK's Tor icon connects first and then opens the French browser. */
    private void connectAndOpenDirectly(){
        try{
            status.setText("Connexion au réseau Tor…");
            TorBridge.request(this,new JSONObject().put("operation","connect"),(ok,value)->{
                if(isFinishing()||isDestroyed())return;
                if(!ok||!value.optBoolean("tor_verified")){
                    if(resumed){show(value);Toast.makeText(this,"Connexion Tor non vérifiée",Toast.LENGTH_LONG).show();}
                    return;
                }
                status.setText("Tor connecté · ouverture du navigateur en français…");
                try{
                    TorBridge.request(this,new JSONObject().put("operation","open_browser").put("home_on_open",true),(opened,openedState)->{
                        if(opened){finish();return;}
                        if(resumed){show(openedState);Toast.makeText(this,openedState.optString("error","Ouverture du navigateur non confirmée"),Toast.LENGTH_LONG).show();}
                    });
                }catch(Exception e){if(resumed)status.setText("Ouverture impossible : "+e.getMessage());}
            });
        }catch(Exception e){status.setText("Connexion impossible : "+e.getMessage());}
    }
    private void show(JSONObject state){String value=state.optString("connection_state","error"),label=value.equals("connected")?"Connecté":value.equals("connecting")?"Connexion en cours":value.equals("disconnected")?"Déconnecté":"Erreur";status.setText(label+" · "+state.optInt("bootstrap_percent")+" %\n"+state.optString("error")+(state.optBoolean("tor_verified")?"\nCircuit Tor et diagnostic HTTPS vérifiés.":"\nAucune navigation .onion autorisée sans preuve Tor."));}
    private final Runnable tick=new Runnable(){public void run(){if(!resumed)return;if(Build.VERSION.SDK_INT>=29)TorBridge.request(TorEntryActivity.this,new JSONObject(),(ok,value)->{if(resumed)show(value);});else status.setText("Le moteur intégré demande Android 10 ou plus. Utiliser Tor Browser externe ; son état réseau n’est pas observable par CHK.");main.postDelayed(this,1500);}};
    /** Download only the language model in the ordinary process, never in the isolated Tor process. */
    private void downloadTranslationModel(){downloadTranslationModel(true);}
    /** Language packs may download over the phone's normal connection, but page text never leaves :tor. */
    private void downloadTranslationModel(boolean showProgress){
        if(modelSetupStarted&&modelDownloading)return;
        modelSetupStarted=true;
        if(getSharedPreferences("tor_french",MODE_PRIVATE).getBoolean("installed",false)){
            if(showProgress)status.setText("Traduction FR déjà préparée.");
            return;
        }
        modelDownloading=true;
        if(showProgress)status.setText("Préparation des modèles EN → FR pour Tor (une seule fois)…");
        TranslatorOptions options=new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.FRENCH).build();
        final Translator translator=Translation.getClient(options);
        translator.downloadModelIfNeeded()
            .addOnSuccessListener(unused->{
                translator.close();modelDownloading=false;
                getSharedPreferences("tor_french",MODE_PRIVATE).edit().putBoolean("installed",true).apply();
                if(showProgress&&!isDestroyed())status.setText("Modèle français prêt. Retour à Tor pour traduire.");
            })
            .addOnFailureListener(error->{
                translator.close();modelDownloading=false;
                if(showProgress&&!isDestroyed())status.setText("Installation FR indisponible : "+error.getMessage());
            });
    }
    private void openExternal(){Intent launch=getPackageManager().getLaunchIntentForPackage(PACKAGE);if(launch==null){status.setText("Tor Browser externe non installé.");return;}try{startActivity(launch);status.setText("Application externe lancée · signature et circuit non vérifiés par CHK.");}catch(Exception e){status.setText("Erreur de lancement : "+e.getMessage());}}
    @Override protected void onResume(){
        super.onResume();resumed=true;visible=true;main.post(tick);
        if(getIntent().getBooleanExtra("prepare_translation_model",false)){
            getIntent().removeExtra("prepare_translation_model");
            downloadTranslationModel(true);
            return;
        }
        // Preinstall automatically in CHK's ordinary process so the Tor tab has an integrated FR reader.
        if(!getSharedPreferences("tor_french",MODE_PRIVATE).getBoolean("installed",false))
            downloadTranslationModel(false);
        if(getIntent().getBooleanExtra("disconnect_tor",false)){getIntent().removeExtra("disconnect_tor");command("disconnect");}else if(Build.VERSION.SDK_INT>=29&&getIntent().getBooleanExtra("direct_tor",false)){getIntent().removeExtra("direct_tor");connectAndOpenDirectly();}}
    @Override protected void onPause(){resumed=false;visible=false;main.removeCallbacks(tick);super.onPause();}
}
