package com.chk.agentbrowser;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Export flow shared by Studio's header and on-screen progress.
 * A rendered MP4 is never reported as successful until the native verification
 * succeeds; only then is it copied to public Android storage.
 */
final class StudioExportFlow {
    static final int PHONE_SAVE_REQUEST = 812;
    interface Updated { void onSaved(JSONObject project); }
    private final VideoEditorActivity activity;
    private final VideoEditorEngine engine;
    private final Updated updated;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final LinearLayout panel;
    private final TextView label,detail;
    private final ProgressBar progress;
    private String output="", destination="chk";
    private JSONObject savedProject;
    private boolean verifying=false,handled=false,copying=false,disposed=false,exportStarted=false;
    private String lastState="";
    private String finalMessage="";

    StudioExportFlow(VideoEditorActivity activity,VideoEditorEngine engine,LinearLayout root,Updated updated) {
        this.activity=activity;this.engine=engine;this.updated=updated;
        prefs=activity.getSharedPreferences("studio_export_delivery",Activity.MODE_PRIVATE);
        panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12),dp(9),dp(12),dp(10));
        panel.setBackgroundColor(0xff182831);panel.setVisibility(View.GONE);
        label=new TextView(activity);label.setTextSize(15);label.setTypeface(null,1);
        label.setTextColor(0xfff0ffff);label.setText("Export MP4");
        detail=new TextView(activity);detail.setTextSize(12);detail.setTextColor(0xffc1dae0);
        detail.setPadding(0,dp(4),0,dp(7));
        progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);progress.setProgressTintList(ColorStateList.valueOf(0xff20d4d3));
        panel.addView(label);panel.addView(detail);panel.addView(progress,new LinearLayout.LayoutParams(-1,dp(6)));
        root.addView(panel,new LinearLayout.LayoutParams(-1,-2));
        panel.setContentDescription("État de l’export vidéo et pourcentage");
        panel.setOnClickListener(v->details());
        output=prefs.getString("output","");
        destination=prefs.getString("destination","chk");
        if(!output.isEmpty()){
            exportStarted=true;
            label.setText("Export à vérifier");
            detail.setText(output);
            panel.setVisibility(View.VISIBLE);
        }
    }
    private int dp(int n){return MobileUi.dp(activity,n);}
    private void show(String headline,String sub,int percentage){
        if(disposed)return;
        panel.setVisibility(View.VISIBLE);label.setText(headline);detail.setText(sub);
        progress.setIndeterminate(percentage<0);
        if(percentage>=0)progress.setProgress(Math.max(0,Math.min(100,percentage)));
    }
    private String cleanError(String error){
        if(error==null||error.trim().isEmpty())return "Échec inconnu du moteur vidéo.";
        if(error.contains("Images MP4 non vérifiables"))
            return "Les images du MP4 ne passent pas la vérification. Aucun export final n’a été enregistré.";
        if(error.contains("Codec")||error.contains("decoder")||error.contains("MediaCodec"))
            return "Le décodeur vidéo Android a échoué. Vérifie les codecs ou réessaie avec des médias compatibles.";
        return error.length()>260?error.substring(0,260):error;
    }
    void open(JSONObject project,int revision){
        if(project==null){message("Aucun montage ouvert.");return;}
        JSONObject state=engine.status();
        if(("running".equals(state.optString("state"))||"preparing".equals(state.optString("state")))
            &&output.equals(state.optString("output"))){refresh();details();return;}
        if(project.optJSONArray("clips")==null||project.optJSONArray("clips").length()==0){message("Ajoute des vidéos au montage.");return;}
        final String name=fileName(project.optString("output","CHK_Studio.mp4"));
        AgentDialog.Builder dialog=new AgentDialog.Builder(activity);
        dialog.setTitle("Exporter le montage")
            .setMessage("Choisis la destination avant de lancer le rendu.\n"
                +project.optJSONArray("clips").length()+" plans · "+project.optInt("resolution",720)+"p\n"
                +"Le pourcentage restera visible dans Studio et la notification Android.")
            .setItems(new String[]{
                "Dossier CHK — choisir un emplacement",
                "Téléphone — choisir le dossier après le rendu",
                "Téléphone — Téléchargements / CHK Studio",
                "Dossier CHK actuel : "+name
            },(d,choice)->{
                if(choice==0)browseChk(project,revision,initialFolder(project));
                else if(choice==1)prepare(project,revision,project.optString("output",""),"phone");
                else if(choice==2)prepare(project,revision,project.optString("output",""),"downloads");
                else prepare(project,revision,project.optString("output",""),"chk");
            }).setNegativeButton("Annuler",null);
        try{
            String previous=project.optString("output","");
            if(!previous.isEmpty()&&new WorkspaceStore(activity).file(previous).isFile()){
                // Keep existing Studio's Replace / New copy actions for old MP4s.
                dialog.setNeutralButton("Remplacer",(d,n)->render(project,revision,previous,"chk",true))
                    .setPositiveButton("Nouvelle copie",(d,n)->render(project,revision,copyName(previous),"chk",false));
            }
        }catch(Exception ignored){}
        dialog.show();
    }
    private String initialFolder(JSONObject project){
        String path=project.optString("output","");
        int slash=path.lastIndexOf('/');
        String dir=slash>=0?path.substring(0,slash):"Vidéos";
        try{if(new WorkspaceStore(activity).file(dir).isDirectory())return dir;}catch(Exception ignored){}
        return "Vidéos";
    }
    private String fileName(String path){
        String name=path.substring(path.lastIndexOf('/')+1);
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")?name:name+".mp4";
    }
    private String copyName(String path){
        int dot=path.toLowerCase(java.util.Locale.ROOT).lastIndexOf(".mp4");
        if(dot!=path.length()-4)return path+"-"+System.currentTimeMillis()+".mp4";
        return path.substring(0,dot)+"-"+System.currentTimeMillis()+".mp4";
    }
    private void browseChk(JSONObject project,int revision,String folder){
        try{
            WorkspaceStore store=new WorkspaceStore(activity);
            File dir=store.file(folder);
            if(!dir.isDirectory()){message("Dossier CHK introuvable : "+folder);return;}
            File[] entries=dir.listFiles(File::isDirectory);
            if(entries==null)entries=new File[0];
            Arrays.sort(entries,(left,right)->left.getName().compareToIgnoreCase(right.getName()));
            ArrayList<String> choices=new ArrayList<>();
            choices.add("✓ Exporter dans "+(folder.isEmpty()?"Accueil CHK":folder));
            choices.add("↑ Dossier parent");
            for(File f:entries)if(!f.getName().startsWith("."))choices.add("📁 "+f.getName());
            new AgentDialog.Builder(activity).setTitle("Dossier de destination CHK")
                .setItems(choices.toArray(new String[0]),(d,index)->{
                    if(index==0){
                        String target=(folder.isEmpty()?"":folder+"/")+fileName(project.optString("output","CHK_Studio.mp4"));
                        prepare(project,revision,target,"chk");
                    }else if(index==1){
                        int slash=folder.lastIndexOf('/');
                        browseChk(project,revision,slash<0?"":folder.substring(0,slash));
                    }else{
                        browseChk(project,revision,(folder.isEmpty()?"":folder+"/")+choices.get(index).substring(3));
                    }
                })
                .setNeutralButton("Nouveau dossier",(d,n)->newFolder(project,revision,folder))
                .setNegativeButton("Fermer",null).show();
        }catch(Exception ex){message("Accès au dossier CHK impossible : "+ex.getMessage());}
    }
    private void newFolder(JSONObject project,int revision,String parent){
        android.widget.EditText name=new android.widget.EditText(activity);
        name.setSingleLine(true);name.setHint("Nom du dossier");
        new AgentDialog.Builder(activity).setTitle("Créer un dossier CHK").setView(name)
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Créer",(d,n)->{
                String child=name.getText().toString().trim();
                if(child.isEmpty()||child.equals(".")||child.equals("..")||child.contains("/")||child.contains("\\")){
                    message("Nom de dossier invalide.");return;
                }
                String next=(parent.isEmpty()?"":parent+"/")+child;
                try{if(!new WorkspaceStore(activity).file(next).mkdir())throw new java.io.IOException("Impossible de créer le dossier");browseChk(project,revision,next);}
                catch(Exception e){message("Création impossible : "+e.getMessage());}
            }).show();
    }
    private void prepare(JSONObject project,int revision,String requestedPath,String where){
        try{
            WorkspaceStore store=new WorkspaceStore(activity);
            String target=requestedPath.isEmpty()?"Vidéos/CHK_Studio.mp4":requestedPath;
            File file=store.file(target);
            final String chosen=target;
            if(file.exists()){
                new AgentDialog.Builder(activity).setTitle("Ce MP4 existe déjà")
                    .setMessage("L'ancien fichier sera conservé, sauf si tu choisis explicitement Remplacer.\n"+target)
                    .setNegativeButton("Annuler",null)
                    .setNeutralButton("Remplacer",(d,n)->render(project,revision,chosen,where,true))
                    .setPositiveButton("Nouvelle copie",(d,n)->render(project,revision,copyName(chosen),where,false)).show();
            }else render(project,revision,target,where,false);
        }catch(Exception ex){message("Destination non disponible : "+ex.getMessage());}
    }
    private void render(JSONObject project,int revision,String chosen,String where,boolean replace){
        if(disposed)return;
        try{
            JSONObject snapshot=new JSONObject(project.toString()).put("output",chosen);
            destination=where;output=chosen;handled=false;verifying=false;copying=false;exportStarted=false;finalMessage="";
            savedProject=snapshot;
            persist();
            show("Préparation de l’export · 0 %",chosen,0);
            engine.command(activity,"video_editor_project_save",
                new JSONObject().put("project",snapshot).put("expected_revision",revision),
                (saved,response)->main.post(()->{
                    if(disposed)return;
                    if(!saved){failure("Sauvegarde impossible : "+response);return;}
                    try{
                        savedProject=new JSONObject(response).getJSONObject("project");
                        updated.onSaved(savedProject);
                        show("Export MP4 · préparation",chosen,-1);
                        engine.command(activity,"video_editor_export",
                            new JSONObject().put("replace",replace),
                            (started,message)->main.post(()->{
                                if(disposed)return;
                                if(!started){failure(message);return;}
                                exportStarted=true;
                                show("Export MP4 en cours · 0 %",chosen,0);
                                refresh();
                            }));
                    }catch(Exception error){failure("Erreur du projet : "+error.getMessage());}
                }));
        }catch(Exception ex){failure(ex.getMessage());}
    }
    void refresh(){
        if(disposed||copying||output.isEmpty()||handled||!exportStarted)return;
        JSONObject state=engine.status();
        if(!output.equals(state.optString("output","")))return;
        String current=state.optString("state","");
        int percent=Math.max(0,Math.min(99,state.optInt("progress_percent",0)));
        if(current.equals("preparing"))show("Export MP4 · préparation",output,-1);
        else if(current.equals("running")){
            show(percent>=99?"Export · 99 % · vérification en cours":"Export MP4 · "+percent+" %",
                fileName(output)+" · "+destinationName(),percent);
        }else if(current.equals("failed")){
            failure(state.optString("error","Le rendu a échoué."));
        }else if(current.equals("completed")&&!verifying){
            verifying=true;
            show("Export · 99 % · vérification", "Contrôle de la durée, des images et du son…",99);
            try{
                JSONObject query=new JSONObject();
                if(savedProject!=null)query.put("project",savedProject);
                engine.command(activity,"video_editor_verify_output",query,(ok,result)->main.post(()->{
                    if(disposed||handled)return;
                    try{
                        if(!ok)throw new IllegalStateException(result);
                        JSONObject info=new JSONObject(result);
                        if(!info.optBoolean("valid",false))
                            throw new IllegalStateException(info.optString("error","Fichier non validé par Studio."));
                        completedRendering();
                    }catch(Exception ex){failure("Vérification MP4 : "+ex.getMessage());}
                }));
            }catch(Exception e){failure("Vérification : "+e.getMessage());}
        }
    }
    private String destinationName(){
        return "chk".equals(destination)?"Dossier CHK":
            "phone".equals(destination)?"Téléphone (dossier au choix)":"Téléchargements Android";
    }
    private void completedRendering(){
        handled=true;
        show("Export terminé et vérifié · 100 %",output,100);
        if("chk".equals(destination)){
            finalMessage="MP4 vérifié dans Fichiers CHK :\n"+output;
            clearPending();
            new AgentDialog.Builder(activity).setTitle("MP4 terminé").setMessage(finalMessage)
                .setPositiveButton("Lire", (d,n)->activity.startActivity(new Intent(activity,MediaPreviewActivity.class).putExtra("path",output)))
                .setNegativeButton("Fermer",null).show();
        }else if("phone".equals(destination)){
            show("Rendu terminé · 100 %","Choisis maintenant le dossier du téléphone pour enregistrer le MP4.",100);
            try{
                Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("video/mp4")
                    .putExtra(Intent.EXTRA_TITLE,fileName(output));
                activity.startActivityForResult(intent,PHONE_SAVE_REQUEST);
            }catch(Exception e){failure("Sélecteur Android indisponible : "+e.getMessage());}
        }else{
            if(Build.VERSION.SDK_INT>=29)saveToDownloads();
            else{
                destination="phone";
                completedRenderingFallback();
            }
        }
    }
    private void completedRenderingFallback(){
        try{
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("video/mp4")
                .putExtra(Intent.EXTRA_TITLE,fileName(output));
            activity.startActivityForResult(intent,PHONE_SAVE_REQUEST);
        }catch(Exception e){failure("Sélecteur Android indisponible : "+e.getMessage());}
    }
    boolean onActivityResult(int request,int result,Intent data){
        if(request!=PHONE_SAVE_REQUEST)return false;
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null){
            finalMessage="Copie sur le téléphone annulée. Le MP4 vérifié reste dans CHK :\n"+output;
            show("Export CHK terminé · 100 %",finalMessage,100);
            clearPending();return true;
        }
        copyToPhone(data.getData(),false);
        return true;
    }
    @androidx.annotation.RequiresApi(29)
    private void saveToDownloads(){
        try{
            ContentValues values=new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,fileName(output));
            values.put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/CHK Studio");
            values.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri target=activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
            if(target==null)throw new java.io.IOException("Création dans Téléchargements refusée.");
            copyToPhone(target,true);
        }catch(Exception e){failure("Téléchargements Android : "+e.getMessage());}
    }
    private static String hex(byte[] bytes){
        StringBuilder b=new StringBuilder(bytes.length*2);
        for(byte x:bytes)b.append(String.format(java.util.Locale.ROOT,"%02x",x&255));
        return b.toString();
    }
    private void copyToPhone(Uri target,boolean downloads){
        copying=true;
        show("Copie du MP4 sur le téléphone · 0 %",target.toString(),0);
        final String path=output;
        WorkspaceCommands.IO.execute(()->{
            try{
                File source=new WorkspaceStore(activity).file(path);
                if(!source.isFile()||source.length()<1000)throw new java.io.IOException("MP4 source absent.");
                long total=source.length(),written=0;
                MessageDigest hash=MessageDigest.getInstance("SHA-256");
                try(InputStream in=new FileInputStream(source);
                    OutputStream out=activity.getContentResolver().openOutputStream(target,"w")){
                    if(out==null)throw new java.io.IOException("Écriture Android refusée.");
                    byte[] buffer=new byte[131072];
                    int n,last=-1;
                    while((n=in.read(buffer))>=0){
                        if(n==0)continue;
                        out.write(buffer,0,n);hash.update(buffer,0,n);written+=n;
                        int percentage=(int)Math.min(99,written*100/Math.max(total,1));
                        if(percentage!=last){
                            last=percentage;
                            final int current=percentage;
                            main.post(()->show("Copie sur le téléphone · "+current+" %",fileName(path),current));
                        }
                    }
                    out.flush();
                }
                if(written!=total)throw new java.io.IOException("Copie incomplète.");
                String original=hex(hash.digest());
                MessageDigest check=MessageDigest.getInstance("SHA-256");
                long readBack=0;
                try(InputStream in=activity.getContentResolver().openInputStream(target)){
                    if(in==null)throw new java.io.IOException("Fichier de destination illisible.");
                    byte[] buffer=new byte[131072];int n;
                    while((n=in.read(buffer))!=-1){check.update(buffer,0,n);readBack+=n;}
                }
                if(readBack!=total||!original.equals(hex(check.digest())))
                    throw new java.io.IOException("Contrôle SHA-256 de la copie incorrect.");
                if(downloads){
                    ContentValues ready=new ContentValues();
                    ready.put(MediaStore.MediaColumns.IS_PENDING,0);
                    activity.getContentResolver().update(target,ready,null,null);
                }
                final String location=downloads?"Téléchargements/CHK Studio":target.toString();
                main.post(()->{
                    copying=false;finalMessage="MP4 copié et vérifié sur Android :\n"+location;
                    show("Enregistré sur le téléphone · 100 %",finalMessage,100);
                    clearPending();
                    new AgentDialog.Builder(activity).setTitle("MP4 disponible sur le téléphone")
                        .setMessage(finalMessage).setPositiveButton("OK",null).show();
                });
            }catch(Exception ex){
                try{activity.getContentResolver().delete(target,null,null);}catch(Exception ignored){}
                main.post(()->{copying=false;failure("Copie Android : "+ex.getMessage());});
            }
        });
    }
    private void persist(){prefs.edit().putString("output",output).putString("destination",destination).apply();}
    private void clearPending(){prefs.edit().remove("output").remove("destination").apply();}
    private void failure(String error){
        handled=true;verifying=false;copying=false;
        String text=cleanError(error);
        finalMessage="Échec de l’export : "+text;
        show("EXPORT ÉCHOUÉ — aucun MP4 final confirmé",finalMessage,0);
        clearPending();
        new AgentDialog.Builder(activity).setTitle("Export non terminé")
            .setMessage(text+"\n\nLe montage et ses sources restent conservés dans CHK.")
            .setPositiveButton("Compris",null).show();
    }
    private void message(String text){
        if(disposed)return;
        new AgentDialog.Builder(activity).setTitle("Export Studio")
            .setMessage(text).setPositiveButton("OK",null).show();
    }
    private void details(){
        String msg=finalMessage.isEmpty()?
            ("Destination : "+destinationName()+"\n"+output+
                "\n\nPourcentage visible ici et dans les notifications. Le fichier final ne sera déclaré terminé qu’après vérification."):
            finalMessage;
        new AgentDialog.Builder(activity).setTitle("Suivi de l’export")
            .setMessage(msg)
            .setNegativeButton("Fermer",null)
            .setPositiveButton("Actualiser",(d,n)->refresh())
            .setNeutralButton("Annuler le rendu",(d,n)->{
                String state=engine.status().optString("state");
                if("running".equals(state)||"preparing".equals(state))
                    engine.command(activity,"video_editor_cancel",new JSONObject(),(ok,result)->main.post(()->{
                        if(ok)failure("Export annulé à la demande.");
                        else message(result);
                    }));
            }).show();
    }
    void onResume(){refresh();}
    void dispose(){disposed=true;}
}
