package com.chk.agentbrowser;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
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
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Public GitHub Releases: no PAT, API credit, or manual GitHub download.
 * Only the existing package name + EXACT installed Android signer can be upgraded.
 * Android's system package installer remains the authority for final approval.
 */
public final class UpdateManager {
    private static final String API =
        "https://api.github.com/repos/Chasmet/CHK-Agent-Browser/releases/latest";
    public static final String ACTION_INSTALL_READY = "com.chk.agentbrowser.INSTALL_READY";
    private static final Object DOWNLOAD_LOCK=new Object();
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public interface Listener { void onResult(String message, Release release); }
    public interface ReadyListener { void completed(File apk); }
    public long downloadId(){return prefs.getLong("download_id",-1);}
    public void checkCompletedApk(ReadyListener callback){
        new Thread(()->{File file=completedApk();ui.post(()->callback.completed(file));},"chk-verify-apk").start();
    }
    public static final class Release {
        public final String version, url, filename;
        Release(String v, String u, String f) { version=v; url=u; filename=f; }
    }
    public UpdateManager(Context c) {
        context = c;
        prefs = c.getSharedPreferences("apk_updates",Context.MODE_PRIVATE);
    }
    public void check(Listener callback) {
        new Thread(() -> {
            HttpURLConnection c=null;
            try {
                c=(HttpURLConnection)new URL(API).openConnection();
                c.setConnectTimeout(12000);c.setReadTimeout(12000);
                c.setRequestProperty("Accept","application/vnd.github+json");
                c.setRequestProperty("User-Agent","CHK-Agent-Browser-Updater/2.0");
                int status=c.getResponseCode();
                if(status==404){respond(callback,"Aucune version officielle publiée.",null);return;}
                if(status!=200){respond(callback,"Erreur réseau GitHub HTTP "+status,null);return;}
                try(InputStream input=c.getInputStream()) {
                    java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
                    byte[] buf=new byte[4096];int n;
                    while((n=input.read(buf))!=-1) {
                        if(out.size()>200000)throw new Exception("Réponse trop volumineuse");
                        out.write(buf,0,n);
                    }
                    JSONObject data=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
                    String remote=data.optString("tag_name","").replaceFirst("^[vV]","");
                    String local=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;
                    if(!newer(remote,local)) {
                        respond(callback,"Application à jour ("+local+").",null);return;
                    }
                    JSONArray assets=data.optJSONArray("assets");
                    if(assets!=null)for(int i=0;i<assets.length();i++) {
                        JSONObject asset=assets.optJSONObject(i);
                        if(asset==null)continue;
                        String name=asset.optString("name"),link=asset.optString("browser_download_url");
                        if(name.equals("CHK-Agent-Browser-"+remote+".apk") &&
                           link.startsWith("https://github.com/Chasmet/CHK-Agent-Browser/releases/download/")) {
                            respond(callback,"Version "+remote+" disponible à installer",new Release(remote,link,name));
                            return;
                        }
                    }
                    respond(callback,"Aucun APK officiel compatible trouvé.",null);
                }
            }catch(Exception e) {
                respond(callback,"Vérification impossible : "+e.getClass().getSimpleName(),null);
            }finally {if(c!=null)c.disconnect();}
        },"chk-apk-update-check").start();
    }
    private void respond(Listener callback,String status,Release release) {
        ui.post(()->callback.onResult(status,release));
    }
    public static boolean newer(String remote,String local) {
        if(remote==null||local==null)return false;
        if(!remote.matches("[0-9]+\\.[0-9]+\\.[0-9]+") ||
           !local.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))return false;
        String[] a=remote.split("\\."),b=local.split("\\.");
        try {
            for(int i=0;i<3;i++) {
                long x=Long.parseLong(a[i]),y=Long.parseLong(b[i]);
                if(x!=y)return x>y;
            }
        }catch(NumberFormatException ignored){}
        return false;
    }
    public long download(Release release) {
        synchronized(DOWNLOAD_LOCK){
        if(release==null || !release.filename.matches("CHK-Agent-Browser-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk") ||
           !release.url.startsWith("https://github.com/Chasmet/CHK-Agent-Browser/releases/download/"))
            throw new IllegalArgumentException("Release non autorisée");
        File root=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if(root==null)throw new IllegalStateException("Stockage du téléphone indisponible");
        DownloadManager manager=(DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);
        long previous=downloadId();
        if(previous>=0){
            try(android.database.Cursor row=manager.query(new DownloadManager.Query().setFilterById(previous))){
                if(row!=null&&row.moveToFirst()&&release.filename.equals(prefs.getString("filename",""))){
                    int state=row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if(state==DownloadManager.STATUS_PENDING||state==DownloadManager.STATUS_RUNNING||state==DownloadManager.STATUS_PAUSED)return previous;
                    if(state==DownloadManager.STATUS_SUCCESSFUL&&completedApk()!=null)return previous;
                }
            }
            manager.remove(previous);
        }
        File apk=new File(root,release.filename);
        if(apk.exists() && !apk.delete())throw new IllegalStateException("Ancien fichier verrouillé");
        DownloadManager.Request request=new DownloadManager.Request(Uri.parse(release.url));
        request.setTitle("Mise à jour CHK Agent Browser "+release.version);
        request.setDescription("Téléchargement officiel avant installation Android");
        request.setMimeType("application/vnd.android.package-archive");
        request.setAllowedOverMetered(true);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,release.filename);
        long id=((DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(request);
        prefs.edit().putLong("download_id",id).putString("filename",release.filename)
            .putString("version",release.version).apply();
        return id;
        }
    }
    public String downloadProgress(){
        long id=prefs.getLong("download_id",-1);if(id<0)return null;
        try(android.database.Cursor c=((DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE)).query(new DownloadManager.Query().setFilterById(id))){
            if(c==null||!c.moveToFirst())return null;int status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if(status==DownloadManager.STATUS_SUCCESSFUL)return "ready";
            if(status==DownloadManager.STATUS_FAILED)return "Téléchargement interrompu. Vérifie les mises à jour pour réessayer.";
            long received=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            return total>0?"Téléchargement : "+Math.min(100,received*100/total)+" %":"Téléchargement en cours…";
        }catch(Exception e){return null;}
    }
    public boolean isOurDownload(long id) {
        return id>=0 && prefs.getLong("download_id",-1L)==id;
    }
    public File completedApk() {
        long id=prefs.getLong("download_id",-1L);
        if(id<0)return null;
        android.database.Cursor cur=null;
        try {
            DownloadManager dm=(DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);
            cur=dm.query(new DownloadManager.Query().setFilterById(id));
            if(cur==null || !cur.moveToFirst() ||
              cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))!=DownloadManager.STATUS_SUCCESSFUL)
                return null;
            String name=prefs.getString("filename","");
            if(!name.matches("CHK-Agent-Browser-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk"))return null;
            File root=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if(root==null)return null;
            File f=new File(root,name).getCanonicalFile();
            if(!root.getCanonicalFile().equals(f.getParentFile()) || !f.isFile() || f.length()<10000)
                return null;
            // Reject even GitHub-hosted APKs if the package or signing certificate changes.
            return isSignedCompatibleUpdate(f)?f:null;
        }catch(Exception ignored){return null;}
        finally{if(cur!=null)cur.close();}
    }
    @SuppressWarnings("deprecation")
    public boolean isSignedCompatibleUpdate(File f) {
        if(f==null || !f.isFile())return false;
        try {
            PackageManager pm=context.getPackageManager();
            int flags=Build.VERSION.SDK_INT>=28 ?
                PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo installed=pm.getPackageInfo(context.getPackageName(),flags);
            PackageInfo archive=pm.getPackageArchiveInfo(f.getAbsolutePath(),flags);
            if(archive==null || !context.getPackageName().equals(archive.packageName))return false;
            long newerVersion=Build.VERSION.SDK_INT>=28?archive.getLongVersionCode():archive.versionCode;
            long installedVersion=Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode;
            if(newerVersion<=installedVersion)return false;
            Signature[] a=signatures(archive),b=signatures(installed);
            return a!=null && b!=null && a.length>0 && Arrays.equals(a,b);
        }catch(Exception ignored){return false;}
    }
    @SuppressWarnings("deprecation")
    private static Signature[] signatures(PackageInfo info) {
        if(Build.VERSION.SDK_INT>=28) {
            if(info.signingInfo==null)return null;
            return info.signingInfo.getApkContentsSigners();
        }
        return info.signatures;
    }
    /** Launch native Android installer only when SettingsActivity is in the foreground. */
    public boolean install(File apk) {
        if(!isSignedCompatibleUpdate(apk))
            throw new IllegalArgumentException("Signature, version ou application non compatible");
        if(Build.VERSION.SDK_INT>=26 && !context.getPackageManager().canRequestPackageInstalls()) {
            Intent permission=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:"+context.getPackageName()));
            context.startActivity(permission);
            return false;
        }
        Uri uri=Uri.parse("content://"+context.getPackageName()+".apks/"+Uri.encode(apk.getName()));
        Intent install=new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri,"application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if(!(context instanceof android.app.Activity))install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(install);
        return true;
    }
}
