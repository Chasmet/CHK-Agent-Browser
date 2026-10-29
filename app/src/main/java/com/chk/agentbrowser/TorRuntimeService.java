package com.chk.agentbrowser;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.JSONObject;

/** Only this process and its Tor WebView can access the embedded engine and its proxy. */
@androidx.annotation.RequiresApi(29)
public final class TorRuntimeService extends Service {
    private final Messenger receiver=new Messenger(new Handler(Looper.getMainLooper(),message->{
        Bundle b=message.getData();String id=b.getString("id","");Messenger reply=message.replyTo;
        try{TorRuntime.get(this).command(new JSONObject(b.getString("json","{}")),(ok,value)->{try{Bundle result=new Bundle();result.putString("id",id);result.putBoolean("ok",ok);result.putString("json",value.toString());Message answer=Message.obtain(null,1);answer.setData(result);reply.send(answer);}catch(Exception ignored){}});}catch(Exception e){try{Message answer=Message.obtain(null,1);Bundle result=new Bundle();result.putString("id",id);result.putString("json",TorBridge.error(e.getMessage()).toString());answer.setData(result);reply.send(answer);}catch(Exception ignored){}}return true;
    }));
    @Override public IBinder onBind(Intent intent){return receiver.getBinder();}
    @Override public void onCreate(){super.onCreate();TorRuntime.get(this);}
    void activate(){
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("chk_tor","Espace Tor indépendant",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,TorEntryActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,"chk_tor").setSmallIcon(com.chk.agentbrowser.R.drawable.ic_nav_tor).setContentTitle("CHK · Espace Tor").setContentText("Espace indépendant · consulter l’état dans CHK").setOngoing(true).setContentIntent(open).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(731,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(731,n);
    }
}
