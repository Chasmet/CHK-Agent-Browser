package com.chk.agentbrowser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Uses actual DOM input/change events and verifies values again after framework rendering. */
final class WebKeyboard {
    private static String source;
    private WebKeyboard(){}
    private static synchronized String script(Context c)throws Exception{
        if(source==null)try(InputStream in=c.getAssets().open("agent_keyboard.js")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);source=new String(out.toByteArray(),"UTF-8");}return source;
    }
    static String invocation(Context c,JSONObject p)throws Exception{return script(c)+"\n;window.__chkAgentKeyboard.run("+p.toString()+");";}
    private static JSONObject result(String raw)throws Exception{return new JSONObject(new JSONArray("["+raw+"]").getString(0));}
    static void execute(Context context,WebView web,JSONObject p,AgentClient.ResultCallback cb){
        if(!cb.isActive())return;
        try{
            if(!java.util.Arrays.asList("inspect","verify").contains(p.optString("operation","inspect")))AgentFeedback.point(web,20,20,"Clavier CHK");
            String host=p.optString("expected_host","");String actual=android.net.Uri.parse(web.getUrl()==null?"":web.getUrl()).getHost();
            if(!host.isEmpty()&&!host.equalsIgnoreCase(actual))throw new Exception("La destination web a changé : inspecte la page avant la saisie.");
            web.evaluateJavascript(invocation(context,p),raw->{
                if(!cb.isActive())return;
                try{JSONObject out=result(raw);
                    // A partial fill can still have a receipt: wait for the site to settle before reporting counts.
                    if(!out.optBoolean("ok") && out.optString("receipt","").isEmpty()){cb.finish(false,out.toString());return;}
                    if(out.optBoolean("native_enter")){
                        web.requestFocus();boolean down=web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));boolean up=web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER));
                        out.remove("native_enter");out.put("key_sent",down||up).put("submission_confirmed",false);cb.finish(down||up,out.toString());return;
                    }
                    String receipt=out.optString("receipt","");
                    if(receipt.isEmpty()){cb.finish(true,out.toString());return;}
                    new Handler(Looper.getMainLooper()).postDelayed(()->{
                        if(!cb.isActive())return;
                        try{JSONObject verify=new JSONObject().put("operation","verify").put("receipt",receipt).put("expected_context",out.getString("context"));
                            web.evaluateJavascript(invocation(context,verify),checked->{if(!cb.isActive())return;try{JSONObject finalState=result(checked);cb.finish(finalState.optBoolean("ok"),finalState.toString());}catch(Exception e){cb.finish(false,"Saisie peut-être appliquée : la vérification de la page a été interrompue.");}});
                        }catch(Exception e){cb.finish(false,"Saisie peut-être appliquée : contexte web interrompu, relis la page.");}
                    },160L);
                }catch(Exception e){cb.finish(false,"Le clavier web n’a pas renvoyé de résultat exploitable.");}
            });
        }catch(Exception e){cb.finish(false,e.getMessage()==null?"Clavier web indisponible.":e.getMessage());}
    }
}
