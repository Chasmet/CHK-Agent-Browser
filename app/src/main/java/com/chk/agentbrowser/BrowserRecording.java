package com.chk.agentbrowser;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import org.json.JSONObject;
import java.io.File;

/** Encodes the compositor output of the actual controlled WebView, on its own private display. */
final class BrowserRecording {
    private static volatile Session current;
    private BrowserRecording(){}
    static boolean active(){return current!=null&&!current.finishing;}
    static boolean owns(WebView web){return active()&&current.web==web;}
    static JSONObject status(Context app,JSONObject args)throws Exception {
        String project=ProductionProject.root(args),id=ProductionProject.id(args);
        if(current!=null&&current.project.equals(project)&&current.id.equals(id))return current.status();
        JSONObject saved=AgentFiles.read(new WorkspaceStore(app).file(project+"/Captures/"+id+".json"));
        if("recording".equals(saved.optString("state")))saved.put("state","interrupted")
            .put("valid",false).put("error","Processus interrompu : le fichier partiel reste non vérifié");
        return saved;
    }
    static void start(Context app,WebView web,JSONObject args,AgentClient.ResultCallback callback){
        WorkspaceCommands.UI.post(()->{
            try{
                if(Build.VERSION.SDK_INT<26)throw new Exception("La capture de l'onglet demande Android 8 ou plus");
                if(!AgentClient.get(app).isPreviewAllowed())throw new Exception("Active les aperçus de l'agent dans Réglages");
                String project=ProductionProject.root(args),id=ProductionProject.id(args);
                ProductionProject.read(app,project);
                if(current!=null){
                    if(current.project.equals(project)&&current.id.equals(id)){callback.finish(true,current.status().toString());return;}
                    throw new Exception("Un autre onglet est déjà en cours d'enregistrement");
                }
                File record=new WorkspaceStore(app).file(project+"/Captures/"+id+".json");
                if(record.isFile()){
                    JSONObject previous=status(app,args);
                    if("completed".equals(previous.optString("state"))){callback.finish(true,previous.toString());return;}
                    throw new Exception("Cet enregistrement a été interrompu. Utilise un nouveau request_id");
                }
                Session session=new Session(app.getApplicationContext(),web,project,id,args);
                current=session;
                try{session.start();callback.finish(true,session.status().toString());}
                catch(Exception error){session.stop(error.getMessage());throw error;}
            }catch(Exception error){callback.finish(false,error.getMessage());}
        });
    }
    static void stop(Context app,JSONObject args,AgentClient.ResultCallback callback){
        WorkspaceCommands.UI.post(()->{
            try{
                String path=ProductionProject.root(args),id=ProductionProject.id(args);
                if(current!=null&&current.project.equals(path)&&current.id.equals(id))current.stop("");
                callback.finish(true,status(app,args).toString());
            }catch(Exception error){callback.finish(false,error.getMessage());}
        });
    }
    static void stopFromNotification(){if(current!=null)current.stop("");}
    static void capture(WebView web,JSONObject info,PrivateWebCapture.Result result){
        if(!owns(web)){result.unavailable("Enregistrement terminé");return;}
        current.capture(info,result);
    }
    @android.annotation.TargetApi(26)
    private static final class Session {
        final Context app;final WebView web;final String project,id,tabId;final JSONObject args;
        final long started=SystemClock.elapsedRealtime();
        MediaRecorder recorder;VirtualDisplay display;Presentation presentation;FrameLayout holder;
        ViewGroup parent;ViewGroup.LayoutParams layout;int index,visibility,left,top,right,bottom,width,height;
        boolean preraster,moved,startedRecorder;volatile boolean finishing;File partial,destination,receipt;
        String sourceUrl="",error="";volatile JSONObject finalStatus;
        final Runnable drawTick=new Runnable(){public void run(){
            if(finishing)return;
            web.invalidate();WorkspaceCommands.UI.postDelayed(this,42);
        }};
        final Runnable monitor=new Runnable(){public void run(){
            if(finishing)return;
            if(!BrowserWebState.alive(web)||web.getParent()!=holder){stop("L'onglet a été fermé ou repris");return;}
            if(!AgentClient.get(app).isPreviewAllowed()){stop("L'autorisation d'aperçu a été retirée");return;}
            if(SystemClock.elapsedRealtime()-started>=args.optLong("max_duration_ms",120000)){
                stop("");return;
            }
            web.onResume();
            if(Build.VERSION.SDK_INT>=33)android.graphics.HardwareRenderer.setDrawingEnabled(true);
            WorkspaceCommands.UI.postDelayed(this,500);
        }};
        Session(Context app,WebView web,String project,String id,JSONObject args){
            this.app=app;this.web=web;this.project=project;this.id=id;this.args=args;this.tabId=BrowserTabIds.id(web);
        }
        @SuppressWarnings("deprecation") void start()throws Exception {
            if(!BrowserWebState.alive(web)||web.getWidth()<2||web.getHeight()<2)throw new Exception("Onglet non chargé ou sans dimensions");
            android.view.Window owner=null;
            try{owner=NativeKeyboard.window();}catch(Exception ignored){}
            if(owner!=null&&(owner.getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0)
                throw new SecurityException("Fenêtre protégée : capture refusée");
            long limit=args.optLong("max_duration_ms",120000);
            if(limit<1000||limit>600000)throw new Exception("Durée de capture entre 1 seconde et 10 minutes");
            width=args.optInt("width",web.getWidth())/2*2;height=args.optInt("height",web.getHeight())/2*2;
            if(width<160||height<160||width>4096||height>4096||(long)width*height>8500000)
                throw new Exception("Dimensions de l'onglet non prises en charge");
            WorkspaceStore store=new WorkspaceStore(app);
            partial=store.file(project+"/Captures/"+id+".partial.mp4");
            destination=store.file(project+"/Captures/"+id+".mp4");
            receipt=store.file(project+"/Captures/"+id+".json");
            if(destination.exists()||partial.exists())throw new Exception("Un fichier porte déjà ce request_id");
            TransferLimits.requireSpace(partial,32*1024*1024);
            BrowserRecordingService.start(app);
            recorder=new MediaRecorder();recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(width,height);recorder.setVideoFrameRate(24);
            recorder.setVideoEncodingBitRate(Math.min(12000000,Math.max(1500000,width*height*4)));
            recorder.setMaxDuration((int)limit);recorder.setMaxFileSize(512L*1024*1024);
            recorder.setOutputFile(partial.getAbsolutePath());
            recorder.setOnInfoListener((r,what,extra)->{
                if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED||what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)stop("");
            });
            recorder.setOnErrorListener((r,what,extra)->stop("Encodeur vidéo interrompu : "+what+"/"+extra));
            recorder.prepare();
            if(Build.VERSION.SDK_INT>=33)android.graphics.HardwareRenderer.setDrawingEnabled(true);
            parent=web.getParent() instanceof ViewGroup?(ViewGroup)web.getParent():null;
            index=parent==null?0:parent.indexOfChild(web);layout=web.getLayoutParams();visibility=web.getVisibility();
            left=web.getLeft();top=web.getTop();right=web.getRight();bottom=web.getBottom();
            preraster=web.getSettings().getOffscreenPreRaster();sourceUrl=web.getUrl()==null?"":web.getUrl();
            DisplayManager manager=(DisplayManager)app.getSystemService(Context.DISPLAY_SERVICE);
            display=manager.createVirtualDisplay("CHK agent recording",width,height,app.getResources().getDisplayMetrics().densityDpi,
                recorder.getSurface(),DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY|DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
            if(display==null)throw new Exception("Affichage privé indisponible");
            presentation=new Presentation(app,display.getDisplay(),android.R.style.Theme_Material_Light_NoActionBar);
            presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED|
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            presentation.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
            holder=new FrameLayout(presentation.getContext());
            if(parent!=null)parent.removeView(web);moved=true;
            web.setVisibility(View.VISIBLE);web.getSettings().setOffscreenPreRaster(true);
            holder.addView(web,new FrameLayout.LayoutParams(width,height));
            presentation.setContentView(holder);presentation.show();presentation.getWindow().setLayout(width,height);
            recorder.start();startedRecorder=true;web.onResume();web.invalidate();
            StudioAutomation.write(receipt,status());WorkspaceCommands.UI.postDelayed(monitor,500);WorkspaceCommands.UI.post(drawTick);
        }
        JSONObject status()throws Exception {
            if(finalStatus!=null)return new JSONObject(finalStatus.toString());
            return new JSONObject().put("request_id",id).put("project_path",project)
                .put("state",finishing?"finalizing":"recording").put("output",project+"/Captures/"+id+".mp4")
                .put("elapsed_ms",SystemClock.elapsedRealtime()-started).put("width",width).put("height",height)
                .put("source_url",sourceUrl).put("source","private_webview_compositor")
                .put("tab_id",tabId).put("has_audio",false).put("valid",false)
                .put("screen_capture_permission_required",false).put("protected_content_supported",false);
        }
        void capture(JSONObject info,PrivateWebCapture.Result result){
            try{
                Bitmap bitmap=Bitmap.createBitmap(info.getInt("image_width"),info.getInt("image_height"),Bitmap.Config.ARGB_8888);
                PixelCopy.request(presentation.getWindow(),new Rect(0,0,width,height),bitmap,status->{
                    if(status==PixelCopy.SUCCESS&&!finishing)result.ready(bitmap);
                    else{bitmap.recycle();result.unavailable("Pixels de l'enregistrement indisponibles : "+status);}
                },WorkspaceCommands.UI);
            }catch(Exception failure){result.unavailable(failure.getMessage());}
        }
        void restore(){
            WorkspaceCommands.UI.removeCallbacks(monitor);WorkspaceCommands.UI.removeCallbacks(drawTick);
            try{
                if(moved&&(web.getParent()==holder||web.getParent()==null)){
                    if(web.getParent()==holder)holder.removeView(web);
                    web.setVisibility(visibility);web.getSettings().setOffscreenPreRaster(preraster);
                    if(parent!=null&&BrowserWebState.alive(web))parent.addView(web,Math.min(index,parent.getChildCount()),layout);
                    web.measure(View.MeasureSpec.makeMeasureSpec(right-left,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(bottom-top,View.MeasureSpec.EXACTLY));
                    web.layout(left,top,right,bottom);
                }
            }catch(Exception ignored){}
            try{if(presentation!=null)presentation.dismiss();}catch(Exception ignored){}
            try{if(display!=null)display.release();}catch(Exception ignored){}
        }
        void stop(String reason){
            if(finishing)return;finishing=true;error=reason==null?"":reason;
            restore();
            WorkspaceCommands.IO.execute(()->{
                JSONObject result;
                try{
                    if(!startedRecorder)throw new Exception(error.isEmpty()?"L'enregistrement n'a pas démarré":error);
                    recorder.stop();
                    if(!error.isEmpty())throw new Exception(error);
                    if(partial.length()<1000)throw new Exception("Enregistrement vide");
                    MediaMetadataRetriever probe=new MediaMetadataRetriever();
                    long duration;
                    try{
                        probe.setDataSource(partial.getAbsolutePath());
                        duration=Long.parseLong(probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                        Bitmap frame=probe.getFrameAtTime(0);
                        if(frame==null||duration<250)throw new Exception("Aucune image vidéo décodable");
                        frame.recycle();
                    }finally{probe.release();}
                    if(destination.exists()||!partial.renameTo(destination))throw new Exception("Finalisation MP4 impossible");
                    result=status().put("state","completed").put("valid",true).put("duration_ms",duration)
                        .put("size",destination.length()).put("sha256",WorkspaceTransfer.hash(destination));
                    ProductionProject.asset(app,project,result.getString("output"),"browser_recording",result);
                }catch(Exception failure){
                    try{result=status().put("state","failed").put("valid",false).put("error",failure.getMessage());}
                    catch(Exception ignored){result=new JSONObject();}
                }finally{try{if(recorder!=null)recorder.release();}catch(Exception ignored){}}
                finalStatus=result;
                try{if(receipt!=null)StudioAutomation.write(receipt,result);}catch(Exception ignored){}
                WorkspaceCommands.UI.post(()->{if(current==this)current=null;BrowserRecordingService.finish(app);});
            });
        }
    }
}
