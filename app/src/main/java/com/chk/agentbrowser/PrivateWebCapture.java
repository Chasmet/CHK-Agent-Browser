package com.chk.agentbrowser;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.graphics.PixelFormat;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.PixelCopy;
import android.webkit.WebView;
import android.widget.FrameLayout;
import org.json.JSONObject;

/** Renders the existing CHK tab on its own PRIVATE display, never mirrors the phone. */
final class PrivateWebCapture {
    interface Result {void ready(Bitmap image);void unavailable(String reason);}
    static void capture(Context app,WebView web,JSONObject info,AgentClient.ResultCallback owner,Result result){
        if(android.os.Build.VERSION.SDK_INT<26){result.unavailable("Rendu privé PixelCopy disponible à partir d’Android 8.");return;}
        new Session(app,web,info,owner,result).start();
    }
    @android.annotation.TargetApi(26)
    private static final class Session {
        final Context app;final WebView web;final JSONObject info;final AgentClient.ResultCallback owner;final Result result;
        ViewGroup parent;ViewGroup.LayoutParams layout;int index,visibility;int left,top,right,bottom;boolean preraster;
        ImageReader reader;VirtualDisplay display;Presentation presentation;FrameLayout holder;boolean ended,moved;
        Runnable timeout,monitor;String expected;Bitmap pending;
        Session(Context app,WebView web,JSONObject info,AgentClient.ResultCallback owner,Result result){this.app=app;this.web=web;this.info=info;this.owner=owner;this.result=result;}
        void start(){
            try{
                if(!owner.isActive())throw new Exception("Capture annulée.");
                if(!BrowserWebState.alive(web))throw new Exception("Onglet fermé.");
                if(AgentVision.visibleWindow(web))throw new Exception("L'onglet est revenu au premier plan : refaire l'observation.");
                try{if((NativeKeyboard.window().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0)throw new SecurityException("Fenêtre protégée.");}catch(SecurityException e){throw e;}catch(Exception ignored){}
                int width=web.getWidth(),height=web.getHeight();
                if(width<=0||height<=0||(long)width*height>4_500_000L)throw new Exception("Dimensions de capture privée hors limite.");
                expected=AgentVision.context(web);parent=web.getParent() instanceof ViewGroup?(ViewGroup)web.getParent():null;
                layout=web.getLayoutParams();index=parent==null?-1:parent.indexOfChild(web);visibility=web.getVisibility();preraster=web.getSettings().getOffscreenPreRaster();
                left=web.getLeft();top=web.getTop();right=web.getRight();bottom=web.getBottom();
                reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
                // Drain compositor buffers; PixelCopy reads the Presentation window directly.
                reader.setOnImageAvailableListener(r->{try(android.media.Image image=r.acquireLatestImage()){}catch(Exception ignored){}},WorkspaceCommands.UI);
                DisplayManager manager=(DisplayManager)app.getSystemService(Context.DISPLAY_SERVICE);
                display=manager.createVirtualDisplay("CHK private tab capture",width,height,app.getResources().getDisplayMetrics().densityDpi,reader.getSurface(),DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                if(display==null)throw new Exception("Affichage privé indisponible.");
                presentation=new Presentation(app,display.getDisplay(),android.R.style.Theme_Material_Light_NoActionBar);
                presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                presentation.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
                holder=new FrameLayout(presentation.getContext());
                if(parent!=null)parent.removeView(web);moved=true;
                web.setVisibility(View.VISIBLE);web.getSettings().setOffscreenPreRaster(true);
                holder.addView(web,new FrameLayout.LayoutParams(width,height));presentation.setContentView(holder);presentation.show();
                presentation.getWindow().setLayout(width,height);web.onResume();web.invalidate();
                timeout=()->fail("Rendu privé sans pixels confirmés après 6 secondes.");WorkspaceCommands.UI.postDelayed(timeout,6000L);
                monitor=new Runnable(){public void run(){if(ended)return;if(!owner.isActive()||!BrowserWebState.alive(web)||web.getParent()!=holder||parent!=null&&AgentVision.visibleWindow(parent)){fail("Capture interrompue : onglet repris, fermé ou autorisation révoquée.");return;}WorkspaceCommands.UI.postDelayed(this,100L);}};
                WorkspaceCommands.UI.postDelayed(monitor,100L);
                web.postVisualStateCallback(1,new WebView.VisualStateCallback(){public void onComplete(long id){if(!ended)web.postOnAnimation(()->web.postOnAnimation(()->copy(0)));}});
            }catch(Exception e){fail(e.getMessage()==null?"Capture privée indisponible.":e.getMessage());}
        }
        void copy(int attempt){
            if(ended)return;
            if(!owner.isActive()){fail("Capture annulée.");return;}
            try{
                if(!expected.equals(AgentVision.context(web)))throw new Exception("Page ou dimensions remplacées pendant la capture.");
                Bitmap bitmap=Bitmap.createBitmap(info.getInt("image_width"),info.getInt("image_height"),Bitmap.Config.ARGB_8888);pending=bitmap;
                int[] location=new int[2];web.getLocationInWindow(location);
                android.graphics.Rect bounds=new android.graphics.Rect(location[0],location[1],location[0]+web.getWidth(),location[1]+web.getHeight());
                PixelCopy.request(presentation.getWindow(),bounds,bitmap,status->{
                    pending=null;
                    if(ended){bitmap.recycle();return;}
                    if(status!=PixelCopy.SUCCESS){bitmap.recycle();if(attempt<4)WorkspaceCommands.UI.postDelayed(()->copy(attempt+1),120L);else fail("PixelCopy privé indisponible ("+status+").");return;}
                    // Ensure pixels and the initial DOM observation still describe the same state.
                    AgentVision.observeWeb(app,web,new AgentVision.Observation(){
                        public void ready(JSONObject current){
                            if(ended){bitmap.recycle();return;}
                            if(!owner.isActive()||!expected.equals(AgentVision.context(web))||!info.optString("revision").equals(current.optString("revision"))){bitmap.recycle();fail("La page a changé pendant la capture privée.");return;}
                            ended=true;cleanup();result.ready(bitmap);
                        }
                        public void failed(String error){bitmap.recycle();fail(error);}
                    });
                },WorkspaceCommands.UI);
            }catch(Exception e){if(pending!=null){pending.recycle();pending=null;}fail(e.getMessage());}
        }
        void fail(String reason){if(ended)return;ended=true;cleanup();result.unavailable(reason);}
        void cleanup(){
            if(timeout!=null)WorkspaceCommands.UI.removeCallbacks(timeout);if(monitor!=null)WorkspaceCommands.UI.removeCallbacks(monitor);
            // Do not recycle a bitmap still owned by an in-flight PixelCopy.
            try{if(moved&&(web.getParent()==holder||web.getParent()==null)){if(web.getParent()==holder)holder.removeView(web);web.getSettings().setOffscreenPreRaster(preraster);web.setVisibility(visibility);
                if(parent!=null&&BrowserWebState.alive(web))parent.addView(web,Math.min(index,parent.getChildCount()),layout);
                else if(layout!=null)web.setLayoutParams(layout);
                web.measure(View.MeasureSpec.makeMeasureSpec(right-left,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(bottom-top,View.MeasureSpec.EXACTLY));web.layout(left,top,right,bottom);
            }}catch(Exception ignored){}
            try{if(presentation!=null)presentation.dismiss();}catch(Exception ignored){}
            try{if(display!=null)display.release();}catch(Exception ignored){}
            try{if(reader!=null)reader.close();}catch(Exception ignored){}
        }
    }
}
