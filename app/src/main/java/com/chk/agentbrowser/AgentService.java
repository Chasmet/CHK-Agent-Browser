package com.chk.agentbrowser;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Handler;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.SystemClock;

/**
 * A user-enabled persistent MCP foreground service. Background WebView actions
 * run only while autonomous mode is enabled in Settings. Android can still
 * suspend network access while the device is sleeping or power saving.
 */
public final class AgentService extends Service implements AgentClient.IncomingListener {
    public static final String ACTION_STOP="com.chk.agentbrowser.STOP_AGENT";
    private static final String CHANNEL_RUNNING="mcp_connection";
    private static final String CHANNEL_APPROVAL="mcp_approval";
    private static final int ID_RUNNING=420;
    private static final int ID_APPROVAL=421;
    private static volatile AgentService instance;
    private NotificationManager notifications;
    private BackgroundBrowser background;
    private boolean deliberateStop=false;
    private PowerManager.WakeLock sessionLock;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Handler main=new Handler(android.os.Looper.getMainLooper());
    private final Runnable maintenance=new Runnable(){
        @Override public void run(){
            if(!AgentClient.get(AgentService.this).isEnabled())return;
            maintainSessionLock();
            AgentClient.get(AgentService.this).reconnect();
            refreshNotification();
            main.postDelayed(this,30000L);
        }
    };
    private void maintainSessionLock(){
        if(sessionLock==null)return;
        if(sessionLock.isHeld())sessionLock.release();
        // Bounded and renewed only while the user-enabled foreground session runs.
        if(AgentClient.get(this).isEnabled())sessionLock.acquire(120000L);
    }

