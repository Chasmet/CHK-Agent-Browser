package com.chk.agentbrowser;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded file transfer helper. It never reads arbitrary phone files. */
public final class BrowserTransferManager {
    private static final long MAX_FILE=12L*1024L*1024L;
    private static final long MAX_TOTAL=32L*1024L*1024L;
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final Handler UI=new Handler(Looper.getMainLooper());
    public interface UploadCallback{void done(boolean ok,Uri[] uris,String message);}
    public interface DownloadCallback{void done(boolean ok,String message);}

    private BrowserTransferManager(){}

    public static void prepareUploads(Context context,JSONArray files,String bearer,UploadCallback callback){
        if(files==null || files.length()<1 || files.length()>8){
            callback.done(false,null,"Entre 1 et 8 fichiers sont requis.");return;
        }
        final Context app=context.getApplicationContext();
        IO.execute(()->{
            try{
                File root=new File(app.getCacheDir(),"agent_uploads");
                if(!root.exists()&&!root.mkdirs())throw new Exception("Stockage temporaire indisponible");
                cleanup(root);
                Uri[] uris=new Uri[files.length()];
                long total=0;
                for(int i=0;i<files.length();i++){
                    JSONObject item=files.optJSONObject(i);
                    if(item==null)throw new Exception("Description de fichier invalide");
                    String url=item.optString("url","");
                    String name=safeName(item.optString("name","file-"+(i+1)));
                    String expected=item.optString("sha256","").toLowerCase(Locale.ROOT);
                    if(!url.startsWith(AgentClient.BASE+"/agentbrowser/api/transfers/"))
                        throw new Exception("Source de transfert non autorisée");
                    File target=new File(root,Long.toHexString(System.nanoTime())+"-"+name);
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");
                    long size=download(url,bearer,target,digest);
                    total+=size;
                    if(size<=0||size>MAX_FILE||total>MAX_TOTAL){target.delete();throw new Exception("Taille de fichier refusée");}
                    String actual=hex(digest.digest());
                    if(!expected.isEmpty()&&!expected.equals(actual)){target.delete();throw new Exception("Empreinte du fichier incorrecte");}
                    uris[i]=new Uri.Builder().scheme("content")
                        .authority(app.getPackageName()+".browserfiles").appendPath(target.getName()).build();
                }
                UI.post(()->callback.done(true,uris,"Fichiers préparés"));
            }catch(Exception e){
                UI.post(()->callback.done(false,null,"Importation impossible : "+safeMessage(e)));
            }
        });
    }

    private static long download(String value,String bearer,File target,MessageDigest digest)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(value).openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","*/*");c.setRequestProperty("User-Agent","CHK-Agent-Browser/2.1");
        c.setRequestProperty("Authorization","Bearer "+bearer);
        int code=c.getResponseCode();
        if(code<200||code>=300){c.disconnect();throw new Exception("Serveur de transfert HTTP "+code);}
        long total=0;
        try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(target)){
            byte[] buf=new byte[16384];int n;
            while((n=in.read(buf))!=-1){
                total+=n;if(total>MAX_FILE)throw new Exception("Fichier supérieur à 12 Mo");
                digest.update(buf,0,n);out.write(buf,0,n);
            }
        }finally{c.disconnect();}
        return total;
    }

    public static void downloadToApp(Context context,String url,String userAgent,String mime,DownloadCallback callback){
        if(url==null||!url.startsWith("https://")){callback.done(false,"Téléchargement HTTPS requis.");return;}
        try{
            String name=android.webkit.URLUtil.guessFileName(url,null,mime);
            DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url));
            request.setTitle(name);
            if(mime!=null&&!mime.isEmpty())request.setMimeType(mime);
            request.addRequestHeader("User-Agent",userAgent==null?"CHK-Agent-Browser/2.1":userAgent);
            String cookies=CookieManager.getInstance().getCookie(url);
            if(cookies!=null&&!cookies.isEmpty())request.addRequestHeader("Cookie",cookies);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,name);
            DownloadManager manager=(DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);
            long id=manager.enqueue(request);
            long started=android.os.SystemClock.elapsedRealtime();
            Runnable[] poll=new Runnable[1];
            poll[0]=()->{
                try(Cursor c=manager.query(new DownloadManager.Query().setFilterById(id))){
                    if(c!=null&&c.moveToFirst()){
                        int status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if(status==DownloadManager.STATUS_SUCCESSFUL){
                            String local=c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                            long size=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                            callback.done(true,"Téléchargement confirmé : "+name+" · "+size+" octets · "+(local==null?"stockage application":local));
                            return;
                        }
                        if(status==DownloadManager.STATUS_FAILED){
                            callback.done(false,"Téléchargement refusé ou interrompu par Android.");return;
                        }
                    }
                }catch(Exception e){callback.done(false,"Vérification du téléchargement impossible.");return;}
                if(android.os.SystemClock.elapsedRealtime()-started>90000L){
                    callback.done(false,"Téléchargement non confirmé après 90 secondes.");return;
                }
                UI.postDelayed(poll[0],1000L);
            };
            UI.postDelayed(poll[0],800L);
        }catch(Exception e){callback.done(false,"Téléchargement impossible : "+safeMessage(e));}
    }

    private static void cleanup(File root){
        File[] files=root.listFiles();if(files==null)return;
        long cutoff=System.currentTimeMillis()-3600000L;
        for(File f:files)if(f.isFile()&&f.lastModified()<cutoff)f.delete();
    }
    private static String safeName(String value){
        String cleaned=value.replaceAll("[^A-Za-z0-9._-]","_");
        if(cleaned.length()>120)cleaned=cleaned.substring(cleaned.length()-120);
        if(cleaned.isEmpty())cleaned="file";
        return cleaned;
    }
    private static String hex(byte[] value){
        StringBuilder b=new StringBuilder(value.length*2);
        for(byte v:value)b.append(String.format(Locale.ROOT,"%02x",v&255));
        return b.toString();
    }
    private static String safeMessage(Exception e){
        String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;
    }
}
