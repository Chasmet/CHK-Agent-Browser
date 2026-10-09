package com.chk.agentbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.ArrayList;

/** Mobile-first timeline editor with native preview and offline Media3 export. */
public final class VideoEditorActivity extends Activity {
    private final VideoEditorEngine engine=VideoEditorEngine.get();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout timeline,controls;
    private TextView summary,renderStatus;
    private VideoView preview;
    private JSONObject project;
    private boolean alive;
    private final Runnable ticker=new Runnable(){public void run(){
        if(!alive)return;
        JSONObject s=engine.status();
        renderStatus.setText("Export : "+s.optString("state")+" · "+s.optInt("progress_percent")+" % "+
            s.optString("error",""));
        handler.postDelayed(this,1300);
    }};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff0d1423);
        getWindow().setNavigationBarColor(0xff0d1423);
        LinearLayout root=MobileUi.column(this);
        root.setBackgroundColor(0xff0d1423);
        setContentView(root);
        TextView title=MobileUi.text(this,"🎬 Studio vidéo CHK",22,MobileUi.TEXT);
        root.addView(title);
        TextView hint=MobileUi.text(this,"Timeline · filtres · découpes · audio original · export MP4 local",13,MobileUi.MUTED);
        root.addView(hint);
        ScrollView sc=new ScrollView(this);
        root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        controls=MobileUi.column(this);
        sc.addView(controls);
        preview=new VideoView(this);
        LinearLayout.LayoutParams pv=new LinearLayout.LayoutParams(-1,MobileUi.dp(this,270));
        pv.setMargins(MobileUi.dp(this,12),0,MobileUi.dp(this,12),MobileUi.dp(this,6));
        controls.addView(preview,pv);
        preview.setBackgroundColor(0xff000000);
        preview.setOnPreparedListener(mp->{mp.setLooping(true);preview.start();});
        TextView preset=button("Charger automatiquement Alpha Omega (6 Grok + 6 WAV)");
        preset.setOnClickListener(v->{
            engine.command(this,"video_editor_preset_alpha_omega",new JSONObject(),(ok,res)->
                runOnUiThread(()->{
                    if(ok){readProject();toast("Projet Alpha Omega chargé");}
                    else new AlertDialog.Builder(this).setTitle("Projet impossible").setMessage(res).setPositiveButton("OK",null).show();
                }));
        });
        TextView addVideo=button("＋ Ajouter une vidéo depuis Fichiers");
        addVideo.setOnClickListener(v->chooseFile("video"));
        TextView addAudio=button("＋ Ajouter un WAV / MP3 depuis Fichiers");
        addAudio.setOnClickListener(v->chooseFile("audio"));
        summary=MobileUi.text(this,"Chargement…",14,MobileUi.MUTED);controls.addView(summary);
        HorizontalScrollView scroll=new HorizontalScrollView(this);
        scroll.setFillViewport(false);
        controls.addView(scroll,new LinearLayout.LayoutParams(-1,MobileUi.dp(this,186)));
        timeline=new LinearLayout(this);timeline.setOrientation(LinearLayout.HORIZONTAL);scroll.addView(timeline);
        TextView destination=button("Nom du fichier final");
        destination.setOnClickListener(v->editOutput());
        TextView save=button("Sauvegarder le montage");
        save.setOnClickListener(v->saveProject());
        TextView export=button("Exporter MP4 avec les effets et l'audio original");
        export.setOnClickListener(v->new AlertDialog.Builder(this)
            .setTitle("Exporter le montage")
            .setMessage("Création locale du MP4. Le rendu prend plusieurs minutes et consomme de la batterie. Garder le téléphone branché.")
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Exporter",(d,w)->engine.command(this,"video_editor_export",new JSONObject(),(ok,result)->
                runOnUiThread(()->toast(ok?"Export lancé":result)))).show());
        TextView cancel=button("Interrompre l'export");
        cancel.setOnClickListener(v->engine.command(this,"video_editor_cancel",new JSONObject(),(ok,result)->
                runOnUiThread(()->toast("Export interrompu"))));
        renderStatus=MobileUi.text(this,"",14,MobileUi.MUTED);controls.addView(renderStatus);
        TextView close=button("Retour au navigateur");
        close.setOnClickListener(v->finish());
        alive=true;
        readProject();
        handler.post(ticker);
    }
    private TextView button(String label){
        TextView item=MobileUi.action(this,label);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(MobileUi.dp(this,12),MobileUi.dp(this,4),MobileUi.dp(this,12),MobileUi.dp(this,4));
        controls.addView(item,p);
        return item;
    }
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private void readProject(){
        engine.command(this,"video_editor_project_read",new JSONObject(),(ok,result)->runOnUiThread(()->{
            try{if(ok){project=new JSONObject(result);drawTimeline();}}
            catch(Exception ex){toast(ex.getMessage());}
        }));
    }
    private JSONArray clips(){return project.optJSONArray("clips")==null?new JSONArray():project.optJSONArray("clips");}
    private JSONArray audio(){return project.optJSONArray("audio")==null?new JSONArray():project.optJSONArray("audio");}
    private void drawTimeline(){
        if(project==null||timeline==null)return;
        timeline.removeAllViews();
        long ms=0;JSONArray scenes=clips();
        for(int i=0;i<scenes.length();i++){
            JSONObject c=scenes.optJSONObject(i);if(c==null)continue;
            long duration=c.optLong("duration_ms",10000);ms+=duration;
            final int at=i;
            LinearLayout line=MobileUi.column(this);line.setGravity(Gravity.CENTER_VERTICAL);
            TextView name=MobileUi.action(this,(i+1)+" · "+basename(c.optString("path"))+
                "\n"+String.format(Locale.ROOT,"%.1fs",duration/1000.0)+" · "+
                c.optString("filter","aucun"));
            name.setMaxLines(2);
            line.addView(name,new LinearLayout.LayoutParams(MobileUi.dp(this,185),MobileUi.dp(this,70)));
            name.setOnClickListener(v->showClipMenu(at));
            LinearLayout movements=new LinearLayout(this);
            TextView up=small("←");up.setOnClickListener(v->moveClip(at,-1));movements.addView(up);
            TextView down=small("→");down.setOnClickListener(v->moveClip(at,1));movements.addView(down);
            line.addView(movements);
            timeline.addView(line);
        }
        summary.setText("Projet : "+project.optString("name")+" · "+scenes.length()+
            " vidéos · "+audio().length()+" audios · "+String.format(Locale.ROOT,"%.1f",ms/1000.0)+" s");
        if(scenes.length()>0&&preview.getTag()==null)play(scenes.optJSONObject(0).optString("path"));
    }
    private TextView small(String label){
        TextView v=MobileUi.action(this,label);
        v.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(MobileUi.dp(this,46),MobileUi.dp(this,52));
        p.setMargins(MobileUi.dp(this,4),0,MobileUi.dp(this,4),0);v.setLayoutParams(p);
        return v;
    }
    private String basename(String path){int k=path.lastIndexOf('/');return k<0?path:path.substring(k+1);}
    private void play(String path){
        try{
            WorkspaceStore store=new WorkspaceStore(this);
            preview.stopPlayback();
            preview.setVideoPath(store.file(path).getAbsolutePath());
            preview.setTag(path);
            preview.seekTo(0);
        }catch(Exception ex){toast(ex.getMessage());}
    }
    private void moveClip(int at,int delta){
        JSONArray arr=clips();int dest=at+delta;if(dest<0||dest>=arr.length())return;
        Object temp=arr.opt(at),other=arr.opt(dest);
        try{arr.put(at,other);arr.put(dest,temp);project.put("clips",arr);saveThenRefresh();}
        catch(Exception ex){toast(ex.getMessage());}
    }
    private void showClipMenu(int index){
        JSONObject clip=clips().optJSONObject(index);if(clip==null)return;
        play(clip.optString("path"));
        String[] opts={"Lire la vidéo","Filtre : aucun","Filtre : cinéma","Filtre : noir et blanc",
           "Filtre : froid","Filtre : chaud","Filtre : contraste","Filtre : nuit","Filtre : vintage",
           "Découper la durée","Fondu au noir (0,18 s)","Sans fondu","Supprimer le plan"};
        String[] keys={"aucun","cinema","noir","froid","chaud","contraste","nuit","vintage"};
        new AlertDialog.Builder(this).setTitle("Plan "+(index+1)).setItems(opts,(dialog,choice)->{
            try{
                if(choice==0)play(clip.getString("path"));
                else if(choice>=1&&choice<=8){clip.put("filter",keys[choice-1]);saveThenRefresh();}
                else if(choice==9)editDuration(index);
                else if(choice==10){clip.put("fade_ms",180);saveThenRefresh();}
                else if(choice==11){clip.put("fade_ms",0);saveThenRefresh();}
                else if(choice==12){
                    JSONArray kept=new JSONArray();
                    for(int i=0;i<clips().length();i++)if(i!=index)kept.put(clips().get(i));
                    project.put("clips",kept);preview.setTag(null);saveThenRefresh();
                }
            }catch(Exception ex){toast(ex.getMessage());}
        }).show();
    }
    private void editDuration(int index){
        JSONObject clip=clips().optJSONObject(index);if(clip==null)return;
        EditText entry=new EditText(this);
        entry.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        entry.setText(String.format(Locale.ROOT,"%.1f",clip.optLong("duration_ms",10000)/1000.0));
        new AlertDialog.Builder(this).setTitle("Durée en secondes").setView(entry)
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Appliquer",(d,w)->{
                try{
                    long ms=Math.round(Double.parseDouble(entry.getText().toString().replace(',','.'))*1000);
                    clip.put("duration_ms",ms);saveThenRefresh();
                }catch(Exception ex){toast("Durée invalide");}
            }).show();
    }
    private void editOutput(){
        if(project==null)return;
        EditText entry=new EditText(this);entry.setText(project.optString("output"));
        new AlertDialog.Builder(this).setTitle("Chemin de sortie dans Fichiers").setView(entry)
            .setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(d,w)->{
                try{project.put("output",entry.getText().toString().trim());saveThenRefresh();}
                catch(Exception ex){toast(ex.getMessage());}
            }).show();
    }
    private void saveThenRefresh(){saveProject();drawTimeline();}
    private void saveProject(){
        if(project==null)return;
        try{
            engine.command(this,"video_editor_project_save",
               new JSONObject().put("project",project),(ok,result)->runOnUiThread(()->{
                   if(!ok)toast(result);
               }));
        }catch(Exception ex){toast(ex.getMessage());}
    }
    private void chooseFile(String kind){chooseFolder(kind,"");}
    private void chooseFolder(String kind,String folder){
        new Thread(()->{
            try{
                WorkspaceStore store=new WorkspaceStore(this);
                JSONObject results=store.list(folder,"",0);
                JSONArray rows=results.getJSONArray("items");
                ArrayList<String> names=new ArrayList<>(),paths=new ArrayList<>();
                if(!folder.isEmpty()){names.add("‹ Dossier parent");paths.add("..");}
                for(int i=0;i<rows.length();i++){
                    JSONObject row=rows.getJSONObject(i);String p=row.getString("path");
                    if(row.getBoolean("directory")){names.add("📁 "+row.getString("name"));paths.add(p);}
                    else if(kind.equals("video")?p.endsWith(".mp4"):
                         (p.endsWith(".wav")||p.endsWith(".mp3")||p.endsWith(".m4a"))){
                         names.add(row.getString("name"));paths.add(p);
                    }
                }
                runOnUiThread(()->new AlertDialog.Builder(this)
                    .setTitle("Fichiers / "+folder).setItems(names.toArray(new String[0]),(d,which)->{
                        String p=paths.get(which);
                        if(p.equals("..")){int cut=folder.lastIndexOf('/');chooseFolder(kind,cut<0?"":folder.substring(0,cut));}
                        else{
                            try{
                                WorkspaceStore s=new WorkspaceStore(this);
                                if(s.file(p).isDirectory())chooseFolder(kind,p);
                                else addFile(kind,p);
                            }catch(Exception ex){toast(ex.getMessage());}
                        }
                    }).setNegativeButton("Fermer",null).show());
            }catch(Exception ex){runOnUiThread(()->toast(ex.getMessage()));}
        }).start();
    }
    private void addFile(String kind,String path){
        if(project==null)return;
        try{
            JSONArray target=kind.equals("video")?clips():audio();
            JSONObject c=new JSONObject().put("path",path).put("start_ms",0).put("duration_ms",10000);
            if(kind.equals("video"))c.put("filter","aucun").put("fade_ms",0);
            target.put(c);project.put(kind.equals("video")?"clips":"audio",target);
            saveThenRefresh();
        }catch(Exception ex){toast(ex.getMessage());}
    }
    @Override protected void onDestroy(){
        alive=false;handler.removeCallbacks(ticker);
        preview.stopPlayback();super.onDestroy();
    }
}
