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
import android.os.PowerManager;
import android.os.Handler;
import android.net.Uri;
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
    private TextView mcpStatus, status, local2Status, local3Status;
    private Button mcpConnect, mcpCopy, mcpDisable, check, install;
    private boolean askedToInstall, awaitingInstallPermission;
    private long validatedDownloadId=Long.MIN_VALUE;
    private Switch autonomousMode, previewMode;
    private final Handler refresh=new Handler();
    private final Runnable statusTick=new Runnable(){
        @Override public void run(){showMcp();showDownloadProgress();if(local2Status!=null)local2Status.setText(Local2Client.get(SettingsActivity.this).status()+"\nMode autonome : "+(agent.isAutonomous()?"actif":"désactivé")+"\nNavigateur : "+agent.local2BrowserSummary());if(local3Status!=null)local3Status.setText(Local3Client.get(SettingsActivity.this).status());refresh.postDelayed(this,2000L);}
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        if(state!=null)awaitingInstallPermission=state.getBoolean("awaiting_install_permission",false);
        updates=new UpdateManager(this);
        agent=AgentClient.get(this);
        findViewById(R.id.settings_back).setOnClickListener(v->finish());
        android.view.ViewGroup group=(android.view.ViewGroup)findViewById(R.id.battery_settings).getParent();
        Button folders=new Button(this);folders.setText("Dossiers externes autorisés à l’agent");
        folders.setOnClickListener(v->startActivity(new Intent(this,ExternalFilesActivity.class)));group.addView(folders);
        Switch ecoMode=new Switch(this);
        ecoMode.setText("Mode éco MCP (écran éteint : commandes potentiellement retardées)");
        ecoMode.setTextSize(14);
        ecoMode.setPadding(16,14,16,14);
        ecoMode.setChecked(agent.isEcoMode());
        ecoMode.setOnCheckedChangeListener((button,enabled)->{
            agent.setEcoMode(enabled);
            AgentService.refresh(this);
            Toast.makeText(this,enabled?"Mode éco activé : le traitement en veille peut être retardé":"Mode MCP réactif activé",Toast.LENGTH_LONG).show();
        });
        group.addView(ecoMode);
        int localInsert = group.indexOfChild(findViewById(R.id.mcp_diagnostic)) + 1;
        TextView channels = new TextView(this);
        channels.setText("CONNEXIONS MCP INDÉPENDANTES");
        channels.setTextSize(18);
        channels.setPadding(16,24,16,12);
        group.addView(channels, localInsert++);
        TextView renderHelp = new TextView(this);
        renderHelp.setText("MCP Render : utilise Activer / reconnecter Render ci-dessus. Render, Local 2 et Local 3 fonctionnent indépendamment.");
        renderHelp.setPadding(16,8,16,16);
        group.addView(renderHelp, localInsert++);
        Switch local2 = new Switch(this);
        local2.setText("MCP Local 2 (PC) : activer / désactiver");
        local2.setChecked(Local2Client.get(this).enabled());
        group.addView(local2, localInsert++);
        Button local2Config = new Button(this);
        local2Config.setText("Importer le lien MCP ChatGPT / Supabase");
        local2Config.setAllCaps(false);
        group.addView(local2Config, localInsert++);
        Button local2Paste = new Button(this);
        local2Paste.setText("Importer le lien MCP copié");
        local2Paste.setAllCaps(false);
        group.addView(local2Paste, localInsert++);
        local2Status = new TextView(this);
        local2Status.setText(Local2Client.get(this).status());
        group.addView(local2Status, localInsert++);
        TextView local2Help = new TextView(this);
        local2Help.setText("Copie ton lien MCP Supabase, puis utilise Importer : l’application le convertit automatiquement en adresse directe du PC. Aucun polling Supabase. Activation et connexion sont indépendantes.");
        local2Help.setPadding(16,8,16,12);
        group.addView(local2Help, localInsert++);
        Button local2Off = new Button(this);
        local2Off.setText("Désactiver MCP Local 2 uniquement");
        local2Off.setAllCaps(false);
        local2Off.setOnClickListener(v -> {
            Local2Client.get(this).configure(Local2Client.get(this).url(),false);
            local2.setChecked(false);
            if(!agent.isEnabled() && !Local3Client.get(this).enabled()) stopService(new Intent(this,AgentService.class));
            local2Status.setText("Local 2 désactivé · Render inchangé");
        });
        group.addView(local2Off, localInsert++);
        local2Paste.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                android.content.ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0)
                    throw new IllegalArgumentException("Copie d’abord ton lien MCP ChatGPT.");
                CharSequence source = clip.getItemAt(0).coerceToText(this);
                Local2Client.get(this).configure(source == null ? "" : source.toString(), true);
                local2.setChecked(true);
                AgentService.ensureRunning(this);
                local2Status.setText("Local 2 activé · tentative de connexion directe au PC");
                Toast.makeText(this, "Lien importé localement · Render inchangé", Toast.LENGTH_LONG).show();
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        local2Config.setOnClickListener(v -> {
            android.widget.EditText field = new android.widget.EditText(this);
            field.setSingleLine(true);
            field.setText(Local2Client.get(this).url());
            new android.app.AlertDialog.Builder(this).setTitle("MCP Windows Local 2")
                .setView(field).setNegativeButton("Annuler", null)
                .setPositiveButton("Enregistrer", (dialog,which) -> {
                    try {
                        Local2Client.get(this).configure(field.getText().toString(),local2.isChecked());
                        AgentService.ensureRunning(this);
                        local2Status.setText(Local2Client.get(this).status());
                    } catch(IllegalArgumentException e) {
                        Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();
                    }
                }).show();
        });
        local2.setOnCheckedChangeListener((button,on) -> {
            try {
                Local2Client.get(this).configure(Local2Client.get(this).url(),on);
                AgentService.ensureRunning(this);
                if(!on && !agent.isEnabled() && !Local3Client.get(this).enabled())stopService(new Intent(this,AgentService.class));
            }catch(IllegalArgumentException e) {
                local2.setChecked(false);
                Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();
            }
            local2Status.setText(Local2Client.get(this).status());
        });
        // Local 3 is a separate self-hosted app channel: no Render/Supabase polling.
        TextView local3Heading=new TextView(this);
        local3Heading.setText("MCP LOCAL 3 · SERVEUR PERSONNEL");
        local3Heading.setTextSize(18);
        local3Heading.setPadding(16,26,16,10);
        group.addView(local3Heading,localInsert++);
        Switch local3=new Switch(this);
        local3.setText("CHK Browser Local 3 : activer / désactiver");
        local3.setTextSize(15);
        local3.setPadding(16,10,16,10);
        local3.setChecked(Local3Client.get(this).enabled());
        group.addView(local3,localInsert++);
        TextView local3Help=new TextView(this);
        local3Help.setText("Serveur sur ton PC, sans requêtes Render ou Supabase. "
            +"Pour la première association, colle l’adresse HTTPS réelle du serveur et crée une invitation avec app_invite dans le connecteur "
            +"CHK Browser Local 3 de ChatGPT, puis colle-la ici. Elle expire après 15 minutes. "
            +"La clé privée de l'application reste uniquement sur ton téléphone. "
            +"Les actions sensibles nécessitent l'approbation prévue sur le serveur.");
        local3Help.setPadding(16,8,16,12);
        group.addView(local3Help,localInsert++);
        Button local3Pair=new Button(this);
        local3Pair.setAllCaps(false);
        local3Pair.setText("Associer Local 3 avec une invitation");
        group.addView(local3Pair,localInsert++);
        local3Status=new TextView(this);
        local3Status.setPadding(16,10,16,12);
        local3Status.setText(Local3Client.get(this).status());
        group.addView(local3Status,localInsert++);
        local3.setOnCheckedChangeListener((b,on)->{
            Local3Client.get(this).setEnabled(on);
            if(on){
                if(Local3Client.get(this).paired())AgentService.ensureRunning(this);
                else Toast.makeText(this,"Associe d'abord le serveur avec une invitation.",Toast.LENGTH_LONG).show();
            }else if(!agent.isEnabled()&&!Local2Client.get(this).enabled())
                stopService(new Intent(this,AgentService.class));
            local3Status.setText(Local3Client.get(this).status());
        });
        local3Pair.setOnClickListener(v->{
            android.widget.LinearLayout fields=new android.widget.LinearLayout(this);
            fields.setPadding(24,10,24,0);
            fields.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.EditText endpoint=new android.widget.EditText(this);
            endpoint.setSingleLine(true);
            endpoint.setHint("https://ton-serveur/mcp");
            endpoint.setText(Local3Client.get(this).serverUrl());
            endpoint.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                |android.text.InputType.TYPE_TEXT_VARIATION_URI);
            fields.addView(endpoint);
            android.widget.EditText invitation=new android.widget.EditText(this);
            invitation.setSingleLine(true);
            invitation.setHint("chkinvite_…");
            fields.addView(invitation);
            new AgentDialog.Builder(this).setTitle("Association sécurisée · Local 3")
                .setMessage("Colle l'adresse HTTPS réelle de ton serveur MCP (ou son lien /mcp), "
                    +"puis l'invitation app_invite à usage unique créée dans ChatGPT.")
                .setView(fields)
                .setNegativeButton("Annuler",null)
                .setPositiveButton("Associer", (dialog,which)->{
                    try {
                        Local3Client.get(this).configureBase(endpoint.getText().toString());
                    } catch(IllegalArgumentException error) {
                        Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();
                        return;
                    }
                    local3Status.setText("Association au serveur personnel en cours…");
                    Local3Client.get(this).pair(invitation.getText().toString(),(ok,message)->{
                        if(isFinishing()||isDestroyed())return;
                        if(ok){
                            local3.setChecked(true);
                            AgentService.ensureRunning(this);
                        }
                        local3Status.setText(Local3Client.get(this).status());
                        Toast.makeText(this,message,Toast.LENGTH_LONG).show();
                    });
                }).show();
        });
        Button publications=new Button(this);
        publications.setText("Publications et campagnes CHK · historique MCP");
        publications.setAllCaps(false);
        publications.setOnClickListener(v->showPublicationJobs());
        group.addView(publications);

        Switch autoUpdate=findViewById(R.id.auto_update);autoUpdate.setChecked(getSharedPreferences("update_monitor",MODE_PRIVATE).getBoolean("enabled",true));autoUpdate.setOnCheckedChangeListener((b,on)->getSharedPreferences("update_monitor",MODE_PRIVATE).edit().putBoolean("enabled",on).apply());
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
            new AgentDialog.Builder(this)
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
                PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
                if(Build.VERSION.SDK_INT>=23&&!power.isIgnoringBatteryOptimizations(getPackageName()))
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:"+getPackageName())));
                else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:"+getPackageName())));
            }catch(Exception e){
                Toast.makeText(this,"Ouvre Paramètres Android > Applications > Batterie > CHK Agent Browser",Toast.LENGTH_LONG).show();
            }
        });
        mcpStatus=findViewById(R.id.mcp_status);
        mcpConnect=findViewById(R.id.mcp_connect);
        mcpCopy=findViewById(R.id.mcp_copy);
        mcpDisable=findViewById(R.id.mcp_disable);
        mcpDisable.setText("Désactiver MCP Render uniquement");
        mcpConnect.setText("Activer / reconnecter MCP Render");
        status=findViewById(R.id.update_status);
        check=findViewById(R.id.check_update);
        install=findViewById(R.id.install_update);
        mcpConnect.setOnClickListener(v->connectMcp());
        mcpCopy.setOnClickListener(v->copyMcp());
        findViewById(R.id.mcp_diagnostic).setOnClickListener(v->
            startActivity(new Intent(this,DiagnosticActivity.class)));
        mcpDisable.setOnClickListener(v->{
            agent.disable();
            if(!Local2Client.get(this).enabled() && !Local3Client.get(this).enabled())stopService(new Intent(this, AgentService.class));
            showMcp();
        });
        check.setOnClickListener(v->checkUpdate());
        install.setOnClickListener(v->confirmInstall());
        findViewById(R.id.clear_history).setOnClickListener(v->
            new AgentDialog.Builder(this)
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
    private void showPublicationJobs(){
        try{
            org.json.JSONArray items=PublicationJobs.command(this,"publication_list",new org.json.JSONObject())
                .getJSONArray("items");
            if(items.length()==0){
                new AgentDialog.Builder(this).setTitle("Publications CHK")
                    .setMessage("Aucune campagne enregistrée. Les agents peuvent préparer une publication avec publication_prepare via les tâches MCP.")
                    .setPositiveButton("Fermer",null).show();
                return;
            }
            String[] labels=new String[items.length()];
            for(int i=0;i<items.length();i++){
                org.json.JSONObject item=items.getJSONObject(i);
                labels[i]=item.optString("platform").toUpperCase(java.util.Locale.ROOT)+" · "
                    +item.optString("status")+"\n"+item.optString("title");
            }
            new AgentDialog.Builder(this).setTitle("Campagnes CHK · "+items.length())
                .setItems(labels,(dialog,index)->{
                    try{
                        String id=items.getJSONObject(index).getString("id");
                        org.json.JSONObject detail=PublicationJobs.command(this,"publication_status",
                            new org.json.JSONObject().put("id",id));
                        String summary="Plateforme : "+detail.optString("platform")
                            +"\nCompte : "+detail.optString("account")
                            +"\nÉtat : "+detail.optString("status")
                            +"\nProgrammation : "+detail.optString("schedule_at","immédiate")
                            +"\nConfirmation du navigateur : "+detail.optBoolean("remote_verified")
                            +"\n\n"+detail.optString("next",detail.optString("proof_message"));
                        new AgentDialog.Builder(this).setTitle(detail.optString("title","Publication"))
                            .setMessage(summary).setPositiveButton("OK",null).show();
                    }catch(Exception error){Toast.makeText(this,"Journal Publication illisible.",Toast.LENGTH_LONG).show();}
                }).setNegativeButton("Fermer",null).show();
        }catch(Exception error){Toast.makeText(this,"Historique Publications indisponible.",Toast.LENGTH_LONG).show();}
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putBoolean("awaiting_install_permission",awaitingInstallPermission);}
    @Override protected void onPause(){refresh.removeCallbacks(statusTick);super.onPause();}
    private void showMcp() {
        boolean enabled=agent.isEnabled(), link=!agent.mcpUrl().isEmpty();
        String message=enabled
            ?(agent.isDirectMode()
                ?"Serveur MCP DIRECT sur Android (aucun appel Render). Mode autonome : "
                    +(agent.isAutonomous()?"ACTIF":"DÉSACTIVÉ")
                    +"\\nTailscale privé : "+DirectMcpServer.tailscaleIp()+":"+DirectMcpServer.PORT
                    +"\\nATTENTION : adresse privée, non accessible au ChatGPT cloud. HTTPS public à configurer."
                :"MCP Render historique activé. Mode autonome : "+(agent.isAutonomous()?"ACTIF":"DÉSACTIVÉ"))
            :"MCP Render désactivé · Local 2 et Local 3 inchangés.";
        if(enabled && Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            message += "\nAutorise les notifications pour voir les demandes de confirmation.";
        }
        if(enabled){
            message+="\n"+agent.connectionStatus();
            if(Build.VERSION.SDK_INT>=23){
                PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
                message+="\nBatterie : "+(power.isIgnoringBatteryOptimizations(getPackageName())
                    ?"connexion protégée de la veille Android":"restriction active — utiliser le bouton Batterie");
            }
        }
        mcpStatus.setText(message);
        mcpCopy.setEnabled(enabled&&link);
        mcpDisable.setEnabled(enabled);
    }
    private void connectMcp() {
        if(agent.isDirectMode()){
            agent.enableDirect();
            AgentService.ensureRunning(this);
            showMcp();
            return;
        }
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
        Toast.makeText(this,agent.isDirectMode()
            ?"Adresse Tailscale privée copiée. Non utilisable depuis ChatGPT cloud sans HTTPS public."
            :"Adresse MCP copiée. Ne la partage pas publiquement.",Toast.LENGTH_LONG).show();
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
                new AgentDialog.Builder(this)
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
    private void showDownloadProgress(){
        if(!check.isEnabled())return;
        String message=updates.downloadProgress();
        if(message==null)return;
        if(message.equals("ready")){if(install.getVisibility()!=View.VISIBLE)showInstall();}
        else status.setText(message);
    }
    private void showInstall() {
        long id=updates.downloadId();
        if(id==validatedDownloadId)return;
        // DownloadManager status is cheap; certificate parsing runs outside the UI.
        if(!"ready".equals(updates.downloadProgress())){install.setVisibility(View.GONE);return;}
        validatedDownloadId=id;install.setVisibility(View.GONE);
        updates.checkCompletedApk(ready->{
            if(isFinishing()||isDestroyed()||id!=updates.downloadId())return;
            install.setVisibility(ready==null?View.GONE:View.VISIBLE);
            status.setText(ready==null?"APK déjà installé ou incompatible. Vérifie les mises à jour pour réessayer.":
                "Mise à jour vérifiée et téléchargée. Appuie sur Installer ; Android demandera confirmation.");
        });
    }
    private void confirmInstall() {
        if(updates.completedApk()==null) {
            status.setText("Pas de mise à jour compatible téléchargée.");
            return;
        }
        new AgentDialog.Builder(this)
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
        refresh.removeCallbacks(statusTick);refresh.post(statusTick);
        if(agent!=null && (agent.isEnabled() || Local2Client.get(this).enabled() || Local3Client.get(this).enabled())) {
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