    public static void ensureRunning(Context context) {
        if(!AgentClient.get(context).isEnabled())return;
        Intent intent=new Intent(context,AgentService.class);
        try{
            if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);
            else context.startService(intent);
        }catch(RuntimeException ignored) {
            // Foreground-service starts from background can be blocked by Android.
        }
    }
    public static String latestBackgroundUrl() {
        AgentService running=instance;
        return running!=null&&running.background!=null
            ?running.background.pageUrl():"";
    }
    public static void syncVisible(Context context,String url) {
        if(url==null||!url.startsWith("https://"))return;
        context.getApplicationContext().getSharedPreferences("browser_mcp_v1",MODE_PRIVATE)
            .edit().putString("last_page",url).apply();
        AgentService running=instance;
        if(running!=null&&running.background!=null)
            running.background.syncFromVisible(url);
    }
    @Override public void onCreate() {
        super.onCreate();
        instance=this;
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        sessionLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CHKAgentBrowser:McpSession");
        sessionLock.setReferenceCounted(false);
        connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){
                AgentLog.add(AgentService.this,"réseau","connexion disponible");
                AgentClient.get(AgentService.this).reconnect();
                refreshNotification();
            }
            @Override public void onLost(Network network){
                AgentLog.add(AgentService.this,"réseau","connexion perdue");
                refreshNotification();
            }
            @Override public void onCapabilitiesChanged(Network network,android.net.NetworkCapabilities caps){
                String type=caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":
                    (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)?"mobile":"autre");
                AgentLog.add(AgentService.this,"réseau","transport actif : "+type);
                AgentClient.get(AgentService.this).reconnect();
            }
        };
        try{connectivity.registerNetworkCallback(new NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),networkCallback);}
        catch(RuntimeException ignored){networkCallback=null;}
        notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel running=new NotificationChannel(CHANNEL_RUNNING,
                "Connexion MCP active",NotificationManager.IMPORTANCE_LOW);
            running.setDescription("Le service réseau MCP reste actif pendant ChatGPT");
            notifications.createNotificationChannel(running);
            NotificationChannel confirmation=new NotificationChannel(CHANNEL_APPROVAL,
                "Commande MCP en attente",NotificationManager.IMPORTANCE_HIGH);
            confirmation.setDescription("Ouvrir le navigateur pour confirmer si le mode autonome est désactivé");
            notifications.createNotificationChannel(confirmation);
        }
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        AgentLog.add(this,"service","démarrage ou reprise du service MCP");
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())) {
            deliberateStop=true;
            AgentLog.add(this,"service","arrêt demandé par le propriétaire");
            AgentClient.get(this).disable();
            if(background!=null) {
                AgentClient.get(this).removeBackgroundHandler(background);
                background.destroy();background=null;
            }
            stopForeground(true);stopSelf();
            return START_NOT_STICKY;
        }
        if(!AgentClient.get(this).isEnabled()){
            deliberateStop=true;
            stopSelf();return START_NOT_STICKY;
        }
        try {
            if(Build.VERSION.SDK_INT>=34)
                startForeground(ID_RUNNING,createOngoingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(ID_RUNNING,createOngoingNotification());
        }catch(RuntimeException failure){
            AgentLog.add(this,"service","échec foreground : "+failure.getClass().getSimpleName());
            scheduleRecovery();
            stopSelf();
            return START_NOT_STICKY;
        }
        if(background==null) {
            try {
                background=new BackgroundBrowser(this);
                background.restore();
                AgentClient.get(this).setBackgroundHandler(background);
            }catch(RuntimeException failure) {
                AgentLog.add(this,"webview","moteur autonome indisponible : "+failure.getClass().getSimpleName());
                // Keep network connectivity even on a device missing WebView.
                background=null;
            }
        }
        main.removeCallbacks(maintenance);main.post(maintenance);
        AgentClient.get(this).startServiceMode(this);
        return START_STICKY;
    }
    private PendingIntent openBrowser(int code) {
        Intent open=new Intent(this,MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this,code,open,PendingIntent.FLAG_UPDATE_CURRENT|
            (Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
    }
    private Notification.Builder builder(String channel) {
        return Build.VERSION.SDK_INT>=26?
            new Notification.Builder(this,channel):new Notification.Builder(this);
    }
    private Notification createOngoingNotification() {
        Intent stop=new Intent(this,AgentService.class).setAction(ACTION_STOP);
        PendingIntent stopAction=PendingIntent.getService(this,30,stop,
            PendingIntent.FLAG_UPDATE_CURRENT|
            (Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        boolean auto=AgentClient.get(this).isAutonomous();
        return builder(CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_browser)
            .setContentTitle("CHK Agent Browser — MCP actif")
            .setContentText((auto?"Autonome · ":"Confirmation · ")+AgentClient.get(this).connectionStatus())
            .setContentIntent(openBrowser(31))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Arrêter le MCP",stopAction)
            .build();
    }
    public void refreshNotification() {
        if(notifications!=null&&AgentClient.get(this).isEnabled()) {
            notifications.notify(ID_RUNNING,createOngoingNotification());
        }
    }
    public static void refresh(Context context) {
        AgentService running=instance;
        if(running!=null)running.refreshNotification();
    }
    @Override public void waitingForApproval(String action) {
        if(AgentClient.get(this).isAutonomous())return;
        Notification alert=builder(CHANNEL_APPROVAL)
            .setSmallIcon(R.drawable.ic_browser)
            .setContentTitle("Commande ChatGPT en attente")
            .setContentText("Ouvre CHK Agent Browser pour valider ou activer le mode autonome.")
            .setContentIntent(openBrowser(32)).setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build();
        notifications.notify(ID_APPROVAL,alert);
    }
    @Override public void approvalFinished() {
        notifications.cancel(ID_APPROVAL);
    }
    private void scheduleRecovery() {
        if(!AgentClient.get(this).isEnabled())return;
        try {
            AlarmManager alarms=(AlarmManager)getSystemService(ALARM_SERVICE);
            Intent intent=new Intent(this,AgentRestartReceiver.class)
                .setAction(AgentRestartReceiver.ACTION_RECOVER);
            PendingIntent pending=PendingIntent.getBroadcast(this,490,intent,
                PendingIntent.FLAG_UPDATE_CURRENT|
                (Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
            long next=SystemClock.elapsedRealtime()+60000L;
            if(Build.VERSION.SDK_INT>=23)alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,next,pending);
            else alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,next,pending);
        }catch(RuntimeException ignored){}
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        AgentLog.add(this,"service","tâche retirée par Android, reprise planifiée");
        super.onTaskRemoved(rootIntent);
        scheduleRecovery();
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if(sessionLock!=null&&sessionLock.isHeld())sessionLock.release();
        if(connectivity!=null&&networkCallback!=null){
            try{connectivity.unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}
        }
        AgentClient.get(this).stopServiceMode();
        if(background!=null){
            AgentClient.get(this).removeBackgroundHandler(background);
            background.destroy();background=null;
        }
        if(notifications!=null)notifications.cancel(ID_APPROVAL);
        if(!deliberateStop){
            AgentLog.add(this,"service","service détruit par Android, reprise planifiée");
            scheduleRecovery();
        }
        instance=null;
        super.onDestroy();
    }
}
