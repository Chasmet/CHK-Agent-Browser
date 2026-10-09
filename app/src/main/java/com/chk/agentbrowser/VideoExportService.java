package com.chk.agentbrowser;
import android.app.*;
import android.content.*;
import android.os.*;

/** A visible, cancellable foreground render keeps manual exports alive with the screen off. */
public final class VideoExportService extends Service {
    private final Handler handler=new Handler(Looper.getMainLooper());private PowerManager.WakeLock wake;private long renewed;private static volatile boolean active;
    public static boolean start(Context c){try{Intent intent=new Intent(c,VideoExportService.class);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(intent);else c.startService(intent);return true;}catch(RuntimeException ex){AgentLog.add(c,"video","Protection export indisponible : "+ex.getClass().getSimpleName());return false;}}
    public static boolean active(){return active;}
    @Override public void onCreate(){super.onCreate();active=true;if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=new NotificationChannel("video_render","Exports vidéo",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager.class).createNotificationChannel(channel);}wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,getPackageName()+":video-export");}
    @Override public int onStartCommand(Intent intent,int flags,int id){if(intent!=null&&"cancel".equals(intent.getAction())){VideoEditorEngine.get().command(this,"video_editor_cancel",new org.json.JSONObject(),(ok,res)->{});stopSelf();return START_NOT_STICKY;}startForeground(42,notification(0));handler.removeCallbacks(tick);handler.post(tick);return START_NOT_STICKY;}
    private final Runnable tick=new Runnable(){public void run(){org.json.JSONObject status=VideoEditorEngine.get().status();String state=status.optString("state");if(!state.equals("running")&&!state.equals("preparing")){stopSelf();return;}long now=SystemClock.elapsedRealtime();if(now-renewed>5*60*1000||!wake.isHeld()){if(wake.isHeld())wake.release();wake.acquire(10*60*1000L);renewed=now;}((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(42,notification(status.optInt("progress_percent")));handler.postDelayed(this,1000);}};
    private Notification notification(int progress){int flags=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE;PendingIntent open=PendingIntent.getActivity(this,42,new Intent(this,VideoEditorActivity.class),flags);PendingIntent cancel=PendingIntent.getService(this,43,new Intent(this,VideoExportService.class).setAction("cancel"),flags);Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"video_render"):new Notification.Builder(this);return builder.setSmallIcon(R.drawable.ic_browser).setContentTitle("Export vidéo CHK").setContentText("MP4 · "+progress+" %").setContentIntent(open).setOngoing(true).setProgress(100,progress,progress==0).addAction(new Notification.Action.Builder(null,"Annuler",cancel).build()).build();}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){active=false;handler.removeCallbacksAndMessages(null);if(wake!=null&&wake.isHeld())wake.release();stopForeground(true);super.onDestroy();}
}
