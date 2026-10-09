package com.chk.agentbrowser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One serial writer shared by the UI, visible browser and background agent. */
public final class WorkspaceCommands {
    public static final ExecutorService IO=Executors.newSingleThreadExecutor();
    public static final Handler UI=new Handler(Looper.getMainLooper());
    private WorkspaceCommands(){}
    public static boolean handles(String action){return action.startsWith("files_")||action.startsWith("notes_")||action.equals("workspace_status")||action.startsWith("video_editor_");}
    public static void run(Context context,String action,JSONObject args,AgentClient.ResultCallback cb){
        Context app=context.getApplicationContext();
        if(action.startsWith("video_editor_")){
            VideoEditorEngine.get().command(app,action,args,(ok,result)->cb.finish(ok,result));
            return;
        }
        if(action.equals("files_import")){
            BrowserTransferManager.prepareUploads(app,args.optJSONArray("files"),AgentClient.get(app).deviceTokenForTransfers(),(ok,uris,message)->{
                if(!ok){cb.finish(false,message);return;}
                IO.execute(()->{try{
                    WorkspaceStore s=new WorkspaceStore(app);JSONArray rows=new JSONArray();JSONArray files=args.getJSONArray("files");
                    // Validate the whole destination set before writing any file.
                    java.util.HashSet<String> paths=new java.util.HashSet<>();
                    for(int i=0;i<uris.length;i++){String p=WorkspacePaths.child(args.optString("folder",""),files.getJSONObject(i).getString("name"));if(!paths.add(p)||s.file(p).exists()||!s.file(p).getParentFile().isDirectory())throw new Exception("Destination existante ou parent absent : "+p);}
                    for(int i=0;i<uris.length;i++){String p=WorkspacePaths.child(args.optString("folder",""),files.getJSONObject(i).getString("name"));s.importUri(uris[i],p);rows.put(p);}
                    finish(cb,true,new JSONObject().put("imported",rows).toString());
                }catch(Exception e){finish(cb,false,"Importation interrompue, certains fichiers peuvent être présents : "+e.getMessage());}});
            });return;
        }
        IO.execute(()->{try{
            WorkspaceStore s=new WorkspaceStore(app);String path=args.optString("path","");Object result;
            switch(action){
                case "workspace_status":result=new JSONObject().put("storage","private_phone").put("files",true).put("notes",true).put("trash",true).put("revision_guard",true).put("max_remote_file_bytes",12582912).put("max_local_file_bytes",WorkspaceStore.MAX_IMPORT);break;
                case "files_list":result=s.list(path,args.optString("query",""),args.optInt("offset",0));break;
                case "files_mkdir":s.mkdir(path);result=new JSONObject().put("path",path);break;
                case "files_write_text":result=s.writeText(path,args.getString("text"),args.optBoolean("overwrite",false));break;
                case "files_read_text":result=s.readText(path,args.optInt("offset",0));break;
                case "files_read_bytes":result=s.readBytes(path,args.optLong("offset",0));break;
                case "files_downloads":result=s.downloads();break;
                case "files_import_download":result=new JSONObject().put("path",s.importDownload(args.getString("name"),path));break;
                case "files_preview":result=s.preview(path);break;
                case "files_move":s.move(path,args.getString("destination"));result=new JSONObject().put("path",args.getString("destination"));break;
                case "files_trash":result=new JSONObject().put("trash_id",s.trash(path));break;
                case "files_trash_list":result=s.trashList();break;
                case "files_restore":s.restore(args.getString("id"));result=new JSONObject().put("restored",true);break;
                case "notes_list":result=s.notes(args.optString("query",""),args.optBoolean("deleted",false),args.optInt("offset",0));break;
                case "notes_read":result=s.note(args.getString("id"));break;
                case "notes_save":result=s.saveNote(args.optString("id",""),args.getString("title"),args.getString("body"),args.optString("source_url",""),args.optBoolean("pinned",false),args.optInt("revision",0));break;
                case "notes_trash":s.deleteNote(args.getString("id"),true,args.getInt("revision"));result=new JSONObject().put("trashed",true);break;
                case "notes_restore":s.deleteNote(args.getString("id"),false,args.getInt("revision"));result=new JSONObject().put("restored",true);break;
                case "notes_export":JSONObject n=s.note(args.getString("id"));result=s.writeText(path,"# "+n.getString("title")+"\n\n"+n.getString("body")+"\n\n"+n.getString("source_url"),false);break;
                default:throw new Exception("Commande espace de travail inconnue");
            }
            finish(cb,true,result.toString());
        }catch(Exception e){finish(cb,false,e.getMessage()==null?"Opération impossible":e.getMessage());}});
    }
    private static void finish(AgentClient.ResultCallback cb,boolean ok,String text){UI.post(()->cb.finish(ok,text));}
}
