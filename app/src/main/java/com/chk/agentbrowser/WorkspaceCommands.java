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
    public static boolean handles(String action){return action.startsWith("files_")||action.startsWith("notes_")||action.equals("workspace_status")||action.startsWith("video_editor_")||action.startsWith("media_")||action.startsWith("document_")||action.startsWith("cut_")||action.equals("workspace_capabilities")||PublicationJobs.metadataAction(action);}
    public static void run(Context context,String action,JSONObject args,AgentClient.ResultCallback cb){
        if(!cb.isActive())return;
        Context app=context.getApplicationContext();
        if(action.startsWith("video_editor_")){
            VideoEditorEngine.get().command(app,action,args,(ok,result)->cb.finish(ok,result));
            return;
        }
        if(action.equals("files_import")){
            BrowserTransferManager.prepareUploads(app,args.optJSONArray("files"),AgentClient.get(app).deviceTokenForTransfers(),(ok,uris,message)->{
                if(!ok){cb.finish(false,message);return;}
                IO.execute(()->{try{
                    if(!cb.isActive())return;
                    WorkspaceStore s=new WorkspaceStore(app);JSONArray rows=new JSONArray();JSONArray files=args.getJSONArray("files");
                    // Validate the whole destination set before writing any file.
                    java.util.HashSet<String> paths=new java.util.HashSet<>();
                    for(int i=0;i<uris.length;i++){String p=WorkspacePaths.child(args.optString("folder",""),files.getJSONObject(i).getString("name"));if(!paths.add(p)||s.file(p).exists()||!s.file(p).getParentFile().isDirectory())throw new Exception("Destination existante ou parent absent : "+p);}
                    for(int i=0;i<uris.length;i++){if(!cb.isActive())return;String p=WorkspacePaths.child(args.optString("folder",""),files.getJSONObject(i).getString("name"));s.importUri(uris[i],p);rows.put(p);}
                    finish(cb,true,new JSONObject().put("imported",rows).toString());
                }catch(Exception e){finish(cb,false,"Importation interrompue, certains fichiers peuvent être présents : "+e.getMessage());}});
            });return;
        }
        if(action.equals("media_transcribe")){
            final JSONObject request=args;
            MediaTranscriber.IO.execute(()->{
                try{
                    if(!cb.isActive())return;
                    JSONObject result=MediaTranscriber.transcribe(app,request.getString("path"),
                        request.optString("language","fr"),request.optInt("max_seconds",120));
                    finish(cb,true,result.toString());
                }catch(Exception e){finish(cb,false,e.getMessage()==null?"Transcription impossible.":e.getMessage());}
            });
            return;
        }
        IO.execute(()->{try{
            if(!cb.isActive())return;
            WorkspaceStore s=new WorkspaceStore(app);String path=args.optString("path","");Object result;
            switch(action){
                case "publication_prepare":
                case "publication_status":
                case "publication_list":
                case "publication_cancel":
                    result=PublicationJobs.command(app,action,args);break;
                case "workspace_capabilities":result=new JSONObject().put("protocol_version",4).put("task_actions",AgentTasks.actions()).put("session_binding",true).put("max_task_steps",50).put("max_local_file_bytes",WorkspaceStore.MAX_IMPORT).put("cut_resume",true).put("studio_project_library",true);break;
                case "files_references":result=WorkspaceReferences.references(app,path);break;
                case "files_archive":result=WorkspaceArchive.create(app,args.getJSONArray("paths"),args.getString("destination"));break;
                case "cut_submit":result=CutStudioAutomation.submit(app,args.toString());break;
                case "cut_status":result=CutStudioAutomation.status(app);break;
                case "cut_cancel":result=CutStudioAutomation.cancel(app,args.toString());break;
                case "cut_resume":result=CutStudioAutomation.resume(app,args);break;
                case "cut_schedule":result=CutStudioStore.importFromAgent(app,args.toString());break;
                case "cut_planning":result=new JSONObject().put("entries",CutStudioStore.all(app));break;
                case "workspace_status":result=new JSONObject().put("storage","private_phone").put("files",true).put("notes",true).put("video_editor",true).put("media_inspection",true).put("workspace_version",4).put("document_reader_version",1).put("pdf_text_extraction",true).put("scanned_pdf_ocr",true).put("docx_text_extraction",true).put("image_ocr",true).put("speech_to_text",true).put("audio_languages","fr,en").put("audio_local_only",true).put("trash",true).put("revision_guard",true).put("max_remote_file_bytes",12582912).put("max_local_file_bytes",WorkspaceStore.MAX_IMPORT);break;
                case "document_read":result=DocumentReader.readFile(app,path,args.optInt("page",1),args.optInt("page_count",8),args.optInt("offset",0));break;
                case "document_page":result=DocumentReader.pdfPreview(app,s.file(path),args.optInt("page",0));break;
                case "media_model_status":result=MediaTranscriber.modelStatus(app);break;
                case "media_info":result=MediaInspection.info(app,path);break;
                case "media_frame":result=MediaInspection.frame(app,path,args.optLong("time_ms",0));break;
                case "media_codecs":result=MediaInspection.codecs();break;
                case "files_catalog":result=WorkspaceCatalog.query(app,args.optString("folder",""),args.optString("category","all"),args.optString("query",""),args.optString("sort","name"),args.optInt("offset",0));break;
                case "files_copy":result=WorkspaceCatalog.copy(app,path,args.getString("destination"));break;
                case "files_list":result=s.list(path,args.optString("query",""),args.optInt("offset",0));break;
                case "files_mkdir":s.mkdir(path);result=new JSONObject().put("path",path);break;
                case "files_write_text":
                    if("CutVideo/schedule_inbox.json".equals(path))
                        result=CutStudioStore.importFromAgent(app,args.getString("text"));
                    else if(CutStudioAutomation.COMMAND_PATH.equals(path))
                        result=CutStudioAutomation.submit(app,args.getString("text"));
                    else if(CutStudioAutomation.CANCEL_PATH.equals(path))
                        result=CutStudioAutomation.cancel(app,args.getString("text"));
                    else result=s.writeText(path,args.getString("text"),args.optBoolean("overwrite",false));
                    break;
                case "files_read_text":result=DocumentReader.supported(path)?DocumentReader.readFile(app,path,args.optInt("page",1),args.optInt("page_count",8),args.optInt("offset",0)):
                        (path.toLowerCase(java.util.Locale.ROOT).matches(".*\\\\.(mp3|wav|m4a|aac|ogg|flac|mp4|webm|mkv|mov)$")?MediaTranscriber.transcribe(app,path,"fr",120):s.readText(path,args.optInt("offset",0)));break;
                case "files_read_bytes":result=s.readBytes(path,args.optLong("offset",0));break;
                case "files_downloads":result=s.downloads();break;
                case "files_import_download":result=new JSONObject().put("path",s.importDownload(args.getString("name"),path));break;
                case "files_preview":result=path.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")?DocumentReader.pdfPreview(app,s.file(path),0):s.preview(path);break;
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
