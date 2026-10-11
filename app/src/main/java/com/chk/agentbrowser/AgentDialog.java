package com.chk.agentbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;

/** Public Android API: make CHK dialogs available to the same agent as its activities. */
final class AgentDialog {
    private AgentDialog(){}
    static <T extends android.app.Dialog> T watch(T dialog){
        Context c=dialog.getContext();
        while(c instanceof android.content.ContextWrapper&&!(c instanceof Activity))c=((android.content.ContextWrapper)c).getBaseContext();
        if(c instanceof Activity)NativeKeyboard.watch((Activity)c,dialog);
        return dialog;
    }
    static final class Builder extends AlertDialog.Builder {
        private final Context owner;
        Builder(Context c){super(c);owner=c;}
        @Override public AlertDialog create(){
            AlertDialog d=super.create();
            if(owner instanceof Activity)NativeKeyboard.watch((Activity)owner,d);
            return d;
        }
    }
}
