package com.chk.agentbrowser;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One real click, guarded by explicit target and postcondition.
 * The optional wait_until_enabled mode is used for reactive buttons (e.g. Grok's
 * arrow after a media upload). It waits, clicks at most once, and persists an
 * idempotency receipt before any unconfirmed navigation can be retried.
 *
 * A sent click is NEVER reported as proof of a completed video or download.
 */
public final class VerifiedClick {
    private VerifiedClick() {}

    private static String result(String state,Object sent,boolean confirmed,String message) {
        try{return new JSONObject().put("state",state).put("action_sent",sent).put("outcome_confirmed",confirmed).put("retry_command",false).put("message",message).toString();}
        catch(Exception e){return message;}
    }
    private static final class DeadlineCallback implements AgentClient.ResultCallback {
        final AgentClient.ResultCallback parent;final Handler handler;final Runnable expiry;
        final java.util.concurrent.atomic.AtomicBoolean done=new java.util.concurrent.atomic.AtomicBoolean();
        boolean attempted;final long timeout;final boolean separateVerification;
        DeadlineCallback(AgentClient.ResultCallback parent,Handler handler,long timeout,boolean separateVerification){
            this.parent=parent;this.handler=handler;this.timeout=timeout;this.separateVerification=separateVerification;
            expiry=()->finish(false,result("timed_out",attempted?JSONObject.NULL:Boolean.FALSE,false,
                attempted?"Retour JavaScript absent ou résultat non confirmé : observer sans répéter le clic.":"Délai dépassé avant le clic : aucun geste envoyé."));
            handler.postDelayed(expiry,timeout);
        }
        void attempting(){
            if(attempted)return;attempted=true;
            if(separateVerification){handler.removeCallbacks(expiry);handler.postDelayed(expiry,timeout);}
        }
        public boolean isActive(){return !done.get()&&parent.isActive();}
        public boolean isLocal2(){return parent.isLocal2();}public boolean isLocal3(){return parent.isLocal3();}
        public String inputLease(){return parent.inputLease();}
        public void finish(boolean ok,String text){if(!done.compareAndSet(false,true))return;handler.removeCallbacks(expiry);parent.finish(ok,text);}
    }
    private static String value(String raw) {
        try { return new JSONArray("[" + raw + "]").getString(0); }
        catch(Exception ignored) { return ""; }
    }

