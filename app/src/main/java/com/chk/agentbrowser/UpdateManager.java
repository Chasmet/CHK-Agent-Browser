package com.chk.agentbrowser;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
public final class UpdateManager {
    private static final String API="https://api.github.com/repos/Chasmet/CHK-Agent-Browser/releases/latest";
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main=new Handler(Looper.getMainLooper());
    public interface Listener {void onResult(String message,Release release);}
    public static class Release {
        public final String version,url,filename;
        Release(String v,String u,String f){version=v;url=u;filename=f;}
    }
    public UpdateManager(Context c){
        context=c;prefs=c.getSharedPreferences("apk_updates",Context.MODE_PRIVATE);
    }
    public void check(Listener callback){
        new Thread(()->{
            HttpURLConnection connection=null;
            try{
                connection=(HttpURLConnection)new URL(API).openConnection();
                connection.setConnectTimeout(12000);connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept","application/vnd.github+json");
                connection.setRequestProperty("User-Agent","CHK-Agent-Browser/1.0");
                int status=connection.getResponseCode();
                if(status==404){respond(callback,"Aucune Release disponible pour le moment.",null);return;}
                if(status!=200){respond(callback,"Erreur GitHub HTTP "+status,null);return;}
                InputStream input=connection.getInputStream();
                byte[] buffer=new byte[4096];
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                int n;while((n=input.read(buffer))!=-1){
                    if(bytes.size()>200000)throw new Exception("Réponse trop volumineuse");
                    bytes.write(buffer,0,n);
                }input.close();
                JSONObject data=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
                String remote=data.optString("tag_name","").replaceFirst("^[vV]","");
                String local=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;
                if(!newer(remote,local)){respond(callback,"Dernière version : "+local,null);return;}
                JSONArray assets=data.optJSONArray("assets");
                if(assets!=null)for(int i=0;i<assets.length();i++){
                    JSONObject a=assets.optJSONObject(i);if(a==null)continue;
                    String name=a.optString("name"),url=a.optString("browser_download_url");
                    if(name.startsWith("CHK-Agent-Browser-")&&name.endsWith(".apk")
                        &&url.startsWith("https://github.com/Chasmet/CHK-Agent-Browser/releases/download/")){
                        respond(callback,"Version "+remote+" disponible",new Release(remote,url,name));return;
                    }
                }
                respond(callback,"Nouvelle version sans APK compatible.",null);
            }catch(Exception e){respond(callback,"Vérification impossible : "+e.getClass().getSimpleName(),null);}
            finally{if(connection!=null)connection.disconnect();}
        }).start();
    }
    private void respond(Listener c,String s,Release r){main.post(()->c.onResult(s,r));}
    static boolean newer(String a,String b){
        String[] x=a.split("\\."),y=b.split("\\.");
        if(x.length!=3||y.length!=3)return false;
        try{
            for(int i=0;i<3;i++){
                int left=Integer.parseInt(x[i]),right=Integer.parseInt(y[i]);
                if(left!=right)return left>right;
            }
        }catch(NumberFormatException e){return false;}
        return false;
    }
    public void download(Release r){
        if(r==null||!r.filename.matches("CHK-Agent-Browser-[a-zA-Z0-9._-]+\\.apk"))
            throw new IllegalArgumentException("APK invalide");
        File root=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if(root==null)throw new IllegalStateException("Stockage indisponible");
        File file=new File(root,r.filename);
        if(file.exists()&&!file.delete())throw new IllegalStateException("Fichier verrouillé");
        DownloadManager.Request request=new DownloadManager.Request(Uri.parse(r.url));
        request.setTitle("CHK Agent Browser "+r.version);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,r.filename);
        long id=((DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(request);
        prefs.edit().putLong("download_id",id).putString("filename",r.filename).apply();
    }
    public File completedApk(){
        long id=prefs.getLong("download_id",-1);
        if(id<0)return null;
        android.database.Cursor cursor=null;
        try{
            cursor=((DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE)).query(new DownloadManager.Query().setFilterById(id));
            if(cursor!=null&&cursor.moveToFirst()&&cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))==DownloadManager.STATUS_SUCCESSFUL){
                File root=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                File file=root==null?null:new File(root,prefs.getString("filename",""));
                if(file!=null&&file.isFile()&&file.length()>10000)return file;
            }
        }catch(Exception ignored){}finally{if(cursor!=null)cursor.close();}
        return null;
    }
    public boolean install(File file){
        if(file==null)return false;
        if(Build.VERSION.SDK_INT>=26&&!context.getPackageManager().canRequestPackageInstalls()){
            context.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.getPackageName())));
            return false;
        }
        Uri uri=Uri.parse("content://"+context.getPackageName()+".apks/"+Uri.encode(file.getName()));
        Intent intent=new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri,"application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);return true;
    }
}
