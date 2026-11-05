package com.chk.agentbrowser;

import android.app.*;
import android.content.*;
import android.os.*;

/** A visible stop action and a bounded wake lock protect the agent's own recording. */
public final class BrowserRecordingService extends Service {
    private PowerManager.WakeLock wake;
    static void start(Context context){
        Intent intent=new Intent(context,BrowserRecordingService.class);
        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);else context.startService(intent);
    }
    static void finish(Context context){context.stopService(new Intent(context,BrowserRecordingService.class));}
    @Override public void onCreate(){
        super.onCreate();
        if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("agent_recording","Enregistrement de l'agent",NotificationManager.IMPORTANCE_LOW));
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,getPackageName()+":agent-recording");
        wake.acquire(11*60*1000L);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"stop".equals(intent.getAction())){BrowserRecording.stopFromNotification();return START_NOT_STICKY;}
        PendingIntent stop=PendingIntent.getService(this,71,new Intent(this,BrowserRecordingService.class).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"agent_recording"):new Notification.Builder(this);
        startForeground(71,builder.setSmallIcon(R.drawable.ic_browser).setContentTitle("CHK · l'agent enregistre son onglet")
            .setContentText("Images de l'onglet CHK · arrêt disponible ici").setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause,"Arrêter",stop).build());
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
}
