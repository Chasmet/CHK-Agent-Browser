package com.chk.agentbrowser;

import android.content.Context;
import android.os.Handler;
import android.webkit.WebView;
import org.json.JSONObject;

/** Commit a publication click exactly once, only on the bound platform host.
 * DOM/browser confirmation is recorded separately from platform analytics. */
final class PublicationBrowserCommands {
    private PublicationBrowserCommands(){}
    static void submit(Context context,WebView web,JSONObject args,Handler handler,
                       AgentClient.ResultCallback callback){
        if(!callback.isActive())return;
        if(web==null||web.getUrl()==null||!BrowserWebState.alive(web)){
            callback.finish(false,"Onglet de publication indisponible.");return;
        }
        final String id=args.optString("id","");
        final long documentVersion=BrowserWebState.documentVersion(web);
        final String originalAddress=web.getUrl();
        final Runnable finalAction=()->{
            if(!callback.isActive()||!BrowserWebState.alive(web)
                ||documentVersion!=BrowserWebState.documentVersion(web)
                ||!originalAddress.equals(web.getUrl())){
                callback.finish(false,"Page modifiée durant le contrôle de compte. Aucun clic.");return;
            }
            try{
                // Record an irreversible attempt before dispatching the browser click.
                PublicationJobs.begin(context,args,web.getUrl());
            }catch(Exception e){callback.finish(false,"Publication refusée : "+e.getMessage());return;}
            final AgentClient.ResultCallback receipt=new AgentClient.ResultCallback(){
                @Override public boolean isActive(){return callback.isActive();}
                @Override public void finish(boolean ok,String message){
                    try{
                        JSONObject state=PublicationJobs.finish(context,id,ok,message,web.getUrl());
                        boolean confirmed=state.optString("status").endsWith("browser_confirmed");
                        callback.finish(confirmed,state.toString());
                    }catch(Exception e){
                        callback.finish(false,"Le clic peut avoir eu lieu, mais le journal est incertain : "
                            +e.getClass().getSimpleName()+". Ne pas republier.");
                    }
                }
            };
            VerifiedClick.run(web,args,handler,receipt);
        };
        try{
            JSONObject plan=PublicationJobs.command(context,PublicationJobs.ACTION_STATUS,
                new JSONObject().put("id",id));
            String account=plan.optString("account","");
            if(account.isEmpty()){finalAction.run();return;}
            String selector=args.optString("account_selector","");
            if(selector.length()<2||selector.length()>340){
                callback.finish(false,"Sélecteur du compte connecté obligatoire ; inspecte d'abord la page.");return;
            }
            web.evaluateJavascript(BrowserScripts.accountMatches(JSONObject.quote(selector),
                JSONObject.quote(account)),raw->{
                try{
                    String state=new org.json.JSONArray("["+raw+"]").getString(0);
                    if(!"MATCH".equals(state)){
                        callback.finish(false,"Compte connecté non confirmé ("+state+"). Aucun clic de publication.");return;
                    }
                    finalAction.run();
                }catch(Exception error){callback.finish(false,"Compte non vérifiable. Aucun clic de publication.");}
            });
        }catch(Exception error){
            callback.finish(false,"Publication non préparée : "+error.getMessage());
        }
    }
}
