package com.chk.agentbrowser;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.webkit.PermissionRequest;
import android.webkit.GeolocationPermissions;
import android.webkit.WebView;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** A web origin and Android runtime permissions both require the owner's consent. */
final class BrowserPermissions {
    static final int REQUEST=6421;
    private static final WeakHashMap<Activity,Runnable> PENDING=new WeakHashMap<>();
    private BrowserPermissions(){}
    private static void authorize(Activity activity,String origin,String message,String[] permissions,Runnable granted,Runnable denied){
        if(!NativeKeyboard.foreground()||activity.isFinishing()||!origin.startsWith("https://")){denied.run();return;}
        new AgentDialog.Builder(activity).setTitle("Autorisation du site")
            .setMessage(origin+"\n\n"+message).setNegativeButton("Refuser",(d,w)->denied.run())
            .setOnCancelListener(d->denied.run()).setPositiveButton("Autoriser",(d,w)->{
                if(PENDING.containsKey(activity)){denied.run();return;}
                ArrayList<String> missing=new ArrayList<>();for(String permission:permissions)if(activity.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)missing.add(permission);
                if(missing.isEmpty()){granted.run();return;}
                PENDING.put(activity,()->{for(String permission:permissions)if(activity.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED){denied.run();return;}if(activity.isFinishing())denied.run();else granted.run();});
                activity.requestPermissions(missing.toArray(new String[0]),REQUEST);
            }).show();
    }
    static void media(Activity activity,WebView web,PermissionRequest request){
        long version=BrowserWebState.documentVersion(web);String page=web.getUrl();
        ArrayList<String> resources=new ArrayList<>(),permissions=new ArrayList<>();
        for(String resource:request.getResources()){
            if(resource.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)){resources.add(resource);permissions.add(Manifest.permission.RECORD_AUDIO);}
            if(resource.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)){resources.add(resource);permissions.add(Manifest.permission.CAMERA);}
        }
        if(resources.isEmpty()){request.deny();return;}
        authorize(activity,request.getOrigin().toString(),"Autoriser l'accès demandé au microphone ou à la caméra ?",permissions.toArray(new String[0]),()->{
            if(BrowserWebState.alive(web)&&version==BrowserWebState.documentVersion(web)&&page!=null&&page.equals(web.getUrl()))request.grant(resources.toArray(new String[0]));else request.deny();
        },request::deny);
    }
    static void location(Activity activity,String origin,GeolocationPermissions.Callback cb){
        authorize(activity,origin,"Autoriser ce site à connaître ta position ?",new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},()->cb.invoke(origin,true,false),()->cb.invoke(origin,false,false));
    }
    static void result(Activity activity,int request){if(request==REQUEST){Runnable pending=PENDING.remove(activity);if(pending!=null)pending.run();}}
}
