package com.chk.agentbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import org.json.JSONObject;
import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Cut Vidéo intégré à CHK : découpe Media3 hors ligne, bibliothèque, planning et validation. */
@OptIn(markerClass=UnstableApi.class)
public final class CutStudioActivity extends Activity {
    private static final int PICK_VIDEO=9031;
    private static final int INK=0xfff2f5ff, MUTED=0xffa8b7cb, PANEL=0xff252a34;
    private final String[] networks={"YouTube","TikTok","Instagram","X"};
    private final String[] keys={"youtube","tiktok","instagram","x"};
    private LinearLayout content;
    private TextView status;
    private int tab=0;
    private Uri source;
    private String sourceName="";
    private long sourceDurationMs;
    private EditText lengthInput,startInput,endInput;
    private WorkspaceStore workspace;
    private Transformer transformer;
    private File pendingTemp;
    private File outputDir;
    private String outputFolder="";
    private boolean exporting,disposed;
    private int segmentIndex;
    private final List<long[]> segments=new ArrayList<>();

    @Override public void onCreate(Bundle state){
        super.onCreate(state);setContentView(R.layout.activity_cut_studio);
        content=findViewById(R.id.cut_content);status=findViewById(R.id.cut_status);
        try{workspace=new WorkspaceStore(this);ensureFolder();}
        catch(Exception e){message("Espace CHK indisponible : "+e.getMessage());}
        findViewById(R.id.cut_back).setOnClickListener(v->finish());
        findViewById(R.id.cut_tab_export).setOnClickListener(v->render(0));
        findViewById(R.id.cut_tab_library).setOnClickListener(v->render(1));
        findViewById(R.id.cut_tab_planning).setOnClickListener(v->render(2));
        render(getIntent().hasExtra("schedule_id")?2:0);
        requestNotificationPermission();
    }
    private void requestNotificationPermission(){
        if(android.os.Build.VERSION.SDK_INT>=33&&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=
                android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},9032);
    }
    private void ensureFolder() throws Exception{
        File f=workspace.file(CutStudioStore.FOLDER);
        if(!f.exists())workspace.mkdir(CutStudioStore.FOLDER);
        else if(!f.isDirectory())throw new IllegalStateException("CutVideo n'est pas un dossier");
    }
    @Override protected void onResume(){super.onResume();if(content!=null&&!exporting&&tab!=0)render(tab);}
    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(null,1);t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(8),dp(6),dp(8),dp(6));return t;
    }
    private LinearLayout column(){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private LinearLayout panel(){
        LinearLayout l=column();l.setPadding(dp(10),dp(10),dp(10),dp(12));
        l.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);
        content.addView(l,lp);return l;
    }
    private Button button(String label,LinearLayout parent,Runnable action){
        Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setAllCaps(false);
        b.setOnClickListener(v->action.run());parent.addView(b,new LinearLayout.LayoutParams(-1,dp(49)));
        return b;
    }
    private EditText field(LinearLayout parent,String label,String hint,String value,boolean numeric){
        parent.addView(text(label,13,MUTED,false));
        EditText e=new EditText(this);e.setTextColor(INK);e.setHintTextColor(MUTED);
        e.setHint(hint);e.setText(value);e.setSingleLine(!label.startsWith("Description"));
        if(numeric)e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        else e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        parent.addView(e,new LinearLayout.LayoutParams(-1,dp(53)));return e;
    }
    private void message(String text){if(status!=null)status.setText(text);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();message(text);}
    private void render(int which){
        tab=which;content.removeAllViews();
        int[] ids={R.id.cut_tab_export,R.id.cut_tab_library,R.id.cut_tab_planning};
        for(int i=0;i<3;i++){
            TextView v=findViewById(ids[i]);v.setTextColor(i==which?0xff69e2c6:INK);
            v.setTypeface(null,i==which?1:0);
        }
        if(which==0)renderCut();else if(which==1)renderLibrary();else renderPlanning();
    }
    private void renderCut(){
        LinearLayout p=panel();p.addView(text("01 · Choisir une vidéo",18,INK,true));
        p.addView(text(source==null?"Aucune source sélectionnée":
            sourceName+" · "+format(sourceDurationMs),13,MUTED,false));
        button("Importer depuis le téléphone",p,()->{
            if(exporting){toast("Export en cours");return;}
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*")
                .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i,PICK_VIDEO);
        });
        button("Choisir dans Fichiers CHK",p,()->chooseWorkspace(""));
        LinearLayout v=panel();v.addView(text("02 · Durée des morceaux",18,INK,true));
        lengthInput=field(v,"Longueur (secondes)","15 / 30 / 60 / 90","30",true);
        LinearLayout presets=new LinearLayout(this);presets.setOrientation(LinearLayout.HORIZONTAL);
        for(int seconds:new int[]{15,30,60,90}){
            Button b=new Button(this);b.setText(seconds+"s");b.setTextSize(12);b.setAllCaps(false);
            presets.addView(b,new LinearLayout.LayoutParams(0,dp(49),1));
            b.setOnClickListener(w->lengthInput.setText(String.valueOf(seconds)));
        }
        v.addView(presets);
        startInput=field(v,"Début (secondes)","0","0",true);
        endInput=field(v,"Fin (secondes) · vide = fin de vidéo","Fin",
            sourceDurationMs>0?String.format(Locale.US,"%.2f",sourceDurationMs/1000d):"",true);
        v.addView(text("Découpe exacte via Media3. Maximum 100 morceaux par lot, durée 1 à 600 s.",12,MUTED,false));
        button(exporting?"Export en cours…":"Découper et enregistrer les MP4",v,this::startCut);
        if(exporting)button("Annuler l'export",v,this::cancelCut);
        LinearLayout info=panel();info.addView(text("03 · Publier",18,INK,true));
        info.addView(text("Les morceaux arrivent dans Fichiers CHK > CutVideo. Ouvre « Mes vidéos » pour les programmer sur chaque réseau.",13,MUTED,false));
        button("Ouvrir l'éditeur vidéo avancé",info,()->
            startActivity(new Intent(this,StudioProjectsActivity.class)));
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK_VIDEO||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try{
            getContentResolver().takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }catch(SecurityException ignored){}
        setSource(uri,displayName(uri));
    }
    private String displayName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},
            null,null,null)){
            if(c!=null&&c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}
        return "video.mp4";
    }
    private void setSource(Uri uri,String name){
        if(exporting){toast("Termine ou annule le découpage actuel");return;}
        MediaMetadataRetriever m=new MediaMetadataRetriever();
        try{
            m.setDataSource(this,uri);
            String raw=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long duration=Long.parseLong(raw==null?"0":raw);
            if(duration<=0)throw new IllegalArgumentException("Durée inconnue");
            source=uri;sourceName=name;sourceDurationMs=duration;
            render(0);message("Vidéo prête : "+name);
        }catch(Exception ex){toast("Vidéo non prise en charge : "+ex.getMessage());}
        finally{m.release();}
    }
    private void chooseWorkspace(String folder){
        if(workspace==null)return;
        try{
            File dir=WorkspacePaths.resolve(new File(getFilesDir(),"workspace"),folder,true);
            File[] all=dir.listFiles();if(all==null)throw new IllegalStateException("Dossier illisible");
            List<File> entries=new ArrayList<>();
            for(File f:all)
                if(f.isDirectory()||(f.isFile()&&f.getName().toLowerCase(Locale.ROOT).endsWith(".mp4")))
                    entries.add(f);
            entries.sort((a,b)->a.isDirectory()==b.isDirectory()?
                a.getName().compareToIgnoreCase(b.getName()):(a.isDirectory()?-1:1));
            String[] names=new String[entries.size()];
            for(int i=0;i<entries.size();i++)names[i]=
                (entries.get(i).isDirectory()?"▣  ":"▶  ")+entries.get(i).getName();
            new AlertDialog.Builder(this).setTitle(folder.isEmpty()?"Fichiers CHK":folder)
                .setItems(names,(d,n)->{
                    File f=entries.get(n);String relative=folder.isEmpty()?f.getName():folder+"/"+f.getName();
                    if(f.isDirectory())chooseWorkspace(relative);
                    else try{setSource(Uri.fromFile(workspace.file(relative)),f.getName());}
                        catch(Exception e){toast(e.getMessage());}
                }).setNegativeButton("Fermer",null).show();
        }catch(Exception e){toast("Fichiers : "+e.getMessage());}
    }
    private double parse(EditText e,double fallback){
        String s=e.getText().toString().trim().replace(',','.');
        return s.isEmpty()?fallback:Double.parseDouble(s);
    }
    private void startCut(){
        if(exporting||source==null||workspace==null){toast("Choisis d'abord une vidéo");return;}
        try{
            double size=parse(lengthInput,30),start=parse(startInput,0),
                end=parse(endInput,sourceDurationMs/1000d);
            if(!Double.isFinite(size)||!Double.isFinite(start)||!Double.isFinite(end)||
                size<1||size>600||start<0||end<=start||end*1000>sourceDurationMs+50)
                throw new IllegalArgumentException("Durée, début ou fin invalides");
            long length=Math.round(size*1000),begin=Math.round(start*1000),
                stop=Math.min(sourceDurationMs,Math.round(end*1000));
            segments.clear();
            while(begin<stop){
                if(segments.size()>=100)throw new IllegalArgumentException(
                    "Plus de 100 morceaux. Augmente la durée ou réduis la plage.");
                long next=Math.min(stop,begin+length);segments.add(new long[]{begin,next});begin=next;
            }
            String folder="lot_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US)
                .format(new Date())+"_"+UUID.randomUUID().toString().substring(0,6);
            outputFolder=CutStudioStore.FOLDER+"/"+folder;
            workspace.mkdir(outputFolder);
            outputDir=workspace.file(outputFolder);
            exporting=true;segmentIndex=0;message("Préparation "+segments.size()+" morceaux…");
            render(0);exportNext();
        }catch(Exception e){toast("Découpe impossible : "+e.getMessage());}
    }
    private void exportNext(){
        if(!exporting||disposed)return;
        if(segmentIndex>=segments.size()){
            exporting=false;transformer=null;pendingTemp=null;
            message(segments.size()+" morceaux créés dans "+outputFolder);
            render(1);return;
        }
        long[] times=segments.get(segmentIndex);
        String base=sourceName.replaceAll("\\.[^.]+$","").replaceAll("[^A-Za-z0-9_-]","_");
        if(base.isEmpty())base="clip";
        if(base.length()>36)base=base.substring(0,36);
        String name=String.format(Locale.US,"%s_%02d.mp4",base,segmentIndex+1);
        File dest=new File(outputDir,name);
        pendingTemp=new File(outputDir,"temp_"+UUID.randomUUID()+".mp4");
        try{
            MediaItem media=new MediaItem.Builder().setUri(source)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(times[0]).setEndPositionMs(times[1]).build()).build();
            EditedMediaItem edited=new EditedMediaItem.Builder(media).build();
            File temporary=pendingTemp;
            transformer=new Transformer.Builder(this).addListener(new Transformer.Listener(){
                @Override public void onCompleted(Composition c,ExportResult r){
                    if(!exporting||disposed)return;
                    transformer=null;
                    if(dest.exists()||!temporary.renameTo(dest)){
                        temporary.delete();failCut("Enregistrement de "+name+" impossible");return;
                    }
                    pendingTemp=null;segmentIndex++;
                    message("Morceau "+segmentIndex+"/"+segments.size()+" exporté");
                    exportNext();
                }
                @Override public void onError(Composition c,ExportResult r,ExportException ex){
                    failCut("Codec/export : "+ex.getMessage());
                }
            }).build();
            message("Export "+(segmentIndex+1)+"/"+segments.size()+" · "+
                format(times[0])+" → "+format(times[1]));
            transformer.start(edited,temporary.getAbsolutePath());
        }catch(Exception ex){failCut(ex.getMessage());}
    }
    private void failCut(String error){
        if(transformer!=null){transformer.cancel();transformer=null;}
        if(pendingTemp!=null){pendingTemp.delete();pendingTemp=null;}
        exporting=false;toast("Export arrêté : "+error+". Les morceaux terminés sont conservés.");
        render(1);
    }
    private void cancelCut(){
        if(!exporting)return;
        if(transformer!=null){transformer.cancel();transformer=null;}
        if(pendingTemp!=null){pendingTemp.delete();pendingTemp=null;}
        exporting=false;toast("Export annulé ; morceaux terminés conservés");render(1);
    }
    private void renderLibrary(){
        LinearLayout head=panel();head.addView(text("Bibliothèque Cut Vidéo",18,INK,true));
        head.addView(text("MP4 locaux, sans suppression automatique des fichiers sources.",12,MUTED,false));
        try{
            ensureFolder();
            File root=workspace.file(CutStudioStore.FOLDER);
            File[] folders=root.listFiles();int count=0;
            if(folders!=null)for(File folder:folders){
                if(!folder.isDirectory())continue;
                File[] files=folder.listFiles();if(files==null)continue;
                int n=0;for(File f:files)if(f.getName().endsWith(".mp4")&&
                    !f.getName().startsWith("temp_"))n++;
                if(n==0)continue;
                count+=n;LinearLayout section=panel();
                section.addView(text(folder.getName()+" · "+n+" vidéos",15,INK,true));
                for(File f:files){
                    if(!f.getName().endsWith(".mp4")||f.getName().startsWith("temp_"))continue;
                    String path=CutStudioStore.FOLDER+"/"+folder.getName()+"/"+f.getName();
                    section.addView(text(f.getName()+" · "+(f.length()/1024/1024)+" Mo",13,MUTED,false));
                    button("▶ Lire",section,()->
                        startActivity(new Intent(this,MediaPreviewActivity.class).putExtra("path",path)));
                    button("Planifier / créer des métadonnées",section,()->scheduleEditor(path,null));
                    button("Partager le MP4",section,()->shareVideo(path));
                }
            }
            if(count==0)head.addView(text("Aucun morceau. Reviens à « Découper ».",13,MUTED,false));
            else head.addView(text(count+" morceaux MP4 disponibles",13,0xff69e2c6,true));
        }catch(Exception e){toast("Bibliothèque indisponible : "+e.getMessage());}
    }
    private void shareVideo(String path){
        try{
            Uri uri=workspace.uri(path);
            Intent share=new Intent(Intent.ACTION_SEND).setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM,uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newUri(getContentResolver(),"Vidéo Cut",uri));
            startActivity(Intent.createChooser(share,"Partager le fichier vidéo"));
        }catch(Exception e){toast("Partage : "+e.getMessage());}
    }
    private String format(long ms){
        long sec=ms/1000;return String.format(Locale.FRANCE,"%02d:%02d",sec/60,sec%60);
    }
    private void renderPlanning(){
        LinearLayout head=panel();head.addView(text("Planning de publication",18,INK,true));
        head.addView(text("Rappels Android locaux. Les plateformes doivent confirmer l'envoi. X : publication manuelle, sans programmation native dans ce module.",12,MUTED,false));
        List<JSONObject> entries=CutStudioStore.list(this);
        head.addView(text(entries.size()+" programmations enregistrées",13,0xff69e2c6,true));
        for(JSONObject j:entries){
            LinearLayout p=panel();
            long at=j.optLong("at");boolean done=j.optBoolean("published");
            String state=done?"Publié (confirmé manuellement)":
                at<=System.currentTimeMillis()?"À publier":"Programmé (rappel local)";
            p.addView(text(j.optString("platform").toUpperCase(Locale.ROOT)+
                " · "+j.optString("account"),14,0xff69e2c6,true));
            p.addView(text(j.optString("title"),15,INK,true));
            p.addView(text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
                DateFormat.SHORT,Locale.FRANCE).format(new Date(at)),13,MUTED,false));
            p.addView(text(state+" · "+j.optString("path"),12,MUTED,false));
            button("Ouvrir le réseau + copier les métadonnées",p,()->openPlatform(j));
            button("Partager le fichier vidéo",p,()->shareVideo(j.optString("path")));
            button("Modifier",p,()->scheduleEditor(j.optString("path"),j));
            button("Dupliquer",p,()->scheduleEditor(j.optString("path"),copyForDuplicate(j)));
            button(done?"Annuler la validation":"Valider : publié",p,()->{
                try{CutStudioStore.published(this,j.optString("id"),!done);render(2);}
                catch(Exception e){toast(e.getMessage());}
            });
            button("Supprimer la programmation",p,()->new AlertDialog.Builder(this)
                .setTitle("Supprimer le rappel ?").setMessage("Le fichier MP4 sera conservé.")
                .setNegativeButton("Annuler",null).setPositiveButton("Supprimer",(d,n)->{
                    try{CutStudioStore.remove(this,j.optString("id"));render(2);}
                    catch(Exception e){toast(e.getMessage());}
                }).show());
        }
    }
    private JSONObject copyForDuplicate(JSONObject item){
        try{
            JSONObject clone=new JSONObject(item.toString());clone.remove("id");
            clone.put("published",false);
            clone.put("at",Math.max(System.currentTimeMillis()+3600000L,
                item.optLong("at")+86400000L));
            return clone;
        }catch(Exception ignored){return null;}
    }
    private Spinner spinner(LinearLayout p,String label,String[] values,int selected){
        p.addView(text(label,13,MUTED,false));
        Spinner s=new Spinner(this);ArrayAdapter<String> a=new ArrayAdapter<>(
            this,android.R.layout.simple_spinner_dropdown_item,values);
        s.setAdapter(a);s.setSelection(Math.max(0,selected));p.addView(s);return s;
    }
    private void scheduleEditor(String path,JSONObject existing){
        if(workspace==null)return;
        final LinearLayout form=column();form.setPadding(dp(16),dp(10),dp(16),dp(8));
        TextView file=text(path,12,MUTED,false);form.addView(file);
        Spinner network=spinner(form,"Plateforme",networks,networkIndex(
            existing==null?"youtube":existing.optString("platform")));
        Spinner account=spinner(form,"Compte",new String[]{"CHKNOIRSHADOW","QG"},
            existing!=null&&"qg".equals(existing.optString("account"))?1:0);
        Spinner visibility=spinner(form,"Visibilité",new String[]{"Publique","Non répertoriée","Privée"},
            existing==null?0:visibilityIndex(existing.optString("visibility")));
        String fallback=new File(path).getName().replace(".mp4","").replace('_',' ');
        String suggested=fallback.length()>45?fallback.substring(0,45):fallback;
        EditText title=field(form,"Titre","Titre",existing==null?suggested:
            existing.optString("title"),false);
        EditText description=field(form,"Description","Description",
            existing==null?"":existing.optString("description"),false);
        EditText hashtags=field(form,"Hashtags · maximum 5","#video #shorts",
            existing==null?"#video #shorts":existing.optString("hashtags"),false);
        button("Coller un bloc de métadonnées ChatGPT",form,()->{
            ClipboardManager manager=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(manager==null||!manager.hasPrimaryClip()||manager.getPrimaryClip()==null)return;
            CharSequence raw=manager.getPrimaryClip().getItemAt(0).coerceToText(this);
            if(raw==null||raw.length()==0)return;
            String[] lines=raw.toString().trim().split("\\n");
            StringBuilder body=new StringBuilder(),tags=new StringBuilder();
            boolean hasTitle=false;
            for(String line:lines){
                String v=line.trim().replaceFirst("(?i)^(titre|description|hashtags?)\\s*:\\s*","");
                if(v.isEmpty())continue;
                if(v.startsWith("#")){if(tags.length()>0)tags.append(" ");tags.append(v);}
                else if(!hasTitle){title.setText(v);hasTitle=true;}
                else{if(body.length()>0)body.append(" ");body.append(v);}
            }
            description.setText(body.toString());hashtags.setText(tags.toString());
            toast("Métadonnées collées : vérifier avant de programmer");
        });
        Calendar selected=Calendar.getInstance();
        selected.setTimeInMillis(existing!=null?existing.optLong("at",
            System.currentTimeMillis()+86400000L):System.currentTimeMillis()+86400000L);
        Button date=button("Date : "+android.text.format.DateFormat.getDateFormat(this).format(selected.getTime()),
            form,()->{});
        Button time=button("Heure : "+android.text.format.DateFormat.getTimeFormat(this).format(selected.getTime()),
            form,()->{});
        date.setOnClickListener(v->new DatePickerDialog(this,(picker,y,m,d)->{
            selected.set(y,m,d);date.setText("Date : "+
                android.text.format.DateFormat.getDateFormat(this).format(selected.getTime()));
        },selected.get(Calendar.YEAR),selected.get(Calendar.MONTH),
            selected.get(Calendar.DAY_OF_MONTH)).show());
        time.setOnClickListener(v->new TimePickerDialog(this,(picker,h,m)->{
            selected.set(Calendar.HOUR_OF_DAY,h);selected.set(Calendar.MINUTE,m);
            time.setText("Heure : "+
                android.text.format.DateFormat.getTimeFormat(this).format(selected.getTime()));
        },selected.get(Calendar.HOUR_OF_DAY),selected.get(Calendar.MINUTE),true).show());
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle(existing!=null&&existing.has("id")?"Modifier le planning":"Nouvelle programmation")
            .setView(scroll).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                JSONObject row=new JSONObject().put("path",path)
                    .put("platform",keys[network.getSelectedItemPosition()])
                    .put("account",account.getSelectedItemPosition()==0?"chknoirshadow":"qg")
                    .put("visibility",new String[]{"public","unlisted","private"}[visibility.getSelectedItemPosition()])
                    .put("title",title.getText().toString().trim())
                    .put("description",description.getText().toString().trim())
                    .put("hashtags",hashtags.getText().toString().trim())
                    .put("at",selected.getTimeInMillis())
                    .put("published",existing!=null&&existing.has("id")&&existing.optBoolean("published"));
                if(existing!=null&&existing.has("id"))row.put("id",existing.getString("id"));
                CutStudioStore.save(this,row);dialog.dismiss();render(2);
                toast("Programmation enregistrée. Valide sur le réseau après publication.");
            }catch(Exception ex){toast("Programmation : "+ex.getMessage());}
        }));
        dialog.show();
    }
    private int networkIndex(String v){
        for(int i=0;i<keys.length;i++)if(keys[i].equals(v))return i;
        return 0;
    }
    private int visibilityIndex(String v){return "private".equals(v)?2:"unlisted".equals(v)?1:0;}
    private void openPlatform(JSONObject item){
        String network=item.optString("platform");
        String url;
        switch(network){
            case "youtube":url="https://studio.youtube.com/";break;
            case "tiktok":url="https://www.tiktok.com/upload";break;
            case "instagram":url="https://www.instagram.com/";break;
            default:url="https://x.com/compose/post";
        }
        String text=CutStudioStore.text(item);
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
            ClipData.newPlainText("Métadonnées Cut Vidéo",text));
        try{
            startActivity(new Intent(this,MainActivity.class)
                .putExtra("cutstudio_url",url));
            toast("Métadonnées copiées. Sélectionne le MP4 dans la page du réseau et vérifie la publication.");
        }catch(Exception e){toast("Ouverture du réseau : "+e.getMessage());}
    }
    @Override protected void onDestroy(){
        disposed=true;if(exporting){
            if(transformer!=null)transformer.cancel();
            if(pendingTemp!=null)pendingTemp.delete();
        }
        super.onDestroy();
    }
}
