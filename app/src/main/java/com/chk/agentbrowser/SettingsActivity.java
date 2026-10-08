package com.chk.agentbrowser;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
public class SettingsActivity extends Activity {
    private UpdateManager updates;
    private AgentClient agent;
    private TextView mcpStatus;
    private Button mcpConnect,mcpCopy,mcpDisable;
    private TextView status;
    private Button check,install;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setContentView(R.layout.activity_settings);
        updates=new UpdateManager(this);
        agent=new AgentClient(this);
        mcpStatus=findViewById(R.id.mcp_status);
        mcpConnect=findViewById(R.id.mcp_connect);
        mcpCopy=findViewById(R.id.mcp_copy);
        mcpDisable=findViewById(R.id.mcp_disable);
        mcpConnect.setOnClickListener(v->connectMcp());
        mcpCopy.setOnClickListener(v->copyMcp());
        mcpDisable.setOnClickListener(v->{agent.disable();showMcp();});
        showMcp();
        status=findViewById(R.id.update_status);check=findViewById(R.id.check_update);
        install=findViewById(R.id.install_update);
        try{
            String version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;
            ((TextView)findViewById(R.id.version)).setText("Version installée : "+version);
        }catch(Exception ignored){}
        check.setOnClickListener(v->checkUpdate());install.setOnClickListener(v->installReady());
        findViewById(R.id.clear_history).setOnClickListener(v->
            new AlertDialog.Builder(this).setTitle("Effacer l'historique ?")
            .setMessage("Les favoris et les cookies seront conservés.")
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Effacer",(d,w)->{
                new BrowserStore(this).clearHistory();
                Toast.makeText(this,"Historique effacé",Toast.LENGTH_SHORT).show();
            }).show()
        );
        status.setText("Vérification gratuite via GitHub Releases.");
        install.setVisibility(View.GONE);showInstall();
    }
    private void showMcp(){
        boolean enabled=agent.isEnabled();
        boolean link=!agent.mcpUrl().isEmpty();
        mcpStatus.setText(enabled
            ?"MCP activé. Reviens à la navigation pour recevoir les commandes."
            :"MCP désactivé.");
        mcpCopy.setEnabled(enabled&&link);
        mcpDisable.setEnabled(enabled);
    }
    private void connectMcp(){
        mcpConnect.setEnabled(false);mcpStatus.setText("Association sécurisée avec Render…");
        agent.enable((ok,message)->{
            if(isFinishing()||isDestroyed())return;
            mcpConnect.setEnabled(true);
            showMcp();mcpStatus.setText(message);
        });
    }
    private void copyMcp(){
        if(!agent.isEnabled()||agent.mcpUrl().isEmpty())return;
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
            .setPrimaryClip(ClipData.newPlainText("CHK Agent Browser MCP",agent.mcpUrl()));
        Toast.makeText(this,"Adresse MCP copiée. Garde-la privée.",Toast.LENGTH_LONG).show();
    }
    private void checkUpdate(){
        check.setEnabled(false);status.setText("Vérification GitHub…");
        updates.check((message,release)->{
            if(isFinishing()||isDestroyed())return;
            check.setEnabled(true);status.setText(message);
            if(release!=null){
                new AlertDialog.Builder(this).setTitle("Mise à jour "+release.version)
                .setMessage("Télécharger l'APK officiel ? Les données restent intactes avec la même signature Android.")
                .setNegativeButton("Plus tard",null)
                .setPositiveButton("Télécharger",(d,w)->{
                    try{updates.download(release);status.setText("Téléchargement lancé. Reviens pour installer.");}
                    catch(Exception e){status.setText("Téléchargement impossible : "+e.getMessage());}
                }).show();
            }
        });
    }
    private void showInstall(){install.setVisibility(updates.completedApk()==null?View.GONE:View.VISIBLE);}
    private void installReady(){
        File file=updates.completedApk();
        if(file==null){status.setText("Téléchargement non terminé");showInstall();return;}
        try{if(!updates.install(file))status.setText("Autorise l'installation, puis réessaie.");}
        catch(Exception e){status.setText("Erreur : "+e.getMessage());}
    }
    @Override public void onResume(){super.onResume();if(updates!=null)showInstall();if(agent!=null)showMcp();}
}
