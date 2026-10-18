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
        try{
            // Every media stamp and page domain must still match the immutable plan.
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
    }
}
