package com.chk.agentbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Cut library: collections > export folders > MP4, with no changes to browser file categories. */
public final class CutStudioLibraryActivity extends Activity {
    private static final int BG=0xff111414, SURFACE=0xff252b2b, ALT=0xff1d2222;
    private static final int TEAL=0xff00c7b0, TEXT=Color.WHITE, MUTED=0xffb7c0bf;
    private static final String[] NETWORKS={"youtube","tiktok","instagram","x"};
    private static final String[] NETWORK_LABELS={"YouTube","TikTok","Instagram","X"};
    private CutLibraryState state;
    private WorkspaceStore workspace;
    private File root;
    private LinearLayout content, toolbar;
    private String groupId="", groupName="", folderName="";
    private boolean selecting=false;
    private final Set<String> selection=new HashSet<>();
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        state=new CutLibraryState(this);
        try {
            workspace=new WorkspaceStore(this);
            root=workspace.file("CutVideo");
            if(!root.exists())workspace.mkdir("CutVideo");
        }catch(Exception e){alert("Bibliothèque",e.getMessage());return;}
        render();
    }
    @Override protected void onResume() {
        super.onResume();
        if(content!=null)render();
    }
    @Override public void onBackPressed() {
        if(selecting){selecting=false;selection.clear();render();return;}
        if(!folderName.isEmpty()){folderName="";render();return;}
        if(!groupId.isEmpty()){groupId="";groupName="";render();return;}
        super.onBackPressed();
    }
    private TextView label(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);
        if(bold)t.setTypeface(null,Typeface.BOLD);
        return t;
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(1);return v;}
    private void add(LinearLayout host,View v,int bottom) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin=dp(bottom);host.addView(v,p);
    }
    private LinearLayout surface(LinearLayout parent){
        LinearLayout p=column();p.setPadding(dp(13),dp(12),dp(13),dp(12));
        p.setBackground(CutStudioHomeActivity.surface(SURFACE,dp(12)));
        add(parent,p,10);return p;
    }
    private void labelLine(LinearLayout panel,String title,String summary){
        add(panel,label(title,16,TEXT,true),5);
        if(summary!=null&&!summary.isEmpty())add(panel,label(summary,12,MUTED,false),4);
    }
    private void action(LinearLayout parent,String title,Runnable callback){
        TextView t=label(title,13,TEAL,true);t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10),dp(12),dp(10),dp(12));
        t.setBackground(CutStudioHomeActivity.surface(ALT,dp(9)));
        add(parent,t,6);t.setOnClickListener(v->callback.run());
    }
    private void render(){
        LinearLayout screen=column();screen.setBackgroundColor(BG);
        LinearLayout top=column();top.setBackgroundColor(0xff00796b);
        top.setPadding(dp(16),dp(14),dp(16),dp(12));screen.addView(top);
        TextView heading=label("‹   "+(folderName.isEmpty()?(groupId.isEmpty()?"MES VIDÉOS":groupName):folderName),20,TEXT,true);
        top.addView(heading);heading.setOnClickListener(v->onBackPressed());
        TextView sub=label("CUT VIDÉO · bibliothèque locale",12,0xffdbfff5,false);add(top,sub,0);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        content=column();content.setPadding(dp(14),dp(16),dp(14),dp(12));
        scroll.addView(content);screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(screen);
        toolbar=surface(content);
        try{
            if(!folderName.isEmpty())renderVideos();
            else if(!groupId.isEmpty())renderGroup();
            else renderRoot();
        }catch(Exception e){labelLine(surface(content),"Impossible d'afficher la bibliothèque",e.getMessage());}
    }
    private List<File> folders(){
        File[] files=root.listFiles();List<File> result=new ArrayList<>();
        if(files!=null)for(File file:files)
            if(file.isDirectory()&&!file.getName().startsWith(".")&&countVideo(file)>0)result.add(file);
        sort(result,true);
        return result;
    }
    private List<File> videos(File folder){
        File[] files=folder.listFiles();List<File> result=new ArrayList<>();
        if(files!=null)for(File f:files)
            if(f.isFile()&&f.getName().toLowerCase(Locale.ROOT).endsWith(".mp4")&&!f.getName().startsWith("temp_"))result.add(f);
        sort(result,false);
        return result;
    }
    private void sort(List<File> files,boolean folders){
        final int mode=state.sort();
        files.sort((a,b)->{
            if(mode==1)return a.getName().compareToIgnoreCase(b.getName());
            if(mode==2)return Long.compare(sizeOf(b),sizeOf(a));
            if(mode==3&&folders)return Integer.compare(countVideo(b),countVideo(a));
            return Long.compare(b.lastModified(),a.lastModified());
        });
    }
    private int countVideo(File folder){
        File[] list=folder.listFiles();int n=0;
        if(list!=null)for(File f:list)
            if(f.isFile()&&f.getName().toLowerCase(Locale.ROOT).endsWith(".mp4")&&!f.getName().startsWith("temp_"))n++;
        return n;
    }
    private long sizeOf(File file){
        if(file.isFile())return file.length();
        long size=0;File[] list=file.listFiles();
        if(list!=null)for(File f:list)if(f.isFile())size+=f.length();
        return size;
    }
    private String bytes(long bytes){
        if(bytes>=1048576)return String.format(Locale.FRANCE,"%.1f Mo",bytes/1048576d);
        return String.format(Locale.FRANCE,"%.1f Ko",bytes/1024d);
    }
    private void renderRoot() throws Exception {
        List<File> all=folders();JSONArray groups=state.groups();int videos=0;long size=0;
        for(File f:all){videos+=countVideo(f);size+=sizeOf(f);}
        labelLine(toolbar,groups.length()+" classements · "+all.size()+" dossiers · "+videos+" vidéos",bytes(size)+" utilisés");
        action(toolbar,"＋ CRÉER UN CLASSEMENT",()->editGroup(null));
        action(toolbar,"⚙ AFFICHAGE ET TRI",this::displayOptions);
        action(toolbar,"CORBEILLE · RESTAURER",this::showTrash);
        for(int i=0;i<groups.length();i++){
            JSONObject g=groups.optJSONObject(i);if(g==null)continue;
            String id=g.optString("id"), name=g.optString("name");
            int folderCount=0,fileCount=0;long totalSize=0;
            for(File f:all)if(id.equals(state.groupFor(f.getName()))){
                folderCount++;fileCount+=countVideo(f);totalSize+=sizeOf(f);
            }
            LinearLayout p=surface(content);
            labelLine(p,"▣   "+name,folderCount+" dossiers · "+fileCount+" vidéos · "+bytes(totalSize));
            String note=state.note("group:"+id);if(!note.isEmpty())add(p,label("✎ "+shortText(note),12,MUTED,false),5);
            action(p,"OUVRIR LE CLASSEMENT",()->{groupId=id;groupName=name;render();});
            action(p,"⋮ Actions",()->groupActions(id,name));
        }
        int count=0;
        for(File folder:all)if(state.groupFor(folder.getName()).isEmpty()){
            renderFolderCard(folder);count++;
        }
        if(count==0&&groups.length()==0)add(content,label("Aucun dossier. Découpe une vidéo depuis l'accueil pour commencer.",14,MUTED,false),12);
    }
    private void renderGroup(){
        List<File> all=folders();int foldersCount=0,videoCount=0;long size=0;
        for(File folder:all)if(groupId.equals(state.groupFor(folder.getName()))){
            foldersCount++;videoCount+=countVideo(folder);size+=sizeOf(folder);
        }
        labelLine(toolbar,foldersCount+" dossiers · "+videoCount+" vidéos",bytes(size));
        String note=state.note("group:"+groupId);
        if(!note.isEmpty())add(toolbar,label("Note : "+shortText(note),13,MUTED,false),5);
        action(toolbar,"＋ AJOUTER DES DOSSIERS",this::addFolders);
        action(toolbar,"✎ NOTE DU CLASSEMENT",()->editNote("group:"+groupId));
        action(toolbar,"⚙ AFFICHAGE ET TRI",this::displayOptions);
        for(File folder:all)if(groupId.equals(state.groupFor(folder.getName())))renderFolderCard(folder);
        if(foldersCount==0)add(content,label("Ce classement est vide. Ajoute des dossiers sans déplacer leurs vidéos.",13,MUTED,false),10);
    }
    private String shortText(String s){return s.replace('\n',' ').length()>100?s.replace('\n',' ').substring(0,100)+"…":s.replace('\n',' ');}
    private void renderFolderCard(File folder){
        LinearLayout card=surface(content);
        labelLine(card,"▣   "+folder.getName(),countVideo(folder)+" vidéos · "+bytes(sizeOf(folder)));
        String note=state.note("folder:"+folder.getName());
        if(!note.isEmpty())add(card,label("✎ "+shortText(note),12,MUTED,false),5);
        action(card,"OUVRIR LE DOSSIER",()->{folderName=folder.getName();selecting=false;selection.clear();render();});
        action(card,"⋮ Actions",()->folderActions(folder));
    }
    private void renderVideos() throws Exception {
        File folder=workspace.file("CutVideo/"+folderName);
        if(!folder.isDirectory())throw new Exception("Ce dossier a été renommé ou supprimé");
        List<File> videos=videos(folder);long size=0;
        for(File f:videos)size+=sizeOf(f);
        labelLine(toolbar,videos.size()+" vidéos · "+bytes(size),"Dossier : "+folderName);
        String note=state.note("folder:"+folderName);
        if(!note.isEmpty())add(toolbar,label("✎ "+shortText(note),13,MUTED,false),5);
        action(toolbar,"✎ NOTE DU DOSSIER",()->editNote("folder:"+folderName));
        action(toolbar,selecting?"TERMINER LA SÉLECTION":"SÉLECTIONNER",()->{
            selecting=!selecting;selection.clear();render();
        });
        if(selecting){
            action(toolbar,"TOUT SÉLECTIONNER / DÉSÉLECTIONNER",()->{
                if(selection.size()==videos.size())selection.clear();
                else for(File f:videos)selection.add(path(f));
                render();
            });
            action(toolbar,"PARTAGER LES VIDÉOS SÉLECTIONNÉES",()->shareSelected(videos));
            action(toolbar,"SUPPRIMER LA SÉLECTION",()->deleteSelected(videos));
        }
        action(toolbar,"⚙ AFFICHAGE ET TRI",this::displayOptions);
        if(videos.isEmpty())add(content,label("Aucune vidéo dans ce dossier.",13,MUTED,false),10);
        if(state.layout()==1){
            LinearLayout row=null;
            for(int i=0;i<videos.size();i++){
                if(i%2==0){row=new LinearLayout(this);row.setOrientation(0);add(content,row,7);}
                LinearLayout cell=column();LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);
                if(i%2==0)params.rightMargin=dp(6);
                else params.leftMargin=dp(6);
                row.addView(cell,params);videoCard(cell,videos.get(i),true);
            }
        }else for(File video:videos)videoCard(content,video,false);
    }
    private String path(File video){return "CutVideo/"+folderName+"/"+video.getName();}
    private void videoCard(LinearLayout parent,File video,boolean grid){
        LinearLayout p=surface(parent);
        ImageView thumb=new ImageView(this);thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(0xff101818);
        add(p,thumb,7);
        LinearLayout.LayoutParams ip=(LinearLayout.LayoutParams)thumb.getLayoutParams();
        int scale=state.size();
        ip.height=dp(grid?(scale==0?76:scale==2?122:94):(scale==0?116:scale==2?210:155));
        thumb.setLayoutParams(ip);
        loadThumb(thumb,video);
        labelLine(p,"▶ "+video.getName(),bytes(video.length()));
        final String videoPath=path(video);
        if(selecting){
            CheckBox cb=new CheckBox(this);cb.setText("Sélectionner");cb.setTextColor(TEXT);
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL));
            cb.setChecked(selection.contains(videoPath));add(p,cb,2);
            cb.setOnCheckedChangeListener((v,b)->{if(b)selection.add(videoPath);else selection.remove(videoPath);});
        }else{
            action(p,"▶ LIRE",()->openVideo(videoPath));
            action(p,"PLANIFIER LA PUBLICATION",()->startActivity(
                new Intent(this,CutStudioActivity.class).putExtra("cut_schedule_path",videoPath)));
            action(p,"⋮ VIDÉO",()->videoActions(video));
            if(!grid){
                TextView label=label("SUIVI DES PARTAGES",11,MUTED,true);add(p,label,3);
                for(int i=0;i<NETWORKS.length;i++){
                    final String key=NETWORKS[i];
                    CheckBox checked=new CheckBox(this);checked.setText(NETWORK_LABELS[i]);checked.setTextSize(12);
                    checked.setTextColor(TEXT);checked.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL));
                    checked.setChecked(state.isTracked(videoPath,key));add(p,checked,0);
                    checked.setOnCheckedChangeListener((view,value)->{
                        try{state.setTracked(videoPath,key,value);}
                        catch(Exception ex){toast(ex.getMessage());}
                    });
                }
            }
        }
    }
    private void loadThumb(ImageView target,File file){
        new Thread(()->{
            Bitmap frame=null;MediaMetadataRetriever retriever=new MediaMetadataRetriever();
            try{retriever.setDataSource(file.getAbsolutePath());frame=retriever.getFrameAtTime(0);}
            catch(Exception ignored){}finally{try{retriever.release();}catch(Exception ignored){}}
            Bitmap finalFrame=frame;
            runOnUiThread(()->{
                if(!isDestroyed()&&target.isAttachedToWindow()&&finalFrame!=null)target.setImageBitmap(finalFrame);
                else if(finalFrame!=null)finalFrame.recycle();
            });
        },"cut-thumbnail").start();
    }
    private void openVideo(String path){
        startActivity(new Intent(this,MediaPreviewActivity.class).putExtra("path",path));
    }
    private void videoActions(File video){
        String p=path(video);
        String[] opts={"Lire","Planifier","Partager","Renommer","Suivi des réseaux","Supprimer (corbeille)"};
        new AlertDialog.Builder(this).setTitle(video.getName()).setItems(opts,(d,n)->{
            if(n==0)openVideo(p);
            else if(n==1)startActivity(new Intent(this,CutStudioActivity.class).putExtra("cut_schedule_path",p));
            else if(n==2)shareFiles(Arrays.asList(video));
            else if(n==3)renameVideo(video);
            else if(n==4)trackingDialog(p);
            else if(n==5)confirmTrash(Arrays.asList(video),false);
        }).show();
    }
    private void trackingDialog(String path){
        boolean[] checks=new boolean[4];
        for(int i=0;i<4;i++)checks[i]=state.isTracked(path,NETWORKS[i]);
        new AlertDialog.Builder(this).setTitle("Suivi des partages")
            .setMultiChoiceItems(NETWORK_LABELS,checks,(d,which,checked)->checks[which]=checked)
            .setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(d,n)->{
                try{for(int i=0;i<4;i++)state.setTracked(path,NETWORKS[i],checks[i]);render();}
                catch(Exception e){toast(e.getMessage());}
            }).show();
    }
    private void groupActions(String id,String name){
        new AlertDialog.Builder(this).setTitle(name)
            .setItems(new String[]{"Ouvrir","Renommer","Éditer la note","Partager les vidéos","Supprimer le classement (sans supprimer les vidéos)"},(d,n)->{
                if(n==0){groupId=id;groupName=name;render();}
                else if(n==1)editGroup(id);
                else if(n==2)editNote("group:"+id);
                else if(n==3){
                    List<File> files=new ArrayList<>();
                    for(File f:folders())if(id.equals(state.groupFor(f.getName())))files.addAll(videos(f));
                    shareFiles(files);
                }else new AlertDialog.Builder(this).setTitle("Supprimer le classement ?")
                    .setMessage("Les dossiers reviendront à la racine. Aucune vidéo ne sera effacée.")
                    .setNegativeButton("Annuler",null)
                    .setPositiveButton("Supprimer",(x,y)->{
                        try{state.deleteGroup(id);if(id.equals(groupId)){groupId="";groupName="";}render();}
                        catch(Exception e){toast(e.getMessage());}
                    }).show();
            }).show();
    }
    private void folderActions(File folder){
        String name=folder.getName();
        new AlertDialog.Builder(this).setTitle(name)
            .setItems(new String[]{"Ouvrir","Classer / Déplacer","Renommer","Éditer la note","Partager tout le dossier","Supprimer le dossier (corbeille)"},(d,n)->{
                if(n==0){folderName=name;render();}
                else if(n==1)chooseGroup(folder);
                else if(n==2)renameFolder(folder);
                else if(n==3)editNote("folder:"+name);
                else if(n==4)shareFiles(videos(folder));
                else confirmTrash(Arrays.asList(folder),true);
            }).show();
    }
    private void chooseGroup(File folder){
        JSONArray groups=state.groups();
        String[] names=new String[groups.length()+1],ids=new String[groups.length()+1];
        names[0]="Mes vidéos (sans classement)";ids[0]="";
        for(int i=0;i<groups.length();i++){
            JSONObject g=groups.optJSONObject(i);
            names[i+1]=g.optString("name");ids[i+1]=g.optString("id");
        }
        new AlertDialog.Builder(this).setTitle("Classer "+folder.getName())
            .setItems(names,(d,n)->{
                try{state.assign(folder.getName(),ids[n]);render();}
                catch(Exception e){toast(e.getMessage());}
            }).setNeutralButton("Nouveau classement",(d,n)->editGroup(null)).show();
    }
    private void addFolders(){
        List<File> list=folders();
        if(list.isEmpty()){toast("Aucun dossier de découpe disponible");return;}
        String[] names=new String[list.size()];boolean[] checks=new boolean[list.size()];
        for(int i=0;i<list.size();i++){
            names[i]=list.get(i).getName();checks[i]=groupId.equals(state.groupFor(names[i]));
        }
        new AlertDialog.Builder(this).setTitle("Ajouter des dossiers au classement")
            .setMultiChoiceItems(names,checks,(d,n,b)->checks[n]=b)
            .setNegativeButton("Annuler",null).setPositiveButton("Appliquer",(d,n)->{
                try{
                    for(int i=0;i<names.length;i++){
                        if(checks[i])state.assign(names[i],groupId);
                        else if(groupId.equals(state.groupFor(names[i])))state.assign(names[i],"");
                    }
                    render();
                }catch(Exception e){toast(e.getMessage());}
            }).show();
    }
    private void editGroup(String id){
        EditText input=new EditText(this);input.setSingleLine(true);input.setTextColor(TEXT);
        String previous="";
        if(id!=null){
            JSONArray list=state.groups();
            for(int i=0;i<list.length();i++){
                JSONObject g=list.optJSONObject(i);
                if(g!=null&&id.equals(g.optString("id")))previous=g.optString("name");
            }
        }
        input.setText(previous);input.setSelectAllOnFocus(true);
        LinearLayout wrapper=column();wrapper.setPadding(dp(22),0,dp(22),0);wrapper.addView(input);
        new AlertDialog.Builder(this).setTitle(id==null?"Créer un classement":"Renommer le classement")
            .setView(wrapper).setNegativeButton("Annuler",null)
            .setPositiveButton(id==null?"Créer":"Renommer",(d,n)->{
                try{if(id==null)state.createGroup(input.getText().toString());
                    else{state.renameGroup(id,input.getText().toString());if(id.equals(groupId))groupName=input.getText().toString().trim();}
                    render();
                }catch(Exception e){toast(e.getMessage());}
            }).show();
    }
    private void editNote(String key){
        EditText editor=new EditText(this);editor.setText(state.note(key));editor.setTextColor(TEXT);
        editor.setHint("Idées, publications effectuées, prochaines étapes…");
        editor.setHintTextColor(MUTED);editor.setMinLines(5);editor.setGravity(Gravity.TOP);
        LinearLayout wrapper=column();wrapper.setPadding(dp(22),0,dp(22),0);wrapper.addView(editor);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Bloc-notes")
            .setView(wrapper).setNeutralButton("DATE / HEURE",null)
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Enregistrer",(d,n)->{
                try{state.setNote(key,editor.getText().toString());render();}
                catch(Exception e){toast(e.getMessage());}
            }).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(w->{
            editor.append("\n"+DateFormat.getDateTimeInstance().format(new Date())+"\n");
        }));dialog.show();
    }
    private void displayOptions(){
        String[] layouts={"Liste détaillée","Grille compacte"};
        String[] sizes={"Petite","Normale","Grande"};
        String[] sorting={"Plus récent","Nom","Taille","Nombre de vidéos"};
        final int[] values={state.layout(),state.size(),state.sort()};
        String[] opts={"Disposition : "+layouts[values[0]],"Taille : "+sizes[values[1]],"Tri : "+sorting[values[2]]};
        new AlertDialog.Builder(this).setTitle("Affichage et tri").setItems(opts,(d,which)->{
            String[] labels=which==0?layouts:which==1?sizes:sorting;
            new AlertDialog.Builder(this).setTitle(opts[which]).setSingleChoiceItems(labels,values[which],(a,n)->{
                values[which]=n;state.display(values[0],values[1],values[2]);
                a.dismiss();render();
            }).setNegativeButton("Annuler",null).show();
        }).setNegativeButton("Fermer",null).show();
    }
    private void renameFolder(File folder){
        promptName("Renommer le dossier",folder.getName(),name->{
            File target=new File(root,name);
            if(!validName(name)||target.exists()){toast("Nom invalide ou déjà utilisé");return;}
            String old=folder.getName();
            if(!folder.renameTo(target)){toast("Renommage impossible");return;}
            try{
                state.renameFolder(old,name);
                CutStudioStore.rewritePaths(this,"CutVideo/"+old+"/","CutVideo/"+name+"/");
                if(old.equals(folderName))folderName=name;
                render();
            }catch(Exception e){target.renameTo(folder);toast("Métadonnées : "+e.getMessage());}
        });
    }
    private void renameVideo(File file){
        promptName("Renommer la vidéo",file.getName(),name->{
            if(!name.toLowerCase(Locale.ROOT).endsWith(".mp4"))name=name+".mp4";
            if(!validName(name)){toast("Nom de vidéo invalide");return;}
            File target=new File(file.getParentFile(),name);
            if(target.exists()){toast("Ce nom existe déjà");return;}
            String old=path(file), next="CutVideo/"+folderName+"/"+name;
            if(!file.renameTo(target)){toast("Renommage impossible");return;}
            try{state.renameVideo(old,next);CutStudioStore.rewritePaths(this,old,next);render();}
            catch(Exception e){target.renameTo(file);toast("Métadonnées : "+e.getMessage());}
        });
    }
    private interface NameCallback{void renamed(String value);}
    private void promptName(String title,String current,NameCallback callback){
        EditText edit=new EditText(this);edit.setText(current);edit.setTextColor(TEXT);edit.setSelectAllOnFocus(true);
        LinearLayout wrapper=column();wrapper.setPadding(dp(20),0,dp(20),0);wrapper.addView(edit);
        new AlertDialog.Builder(this).setTitle(title).setView(wrapper)
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Enregistrer",(d,n)->callback.renamed(edit.getText().toString().trim())).show();
    }
    private boolean validName(String name){
        return name!=null&&!name.isEmpty()&&name.length()<=120&&!name.startsWith(".")
            &&!name.contains("/")&&!name.contains("\\")&&!name.equals("..");
    }
    private void shareSelected(List<File> videos){
        List<File> chosen=new ArrayList<>();for(File f:videos)if(selection.contains(path(f)))chosen.add(f);
        shareFiles(chosen);
    }
    private void shareFiles(List<File> files){
        if(files.isEmpty()){toast("Aucune vidéo à partager");return;}
        try{
            ArrayList<Uri> uris=new ArrayList<>();
            for(File file:files)if(file.isFile()){
                String folder=file.getParentFile().getName();
                uris.add(workspace.uri("CutVideo/"+folder+"/"+file.getName()));
            }
            if(uris.isEmpty()){toast("Aucun MP4 sélectionné");return;}
            Intent intent=new Intent(uris.size()==1?Intent.ACTION_SEND:Intent.ACTION_SEND_MULTIPLE);
            intent.setType("video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if(uris.size()==1)intent.putExtra(Intent.EXTRA_STREAM,uris.get(0));
            else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);
            ClipData clip=ClipData.newUri(getContentResolver(),"Cut Vidéo",uris.get(0));
            for(int i=1;i<uris.size();i++)clip.addItem(new ClipData.Item(uris.get(i)));
            intent.setClipData(clip);
            startActivity(Intent.createChooser(intent,"Partager les vidéos"));
        }catch(Exception e){toast("Partage : "+e.getMessage());}
    }
    private void deleteSelected(List<File> videos){
        List<File> chosen=new ArrayList<>();for(File f:videos)if(selection.contains(path(f)))chosen.add(f);
        confirmTrash(chosen,false);
    }
    private void confirmTrash(List<File> files,boolean folder){
        if(files.isEmpty()){toast("Aucun élément sélectionné");return;}
        new AlertDialog.Builder(this).setTitle("Envoyer dans la corbeille ?")
            .setMessage(files.size()+" élément(s). Ils seront conservés dans Fichiers CHK/CutVideo/.trash. Les programmations associées seront annulées.")
            .setNegativeButton("Annuler",null).setPositiveButton("Mettre à la corbeille",(d,n)->{
                try{
                    File trash=workspace.file("CutVideo/.trash");
                    if(!trash.exists())workspace.mkdir("CutVideo/.trash");
                    for(File file:files){
                        String old=file.isDirectory()?"CutVideo/"+file.getName()+"/":
                            "CutVideo/"+file.getParentFile().getName()+"/"+file.getName();
                        File dest=new File(trash,System.currentTimeMillis()+"_"+file.getName());
                        if(!file.renameTo(dest))throw new Exception("Échec : "+file.getName());
                        CutStudioStore.removeForPath(this,old);
                        if(file.isFile())state.clearVideo(old);
                    }
                    selecting=false;selection.clear();
                    if(folder){folderName="";groupId="";groupName="";}
                    render();
                }catch(Exception e){toast("Corbeille : "+e.getMessage());}
            }).show();
    }
    private void showTrash(){
        try{
            File trash=workspace.file("CutVideo/.trash");
            File[] files=trash.isDirectory()?trash.listFiles():null;
            if(files==null||files.length==0){toast("La corbeille est vide");return;}
            Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
            String[] names=new String[files.length];
            for(int i=0;i<files.length;i++)names[i]=files[i].getName();
            new AlertDialog.Builder(this).setTitle("Corbeille Cut Vidéo")
                .setItems(names,(d,n)->restore(files[n]))
                .setNegativeButton("Fermer",null).show();
        }catch(Exception e){toast("Corbeille : "+e.getMessage());}
    }
    private void restore(File file){
        String name=file.getName().replaceFirst("^[0-9]+_", "");
        File destination=file.isDirectory()?new File(root,name):new File(root,"recuperation_"+name);
        if(destination.exists()){
            toast("Un dossier ou fichier de ce nom existe déjà");return;
        }
        new AlertDialog.Builder(this).setTitle("Restaurer ?")
            .setMessage("Restaurer "+name+" ? Les rappels de publication supprimés ne seront pas recréés.")
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Restaurer",(d,n)->{
                if(file.renameTo(destination))render();
                else toast("Impossible de restaurer");
            }).show();
    }
    private void alert(String title,String message){
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",(d,n)->finish()).show();
    }
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
}
