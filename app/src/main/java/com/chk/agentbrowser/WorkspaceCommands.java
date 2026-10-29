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
    public static boolean handles(String action){return StudioCommands.handles(action)||action.startsWith("tor_")||action.startsWith("files_")||action.startsWith("notes_")||action.equals("workspace_status")||action.startsWith("video_editor_")||action.startsWith("media_")||action.startsWith("document_")||PublicationJobs.metadataAction(action);}
    public static void run(Context context,String action,JSONObject args,AgentClient.ResultCallback cb){
        if(!cb.isActive())return;
        Context app=context.getApplicationContext();
        if(StudioCommands.handles(action)){StudioCommands.run(app,action,args,cb);return;}
        if(action.startsWith("tor_")){TorSpace.command(app,action,args,cb);return;}
        if(action.equals("media_analyze_audio")||action.equals("media_listen")||action.equals("media_audio_capabilities")){
            try{AgentHearing.execute(app,new JSONObject(args.toString()).put("operation",action.equals("media_listen")?"listen":action.equals("media_analyze_audio")?"analyze":"capabilities"),cb);}
            catch(Exception e){finish(cb,false,e.getMessage());}return;
        }
        if(action.equals("files_browser_capabilities")){finish(cb,true,BrowserAutomation.capabilities().toString());return;}
        if(action.equals("files_browser_upload_status")){
            try{finish(cb,true,BrowserAutomation.uploadStatus(app,args.getString("id")).toString());}
            catch(Exception e){finish(cb,false,e.getMessage());}return;
        }
        if(action.equals("files_task")&&args.optString("operation").equals("cancel"))AgentTasks.requestCancel(args.optString("id"));
        if(action.equals("video_editor_wait")){
            final long deadline=android.os.SystemClock.elapsedRealtime()+Math.max(1000,Math.min(120000,args.optLong("timeout_ms",120000)));
            UI.post(new Runnable(){public void run(){
                if(!cb.isActive())return;
                JSONObject state=VideoEditorEngine.get().status();String status=state.optString("state");
                if(status.equals("completed")){cb.finish(true,state.toString());return;}
                if(!status.equals("preparing")&&!status.equals("running")){cb.finish(false,state.toString());return;}
                if(android.os.SystemClock.elapsedRealtime()>=deadline){cb.finish(false,"Export encore actif : lire video_editor_status ; ne pas relancer l’export.");return;}
                UI.postDelayed(this,1000);
            }});return;
        }
        if(action.equals("video_editor_subtitles_generate")){
            if(!args.has("expected_revision")){finish(cb,false,"expected_revision requis pour générer des sous-titres.");return;}
            final int expected=args.optInt("expected_revision",-1);final String language=args.optString("language","fr");
            VideoEditorEngine.DISK.execute(()->{try{
                JSONObject project=VideoEditorEngine.get().load(app);
                if(project.optInt("revision")!=expected)throw new Exception("Révision périmée : relire le projet Studio.");
                final String projectId=project.optString("project_id");final String snapshot=project.toString();
                MediaTranscriber.IO.execute(()->{try{
                    JSONArray cues=StudioSubtitles.generate(app,new JSONObject(snapshot),language,cb,null);
                    if(!cb.isActive())return;JSONObject candidate=new JSONObject(snapshot).put("subtitles",cues);
                    VideoEditorEngine.get().command(app,"video_editor_project_save",new JSONObject().put("project",candidate).put("expected_revision",expected),(ok,result)->{
                        if(!cb.isActive())return;try{if(!ok){finish(cb,false,result);return;}
                            JSONObject out=new JSONObject(result).put("subtitle_count",cues.length()).put("engine","vosk_local").put("project_id",projectId).put("timing","word_timestamps").put("audio_uploaded",false).put("warning","Paroles et temps automatiques à vérifier.");finish(cb,true,out.toString());
                        }catch(Exception e){finish(cb,false,e.getMessage());}
                    });
                }catch(Exception e){finish(cb,false,e.getMessage()==null?"Sous-titres indisponibles.":e.getMessage());}});
            }catch(Exception e){finish(cb,false,e.getMessage());}});return;
        }
        if(action.startsWith("video_editor_")){
            if(cb.isLocal3()&&action.equals("video_editor_project_save")&&!args.has("expected_revision")){
                cb.finish(false,"expected_revision requis : relire le projet Studio avant de sauvegarder.");return;
            }
            VideoEditorEngine.get().command(app,action,args,(ok,result)->finish(cb,ok,result));
            return;
        }
        if(action.equals("files_open")){IO.execute(()->{try{FileFormats.detect(app,args.optString("path"));FileOpenRequests.open(app,args.optString("path"),cb);}catch(Exception ex){finish(cb,false,ex.getMessage());}});return;}
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
                        request.optString("language","fr"),request.optInt("max_seconds",120),request.optLong("start_ms",0),cb);
                    finish(cb,true,result.toString());
                }catch(Exception e){finish(cb,false,e.getMessage()==null?"Transcription impossible.":e.getMessage());}
            });
            return;
        }
        if(action.equals("workspace_status")){
            // WebView methods (including getUrl/visibility) belong to the main
            // looper. Snapshot the browser there, then read storage on IO.
            UI.post(()->{
                if(!cb.isActive())return;
                try{runStorage(app,action,args,cb,AgentClient.get(app).local2BrowserStatus());}
                catch(Exception e){finish(cb,false,e.getMessage()==null?"État du navigateur indisponible.":e.getMessage());}
            });return;
        }
        runStorage(app,action,args,cb,null);
    }
    private static void runStorage(Context app,String action,JSONObject args,AgentClient.ResultCallback cb,JSONObject browserStatus){
        IO.execute(()->{try{
            if(!cb.isActive())return;
            WorkspaceStore s=new WorkspaceStore(app);String path=args.optString("path","");Object result;
            switch(action){
                case "files_detect_format":result=FileFormats.detect(app,path);break;
                case "files_copy_content":result=new JSONObject().put("path",path).put("text",FileContent.full(app,path,200000)).put("complete",true).put("clipboard_written",false);break;
                case "files_archive_list":result=UniversalFiles.archiveList(app,path,args.optInt("offset",0));break;
                case "files_archive_read":result=UniversalFiles.archiveText(app,path,args.getString("entry"));break;
                case "files_extract_archive":result=UniversalFiles.extract(app,path,args.getString("destination"),args.optJSONArray("entries"),cb);break;
                case "files_convert":result=UniversalFiles.convert(app,args,cb);break;
                case "files_search_content":result=FileOperations.search(app,args,cb);break;
                case "files_database":result=UniversalFiles.database(app,path,args);break;
                case "files_media_inspect":result=UniversalFiles.preview(app,path,args);break;
                case "files_batch":result=FileOperations.batch(app,args,cb);break;
                case "files_verify":JSONObject stat=WorkspaceTransfer.stat(s,path,true);boolean matches=!args.has("expected_sha256")||args.getString("expected_sha256").equals(stat.getString("sha256"));result=stat.put("verified",matches);if(!matches)throw new Exception("Empreinte SHA-256 différente de celle attendue");break;
                case "files_self_test":result=WorkspaceHealth.run(app);break;
                case "files_capabilities":result=WorkspaceCapabilities.catalog(args);break;
                case "files_stat":result=WorkspaceTransfer.stat(s,path,false);break;
                case "files_hash":result=WorkspaceTransfer.stat(s,path,true);break;
                case "files_zip":case "files_unzip":result=WorkspaceArchive.command(app,action,args,cb);break;
                case "files_transfer_begin":case "files_transfer_write":case "files_transfer_status":
                case "files_transfer_commit":case "files_transfer_abort":case "files_transfer_read":
                    result=WorkspaceTransfer.command(app,action,args,cb);break;
                case "files_external":result=ExternalFiles.command(app,args,cb);break;
                case "files_task":result=AgentTasks.command(app,args,cb.isLocal2(),cb.isLocal3());break;
                case "document_create":result=WorkspaceDocuments.create(app,args,cb);break;
                case "publication_prepare":
                case "publication_status":
                case "publication_list":
                case "publication_cancel":
                    result=PublicationJobs.command(app,action,args);break;
                case "workspace_status":result=new JSONObject().put("storage","private_phone").put("files",true).put("notes",true).put("video_editor",true).put("media_inspection",true).put("workspace_version",WorkspaceCapabilities.VERSION).put("capabilities_action","files_capabilities").put("health",WorkspaceHealth.cached(app)).put("resumable_transfers",true).put("sha256_verification",true).put("transfer_chunk_bytes",WorkspaceTransfer.UPLOAD_CHUNK).put("export_chunk_bytes",WorkspaceTransfer.CHUNK).put("archives",true).put("document_creation","pdf,docx,xlsx,pptx,json,csv,txt").put("external_roots",ExternalFiles.grants(app)).put("local2",browserStatus).put("document_reader_version",2).put("pdf_text_extraction",true).put("scanned_pdf_ocr",true).put("docx_text_extraction",true).put("xlsx_value_extraction",true).put("pptx_text_extraction",true).put("image_ocr",true).put("speech_to_text",true).put("audio_languages","fr,en").put("audio_local_only",true).put("trash",true).put("revision_guard",true).put("max_remote_file_bytes",TransferLimits.MAX_FILE_BYTES).put("max_local_file_bytes",WorkspaceStore.MAX_IMPORT).put("large_files_native_stream",true).put("max_file_bytes",TransferLimits.MAX_FILE_BYTES);break;
                case "document_read":result=DocumentReader.readFile(app,path,args.optInt("page",1),args.optInt("page_count",8),args.optInt("offset",0));break;
                case "document_page":result=DocumentReader.pdfPreview(app,s.file(path),args.optInt("page",0));break;
                case "media_model_status":result=MediaTranscriber.modelStatus(app);break;
                case "media_info":result=MediaInspection.info(app,path);break;
                case "media_frame":result=MediaInspection.frame(app,path,args.optLong("time_ms",0));break;
                case "media_codecs":result=MediaInspection.codecs();break;
                case "files_catalog":result=WorkspaceCatalog.query(app,args.optString("folder",""),args.optString("category","all"),args.optString("query",""),args.optString("sort","name"),args.optInt("offset",0));break;
                case "files_copy":result=WorkspaceArchive.copy(app,path,args.getString("destination"),cb);break;
                case "files_list":result=s.list(path,args.optString("query",""),args.optInt("offset",0));break;
                case "files_mkdir":s.mkdir(path);
                    if(!s.file(path).isDirectory())throw new Exception("Dossier créé mais présence non confirmée.");
                    result=new JSONObject().put("path",path).put("exists",true).put("directory",true).put("storage","private_phone");break;
                case "files_write_text":
                    if("CutVideo/schedule_inbox.json".equals(path))
                        result=CutStudioStore.importFromAgent(app,args.getString("text"));
                    else if(CutStudioAutomation.COMMAND_PATH.equals(path))
                        result=CutStudioAutomation.submit(app,args.getString("text"));
                    else if(CutStudioAutomation.CANCEL_PATH.equals(path))
                        result=CutStudioAutomation.cancel(app,args.getString("text"));
                    else result=s.writeText(path,args.getString("text"),args.optBoolean("overwrite",false));
                    break;
                case "files_edit_text":result=s.editText(path,args.getString("text"),args.getString("expected_sha256"));break;
                case "files_read_text":JSONObject type=FileFormats.detect(app,path);String kind=type.getString("kind");
                    if(kind.equals("text"))result=FileContent.page(app,path,args.optInt("offset",0));
                    else if(kind.equals("image"))result=DocumentReader.pageText(DocumentReader.readImage(s.file(path)),args.optInt("offset",0));
                    else if(kind.equals("audio")||kind.equals("media"))result=MediaTranscriber.transcribe(app,path,"fr",120);
                    else result=UniversalFiles.preview(app,path,args);break;
                case "files_read_bytes":result=s.readBytes(path,args.optLong("offset",0));break;
                case "files_downloads":result=s.downloads();break;
                case "files_import_download":result=new JSONObject().put("path",s.importDownload(args.getString("name"),path));break;
                case "files_preview":JSONObject info=FileFormats.detect(app,path);result=info.optString("kind").equals("image")?s.preview(path):info.optString("format").equals("pdf")&&!args.optString("view").equals("text")?DocumentReader.pdfPreview(app,s.file(path),args.optInt("page",0)):UniversalFiles.preview(app,path,args);break;
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
                default:throw new Exception("Commande espace de travail inconnue : "+action+". Consulter files_capabilities.");
            }
            AgentLog.add(app,"WORKSPACE",action+" · résultat confirmé");
            finish(cb,true,result.toString());
        }catch(Exception e){AgentLog.add(app,"WORKSPACE",action+" · échec · "+e.getClass().getSimpleName());finish(cb,false,e.getMessage()==null?"Opération impossible":e.getMessage());}});
    }
    private static void finish(AgentClient.ResultCallback cb,boolean ok,String text){UI.post(()->cb.finish(ok,text));}
}
