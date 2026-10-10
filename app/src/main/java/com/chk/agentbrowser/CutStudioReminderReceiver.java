package com.chk.agentbrowser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.json.JSONObject;

/** Les échéances préviennent l'utilisateur ; aucun réseau n'est publié sans confirmation. */
public final class CutStudioReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL="cut_studio_planning";
    @Override public void onReceive(Context context,Intent intent){
        String action=intent==null?"":intent.getAction();
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)){
            CutStudioStore.reschedule(context);return;
        }
        String id=intent==null||intent.getData()==null?"":intent.getData().getLastPathSegment();
        if(id==null||id.isEmpty())return;
        JSONObject found=null;
        for(JSONObject item:CutStudioStore.list(context))
            if(id.equals(item.optString("id"))){found=item;break;}
        if(found==null||found.optBoolean("published"))return;
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager==null)return;
        if(Build.VERSION.SDK_INT>=26)
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Programmations Cut",NotificationManager.IMPORTANCE_HIGH));
        Intent open=new Intent(context,CutStudioActivity.class).putExtra("schedule_id",id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending=PendingIntent.getActivity(context,id.hashCode(),open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26
            ?new Notification.Builder(context,CHANNEL):new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Cut · publication à effectuer")
            .setContentText(found.optString("platform").toUpperCase(java.util.Locale.ROOT)+" · "+found.optString("title"))
            .setAutoCancel(true).setContentIntent(pending);
        try{manager.notify(id.hashCode(),builder.build());}catch(SecurityException ignored){}
    }
}
