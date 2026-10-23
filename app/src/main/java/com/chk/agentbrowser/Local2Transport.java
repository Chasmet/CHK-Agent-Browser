package com.chk.agentbrowser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Bounded transport and safe error vocabulary. Never copies response bodies or URLs into diagnostics. */
final class Local2Transport {
    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status){super("HTTP "+status);this.status=status;}
    }
    static final class InvalidReply extends IOException {
        InvalidReply(){super("Réponse MCP JSON absente ou invalide");}
    }
    static final class OversizedReply extends IOException {
        OversizedReply(){super("Réponse MCP supérieure à 96 Kio");}
    }
    private Local2Transport(){}
    static JSONObject post(String address,String method,JSONObject parameters)throws Exception{
        return exchange((HttpURLConnection)new URL(address).openConnection(),method,parameters);
    }
    static JSONObject exchange(HttpURLConnection connection,String method,JSONObject parameters)throws Exception{
        try{
            connection.setRequestMethod("POST");connection.setConnectTimeout(8000);connection.setReadTimeout(25000);
            connection.setInstanceFollowRedirects(false);connection.setRequestProperty("Content-Type","application/json");
            connection.setRequestProperty("Accept","application/json");connection.setDoOutput(true);
            byte[] bytes=new JSONObject().put("jsonrpc","2.0").put("id",System.nanoTime()).put("method",method)
                .put("params",parameters).toString().getBytes(StandardCharsets.UTF_8);
            try(OutputStream out=connection.getOutputStream()){out.write(bytes);}
            int status=connection.getResponseCode();
            if(status!=200){
                InputStream error=connection.getErrorStream();if(error!=null)try{error.close();}catch(IOException ignored){}
                throw new HttpFailure(status);
            }
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream result=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int count;
                while((count=in.read(buffer))!=-1){
                    if(result.size()+count>96*1024)throw new OversizedReply();
                    result.write(buffer,0,count);
                }
                try{return new JSONObject(new String(result.toByteArray(),StandardCharsets.UTF_8));}
                catch(org.json.JSONException error){throw new InvalidReply();}
            }
        }finally{connection.disconnect();}
    }
    static String describe(Throwable error){
        if(error instanceof HttpFailure){
            int status=((HttpFailure)error).status;
            if(status==502)return "HTTP 502 : accès au MCP Windows en échec (Funnel ou service local)";
            if(status==401||status==403)return "HTTP "+status+" : association MCP refusée";
            if(status==404)return "HTTP 404 : route MCP introuvable";
            if(status==429)return "HTTP 429 : relais temporairement limité";
            return "HTTP "+status+" : réponse du relais refusée";
        }
        if(error instanceof java.net.SocketTimeoutException)return "Délai réseau dépassé (connexion ou réponse PC)";
        if(error instanceof java.net.UnknownHostException)return "Nom du serveur PC introuvable (DNS)";
        if(error instanceof javax.net.ssl.SSLException)return "Échec de la connexion HTTPS (TLS ou certificat)";
        if(error instanceof java.net.ConnectException)return "Connexion au relais PC refusée ou inaccessible";
        if(error instanceof InvalidReply)return "Réponse MCP JSON absente ou invalide";
        if(error instanceof OversizedReply)return "Réponse MCP supérieure à 96 Kio";
        if(error instanceof Local2Protocol.RpcFailure)return "Erreur RPC MCP "+((Local2Protocol.RpcFailure)error).code;
        // Do not use getMessage(): DNS/TLS/JSON exceptions may contain private URLs or response data.
        return "Échange PC non confirmé ("+error.getClass().getSimpleName()+")";
    }
}
