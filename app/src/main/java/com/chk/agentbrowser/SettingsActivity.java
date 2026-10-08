package com.chk.agentbrowser;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Switch;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;

/** In-app updater and background agent controls. No GitHub website required. */
public final class SettingsActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_CODE = 735;
    private UpdateManager updates;
    private AgentClient agent;
    private TextView mcpStatus, status;
    private Button mcpConnect, mcpCopy, mcpDisable, check, install;
    private boolean askedToInstall, awaitingInstallPermission;
    private Switch autonomousMode, previewMode;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        updates=new UpdateManager(this);
        agent=AgentClient.get(this);
        autonomousMode=findViewById(R.id.autonomous_mode);
        previewMode=findViewById(R.id.preview_mode);
        autonomousMode.setChecked(agent.isAutonomous());
        previewMode.setChecked(agent.isPreviewAllowed());
        autonomousMode.setOnCheckedChangeListener((button,enabled)->{
            if(!enabled){
                agent.setAutonomous(false);
                AgentService.refresh(this);
                showMcp();
                return;
            }
            new AlertDialog.Builder(this)
                .setTitle("Autoriser le mode autonome ?")
                .setMessage("ChatGPT pourra ouvrir des sites, lire des pages, cliquer et remplir des formulaires sans validation à chaque étape, même quand tu utilises une autre application. Cela inclut potentiellement des actions sensibles sur des comptes connectés. N'active ce mode que si tu fais confiance au plugin MCP et à ses commandes. Tu pourras le désactiver à tout moment.")
                .setNegativeButton("Annuler",(dialog,which)->autonomousMode.setChecked(false))
                .setPositiveButton("Activer le mode autonome",(dialog,which)->{
                    agent.setAutonomous(true);
                    AgentService.ensureRunning(this);
                    AgentService.refresh(this);
                    showMcp();
                })
                .setOnCancelListener(dialog->autonomousMode.setChecked(false))
                .show();
        });
        previewMode.setOnCheckedChangeListener((button,enabled)->{
            agent.setPreviewAllowed(enabled);
            showMcp();
        });
        findViewById(R.id.battery_settings).setOnClickListener(v->{
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }catch(Exception e){
                Toast.makeText(this,"Ouvre Paramètres Android > Applications > Batterie > CHK Agent Browser",Toast.LENGTH_LONG).show();
            }
        });
        mcpStatus=findViewById(R.id.mcp_status);
        mcpConnect=findViewById(R.id.mcp_connect);
        mcpCopy=findViewById(R.id.mcp_copy);
        mcpDisable=findViewById(R.id.mcp_disable);
        status=findViewById(R.id.update_status);
        check=findViewById(R.id.check_update);
        install=findViewById(R.id.install_update);
        mcpConnect.setOnClickListener(v->connectMcp());
        mcpCopy.setOnClickListener(v->copyMcp());
        mcpDisable.setOnClickListener(v->{
            agent.disable();
            stopService(new Intent(this, AgentService.class));
            showMcp();
        });
        check.setOnClickListener(v->checkUpdate());
        install.setOnClickListener(v->confirmInstall());
        findViewById(R.id.clear_history).setOnClickListener(v->
            new AlertDialog.Builder(this)
                .setTitle("Effacer l'historique ?")
                .setMessage("Les favoris et les cookies seront conservés.")
                .setNegativeButton("Annuler",null)
                .setPositiveButton("Effacer",(dialog,which)->{
                    new BrowserStore(this).clearHistory();
                    Toast.makeText(this,"Historique effacé",Toast.LENGTH_SHORT).show();
                }).show());
        try {
            String version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;
            ((TextView)findViewById(R.id.version)).setText("Version installée : "+version);
        }catch(Exception ignored){}
        status.setText("GitHub Releases publiques : mises à jour directement sur ce téléphone.");
        showMcp();showInstall();
    }
    private void showMcp() {
        boolean enabled=agent.isEnabled(), link=!agent.mcpUrl().isEmpty();
        String message=enabled
            ?"MCP activé. Mode autonome : "+(agent.isAutonomous()?"ACTIF":"DÉSACTIVÉ")+". Service en arrière-plan : activé quand Android le permet."
            :"MCP désactivé.";
        if(enabled && Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            message += "\nAutorise les notifications pour voir les demandes de confirmation.";
        }
        mcpStatus.setText(message);
        mcpCopy.setEnabled(enabled&&link);
        mcpDisable.setEnabled(enabled);
    }
    private void connectMcp() {
        mcpConnect.setEnabled(false);
        mcpStatus.setText("Connexion sécurisée à Render…");
        agent.enable((ok,message)->{
            if(isFinishing()||isDestroyed())return;
            mcpConnect.setEnabled(true);
            if(ok) {
                AgentService.ensureRunning(this);
                requestNotificationsIfNeeded();
            }
            showMcp();
            if(!ok)mcpStatus.setText(message);
        });
    }
    private void copyMcp() {
        if(!agent.isEnabled()||agent.mcpUrl().isEmpty())return;
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
            .setPrimaryClip(ClipData.newPlainText("Adresse MCP personnelle",agent.mcpUrl()));
        Toast.makeText(this,"Adresse MCP copiée. Ne la partage pas publiquement.",Toast.LENGTH_LONG).show();
    }
    private void requestNotificationsIfNeeded() {
        if(Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notification_asked",false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification_asked",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION_CODE);
        }
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==NOTIFICATION_PERMISSION_CODE)showMcp();
    }
    private void checkUpdate() {
        check.setEnabled(false);
        status.setText("Recherche d'une nouvelle version signée…");
        updates.check((message,release)->{
            if(isFinishing()||isDestroyed())return;
            check.setEnabled(true);
            status.setText(message);
            if(release!=null) {
                new AlertDialog.Builder(this)
                    .setTitle("CHK Agent Browser "+release.version)
                    .setMessage("Télécharger la mise à jour sur ce téléphone ? L'installation garde les données avec la signature Android d'origine.")
                    .setNegativeButton("Plus tard",null)
                    .setPositiveButton("Télécharger",(dialog,which)->{
                        try {
                            updates.download(release);
                            status.setText("Téléchargement en cours. Une notification proposera l'installation ici, sans passer par GitHub.");
                            requestNotificationsIfNeeded();
                        }catch(Exception e) {
                            status.setText("Échec du téléchargement : "+e.getMessage());
                        }
                    }).show();
            }
        });
    }
    private void showInstall() {
        File ready=updates.completedApk();
        install.setVisibility(ready==null?View.GONE:View.VISIBLE);
        if(ready!=null) {
            status.setText("Mise à jour vérifiée et téléchargée. Appuie sur Installer ; Android demandera confirmation.");
        }
    }
    private void confirmInstall() {
        if(updates.completedApk()==null) {
            status.setText("Pas de mise à jour compatible téléchargée.");
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Installer la mise à jour ?")
            .setMessage("L'application sera mise à jour directement sur ce téléphone. Favoris, historique et connexion MCP resteront enregistrés.")
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Installer",(dialog,which)->installReady())
            .show();
    }
    private void installReady() {
        File apk=updates.completedApk();
        if(apk==null) {
            status.setText("APK indisponible ou signature incompatible.");
            showInstall();return;
        }
        try {
            if(!updates.install(apk)) {
                awaitingInstallPermission=true;
                status.setText("Autorise l'installation depuis CHK Agent Browser dans les réglages Android, puis reviens.");
            }else {
                awaitingInstallPermission=false;
                status.setText("Installateur Android ouvert : valide la mise à jour sans désinstaller l'application.");
            }
        }catch(Exception e) {
            status.setText("Installation impossible : "+e.getMessage());
        }
    }
    private void handleInstallationNotification() {
        if(askedToInstall)return;
        Intent launch=getIntent();
        if(launch!=null && UpdateManager.ACTION_INSTALL_READY.equals(launch.getAction())) {
            askedToInstall=true;
            launch.setAction(null);
            confirmInstall();
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        askedToInstall=false;
        handleInstallationNotification();
    }
    @Override public void onResume() {
        super.onResume();
        if(agent!=null && agent.isEnabled()) {
            AgentService.ensureRunning(this);
            requestNotificationsIfNeeded();
            showMcp();
        }
        if(updates!=null) {
            showInstall();
            if(awaitingInstallPermission &&
               (Build.VERSION.SDK_INT<26 || getPackageManager().canRequestPackageInstalls())) {
                awaitingInstallPermission=false;
                installReady();
            } else {
                handleInstallationNotification();
            }
        }
    }
}
