package com.chk.agentbrowser;

import android.content.BroadcastReceiver;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.app.DownloadManager;
import android.os.Build;
import java.io.File;

/** Native-download completion: notify without opening an installer from background. */
public final class UpdateReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "chk_browser_updates";
    public static final int NOTIFICATION_ID = 510;
    @Override public void onReceive(Context context, Intent intent) {
        if(intent==null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction()))
            return;
        long id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1);
        final PendingResult pending=goAsync();
        Context app=context.getApplicationContext();
        new Thread(() -> {
            try {
                UpdateManager updater=new UpdateManager(app);
                if(!updater.isOurDownload(id))return;
                File apk=updater.completedApk();
                if(apk==null)return;
                NotificationManager manager=(NotificationManager)app.getSystemService(Context.NOTIFICATION_SERVICE);
                if(Build.VERSION.SDK_INT>=26) {
                    NotificationChannel channel=new NotificationChannel(CHANNEL,
                        "Mises à jour du navigateur",NotificationManager.IMPORTANCE_HIGH);
                    channel.setDescription("Installation des nouvelles versions signées sur ce téléphone");
                    manager.createNotificationChannel(channel);
                }
                Intent open=new Intent(app,SettingsActivity.class);
                open.setAction(UpdateManager.ACTION_INSTALL_READY);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent tap=PendingIntent.getActivity(app,NOTIFICATION_ID,open,
                    PendingIntent.FLAG_UPDATE_CURRENT |
                    (Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
                Notification.Builder builder=Build.VERSION.SDK_INT>=26 ?
                    new Notification.Builder(app,CHANNEL):new Notification.Builder(app);
                Notification notification=builder
                    .setSmallIcon(R.drawable.ic_browser)
                    .setContentTitle("Mise à jour prête")
                    .setContentText("Appuie pour installer directement sur ce téléphone.")
                    .setContentIntent(tap).setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .build();
                manager.notify(NOTIFICATION_ID,notification);
            }catch(Exception ignored){}
            finally{pending.finish();}
        },"chk-apk-download-completed").start();
    }
}
