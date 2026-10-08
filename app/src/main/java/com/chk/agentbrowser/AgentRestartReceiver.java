package com.chk.agentbrowser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Best-effort MCP recovery after reboot, APK replacement, or Android task removal.
 * Background service launch restrictions can still require opening the app manually.
 */
public final class AgentRestartReceiver extends BroadcastReceiver {
    public static final String ACTION_RECOVER="com.chk.agentbrowser.RECOVER_MCP";
    @Override public void onReceive(Context context,Intent intent) {
        if(intent==null||!AgentClient.get(context).isEnabled())return;
        String action=intent.getAction();
        if(Intent.ACTION_BOOT_COMPLETED.equals(action) ||
           Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
           ACTION_RECOVER.equals(action)){
            AgentService.ensureRunning(context);
        }
    }
}
