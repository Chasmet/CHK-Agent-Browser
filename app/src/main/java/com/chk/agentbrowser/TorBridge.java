package com.chk.agentbrowser;

import android.app.ActivityManager;
import android.content.*;
import android.os.*;
import android.os.Process;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Private Messenger bridge. The regular browser process never loads Tor or changes its proxy. */
final class TorBridge {
    interface Result {void done(boolean ok,JSONObject value);}
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final Map<String,Result> PENDING=new HashMap<>();
    private static final Map<String,Runnable> TIMEOUTS=new HashMap<>();
    private static Messenger remote;
    private static boolean binding;
    private static final ArrayList<Runnable> READY=new ArrayList<>();
    private static final Messenger REPLIES=new Messenger(new Handler(Looper.getMainLooper(),message->{
        Bundle b=message.getData();String id=b.getString("id","");try{complete(id,b.getBoolean("ok"),new JSONObject(b.getString("json","{}")));}catch(Exception e){complete(id,false,error("TOR_IPC_INVALID_RESPONSE"));}return true;
    }));
    private static final ServiceConnection CONNECTION=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder service){remote=new Messenger(service);binding=false;ArrayList<Runnable> waiting=new ArrayList<>(READY);READY.clear();for(Runnable r:waiting)r.run();}
        public void onServiceDisconnected(ComponentName n){lost();}
        public void onBindingDied(ComponentName n){lost();}
        public void onNullBinding(ComponentName n){lost();}
    };
    private TorBridge(){}
    static JSONObject error(String reason){try{return new JSONObject().put("state","failed").put("error",reason).put("outcome_confirmed",false);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static void lost(){remote=null;binding=false;READY.clear();for(String id:new ArrayList<>(PENDING.keySet()))complete(id,false,error("TOR_PROCESS_EXITED_OR_DISCONNECTED"));}
    private static void complete(String id,boolean ok,JSONObject value){Result cb=PENDING.remove(id);Runnable timeout=TIMEOUTS.remove(id);if(timeout!=null)MAIN.removeCallbacks(timeout);if(cb!=null)cb.done(ok,value);}
    static void request(Context c,JSONObject args,Result cb){
        Context app=c.getApplicationContext();MAIN.post(()->{
            if(Build.VERSION.SDK_INT<29){cb.done(false,error("TOR_INTEGRATED_REQUIRES_ANDROID_10"));return;}
            if(args.optString("operation").equals("disconnect")){disconnect(app,args,cb);return;}
            String id=UUID.randomUUID().toString();PENDING.put(id,cb);
            long delay=args.optString("operation").equals("connect")?300000:args.optString("operation").equals("navigate")?95000:45000;
            Runnable timeout=()->complete(id,false,error("TOR_OPERATION_TIMED_OUT: relire l'état avant de répéter"));TIMEOUTS.put(id,timeout);MAIN.postDelayed(timeout,delay);
            Runnable send=()->{try{if(!PENDING.containsKey(id))return;Message m=Message.obtain(null,1);Bundle b=new Bundle();b.putString("id",id);b.putString("json",args.toString());m.setData(b);m.replyTo=REPLIES;remote.send(m);}catch(Exception e){complete(id,false,error("TOR_IPC_SEND_FAILED"));}};
            if(remote!=null){send.run();return;}READY.add(send);
            if(!binding){binding=true;try{if(!app.bindService(new Intent(app,TorRuntimeService.class),CONNECTION,Context.BIND_AUTO_CREATE)){binding=false;READY.clear();complete(id,false,error("TOR_SERVICE_UNAVAILABLE"));}}catch(Exception e){lost();}}
        });
    }
    private static void disconnect(Context app,JSONObject args,Result cb){
        request(app,new JSONObject(),(ok,state)->{
            if(!ok){cb.done(false,state);return;}
            if(args.has("session_id")&&!args.optString("session_id").equals(state.optString("session_id"))){cb.done(false,error("TOR_SESSION_MISMATCH"));return;}
            int pid=state.optInt("engine_process_id",-1),http=state.optInt("http_tunnel_port"),socks=state.optInt("socks_port");
            IO.execute(()->{try{
                ActivityManager am=(ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE);
                if(pid<=0||pid==Process.myPid()||!isTorProcess(am,app,pid))throw new Exception("TOR_PROCESS_IDENTITY_UNCONFIRMED");
                // Kill only our own dedicated :tor process. This also closes every native Tor socket,
                // even if the Tor control connection is unresponsive. The normal browser stays alive.
                Process.killProcess(pid);long until=SystemClock.elapsedRealtime()+10000;
                while(SystemClock.elapsedRealtime()<until&&(isTorProcess(am,app,pid)||listening(http)||listening(socks)))Thread.sleep(100);
                if(isTorProcess(am,app,pid)||listening(http)||listening(socks))throw new Exception("TOR_DISCONNECT_NOT_CONFIRMED");
                JSONObject result=new JSONObject().put("state","completed").put("connection_state","disconnected").put("tor_connected",false).put("tor_verified",false).put("process_stopped",true).put("listeners_closed",true).put("verified_disconnect",true);
                MAIN.post(()->{remote=null;binding=false;try{app.unbindService(CONNECTION);}catch(Exception ignored){}cb.done(true,result);});
            }catch(Exception e){MAIN.post(()->cb.done(false,error(e.getMessage())));}});
        });
    }
    private static boolean isTorProcess(ActivityManager am,Context c,int pid){List<ActivityManager.RunningAppProcessInfo> list=am.getRunningAppProcesses();if(list!=null)for(ActivityManager.RunningAppProcessInfo p:list)if(p.pid==pid&&p.uid==Process.myUid()&&(c.getPackageName()+":tor").equals(p.processName))return true;return false;}
    private static boolean listening(int port){if(port<=0)return false;try(java.net.Socket s=new java.net.Socket()){s.connect(new InetSocketAddress("127.0.0.1",port),150);return true;}catch(Exception e){return false;}}
}
