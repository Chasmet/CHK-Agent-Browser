package com.chk.agentbrowser;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Tracks CHK's own screens only. This is not an Android keyboard or accessibility service. */
public final class AgentApplication extends Application {
    @Override public void onCreate(){
        super.onCreate();
        if(android.os.Build.VERSION.SDK_INT>=28&&getProcessName().endsWith(":tor")){
            // Must precede any WebView/CookieManager access. Never start MCP or the normal browser here.
            android.webkit.WebView.setDataDirectorySuffix("chk_tor");
            return;
        }
        WorkspaceCommands.IO.execute(()->ServiceDiagnostics.captureProcessExit(this));
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
            public void onActivityResumed(Activity a){NativeKeyboard.resumed(a);AgentClient.get(a).nativeScreenChanged();AgentService.ensureRunning(a);}
            public void onActivityPaused(Activity a){NativeKeyboard.paused(a);}
            public void onActivityDestroyed(Activity a){NativeKeyboard.destroyed(a);}
            public void onActivityCreated(Activity a,Bundle s){}
            public void onActivityStarted(Activity a){}
            public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle s){}
        });
    }
}
