package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AgentClient {
    // Existing Render service: no new subscription, no OpenAI API key.
    public static final String BASE = "https://modeliseur-trellis-mcp.onrender.com";
    private static final String PREF = "browser_mcp_v1";
    private final Context app;
    private final SharedPreferences settings;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;
    private volatile boolean inFlight = false;
    private final Runnable nextPoll = this::poll;
    private Activity screen;
    private CommandHandler handler;

    public interface ResultCallback { void finish(boolean success, String result); }
    public interface CommandHandler {
        void onCommand(JSONObject command, ResultCallback callback);
        String pageUrl();
        String pageTitle();
    }
    public interface RegistrationCallback {void completed(boolean ok, String text); }

    public AgentClient(Context context) {
        app = context.getApplicationContext();
        settings = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
    public boolean isEnabled(){return settings.getBoolean("enabled", false);}
    public String mcpUrl(){return settings.getString("mcp_url", "");}
    private String token() {
        String key = settings.getString("device_token", "");
        if(key.matches("[a-f0-9]{64}")) return key;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for(byte b:bytes) sb.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
        key=sb.toString();
        settings.edit().putString("device_token",key).apply();
        return key;
    }
    public void enable(RegistrationCallback callback) {
        io.execute(()->{
            try {
                JSONObject body=new JSONObject();
                body.put("device_token",token());
                JSONObject response=request("POST","/agentbrowser/api/register",body,false);
                if(!response.optBoolean("ok")) throw new Exception(response.optString("error","Échec de l'association"));
                String url=response.optString("mcp_url","");
                if(!url.startsWith(BASE+"/agentbrowser/mcp/"))
                    throw new Exception("Réponse du serveur invalide");
                settings.edit().putString("mcp_url",url).putBoolean("enabled",true).apply();
                ui.post(()->callback.completed(true,"Connexion prête. Copie l'adresse MCP dans ChatGPT."));
            }catch(Exception e) {
                ui.post(()->callback.completed(false,"Connexion impossible : "+e.getMessage()));
            }
        });
    }
    public void disable(){
        settings.edit().putBoolean("enabled",false).apply();
        stop();
    }
    public void start(Activity activity,CommandHandler callback){
        screen=activity;handler=callback;
        if(!isEnabled())return;
        running=true;
        ui.removeCallbacks(nextPoll);
        ui.post(nextPoll);
    }
    public void stop(){
        running=false;ui.removeCallbacks(nextPoll);
        screen=null;handler=null;
    }
    private void poll(){
        if(!running||inFlight||screen==null||handler==null||!isEnabled())return;
        inFlight=true;
        io.execute(()->{
            JSONObject data=null;
            try{data=request("GET","/agentbrowser/api/poll",null,true);}
            catch(Exception ignored){}
            final JSONObject payload=data;
            ui.post(()->{
                inFlight=false;
                if(!running||screen==null)return;
                JSONObject cmd=payload==null?null:payload.optJSONObject("command");
                if(cmd!=null && handler!=null){
                    final String ident=cmd.optString("id","");
                    handler.onCommand(cmd,(ok,result)->sendResult(ident,ok,result));
                }
                // No background access if app is closed or hidden.
                ui.postDelayed(nextPoll,3000);
            });
        });
    }
    private void sendResult(String id,boolean ok,String value){
        io.execute(()->{
            try{
                JSONObject obj=new JSONObject();
                obj.put("id",id);obj.put("ok",ok);
                if(ok)obj.put("result",limit(value,11000));
                else obj.put("error",limit(value,750));
                request("POST","/agentbrowser/api/result",obj,true);
                if(handler!=null){
                    JSONObject heartbeat=new JSONObject();
                    heartbeat.put("url",limit(handler.pageUrl(),500));
                    heartbeat.put("title",limit(handler.pageTitle(),150));
                    request("POST","/agentbrowser/api/heartbeat",heartbeat,true);
                }
            }catch(Exception ignored){}
        });
    }
    private static String limit(String text,int max){
        if(text==null)return "";
        return text.length()>max?text.substring(0,max):text;
    }
    private JSONObject request(String method,String path,JSONObject data,boolean authenticate)throws Exception{
        HttpURLConnection connection=null;
        try{
            URL target=new URL(BASE+path);
            connection=(HttpURLConnection)target.openConnection();
            connection.setConnectTimeout(10000);connection.setReadTimeout(10000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept","application/json");
            connection.setRequestProperty("User-Agent","CHK-Agent-Browser/1.1");
            connection.setInstanceFollowRedirects(false);
            if(authenticate)connection.setRequestProperty("Authorization","Bearer "+token());
            if(data!=null){
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json");
                byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);
                if(bytes.length>24000)throw new Exception("Message trop volumineux");
                try(OutputStream output=connection.getOutputStream()){output.write(bytes);}
            }
            int status=connection.getResponseCode();
            if(status<200||status>=300)throw new Exception("HTTP "+status);
            try(InputStream input=connection.getInputStream()){
                ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                byte[] chunk=new byte[4096];int n;
                while((n=input.read(chunk))!=-1){
                    buffer.write(chunk,0,n);
                    if(buffer.size()>48000)throw new Exception("Réponse trop longue");
                }
                return new JSONObject(new String(buffer.toByteArray(),StandardCharsets.UTF_8));
            }
        }finally{if(connection!=null)connection.disconnect();}
    }
}
