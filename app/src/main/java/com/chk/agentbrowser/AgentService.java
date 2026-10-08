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
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())) {
            deliberateStop=true;
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
            if(Build.VERSION.SDK_INT>=29)
                startForeground(ID_RUNNING,createOngoingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID_RUNNING,createOngoingNotification());
        }catch(RuntimeException failure){
            stopSelf();
            return START_NOT_STICKY;
        }
        if(background==null) {
            try {
                background=new BackgroundBrowser(this);
                background.restore();
                AgentClient.get(this).setBackgroundHandler(background);
            }catch(RuntimeException failure) {
                // Keep network connectivity even on a device missing WebView.
                background=null;
            }
        }
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
            .setContentText(auto?"Mode autonome actif · toucher pour ouvrir":"Contrôle avec confirmation · toucher pour ouvrir")
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
        super.onTaskRemoved(rootIntent);
        scheduleRecovery();
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy() {
        AgentClient.get(this).stopServiceMode();
        if(background!=null){
            AgentClient.get(this).removeBackgroundHandler(background);
            background.destroy();background=null;
        }
        if(notifications!=null)notifications.cancel(ID_APPROVAL);
        if(!deliberateStop)scheduleRecovery();
        instance=null;
        super.onDestroy();
    }
}