    public static void run(WebView web, JSONObject args, Handler handler,
                           AgentClient.ResultCallback parent) {
        AgentClient.ResultCallback callback;
        boolean wait=args.optBoolean("wait_until_enabled",false);
        long budget=Math.max(800L,Math.min(25000L,args.optLong("timeout_ms",wait?20000L:10000L)));
        try { callback=AgentControl.lease(web,false,parent,budget*(wait?2:1)+1000L); } catch(Exception e){parent.finish(false,e.getMessage());return;}
        callback=new DeadlineCallback(callback,handler,budget,wait);
        runBounded(web,args,handler,callback);
    }
    private static void runBounded(WebView web,JSONObject args,Handler handler,AgentClient.ResultCallback callback) {
        if(web == null) { callback.finish(false, "Aucune page active."); return; }
        final String selector=args.optString("selector", "");
        final String expectedSelector=args.optString("expected_selector", "");
        final String expectedText=args.optString("expected_text", "");
        final String expectedUrl=args.optString("expected_url_contains", "");
        final String expectedHost=args.optString("expected_host", "");
        final boolean waitEnabled=args.optBoolean("wait_until_enabled",false);
        final String receiptId=args.optString("receipt_id","");
        if(selector.isEmpty() || selector.length()>340 ||
            (expectedSelector.isEmpty() && expectedText.isEmpty() && expectedUrl.isEmpty()) ||
            expectedSelector.length()>340 || expectedText.length()>250 ||
            expectedUrl.length()>500 ||
            (waitEnabled && receiptId.isEmpty()) || (!receiptId.isEmpty()&&!receiptId.matches("[A-Za-z0-9_-]{1,100}"))) {
            callback.finish(false,"Sélecteur, condition attendue et (en mode attente) receipt_id stable requis.");return;
        }
        if(!expectedHost.isEmpty()){
            String actual=android.net.Uri.parse(web.getUrl()==null?"":web.getUrl()).getHost();
            if(!expectedHost.equalsIgnoreCase(actual)){
                callback.finish(false,"Le domaine a changé. Aucun clic.");return;
            }
        }
        final long timeout=Math.max(800L,Math.min(25000L,args.optLong("timeout_ms",waitEnabled?20000L:10000L)));
        final String condition=BrowserScripts.condition(JSONObject.quote(expectedSelector),
                JSONObject.quote(expectedText),JSONObject.quote(expectedUrl));
        final long start=SystemClock.elapsedRealtime();
        final String originalUrl=web.getUrl()==null?"":web.getUrl();
        final SharedPreferences receipts=!receiptId.isEmpty()
                ?web.getContext().getApplicationContext().getSharedPreferences("chk-click-once-v1",0):null;
        final String actionFingerprint="|"+selector+"|"+expectedSelector+"|"+expectedText+"|"+expectedUrl;
        final String fingerprint=originalUrl+actionFingerprint;
        if(receipts!=null){
            String prior=receipts.getString(receiptId,"");
            if(!prior.isEmpty()){
                // The accepted click can legitimately navigate to a new URL.
                // Its target/postcondition must still match the original receipt;
                // only read the postcondition here, never click again.
                if(!prior.endsWith(actionFingerprint)){
                    callback.finish(false,"receipt_id déjà utilisé pour une autre action. Aucun clic.");return;
                }
                // A delayed site response may have completed since the timeout.
                // Recheck the postcondition, never dispatch another click.
                web.evaluateJavascript(condition,raw->{
                    if(!callback.isActive())return;
                    boolean done="DONE".equals(value(raw));
                    callback.finish(done,done?"Clic antérieur confirmé : condition observée, aucun nouveau clic.":
                        "Clic déjà tenté pour "+receiptId+" ; résultat encore incertain. Aucun nouveau clic.");
                });return;
            }
        }
        final Runnable[] waitButton=new Runnable[1];
        waitButton[0]=()->{
            if(!callback.isActive()||!BrowserWebState.alive(web))return;
            if(waitEnabled && !originalUrl.equals(web.getUrl())){
                callback.finish(false,"Page changée pendant l'attente : aucun clic.");return;
            }
            web.evaluateJavascript(condition,before->{
                if(!callback.isActive()||!BrowserWebState.alive(web))return;
                String state=value(before);
                if("DONE".equals(state)){
                    callback.finish(true,result("already_satisfied",false,false,"Condition déjà satisfaite avant la demande : aucun clic supplémentaire, aucun geste envoyé."));return;
                }
                if(!"WAIT".equals(state)){
                    callback.finish(false,"État avant le clic non vérifiable : "+state);return;
                }
                if(!waitEnabled){clickOnce(web,selector,condition,handler,callback,
                        start,timeout,receipts,receiptId,fingerprint);return;}
                web.evaluateJavascript(BrowserScripts.clickState(JSONObject.quote(selector)),raw->{
                    if(!callback.isActive()||!BrowserWebState.alive(web))return;
                    String status=value(raw);
                    if("READY".equals(status)){
                        clickOnce(web,selector,condition,handler,callback,
                                start,timeout,receipts,receiptId,fingerprint);
                    }else if("WAIT".equals(status)||"MISSING".equals(status)){
                        if(SystemClock.elapsedRealtime()-start>=timeout)
                            callback.finish(false,"Bouton toujours désactivé/absent après attente ; aucun clic envoyé.");
                        else handler.postDelayed(waitButton[0],150L);
                    }else callback.finish(false,"Bouton non vérifiable : "+status);
                });
            });
        };
        handler.post(waitButton[0]);
    }

    private static void clickOnce(WebView web,String selector,String condition,Handler handler,
                                  AgentClient.ResultCallback callback,long start,long timeout,
                                  SharedPreferences receipts,String receiptId,String fingerprint){
        if(!callback.isActive()||!BrowserWebState.alive(web))return;
        // Fail closed if Android is interrupted between dispatch and confirmation.
        if(receipts!=null && !receipts.edit().putString(receiptId,fingerprint).commit()){
            callback.finish(false,"Journal d'idempotence indisponible : aucun clic envoyé.");return;
        }
        if(callback instanceof DeadlineCallback)((DeadlineCallback)callback).attempting();
        final long clickedAt=SystemClock.elapsedRealtime();
        web.evaluateJavascript(BrowserScripts.click(JSONObject.quote(selector)),raw->{
            if(!callback.isActive())return;
            String clicked=value(raw);
            if(!"Clic effectué".equals(clicked)){
                if(receipts!=null && ("Élément désactivé".equals(clicked)||
                        "Élément introuvable".equals(clicked)))
                    receipts.edit().remove(receiptId).commit();
                callback.finish(false,"Aucun clic confirmé : "+clicked);return;
            }
            final Runnable[] verify=new Runnable[1];
            verify[0]=()->{
                if(!callback.isActive()||!BrowserWebState.alive(web))return;
                try{
                    web.evaluateJavascript(condition,after->{
                        if(!callback.isActive())return;
                        String state=value(after);
                        if("DONE".equals(state)){
                            callback.finish(true,result("completed",true,true,"Clic confirmé : condition devenue vraie après un seul clic."));
                        }else if(!"WAIT".equals(state)||
                                SystemClock.elapsedRealtime()-clickedAt>=timeout){
                            callback.finish(false,"Clic envoyé UNE fois ; résultat encore non confirmé ("+
                                    state+"). Ne pas répéter. Vérifier la page ou le post.");
                        }else handler.postDelayed(verify[0],150L);
                    });
                }catch(RuntimeException e){
                    callback.finish(false,"Clic envoyé ; page interrompue. Ne pas répéter sans inspection.");
                }
            };
            handler.post(verify[0]);
        });
    }
}
