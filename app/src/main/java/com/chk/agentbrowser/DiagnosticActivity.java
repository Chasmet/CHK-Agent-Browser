package com.chk.agentbrowser;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.widget.TextView;
import android.widget.Toast;

/** Local diagnostic screen. Never displays tokens, cookies or the private MCP URL. */
public final class DiagnosticActivity extends Activity {
    private AgentClient agent;
    private TextView report,logView;
    private final Handler refresh=new Handler();
    private final Runnable tick=new Runnable(){
        @Override public void run(){render();refresh.postDelayed(this,1500L);}
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        setContentView(R.layout.activity_diagnostic);
        agent=AgentClient.get(this);
        report=findViewById(R.id.diagnostic_report);
        logView=findViewById(R.id.diagnostic_log);
        findViewById(R.id.diagnostic_reconnect).setOnClickListener(v->{
            AgentService.ensureRunning(this);
            agent.reconnect();
            Toast.makeText(this,"Reconnexion demandée",Toast.LENGTH_SHORT).show();
            render();
        });
        findViewById(R.id.diagnostic_copy).setOnClickListener(v->{
            String value=buildReport()+"\n\nJOURNAL TECHNIQUE\n"+AgentLog.dump(this);
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Diagnostic CHK Agent Browser",value));
            Toast.makeText(this,"Diagnostic copié sans jeton ni cookie",Toast.LENGTH_LONG).show();
        });
        findViewById(R.id.diagnostic_clear_log).setOnClickListener(v->{
            AgentLog.clear(this);
            render();
            Toast.makeText(this,"Journal technique effacé",Toast.LENGTH_SHORT).show();
        });
    }

    private String buildReport(){
        String version="inconnue";
        try{version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){}
        String network=networkState();
        String battery="inconnue";
        if(Build.VERSION.SDK_INT>=23){
            PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
            battery=power.isIgnoringBatteryOptimizations(getPackageName())
                ?"optimisation batterie ignorée":"optimisation batterie active";
        }
        return "CHK Agent Browser "+version+
            "\nMCP : "+(agent.isEnabled()?"activé":"désactivé")+
            "\nConnexion : "+agent.connectionStatus()+
            "\nRéseau : "+network+
            "\nMode autonome : "+(agent.isAutonomous()?"actif":"désactivé")+
            "\nAperçus : "+(agent.isPreviewAllowed()?"autorisés":"désactivés")+
            "\nBatterie : "+battery+
            "\nDernière commande : "+agent.lastCommandStatus()+
            "\nDernière erreur réseau : "+agent.lastErrorSummary()+
            "\nRelais : Render /agentbrowser/* · protocole 2.1"+
            "\nSécurité : mots de passe, cookies, jetons et URL MCP privée masqués.";
    }

    private String networkState(){
        try{
            ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            if(Build.VERSION.SDK_INT<23){
                android.net.NetworkInfo info=cm.getActiveNetworkInfo();
                if(info==null||!info.isConnected())return "hors ligne";
                if(info.getType()==ConnectivityManager.TYPE_WIFI)return "Wi-Fi";
                if(info.getType()==ConnectivityManager.TYPE_MOBILE)return "réseau mobile";
                if(info.getType()==ConnectivityManager.TYPE_ETHERNET)return "Ethernet";
                return "autre réseau";
            }
            Network network=cm.getActiveNetwork();
            if(network==null)return "hors ligne";
            NetworkCapabilities cap=cm.getNetworkCapabilities(network);
            if(cap==null)return "connexion détectée";
            if(cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return "Wi-Fi";
            if(cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return "réseau mobile";
            if(cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return "Ethernet";
            return "autre réseau";
        }catch(Exception e){return "indéterminé";}
    }

    private void render(){
        if(report!=null)report.setText(buildReport());
        if(logView!=null)logView.setText(AgentLog.dump(this));
    }
    @Override protected void onResume(){super.onResume();refresh.removeCallbacks(tick);refresh.post(tick);}
    @Override protected void onPause(){refresh.removeCallbacks(tick);super.onPause();}
}
