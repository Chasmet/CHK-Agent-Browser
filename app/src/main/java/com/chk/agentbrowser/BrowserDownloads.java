package com.chk.agentbrowser;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.util.UUID;

/** Durable native downloads. IDs survive MCP timeouts; no file bytes enter JSON. */
final class BrowserDownloads {
    private static final java.util.concurrent.ExecutorService STARTS=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.ExecutorService WORKERS=java.util.concurrent.Executors.newFixedThreadPool(2);
    private static final java.util.Set<String> VERIFYING=java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String,Boolean>());
    private BrowserDownloads(){}
    static void start(Context context,String url,String userAgent,String mime,JSONObject args,AgentClient.ResultCallback cb){
        if(!BrowserPolicy.publicUrl(url)){cb.finish(false,"Adresse de téléchargement HTTP/HTTPS publique requise.");return;}
        final Context app=context.getApplicationContext();
        final String cookies=CookieManager.getInstance().getCookie(url);
        STARTS.execute(()->{
            try{
                if(!cb.isActive())return;
                String id=args.optString("id",UUID.randomUUID().toString());
                File journal=AgentFiles.journal(app,"downloads",id);
                String path=args.optString("workspace_path","");
                String expected=args.optString("sha256","");
                if(!expected.isEmpty()&&!expected.matches("[a-f0-9]{64}"))throw new Exception("SHA-256 attendu invalide.");
                String fingerprint=AgentVision.digest(url+"\n"+path+"\n"+mime+"\n"+expected+"\n"+args.optString("filename",""));
                if(journal.isFile()){
                    if(!AgentFiles.read(journal).getString("fingerprint").equals(fingerprint))throw new Exception("Identifiant de téléchargement déjà utilisé pour une autre demande.");
                    finish(cb,true,status(app,id));return;
                }
                if(!path.isEmpty()){
                    File target=new WorkspaceStore(app).file(path);
                    if(target.exists()||!target.getParentFile().isDirectory())throw new Exception("Destination Studio existante ou dossier absent.");
                }
                File root=app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if(root==null||!root.isDirectory()&&!root.mkdirs())throw new Exception("Stockage des téléchargements indisponible.");
                TransferLimits.requireSpace(root,0);
                String requested=args.optString("filename",android.webkit.URLUtil.guessFileName(url,null,mime));
                java.util.Set<String> reserved=new java.util.HashSet<>();
                DownloadManager manager=(DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE);
                try(Cursor rows=manager.query(new DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_PENDING|DownloadManager.STATUS_RUNNING|DownloadManager.STATUS_PAUSED))){
                    if(rows!=null)while(rows.moveToNext())reserved.add(rows.getString(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)));
                }
                String name=FileNames.available(root,requested,reserved);
                JSONObject state=new JSONObject().put("id",id).put("fingerprint",fingerprint).put("state","enqueuing")
                    .put("filename",name).put("workspace_path",path).put("expected_sha256",expected)
                    .put("mime",mime==null?"":mime).put("created_at",System.currentTimeMillis()).put("size_limit",TransferLimits.MAX_FILE_BYTES);
                // A crash between enqueue and acknowledgment is uncertain, never a reason to enqueue twice.
                AgentFiles.save(journal,state);
                DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url));
                request.setTitle(name);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalFilesDir(app,Environment.DIRECTORY_DOWNLOADS,name);
                if(mime!=null&&!mime.isEmpty())request.setMimeType(mime);
                request.addRequestHeader("User-Agent",userAgent==null?"CHK-Agent-Browser":userAgent);
                if(cookies!=null&&!cookies.isEmpty())request.addRequestHeader("Cookie",cookies);
                if("assets.grok.com".equalsIgnoreCase(Uri.parse(url).getHost())){
                    request.addRequestHeader("Referer","https://grok.com/");request.addRequestHeader("Origin","https://grok.com");
                }
                long nativeId=((DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(request);
                state.put("download_id",nativeId).put("state","downloading");AgentFiles.save(journal,state);
                watch(app,id);
                finish(cb,true,status(app,id));
            }catch(Exception e){finish(cb,false,new JSONObject(),e.getMessage());}
        });
    }
    static void inspect(Context app,JSONObject args,AgentClient.ResultCallback cb){
        WORKERS.execute(()->{try{finish(cb,true,status(app,args.getString("id")));}
            catch(Exception e){finish(cb,false,new JSONObject(),e.getMessage());}});
    }
    static JSONObject status(Context app,String id)throws Exception{
        File journal=AgentFiles.journal(app,"downloads",id);JSONObject state=AgentFiles.read(journal);
        if(state.optString("state").equals("completed")){
            String path=state.optString("path","");
            File file=path.isEmpty()?new File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),state.getString("filename")):new WorkspaceStore(app).file(path);
            if(!file.isFile()||file.length()!=state.optLong("size")||file.lastModified()!=state.optLong("file_updated"))throw new Exception("Fichier téléchargé supprimé ou modifié.");
            return publicState(state);
        }
        if(state.optString("state").equals("failed"))return publicState(state);
        if(!state.has("download_id"))return publicState(state).put("state","unknown").put("completed",false)
            .put("reason","Envoi interrompu pendant la création du téléchargement. Inspecter Téléchargements avant de relancer.");
        DownloadManager manager=(DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE);
        int status;long total,received;
        try(Cursor rows=manager.query(new DownloadManager.Query().setFilterById(state.getLong("download_id")))){
            if(rows==null||!rows.moveToFirst())throw new Exception("Téléchargement absent ou annulé.");
            status=rows.getInt(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            total=rows.getLong(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            received=rows.getLong(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            if(status==DownloadManager.STATUS_FAILED){state.put("state","failed").put("reason","Erreur Android "+rows.getInt(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));AgentFiles.save(journal,state);return publicState(state);}
        }
        if(total>TransferLimits.MAX_FILE_BYTES||received>TransferLimits.MAX_FILE_BYTES){manager.remove(state.getLong("download_id"));throw new Exception("Téléchargement supérieur à 6 Go.");}
        state.put("bytes_received",received).put("bytes_total",total).put("completed",false);
        if(status!=DownloadManager.STATUS_SUCCESSFUL)return publicState(state).put("state","downloading");
        if(!VERIFYING.add(id))return publicState(state).put("state","downloading").put("phase","verifying");
        try{
        state.put("state","verifying");AgentFiles.save(journal,state);
        File source=new File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),state.getString("filename"));
        if(!source.isFile()||source.length()<=0||total>=0&&source.length()!=total)throw new Exception("Fichier téléchargé absent, vide ou incomplet.");
        TransferLimits.checkSize(source.length());
        String mime=state.optString("mime","");String filename=state.getString("filename").toLowerCase(java.util.Locale.ROOT);
        if(mime.equals("video/mp4")||filename.endsWith(".mp4")||state.optString("workspace_path","").toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")){
            byte[] header=new byte[12];try(FileInputStream in=new FileInputStream(source)){if(in.read(header)!=12||!new String(header,4,4,java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp"))throw new Exception("La réponse téléchargée n'est pas un MP4 reconnu.");}
        }
        String sha=WorkspaceTransfer.hash(source);String expected=state.optString("expected_sha256","");
        if(!expected.isEmpty()&&!expected.equals(sha))throw new Exception("Empreinte SHA-256 différente. Import annulé.");
        state.put("size",source.length()).put("sha256",sha).put("state","verified");AgentFiles.save(journal,state);
        String path=state.optString("workspace_path","");
        if(!path.isEmpty()){
            WorkspaceStore store=new WorkspaceStore(app);File target=store.file(path);
            if(target.exists()){
                if(!target.isFile()||target.length()!=source.length()||!sha.equals(WorkspaceTransfer.hash(target)))throw new Exception("Destination occupée par un autre fichier. Aucun écrasement.");
            }else{
                TransferLimits.requireSpace(target,source.length());store.importDownload(state.getString("filename"),path);
                if(!sha.equals(WorkspaceTransfer.hash(target)))throw new Exception("Fichier modifié pendant l'importation. Vérifier la destination.");
            }
            state.put("path",path);
        }
        File published=path.isEmpty()?source:new WorkspaceStore(app).file(path);
        state.put("state","completed").put("completed",true).put("verified",true).put("file_updated",published.lastModified()).put("completed_at",System.currentTimeMillis());
        AgentFiles.save(journal,state);return publicState(state);
        }finally{VERIFYING.remove(id);}
    }
    private static void watch(Context app,String id){
        WorkspaceCommands.UI.postDelayed(()->WORKERS.execute(()->{
            try{if(status(app,id).optString("state").equals("downloading"))watch(app,id);}
            catch(Exception e){try{File file=AgentFiles.journal(app,"downloads",id);JSONObject state=AgentFiles.read(file);state.put("state","failed").put("reason",e.getMessage());AgentFiles.save(file,state);}catch(Exception ignored){}}
        }),2000L);
    }
    private static JSONObject publicState(JSONObject state)throws Exception{JSONObject out=new JSONObject(state.toString());out.remove("fingerprint");return out;}
    private static void finish(AgentClient.ResultCallback cb,boolean ok,JSONObject data){finish(cb,ok,data,null);}
    private static void finish(AgentClient.ResultCallback cb,boolean ok,JSONObject data,String error){
        WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(ok,error==null?data.toString():error);});
    }
}
