package com.chk.agentbrowser;

/** Retry pacing shared by the network client and its regression tests. */
public final class RelayPolicy {
    private RelayPolicy() {}
    public static long pollDelay(int failures, boolean busy) {
        if(failures<=0)return busy?10000L:3000L;
        return Math.min(30000L,1500L << Math.min(failures,4));
    }
}
