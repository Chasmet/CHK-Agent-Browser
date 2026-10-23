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
import org.json.JSONObject;

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
    private DirectMcpServer direct;
    public static boolean directRunning() {
        AgentService running = instance;
        return running != null && running.direct != null && running.direct.listening();
    }
    private boolean deliberateStop=false;
    private volatile boolean foregroundReady;
    private boolean lastRender,lastLocal,lastDirect;
    private String stopReason="fin du service, origine non déterminée";
    private long createdAt;
    private int startCount;
    private boolean sameChannels(){
        return lastRender==AgentClient.get(this).isEnabled()&&lastLocal==Local2Client.get(this).enabled()
            &&lastDirect==AgentClient.get(this).isDirectMode();
    }
    static JSONObject state(Context c){
        JSONObject value=new JSONObject();AgentService service=instance;
        android.content.SharedPreferences p=c.getSharedPreferences("mcp_service_health",0);
        try{value.put("running",service!=null).put("foreground",service!=null&&service.foregroundReady)
            .put("start_commands",service==null?0:service.startCount)
            .put("uptime_ms",service==null?0:SystemClock.elapsedRealtime()-service.createdAt)
            .put("last_stop",p.getString("last_stop","aucun arrêt enregistré"))
            .put("start_error",p.getString("start_error","aucun"))
            .put("next_recovery_at",p.getLong("next_recovery_at",0))
            .put("rapid_stops",p.getInt("rapid_stops",0));}catch(org.json.JSONException ignored){}
        return value;
    }
    static long recoveryDelay(int rapidStops){return Math.min(900000L,60000L << Math.min(4,Math.max(0,rapidStops-1)));}
    public static void reconnect(Context c){
        ensureRunning(c);AgentClient.get(c).reconnect();Local2Client.get(c).reconnect();
    }
    private void resumeEnabledTransports(){
        if(Local2Client.get(this).enabled())Local2Client.get(this).start();
        AgentClient client=AgentClient.get(this);
        if(client.isEnabled()&&!client.isDirectMode()&&!client.serviceModeRunning())client.startServiceMode(this);
        refreshNotification();
    }
    private void networkChanged(){
        AgentClient.get(this).reconnect();
        if(foregroundReady)Local2Client.get(this).reconnect();
    }
    private PowerManager.WakeLock sessionLock;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String networkSignature="";
    private final Handler main=new Handler(android.os.Looper.getMainLooper());
    private final Runnable maintenance=new Runnable(){
        @Override public void run(){
            if(!AgentClient.get(AgentService.this).isEnabled() && !Local2Client.get(AgentService.this).enabled())return;
            maintainSessionLock();
            AgentClient.get(AgentService.this).reconnect();
            if(Local2Client.get(AgentService.this).enabled())Local2Client.get(AgentService.this).start();
            if(SystemClock.elapsedRealtime()-createdAt>=120000L){
                android.content.SharedPreferences p=getSharedPreferences("mcp_service_health",0);
                if(p.getInt("rapid_stops",0)>0)p.edit().putInt("rapid_stops",0).apply();
            }
            refreshNotification();
            main.postDelayed(this,30000L);
        }
    };
    private void maintainSessionLock(){
        if(sessionLock==null)return;
        if(sessionLock.isHeld())sessionLock.release();
        // Bounded and renewed only while the user-enabled foreground session runs.
        AgentClient client=AgentClient.get(this);
        if((client.isEnabled() || Local2Client.get(this).enabled())
            &&(!client.isEcoMode()||client.isExecuting()))sessionLock.acquire(120000L);
    }

    public static void ensureRunning(Context context) {
        if(!AgentClient.get(context).isEnabled() && !Local2Client.get(context).enabled())return;
        AgentService live=instance;
        if(live!=null&&live.foregroundReady&&live.sameChannels()){
            live.main.post(()->{if(instance==live)live.resumeEnabledTransports();});return;
        }
        Intent intent=new Intent(context,AgentService.class);
        try{
            if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);
            else context.startService(intent);
        }catch(RuntimeException failure) {
            String type=failure.getClass().getSimpleName();
            android.content.SharedPreferences p=context.getSharedPreferences("mcp_service_health",0);
            if(!type.equals(p.getString("start_error","")))AgentLog.add(context,"service","démarrage refusé : "+type);
            p.edit().putString("start_error",type).apply();
        }
    }
    public static String latestBackgroundUrl() {
        AgentService running=instance;
        return running!=null&&running.background!=null
            ?running.background.pageUrl():"";
    }
    /**
     * Never navigate a background WebView while switching away from a visible form.
     * Cookies are shared, but form state and document JS are NOT shared across WebViews.
     * Preserve two explicit page snapshots instead of overwriting the agent's session.
     */
    public static void syncVisible(Context context,String url) {
        if(url==null||!url.startsWith("https://"))return;
        android.net.Uri requested=android.net.Uri.parse(url);
        if(!BackgroundBrowser.isPublicHttps(requested))return;
        context.getApplicationContext().getSharedPreferences("browser_mcp_v1",MODE_PRIVATE)
            .edit().putString("last_visible_page",url).apply();
    }
    public static String latestVisibleUrl(Context context) {
        return context.getApplicationContext().getSharedPreferences("browser_mcp_v1",MODE_PRIVATE)
            .getString("last_visible_page","");
    }
    @Override public void onCreate() {
        super.onCreate();
        instance=this;createdAt=SystemClock.elapsedRealtime();
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        sessionLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CHKAgentBrowser:McpSession");
        sessionLock.setReferenceCounted(false);
        connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){
                AgentLog.add(AgentService.this,"réseau","connexion disponible");
                networkChanged();
                refreshNotification();
            }
            @Override public void onLost(Network network){
                AgentLog.add(AgentService.this,"réseau","connexion perdue");
                refreshNotification();
            }
            @Override public void onCapabilitiesChanged(Network network,android.net.NetworkCapabilities caps){
                String signature=network.toString()+":"+caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    +":"+caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                    +":"+caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                if(signature.equals(networkSignature))return;
                networkSignature=signature;
                String type=caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":
                    (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)?"mobile":"autre");
                AgentLog.add(AgentService.this,"réseau","transport actif : "+type);
                networkChanged();
            }
        };
        try{
            if(Build.VERSION.SDK_INT>=24)connectivity.registerDefaultNetworkCallback(networkCallback);
            else connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),networkCallback);
        }
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
        startCount++;
        if(foregroundReady&&sameChannels()&&(intent==null||!ACTION_STOP.equals(intent.getAction()))){
            resumeEnabledTransports();return START_STICKY;
        }
        AgentLog.add(this,"service",foregroundReady?"actualisation des canaux MCP":"démarrage ou reprise du service MCP");
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())) {
            deliberateStop=true;stopReason="arrêt demandé par le propriétaire via notification";
            cancelRecovery();
            Local2Client.get(this).disable();
            AgentLog.add(this,"service",stopReason);
            AgentClient.get(this).disable();
            if(background!=null) {
                AgentClient.get(this).removeBackgroundHandler(background);
                background.destroy();background=null;
            }
            stopForeground(true);stopSelf();
            return START_NOT_STICKY;
        }
        if(!AgentClient.get(this).isEnabled() && !Local2Client.get(this).enabled()){
            deliberateStop=true;stopReason="aucun canal MCP activé";cancelRecovery();
            stopSelf();return START_NOT_STICKY;
        }
        try {
            if(Build.VERSION.SDK_INT>=34)
                startForeground(ID_RUNNING,createOngoingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(ID_RUNNING,createOngoingNotification());
        }catch(RuntimeException failure){
            stopReason="démarrage foreground refusé : "+failure.getClass().getSimpleName();
            getSharedPreferences("mcp_service_health",0).edit().putString("start_error",failure.getClass().getSimpleName()).apply();
            AgentLog.add(this,"service",stopReason);
            stopSelf();
            return START_NOT_STICKY;
        }
        foregroundReady=true;cancelRecovery();
        getSharedPreferences("mcp_service_health",0).edit().putString("start_error","aucun").apply();
        if(background!=null&&!AgentClient.get(this).isEnabled()){
            AgentClient.get(this).removeBackgroundHandler(background);background.destroy();background=null;
        }
        if(background==null && AgentClient.get(this).isEnabled()) {
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
        if (AgentClient.get(this).isEnabled() && AgentClient.get(this).isDirectMode()) {
            if (direct == null) {
                try {
                    direct = new DirectMcpServer(this, new DirectMcpServer.Runner() {
                        @Override public boolean active() {
                            AgentClient c = AgentClient.get(AgentService.this);
                            return c.isEnabled() && c.isDirectMode();
                        }
                        @Override public JSONObject status() {
                            JSONObject state = new JSONObject();
                            try {
                                state.put("online", directRunning());
                                state.put("transport", "private-Tailscale-no-Render");
                                state.put("public_https", false);
                                state.put("cloud_chatgpt_access", false);
                                state.put("autonomous", AgentClient.get(AgentService.this).isAutonomous());
                                state.put("page", latestBackgroundUrl());
                            } catch (org.json.JSONException ignored) {}
                            return state;
                        }
                        @Override public void execute(String action, JSONObject args,
                                                      DirectMcpServer.Reply callback) {
                            AgentClient.get(AgentService.this).executeDirect(action, args, callback);
                        }
                    });
                    direct.start();
                    AgentLog.add(this,"mcp","serveur privé Tailscale sur Android démarré ; 0 appel Render");
                } catch (Exception failure) {
                    if (direct != null) direct.close();
                    direct = null;
                    AgentLog.add(this,"mcp","échec serveur direct : " + failure.getClass().getSimpleName());
                }
            }
        } else if (direct != null) {
            direct.close();
            direct = null;
        }
        if (Local2Client.get(this).enabled()) Local2Client.get(this).start();
        else Local2Client.get(this).stop();
        main.removeCallbacks(maintenance);main.post(maintenance);
        AgentClient.get(this).startServiceMode(this);
        lastRender=AgentClient.get(this).isEnabled();lastLocal=Local2Client.get(this).enabled();lastDirect=AgentClient.get(this).isDirectMode();
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
        String connection = Local2Client.get(this).enabled() ? Local2Client.get(this).status()
            : AgentClient.get(this).connectionStatus();
        return builder(CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_browser)
            .setContentTitle("CHK Agent Browser — MCP actif")
            .setContentText((auto?"Autonome · ":"Confirmation · ")+connection)
            .setContentIntent(openBrowser(31))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Arrêter le MCP",stopAction)
            .build();
    }
    public void refreshNotification() {
        if(notifications!=null&&(AgentClient.get(this).isEnabled() || Local2Client.get(this).enabled())) {
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
    private PendingIntent recoveryIntent(){
        Intent intent=new Intent(this,AgentRestartReceiver.class).setAction(AgentRestartReceiver.ACTION_RECOVER);
        return PendingIntent.getBroadcast(this,490,intent,PendingIntent.FLAG_UPDATE_CURRENT|
            (Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
    }
    private void cancelRecovery(){
        try{((AlarmManager)getSystemService(ALARM_SERVICE)).cancel(recoveryIntent());}catch(RuntimeException ignored){}
        getSharedPreferences("mcp_service_health",0).edit().putLong("next_recovery_at",0).apply();
    }
    private void scheduleRecovery() {
        if(!AgentClient.get(this).isEnabled() && !Local2Client.get(this).enabled())return;
        try {
            AlarmManager alarms=(AlarmManager)getSystemService(ALARM_SERVICE);
            PendingIntent pending=recoveryIntent();
            android.content.SharedPreferences p=getSharedPreferences("mcp_service_health",0);
            long delay=recoveryDelay(p.getInt("rapid_stops",0));
            long next=SystemClock.elapsedRealtime()+delay;
            p.edit().putLong("next_recovery_at",System.currentTimeMillis()+delay).apply();
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
        foregroundReady=false;
        android.content.SharedPreferences health=getSharedPreferences("mcp_service_health",0);
        long now=SystemClock.elapsedRealtime(),previous=health.getLong("last_destroy_elapsed",-1);
        int rapid=previous>=0&&now>=previous&&now-previous<120000L?Math.min(10,health.getInt("rapid_stops",0)+1):1;
        health.edit().putString("last_stop",stopReason).putLong("last_destroy_elapsed",now)
            .putInt("rapid_stops",deliberateStop?0:rapid).apply();
        main.removeCallbacksAndMessages(null);
        if(sessionLock!=null&&sessionLock.isHeld())sessionLock.release();
        if(connectivity!=null&&networkCallback!=null){
            try{connectivity.unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}
        }
        if (direct != null) {
            direct.close();
            direct = null;
        }
        Local2Client.get(this).stop();
        AgentClient.get(this).stopServiceMode();
        if(background!=null){
            AgentClient.get(this).removeBackgroundHandler(background);
            background.destroy();background=null;
        }
        if(notifications!=null)notifications.cancel(ID_APPROVAL);
        if(!deliberateStop){
            AgentLog.add(this,"service",stopReason+" ; reprise demandée dans "+(recoveryDelay(rapid)/1000)+" s minimum");
            scheduleRecovery();
        }
        else cancelRecovery();
        if(instance==this)instance=null;
        super.onDestroy();
    }
}
