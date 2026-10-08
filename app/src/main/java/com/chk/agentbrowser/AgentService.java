package com.chk.agentbrowser;

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

/**
 * User-started foreground data-sync service. Network polling survives switching to ChatGPT.
 * Never executes JavaScript in a hidden WebView, and never opens activities from background.
 * A pending action is exposed through a notification that opens the browser for approval.
 */
public final class AgentService extends Service implements AgentClient.IncomingListener {
    public static final String ACTION_STOP = "com.chk.agentbrowser.STOP_AGENT";
    private static final String CHANNEL_RUNNING = "mcp_connection";
    private static final String CHANNEL_APPROVAL = "mcp_approval";
    private static final int ID_RUNNING = 420;
    private static final int ID_APPROVAL = 421;
    private NotificationManager notifications;

    /** Only call from a foreground Activity after the user enabled MCP. */
    public static void ensureRunning(Context context) {
        if(!AgentClient.get(context).isEnabled()) return;
        Intent intent = new Intent(context, AgentService.class);
        try {
            if(Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch(RuntimeException ignored) {
            // OS can deny starts when launched from background.
            // The user can reopen the app and retry in Settings.
        }
    }
    public static void requestStop(Context context) {
        Intent intent = new Intent(context,AgentService.class);
        intent.setAction(ACTION_STOP);
        try {context.startService(intent);}catch(RuntimeException ignored){}
    }
    @Override public void onCreate() {
        super.onCreate();
        notifications = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT >= 26) {
            NotificationChannel running = new NotificationChannel(CHANNEL_RUNNING,
                "Connexion MCP active", NotificationManager.IMPORTANCE_LOW);
            running.setDescription("Maintient le navigateur agentique connecté à ChatGPT");
            notifications.createNotificationChannel(running);
            NotificationChannel approval = new NotificationChannel(CHANNEL_APPROVAL,
                "Commandes MCP à confirmer", NotificationManager.IMPORTANCE_HIGH);
            approval.setDescription("Appuyer pour autoriser ou refuser une action demandée par ChatGPT");
            notifications.createNotificationChannel(approval);
        }
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent != null && ACTION_STOP.equals(intent.getAction())) {
            AgentClient.get(this).disable();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if(!AgentClient.get(this).isEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification ongoing = createOngoingNotification();
        if(Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_RUNNING,ongoing,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID_RUNNING,ongoing);
        }
        AgentClient.get(this).startServiceMode(this);
        return START_STICKY;
    }
    private PendingIntent openBrowser(int code) {
        Intent open = new Intent(this,MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this,code,open,PendingIntent.FLAG_UPDATE_CURRENT |
            (Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
    }
    private Notification.Builder builder(String channel) {
        if(Build.VERSION.SDK_INT>=26) return new Notification.Builder(this,channel);
        return new Notification.Builder(this);
    }
    private Notification createOngoingNotification() {
        Intent stop = new Intent(this,AgentService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this,30,stop,
            PendingIntent.FLAG_UPDATE_CURRENT |
            (Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        return builder(CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_browser)
            .setContentTitle("CHK Agent Browser — MCP actif")
            .setContentText("En ligne via Render. Les actions exigent ton accord.")
            .setContentIntent(openBrowser(31))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Arrêter MCP",stopAction)
            .build();
    }
    @Override public void waitingForApproval(String action) {
        Notification alert=builder(CHANNEL_APPROVAL)
            .setSmallIcon(R.drawable.ic_browser)
            .setContentTitle("ChatGPT demande une action")
            .setContentText("Ouvre CHK Agent Browser pour autoriser ou refuser.")
            .setContentIntent(openBrowser(32))
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build();
        notifications.notify(ID_APPROVAL,alert);
    }
    @Override public void approvalFinished() {
        notifications.cancel(ID_APPROVAL);
    }
    @Override public IBinder onBind(Intent intent) {return null;}
    @Override public void onDestroy() {
        AgentClient.get(this).stopServiceMode();
        notifications.cancel(ID_APPROVAL);
        super.onDestroy();
    }
}
