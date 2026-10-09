package com.chk.agentbrowser;

import android.os.Handler;
import android.os.SystemClock;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Execute at most one click, then verify the requested DOM/text/URL postcondition. */
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
        if(selector.isEmpty() || selector.length()>340 ||
            (expectedSelector.isEmpty() && expectedText.isEmpty() && expectedUrl.isEmpty()) ||
            expectedSelector.length()>340 || expectedText.length()>250 ||
            expectedUrl.length()>500) {
            callback.finish(false,"Clic vérifié : cible ou condition manquante/invalide.");return;
        }
        final long timeout=Math.max(800L,Math.min(20000L,args.optLong("timeout_ms",10000L)));
        final String condition=BrowserScripts.condition(JSONObject.quote(expectedSelector),
                JSONObject.quote(expectedText),JSONObject.quote(expectedUrl));
        final long start=SystemClock.elapsedRealtime();
        web.evaluateJavascript(condition, before->{
            String state=value(before);
            if("DONE".equals(state)) {
                callback.finish(true,"Condition déjà satisfaite : aucun clic supplémentaire.");
                return;
            }
            if(!"WAIT".equals(state)) {
                callback.finish(false,"Impossible de vérifier l'état avant le clic : "+state);
                return;
            }
            web.evaluateJavascript(BrowserScripts.click(JSONObject.quote(selector)),raw->{
                String result=value(raw);
                if(!"Clic effectué".equals(result)) { callback.finish(false,result);return; }
                final Runnable[] check=new Runnable[1];
                check[0]=()->{
                    try {
                        web.evaluateJavascript(condition,rawState->{
                            String current=value(rawState);
                            if("DONE".equals(current)) {
                                callback.finish(true,"Clic confirmé : condition observée sur la page.");
                            } else if(!"WAIT".equals(current) ||
                                SystemClock.elapsedRealtime()-start>=timeout) {
                                callback.finish(false,"Clic déclenché mais résultat NON confirmé ("+
                                        current+"). Ne pas recliquer sans relire la page.");
                            } else {
                                handler.postDelayed(check[0],450L);
                            }
                        });
                    } catch(RuntimeException e) {
                        callback.finish(false,"Page interrompue après le clic : état inconnu.");
                    }
                };
                handler.postDelayed(check[0],450L);
            });
        });
    }
}
