package com.chk.agentbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * First-party workspace picker for an actual WebView file request.
 * The user selects only files already stored in CHK Fichiers; Android remains optional.
 * Never exposes an arbitrary phone directory to the website or remote agent.
 */
public final class WorkspaceMediaChooser {
    public interface Selection { void finish(Uri[] uris); }
    private final Activity activity;
    private final WorkspaceStore store;
    private final WebChromeClient.FileChooserParams params;
    private final Selection completion;
    private final Runnable openAndroid;
    private final LinkedHashSet<String> selected=new LinkedHashSet<>();
    private boolean finished;

    private WorkspaceMediaChooser(Activity activity,WebChromeClient.FileChooserParams params,
                                  Selection completion,Runnable openAndroid)throws IOException{
        this.activity=activity;this.store=new WorkspaceStore(activity);
        this.params=params;this.completion=completion;this.openAndroid=openAndroid;
    }

    public static void show(Activity activity,WebChromeClient.FileChooserParams params,
                            Selection selection,Runnable openAndroid)throws IOException{
        new WorkspaceMediaChooser(activity,params,selection,openAndroid).folder("");
    }

    private boolean multiple(){
        return params.getMode()==WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
    }
    private boolean accepts(String path){
        String[] accepts=params.getAcceptTypes();
        if(accepts==null||accepts.length==0)return true;
        String mime=store.mime(path).toLowerCase(Locale.ROOT);
        for(String item:accepts){
            if(item==null||item.trim().isEmpty()||"*/*".equals(item.trim()))return true;
            for(String token:item.toLowerCase(Locale.ROOT).split(",")){
                String expected=token.trim();
                if(expected.isEmpty()||expected.equals("*/*"))return true;
                if(expected.startsWith(".")&&path.toLowerCase(Locale.ROOT).endsWith(expected))return true;
                if(expected.endsWith("/*")&&mime.startsWith(expected.substring(0,expected.length()-1)))return true;
                if(expected.equals(mime))return true;
                if(expected.equals("image/jpeg")&&(mime.equals("image/jpg")||path.toLowerCase(Locale.ROOT).endsWith(".jpg")))return true;
            }
        }
        return false;
    }

    private void folder(String location){
        try{
            File folder=store.file(location);
            if(!folder.isDirectory())throw new IOException("Dossier indisponible");
            File[] entries=folder.listFiles();
            if(entries==null)throw new IOException("Lecture impossible");
            List<File> files=new ArrayList<>();
            for(File f:entries)if(!f.getName().startsWith(".") &&
                    (f.isDirectory()||(f.isFile()&&accepts(WorkspacePaths.child(location,f.getName())))))
                files.add(f);
            Collections.sort(files,(a,b)->{
                if(a.isDirectory()!=b.isDirectory())return a.isDirectory()?-1:1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            final List<String> actions=new ArrayList<>();
            final List<String> paths=new ArrayList<>();
            if(!location.isEmpty()){actions.add("‹ Dossier précédent");paths.add("..");}
            if(multiple()&&!selected.isEmpty()){actions.add("✓ Importer la sélection ("+selected.size()+")");paths.add("#done");}
            for(File f:files){
                String p=WorkspacePaths.child(location,f.getName());
                String name=f.getName();
                if(f.isDirectory())name="▸ "+name;
                else name=(selected.contains(p)?"✓ ":"")+" "+name+" · "+size(f.length());
                actions.add(name);paths.add(p);
            }
            String[] labels=actions.toArray(new String[0]);
            String title="Fichiers CHK"+(location.isEmpty()?"":" / "+location);
            new AlertDialog.Builder(activity).setTitle(title)
                .setItems(labels,(dialog,which)->{
                    String path=paths.get(which);
                    if("..".equals(path)){folder(parent(location));return;}
                    if("#done".equals(path)){complete();return;}
                    try{
                        if(store.file(path).isDirectory()){folder(path);return;}
                        if(multiple()){if(!selected.add(path))selected.remove(path);folder(location);}
                        else {selected.clear();selected.add(path);complete();}
                    }catch(Exception e){error(location);}
                })
                .setNeutralButton("Fichiers Android",(dialog,which)->{
                    if(finished)return;finished=true;openAndroid.run();
                })
                .setNegativeButton("Annuler",(dialog,which)->cancel())
                .setOnCancelListener(dialog->cancel()).show();
        }catch(Exception e){error(location);}
    }

    private String parent(String path){int idx=path.lastIndexOf('/');return idx<0?"":path.substring(0,idx);}
    private static String size(long value){
        if(value<1024)return value+" o";
        if(value<1048576)return (value/1024)+" Ko";
        return String.format(Locale.FRANCE,"%.1f Mo",value/1048576.0);
    }
    private void error(String location){
        new AlertDialog.Builder(activity).setTitle("Fichiers CHK")
            .setMessage("Ce dossier est momentanément inaccessible.")
            .setPositiveButton("Réessayer",(dialog,which)->folder(location))
            .setNeutralButton("Fichiers Android",(dialog,which)->{
                if(finished)return;finished=true;openAndroid.run();
            })
            .setNegativeButton("Annuler",(dialog,which)->cancel()).show();
    }
    private void complete(){
        if(finished||selected.isEmpty())return;
        try{
            Uri[] uris=new Uri[selected.size()];
            int i=0;
            for(String p:selected){
                if(!store.file(p).isFile()||!accepts(p))throw new IOException("Fichier indisponible");
                uris[i++]=store.uri(p);
            }
            if(Build.VERSION.SDK_INT>=26){
                android.content.pm.PackageInfo pkg=WebView.getCurrentWebViewPackage();
                if(pkg!=null)for(Uri uri:uris)
                    activity.grantUriPermission(pkg.packageName,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            finished=true;completion.finish(uris);
        }catch(Exception e){finished=false;error("");}
    }
    private void cancel(){if(!finished){finished=true;completion.finish(null);}}
}
