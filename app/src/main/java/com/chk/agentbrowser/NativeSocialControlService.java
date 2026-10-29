package com.chk.agentbrowser;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.os.Build;
import android.view.Display;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/**
 * Owner-enabled, session-scoped controller for the ACTUAL Android applications.
 * The app stays outside CHK's task; an optional tiny accessibility overlay
 * supplies return/stop. No security prompts, passwords or verification bypass.
 * No passive collection, streaming or remote action without an active session.
 */
public final class NativeSocialControlService extends AccessibilityService {
    private static volatile NativeSocialControlService current;
    private String lastObservation="";
    private long observedAt;
    private String observedPackage="";
    private WindowManager windows;
    private android.view.View overlay;
    private final Handler main=new Handler(Looper.getMainLooper());

    static NativeSocialControlService activeService(){return current;}

    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        current=this;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(event==null)return;
        CharSequence name=event.getPackageName();
        String pkg=name==null?"":name.toString();
        if(NativeSocialSession.isActive(this,pkg))showOverlay();
        else hideOverlay();
    }
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){
        hideOverlay();
        if(current==this)current=null;
        super.onDestroy();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void hideOverlay(){
        if(overlay!=null&&windows!=null){
            try{windows.removeView(overlay);}catch(Exception ignored){}
        }
        overlay=null;
    }
    private void showOverlay(){
        if(overlay!=null)return;
        try{
            windows=(WindowManager)getSystemService(WINDOW_SERVICE);
            LinearLayout bar=new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setBackgroundColor(0xee172b32);
            bar.setPadding(dp(5),dp(4),dp(5),dp(4));
            TextView back=button("CHK · Retour");
            back.setOnClickListener(v->{
                try{
                    Intent intent=new Intent(this,MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                }catch(Exception ignored){}
            });
            TextView stop=button("Arrêter");
            stop.setOnClickListener(v->{NativeSocialSession.stop(this);hideOverlay();});
            bar.addView(back);bar.addView(stop);
            WindowManager.LayoutParams params=new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
            params.gravity=Gravity.TOP|Gravity.END;
            params.x=dp(8);params.y=dp(54);
            windows.addView(bar,params);
            overlay=bar;
        }catch(Exception ignored){overlay=null;}
    }
    private TextView button(String title){
        TextView t=new TextView(this);
        t.setText(title);t.setTextColor(Color.WHITE);t.setTextSize(12);
        t.setPadding(dp(9),dp(6),dp(9),dp(6));return t;
    }
    private AccessibilityNodeInfo targetRoot()throws Exception{
        JSONObject state=NativeSocialSession.current(this);
        if(state.length()==0)throw new Exception("Aucune session native autorisée ou session expirée");
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)throw new Exception("Fenêtre Android non accessible ; vérifier l'application affichée");
        CharSequence pkg=root.getPackageName();
        if(pkg==null||!pkg.toString().equals(state.optString("target_package"))){
            root.recycle();
            throw new Exception("Seule l'application choisie dans Cut peut être pilotée");
        }
        return root;
    }
    private static String clean(CharSequence value){
        if(value==null)return "";
        String s=value.toString().replace('\n',' ').trim();
        return s.length()>110?s.substring(0,110):s;
    }
    private static void tree(AccessibilityNodeInfo node,String path,JSONArray nodes,int depth)throws Exception{
        if(node==null||nodes.length()>=120||depth>13)return;
        try{
            Rect bounds=new Rect();node.getBoundsInScreen(bounds);
            boolean sensitive=node.isPassword();
            JSONObject out=new JSONObject()
                .put("path",path)
                .put("class",clean(node.getClassName()))
                .put("text",sensitive?"[protégé]":clean(node.getText()))
                .put("hint",sensitive?"":android.os.Build.VERSION.SDK_INT>=26?clean(node.getHintText()):"")
                .put("description",sensitive?"":clean(node.getContentDescription()))
                .put("clickable",node.isClickable()&&!sensitive)
                .put("editable",node.isEditable()&&!sensitive)
                .put("password",sensitive)
                .put("bounds",new JSONObject().put("left",bounds.left).put("top",bounds.top)
                    .put("right",bounds.right).put("bottom",bounds.bottom));
            nodes.put(out);
            for(int i=0;i<node.getChildCount()&&nodes.length()<120;i++){
                AccessibilityNodeInfo child=node.getChild(i);
                if(child!=null){try{tree(child,path+"/"+i,nodes,depth+1);}finally{child.recycle();}}
            }
        }finally{}
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo root,String path)throws Exception{
        if(path==null||!path.matches("root(?:/[0-9]{1,3}){0,13}"))
            throw new Exception("Chemin de contrôle invalide");
        AccessibilityNodeInfo currentNode=AccessibilityNodeInfo.obtain(root);
        String[] parts=path.split("/");
        for(int i=1;i<parts.length;i++){
            int at=Integer.parseInt(parts[i]);
            if(at<0||at>=currentNode.getChildCount()){currentNode.recycle();throw new Exception("Contrôle disparu");}
            AccessibilityNodeInfo next=currentNode.getChild(at);
            currentNode.recycle();
            if(next==null)throw new Exception("Contrôle disparu");
            currentNode=next;
        }
        return currentNode;
    }
    static void command(JSONObject p,AgentClient.ResultCallback callback){
        NativeSocialControlService service=current;
        if(service==null){callback.finish(false,"Active d'abord le service dans les paramètres d'accessibilité Android.");return;}
        service.main.post(()->service.handle(p,callback));
    }
    private void handle(JSONObject p,AgentClient.ResultCallback cb){
        if(!cb.isActive())return;
        try{
            String op=p.optString("operation","status");
            JSONObject session=NativeSocialSession.current(this);
            if(op.equals("status")){
                cb.finish(true,new JSONObject().put("service_connected",true)
                    .put("session_active",session.length()>0)
                    .put("platform",session.optString("platform",""))
                    .put("target_package",session.optString("target_package",""))
                    .put("expires_at",session.optLong("expires_at",0))
                    .put("platform_publication_confirmed",false).toString());return;
            }
            if(op.equals("stop")){
                NativeSocialSession.stop(this);hideOverlay();
                cb.finish(true,new JSONObject().put("state","stopped").toString());return;
            }
            AccessibilityNodeInfo root=targetRoot();
            try{
                if(op.equals("screenshot")){
                    // Android 11+ only. FLAG_SECURE and permission errors remain authoritative.
                    if(Build.VERSION.SDK_INT<30)throw new Exception("Capture native exige Android 11 ou supérieur");
                    String packageAtRequest=session.getString("target_package");
                    takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
                        @Override public void onSuccess(ScreenshotResult result){
                            try{
                                if(!NativeSocialSession.isActive(NativeSocialControlService.this,packageAtRequest))
                                    throw new Exception("Session interrompue pendant la capture");
                                AccessibilityNodeInfo currentRoot=targetRoot();
                                currentRoot.recycle();
                                android.hardware.HardwareBuffer buffer=result.getHardwareBuffer();
                                try{
                                    Bitmap original=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());
                                    if(original==null)throw new Exception("Image Android indisponible");
                                    int width=Math.max(1,Math.round(original.getWidth()*
                                        Math.min(1f,512f/Math.max(original.getWidth(),original.getHeight()))));
                                    int height=Math.max(1,Math.round(original.getHeight()*
                                        Math.min(1f,512f/Math.max(original.getWidth(),original.getHeight()))));
                                    Bitmap small=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                                    Canvas canvas=new Canvas(small);
                                    canvas.drawBitmap(original,null,new Rect(0,0,width,height),null);
                                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                                    small.compress(Bitmap.CompressFormat.JPEG,60,bytes);
                                    small.recycle();
                                    cb.finish(true,new JSONObject()
                                        .put("package",packageAtRequest)
                                        .put("mime_type","image/jpeg")
                                        .put("width",width).put("height",height)
                                        .put("image_base64",Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP))
                                        .put("source","android_accessibility_screenshot")
                                        .put("outcome_confirmed",false).toString());
                                }finally{buffer.close();}
                            }catch(Exception error){if(cb.isActive())cb.finish(false,error.getMessage());}
                        }
                        @Override public void onFailure(int errorCode){
                            if(cb.isActive())cb.finish(false,"Capture Android refusée : "+errorCode);
                        }
                    });
                    return;
                }
                if(op.equals("observe")){
                    JSONArray rows=new JSONArray();
                    tree(root,"root",rows,0);
                    lastObservation=UUID.randomUUID().toString();
                    observedAt=android.os.SystemClock.elapsedRealtime();
                    observedPackage=session.getString("target_package");
                    cb.finish(true,new JSONObject()
                        .put("observation_id",lastObservation).put("expires_in_ms",20000)
                        .put("platform",session.optString("platform"))
                        .put("package",observedPackage).put("controls",rows)
                        .put("truncated",rows.length()>=120).put("published",false).toString());
                    return;
                }
                if(!op.equals("act"))throw new Exception("Opération native inconnue");
                if(!p.optString("observation_id").equals(lastObservation)||
                    android.os.SystemClock.elapsedRealtime()-observedAt>20000||
                    !observedPackage.equals(session.optString("target_package")))
                    throw new Exception("Observation expirée : inspecter la vraie application avant d'agir");
                String type=p.optString("action","");
                if(type.equals("tap")){
                    float x=(float)p.getDouble("x"),y=(float)p.getDouble("y");
                    if(x<0||y<0||x>getResources().getDisplayMetrics().widthPixels||
                        y>getResources().getDisplayMetrics().heightPixels)
                        throw new Exception("Coordonnées hors de l'écran");
                    Path line=new Path();line.moveTo(x,y);
                    GestureDescription gesture=new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(line,0,90)).build();
                    boolean sent=dispatchGesture(gesture,new GestureResultCallback(){
                        @Override public void onCompleted(GestureDescription desc){
                            if(cb.isActive())cb.finish(true,"{\"gesture_sent\":true,\"outcome_confirmed\":false}");
                        }
                        @Override public void onCancelled(GestureDescription desc){
                            if(cb.isActive())cb.finish(false,"Geste annulé : résultat non confirmé");
                        }
                    },main);
                    if(!sent)cb.finish(false,"Geste refusé par Android");return;
                }
                if(type.equals("back")){
                    boolean sent=performGlobalAction(GLOBAL_ACTION_BACK);
                    cb.finish(sent,new JSONObject().put("action_sent",sent)
                        .put("outcome_confirmed",false).toString());return;
                }
                if(!type.equals("click")&&!type.equals("replace_text"))
                    throw new Exception("Action native refusée");
                AccessibilityNodeInfo node=find(root,p.getString("path"));
                try{
                    if(node.isPassword())throw new Exception("Champ protégé");
                    if(type.equals("click")){
                        String label=(clean(node.getText())+" "+clean(node.getContentDescription())).toLowerCase(java.util.Locale.ROOT);
                        if(label.matches(".*(publier|publish|poster|post now|programmer|schedule|confirmer la publication).*"))
                            throw new Exception("Validation finale réservée au propriétaire");
                        boolean sent=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        cb.finish(sent,new JSONObject().put("action_sent",sent)
                            .put("outcome_confirmed",false).toString());
                    }else{
                        if(!node.isEditable())throw new Exception("Champ non éditable");
                        String value=p.getString("text");
                        if(value.length()>500)throw new Exception("Saisie limitée à 500 caractères");
                        Bundle args=new Bundle();
                        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
                        boolean sent=node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
                        cb.finish(sent,new JSONObject().put("action_sent",sent)
                            .put("outcome_confirmed",false).toString());
                    }
                }finally{node.recycle();}
            }finally{root.recycle();}
        }catch(Exception e){cb.finish(false,e.getMessage()==null?"Échec pilotage natif":e.getMessage());}
    }
}
