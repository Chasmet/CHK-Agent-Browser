package com.chk.agentbrowser;

import android.content.Context;
import android.webkit.CookieManager;
import org.json.JSONObject;

/** Fallback read-only for authenticated PDF responses not rendered by Android WebView. */
final class WebPdfReader {
    private WebPdfReader(){}
    static void enrich(Context app,String pageUrl,JSONObject details,AgentClient.ResultCallback cb){
        if(!DocumentReader.webPdfCandidate(pageUrl)||details.optString("text","").trim().length()>40){
            cb.finish(true,details.toString());return;
        }
        // Read the cookie only from the already-open owner session; never serialize it to the relay.
        final String cookie=CookieManager.getInstance().getCookie(pageUrl);
        WorkspaceCommands.IO.execute(()->{
            try{
                if(!cb.isActive())return;
                JSONObject document=DocumentReader.readWebPdf(app,pageUrl,cookie);
                details.put("text",document.optString("text",""));
                details.put("document",document);
                details.put("document_url",pageUrl);
                WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(true,details.toString());});
            }catch(Exception e){
                try{
                    details.put("pdf_read_error",e.getMessage()==null?"PDF inaccessible":e.getMessage());
                    details.put("pdf_read_status","unavailable");
                }catch(Exception ignored){}
                WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(true,details.toString());});
            }
        });
    }
}
