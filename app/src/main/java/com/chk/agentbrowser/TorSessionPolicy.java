package com.chk.agentbrowser;

/** A Tor launch is never a circuit proof. Stale async replies cannot reconnect a stopped session. */
final class TorSessionPolicy {
    private long generation,verifiedAt=-1;
    private String state="disconnected",error="";
    static final long PROOF_MAX_AGE_MS=120000;
    synchronized long begin(){generation++;verifiedAt=-1;state="connecting";error="";return generation;}
    synchronized long stop(){generation++;verifiedAt=-1;state="disconnected";error="";return generation;}
    synchronized boolean confirm(long ticket,boolean circuit,boolean isTor,long now){
        if(ticket!=generation||!state.equals("connecting"))return false;
        if(!circuit||!isTor){fail(ticket,"TOR_DIAGNOSTIC_NOT_CONFIRMED");return false;}
        state="connected";verifiedAt=now;return true;
    }
    synchronized boolean refresh(long ticket,boolean circuit,boolean isTor,long now){
        if(ticket!=generation||!state.equals("connected"))return false;
        if(!circuit||!isTor){fail(ticket,"TOR_DIAGNOSTIC_LOST");return false;}verifiedAt=now;return true;
    }
    synchronized void fail(long ticket,String reason){if(ticket==generation){state="error";error=reason;verifiedAt=-1;}}
    synchronized boolean usable(long now){return state.equals("connected")&&verifiedAt>=0&&now>=verifiedAt&&now-verifiedAt<=PROOF_MAX_AGE_MS;}
    synchronized long generation(){return generation;}
    synchronized String state(){return state;}
    synchronized String error(){return error;}
    synchronized long proofAge(long now){return verifiedAt<0?-1:Math.max(0,now-verifiedAt);}
}
