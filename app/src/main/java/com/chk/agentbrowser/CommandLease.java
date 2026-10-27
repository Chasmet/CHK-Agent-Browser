package com.chk.agentbrowser;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deadline independent from the UI and from WebView callbacks; finish at most once. */
final class CommandLease implements DirectMcpServer.Reply {
    private static final ScheduledExecutorService CLOCK=Executors.newSingleThreadScheduledExecutor();
    private final DirectMcpServer.Reply parent;
    private final AtomicBoolean ended=new AtomicBoolean();
    private final ScheduledFuture<?> alarm;
    CommandLease(long milliseconds,DirectMcpServer.Reply parent){
        this.parent=parent;
        alarm=CLOCK.schedule(()->finish(false,"{\"ok\":false,\"execution_state\":\"timed_out\",\"action_sent\":null,\"outcome_confirmed\":false,\"retry_command\":false,\"message\":\"Délai maximal dépassé : vérifier l’effet avant toute nouvelle action.\"}"),milliseconds,TimeUnit.MILLISECONDS);
    }
    public boolean isActive(){return !ended.get()&&parent.isActive();}
    public void finish(boolean ok,String text){
        if(!ended.compareAndSet(false,true))return;
        if(alarm!=null)alarm.cancel(false);
        parent.finish(ok,text);
    }
}
