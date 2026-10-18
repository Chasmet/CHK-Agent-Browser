package com.chk.agentbrowser;

/** Retry pacing shared by the network client and its regression tests. */
public final class RelayPolicy {
    private RelayPolicy() {}
    public static boolean isTerminalResultStatus(int status){
        return status==404||status==409||status==410;
    }
    public static long pollDelay(int failures, boolean busy) {
        return pollDelay(failures,busy,false);
    }
    /** More responsive when the owner is using CHK, conservative when the phone is asleep. */
    public static long pollDelay(int failures, boolean busy, boolean foreground) {
        if(failures<=0)return busy?10000L:(foreground?1200L:3000L);
        return Math.min(30000L,1500L << Math.min(failures,4));
    }
}
