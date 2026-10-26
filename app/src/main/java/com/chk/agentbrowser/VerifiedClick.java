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

    private static String value(String raw) {
        try { return new JSONArray("[" + raw + "]").getString(0); }
        catch(Exception ignored) { return ""; }
    }

    public static void run(WebView web, JSONObject args, Handler handler,
                           AgentClient.ResultCallback callback) {
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
            (waitEnabled && !receiptId.matches("[A-Za-z0-9_-]{1,100}"))) {
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
        final String originalUrl=web.getUrl();
        final SharedPreferences receipts=waitEnabled
                ?web.getContext().getApplicationContext().getSharedPreferences("chk-click-once-v1",0):null;
        final String fingerprint=(originalUrl==null?"":originalUrl)+"|"+selector+"|"+
                expectedSelector+"|"+expectedText+"|"+expectedUrl;
        if(waitEnabled){
            String prior=receipts.getString(receiptId,"");
            if(!prior.isEmpty()){
                if(!prior.equals(fingerprint)){
                    callback.finish(false,"receipt_id déjà utilisé pour une autre action. Aucun clic.");return;
                }
                callback.finish(false,"Clic déjà tenté pour "+receiptId+
                        ". Inspecter le site ou le résultat avant toute nouvelle génération.");return;
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
                    callback.finish(true,"Condition déjà satisfaite : aucun clic supplémentaire.");return;
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
                        else handler.postDelayed(waitButton[0],350L);
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
                            callback.finish(true,"Clic envoyé UNE fois et résultat attendu confirmé.");
                        }else if(!"WAIT".equals(state)||
                                SystemClock.elapsedRealtime()-start>=timeout){
                            callback.finish(false,"Clic envoyé UNE fois ; résultat encore non confirmé ("+
                                    state+"). Ne pas répéter. Vérifier la page ou le post.");
                        }else handler.postDelayed(verify[0],350L);
                    });
                }catch(RuntimeException e){
                    callback.finish(false,"Clic envoyé ; page interrompue. Ne pas répéter sans inspection.");
                }
            };
            handler.postDelayed(verify[0],400L);
        });
    }
}
