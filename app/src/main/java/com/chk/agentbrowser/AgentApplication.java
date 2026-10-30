package com.chk.agentbrowser;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Tracks CHK's own screens only. This is not an Android keyboard or accessibility service. */
public final class AgentApplication extends Application {
    @Override public void onCreate(){
        super.onCreate();
        // The offline translation worker is a separate private process. It must not
        // start the normal browser, MCP agent or other background services.
        if(android.os.Build.VERSION.SDK_INT>=28&&getProcessName().endsWith(":tortranslate")){
            // Android starts ML Kit's default InitProvider only in CHK's main
            // process. The separate private translation worker has no provider
            // of its own and must initialize ML Kit before checking/downloaded
            // models or creating an offline Translator. No browser, Tor proxy
            // or MCP service is initialized in this process.
            try{
                com.google.mlkit.common.MlKit.initialize(this);
            }catch(IllegalStateException error){
                // Some Android distributions may have initialized this process
                // already. Only that benign duplicate is ignored.
                String reason=error.getMessage();
                if(reason==null||!reason.contains("already initialized"))throw error;
            }
            return;
        }
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
