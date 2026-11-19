package com.chk.agentbrowser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.chk.agentbrowser.nativeview.EngineClient;

/** Owner-granted, non-persistent Android display control (20 minutes maximum). */
final class NativeDisplayAccess {
    private static final long MAX_SESSION_MS=20L*60*1000;
    private static final Handler UI=new Handler(Looper.getMainLooper());
    private static volatile long expiresAt;
    private static Runnable expiry;
    private NativeDisplayAccess(){}
    static boolean agentAllowed(){
        long end=expiresAt;
        return end>0 && SystemClock.elapsedRealtime()<end;
    }
    static synchronized void allowAgent(Context context) {
        expiresAt=SystemClock.elapsedRealtime()+MAX_SESSION_MS;
        if(expiry!=null)UI.removeCallbacks(expiry);
        Context app=context.getApplicationContext();
        expiry=()->{
            expiresAt=0;
            WorkspaceCommands.IO.execute(()->{
                EngineClient client=EngineClient.get(app);
                if(client.isServiceConnected())client.releaseDisplay();
            });
        };
        UI.postDelayed(expiry,MAX_SESSION_MS);
    }
    static synchronized void stopAgent(Context context) {
        expiresAt=0;
        if(expiry!=null)UI.removeCallbacks(expiry);
        expiry=null;
        Context app=context.getApplicationContext();
        WorkspaceCommands.IO.execute(()->{
            EngineClient client=EngineClient.get(app);
            if(client.isServiceConnected())client.releaseDisplay();
        });
    }
}
