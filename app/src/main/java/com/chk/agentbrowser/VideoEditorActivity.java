package com.chk.agentbrowser;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import androidx.media3.common.Player;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.transformer.CompositionPlayer;
import androidx.media3.transformer.Composition;
import org.json.*;
import java.util.*;

/** Thumb-friendly studio: live composition preview, tracks, real edits and local MP4 export. */
@androidx.annotation.OptIn(markerClass={androidx.media3.common.util.UnstableApi.class,androidx.media3.common.util.ExperimentalApi.class})
public final class VideoEditorActivity extends Activity {
    private final VideoEditorEngine engine=VideoEditorEngine.get();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private JSONObject project;
    private LinearLayout tools;
    private StudioTimeline timeline;
    private LinearLayout mainTools;
    private TextView selectionLabel;
    private int selectedAudio=-1;
    private Float auditionSpeed;
    private StudioRenderPreview renderPreview;
    private boolean renderedMode;
    private TextView renderBadge;
    private Runnable preparedEditorAction;
    private String toolPage="Accueil";
    private HorizontalScrollView contextStrip,mainNavigation;
    private WorkspaceMediaChooser studioPicker;
    private final java.util.concurrent.ConcurrentHashMap<String,Long> sourceLengths=new java.util.concurrent.ConcurrentHashMap<>();
    private TextView quality,scope;
    private boolean importing,parallelImport;
    private boolean compact;
    private long lastProjectCheck;
    private boolean previewReady;
    private TextView previewHint,previewCaption;
    private TextView title,clock,status,play,format;
    private String lastExportState="";
    private long exportStateAt;
    private Long editSeek;
    private float restoredZoom=1;
    private boolean auditing;
    private java.util.concurrent.atomic.AtomicBoolean subtitleJob;
    private String subtitleProgress="";

    private androidx.media3.ui.PlayerView viewer;
    private FrameLayout previewCanvas;
    private Dialog fullscreenDialog;
    private SeekBar fullscreenSeek;
    private TextView fullscreenClock, fullscreenPlay;
    private boolean fullscreenScrubbing;
    private ExoPlayer player, audioPlayer;
    private volatile boolean alive;
    private boolean scrubbing,saving,resumed,saveQueued,closeAfterSave;
    private int selected,previewGeneration,revision;
    private String importKind="video",exportCopyPath="";
    private final Runnable ticker=new Runnable(){public void run(){
        if(!alive||!resumed)return;
        if(player!=null&&!scrubbing){long pos=timelinePosition();timeline.position(pos);updateCaption();if(!renderedMode&&player.isPlaying()&&audioPlayer!=null&&audioPlayer.getMediaItemCount()>0&&Math.abs(audioTimelinePosition()-pos)>350)seekAudio(pos);String value=time(pos)+" / "+time(total());if(!clock.getText().toString().equals(value))clock.setText(value);String icon=player.isPlaying()?"Ⅱ":"▶";if(!play.getText().toString().equals(icon))play.setText(icon);}
        if(fullscreenDialog!=null&&fullscreenDialog.isShowing()&&player!=null){
            if(fullscreenClock!=null)fullscreenClock.setText(time(timelinePosition())+" / "+time(total()));
            if(fullscreenPlay!=null)fullscreenPlay.setText(player.isPlaying()?"Ⅱ":"▶");
            if(fullscreenSeek!=null&&!fullscreenScrubbing)fullscreenSeek.setProgress((int)Math.min(Integer.MAX_VALUE,timelinePosition()));
        }
        long now=android.os.SystemClock.elapsedRealtime();if(!saving&&!importing&&project!=null&&now-lastProjectCheck>2500){lastProjectCheck=now;engine.command(VideoEditorActivity.this,"video_editor_project_read",new JSONObject(),(ok,value)->ui.post(()->{if(!alive||saving||importing||scrubbing||!ok)return;try{JSONObject fresh=new JSONObject(value);if(fresh.optInt("revision")!=revision){project=fresh;revision=fresh.optInt("revision");undo.clear();redo.clear();draw();preview();}}catch(Exception ignored){}}));}
        if(renderPreview!=null&&renderPreview.busy()){status.setVisibility(View.VISIBLE);status.setText("Aperçu rendu · "+renderPreview.progress()+" % · toucher pour annuler");ui.postDelayed(this,400);return;}
        JSONObject s=engine.status();String state=s.optString("state");boolean currentOutput=project!=null&&project.optString("output").equals(s.optString("output"));
        if(!state.equals(lastExportState)){lastExportState=state;exportStateAt=android.os.SystemClock.elapsedRealtime();}
        status.setVisibility(subtitleJob!=null||importing||state.equals("running")||state.equals("preparing")||(currentOutput&&(state.equals("failed")||state.equals("completed"))&&android.os.SystemClock.elapsedRealtime()-exportStateAt<6000)?View.VISIBLE:View.GONE);
        status.setText(subtitleJob!=null?subtitleProgress:importing?"Importation des médias…":"running".equals(state)?"Export MP4 · "+s.optInt("progress_percent")+" %":"preparing".equals(state)?"Préparation du rendu…":"completed".equals(state)?"Export terminé · toucher pour lire / partager":"failed".equals(state)?"Export impossible : "+s.optString("error"):"Montage local · sauvegarde automatique");
        ui.postDelayed(this,player!=null&&player.isPlaying()?33:600);
    }};
    @Override public void onCreate(Bundle state){super.onCreate(state);alive=true;if(state!=null){exportCopyPath=state.getString("export_copy_path","");editSeek=state.getLong("studio_position",0);selected=state.getInt("studio_selected",0);selectedAudio=state.getInt("studio_audio",-1);toolPage=state.getString("studio_tool_page","Accueil");restoredZoom=state.getFloat("studio_zoom",1);}
        getWindow().setStatusBarColor(0xff111111);getWindow().setNavigationBarColor(0xff111111);
        compact=getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density<720;
        LinearLayout root=MobileUi.column(this);root.setBackgroundColor(0xff111214);setContentView(root);
        LinearLayout header=new LinearLayout(this);header.setPadding(dp(6),0,dp(8),0);header.setGravity(Gravity.CENTER_VERTICAL);root.addView(header,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView close=chip("×");close.setTextSize(26);close.setContentDescription("Fermer le montage");header.addView(close,new LinearLayout.LayoutParams(dp(42),-1));close.setOnClickListener(v->onBackPressed());
        title=MobileUi.text(this,"Studio ▾",13,MobileUi.TEXT);title.setPadding(dp(3),0,dp(3),0);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));title.setOnClickListener(v->projectMenu());
        quality=chip("720p ▾");quality.setTextSize(12);quality.setBackground(MobileUi.bg(this,0xff292b30));header.addView(quality,new LinearLayout.LayoutParams(dp(64),dp(40)));quality.setOnClickListener(v->qualityMenu());
        TextView export=chip("Exporter");export.setTag("export_project");export.setTextSize(13);export.setTextColor(0xff061a18);export.setTypeface(null,android.graphics.Typeface.BOLD);export.setBackground(MobileUi.bg(this,0xff20d4d3));LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(dp(84),dp(40));ep.leftMargin=dp(6);header.addView(export,ep);export.setOnClickListener(v->export());
        FrameLayout canvas=new FrameLayout(this);previewCanvas=canvas;canvas.setBackgroundColor(0xff000000);root.addView(canvas,new LinearLayout.LayoutParams(-1,0,1));viewer=(androidx.media3.ui.PlayerView)getLayoutInflater().inflate(R.layout.studio_player,canvas,false);canvas.addView(viewer,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        canvas.addOnLayoutChangeListener((v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->applyPreviewFrame());
        TextView empty=MobileUi.text(this,"＋\nAjouter une vidéo",17,MobileUi.MUTED);empty.setGravity(Gravity.CENTER);empty.setTag("empty");canvas.addView(empty,new FrameLayout.LayoutParams(-1,-1));empty.setOnClickListener(v->addMenu("video"));
        previewHint=MobileUi.text(this,"Chargement de l’aperçu…",13,0xffdde2ec);
        previewHint.setGravity(Gravity.CENTER);previewHint.setBackgroundColor(0x99000000);
        canvas.addView(previewHint,new FrameLayout.LayoutParams(-1,-1));
        previewHint.setVisibility(View.GONE);
        previewCaption=MobileUi.text(this,"",18,0xffffffff);previewCaption.setGravity(Gravity.CENTER);
        previewCaption.setShadowLayer(dp(2),0,dp(2),0xff000000);
        FrameLayout.LayoutParams cap=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);cap.bottomMargin=dp(20);
        viewer.addView(previewCaption,cap);
        TextView fullscreenButton=chip("⛶");
        fullscreenButton.setTag("studio_fullscreen");
        fullscreenButton.setTextSize(25);
        fullscreenButton.setContentDescription("Aperçu plein écran");
        fullscreenButton.setBackground(MobileUi.bg(this,0x99000000));
        FrameLayout.LayoutParams fullButtonLayout=new FrameLayout.LayoutParams(dp(52),dp(52),Gravity.RIGHT|Gravity.TOP);
        fullButtonLayout.setMargins(0,dp(6),dp(8),0);
        canvas.addView(fullscreenButton,fullButtonLayout);
        fullscreenButton.setOnClickListener(v->openFullscreen());
        renderPreview=new StudioRenderPreview(this);
        renderBadge=MobileUi.text(this,"Source · Aperçu rendu pour voir les effets",11,0xffe1efff);renderBadge.setMaxLines(2);renderBadge.setPadding(dp(7),dp(5),dp(7),dp(5));renderBadge.setBackgroundColor(0xbb15202d);renderBadge.setTag("studio_render_preview");
        FrameLayout.LayoutParams badgeLayout=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.LEFT);badgeLayout.setMargins(dp(6),dp(6),dp(70),0);canvas.addView(renderBadge,badgeLayout);renderBadge.setOnClickListener(v->{if(renderedMode)preview();else renderedPreview();});
        player=new ExoPlayer.Builder(this).build();
        // Keep ExoPlayer's direct decoder output while seeking on multi-clip projects.
        // GPU filters are applied by Transformer on export; hot-swapping live GL
        // effects can stall hardware decoders during fast clip boundary seeks.
        audioPlayer=new ExoPlayer.Builder(this).build();
        audioPlayer.addListener(new Player.Listener(){@Override public void onMediaItemTransition(MediaItem item,int reason){configureAudio();}});
        viewer.setPlayer(player);
        player.addListener(new Player.Listener(){
            @Override public void onRenderedFirstFrame(){previewReady=true;previewHint.setVisibility(View.GONE);}
            @Override public void onMediaItemTransition(MediaItem item,int reason){configureCurrentClip();}
            @Override public void onPlaybackStateChanged(int state){
                if(state==Player.STATE_READY&&clip()!=null&&StudioImages.isImage(new java.io.File(clip().optString("path"))))previewReady=true;
                if(state==Player.STATE_READY && previewReady)previewHint.setVisibility(View.GONE);
                if(state==Player.STATE_READY&&!renderedMode&&preparedEditorAction!=null){Runnable action=preparedEditorAction;preparedEditorAction=null;ui.post(()->{if(alive&&!saving&&!importing)action.run();});}
                if(state==Player.STATE_ENDED && audioPlayer!=null)audioPlayer.pause();
            }
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error){
                previewHint.setText("Vidéo non lisible sur cet appareil : "+error.getErrorCodeName()+"\nVérifie le codec ou réimporte ce plan.");
                previewHint.setVisibility(View.VISIBLE);preparedEditorAction=null;pausePreview();
            }
        });
        LinearLayout playback=new LinearLayout(this);playback.setPadding(dp(8),0,dp(4),0);playback.setGravity(Gravity.CENTER_VERTICAL);root.addView(playback,new LinearLayout.LayoutParams(-1,dp(42)));
        clock=MobileUi.text(this,"00:00 / 00:00",11,0xffc0c3cc);clock.setPadding(dp(4),0,0,0);playback.addView(clock,new LinearLayout.LayoutParams(0,-2,1));
        play=chip("▶");playback.addView(play,new LinearLayout.LayoutParams(dp(48),-1));play.setOnClickListener(v->{if(player.isPlaying())pausePreview();else playPreview();});
        format=chip("9:16");format.setTextSize(11);format.setContentDescription("Format du montage");playback.addView(format,new LinearLayout.LayoutParams(dp(44),dp(38)));format.setOnClickListener(v->ratioMenu());
        TextView un=chip("↶");un.setContentDescription("Annuler la dernière modification");playback.addView(un,new LinearLayout.LayoutParams(dp(40),-1));un.setOnClickListener(v->history(false));
        TextView re=chip("↷");re.setContentDescription("Rétablir la modification");playback.addView(re,new LinearLayout.LayoutParams(dp(40),-1));re.setOnClickListener(v->history(true));
        timeline=new StudioTimeline(this);root.addView(timeline,new LinearLayout.LayoutParams(-1,dp(192)));
        timeline.zoomBy(restoredZoom);
        timeline.setListener(new StudioTimeline.Listener(){
            public void move(int from,int to){if(saving||importing)return;try{remember();JSONArray items=clips();Object item=items.remove(from);JSONArray out=new JSONArray();for(int i=0;i<=items.length();i++){if(i==to)out.put(item);if(i<items.length())out.put(items.get(i));}project.put("clips",out);selected=to;editSeek=start(to);save();}catch(Exception ex){toast(ex.getMessage());}}
            public void seek(long position,int index){boolean changed=selected!=index||selectedAudio>=0||toolPage.equals("Accueil");selected=index;if(selectedAudio>=0||toolPage.equals("Accueil"))toolPage="Éditer";selectedAudio=-1;pausePreview();seekPreview(position);timeline.audioSelection(-1);if(changed)updateSelectionTools();clock.setText(time(position)+" / "+time(total()));}
            public void scrubbing(boolean active){scrubbing=active;if(active)pausePreview();}
            public void trim(int index,JSONObject value){if(!editable())return;try{remember();clips().put(index,value);selected=index;save();}catch(Exception e){toast(e.getMessage());}}
            public void audio(int index){selectedAudio=index;pausePreview();toolPage="Audio";updateSelectionTools();}
            public void subtitle(int index){pausePreview();toolPage="Texte";updateSelectionTools();editSubtitle(index);}
            public void text(int index){selected=index;selectedAudio=-1;pausePreview();seekPreview(start(index));toolPage="Texte";updateSelectionTools();caption();}
            public void add(String kind){addMenu(kind);}
            public void layer(int index){toolPage="Calques";updateSelectionTools();if(index>=0)layerSettings(index);else layersMenu();}
        });
        LinearLayout summary=new LinearLayout(this);summary.setGravity(Gravity.CENTER_VERTICAL);root.addView(summary,new LinearLayout.LayoutParams(-1,dp(34)));
        scope=MobileUi.text(this,"Projet complet",12,0xffaeb4c1);scope.setPadding(dp(10),0,0,0);summary.addView(scope,new LinearLayout.LayoutParams(0,-2,1));
        TextView minus=chip("−");minus.setTag("studio_zoom_out");minus.setContentDescription("Dézoomer la timeline");summary.addView(minus,new LinearLayout.LayoutParams(dp(44),-1));minus.setOnClickListener(v->timeline.zoomBy(.75f));
        TextView plus=chip("+");plus.setTag("studio_zoom_in");plus.setContentDescription("Zoomer sur la timeline");summary.addView(plus,new LinearLayout.LayoutParams(dp(44),-1));plus.setOnClickListener(v->timeline.zoomBy(1.35f));
        TextView add=chip("＋ Média");add.setTag("studio_add_media");add.setTextSize(12);summary.addView(add,new LinearLayout.LayoutParams(dp(88),-1));add.setOnClickListener(v->addMenu("video"));
        selectionLabel=scope;selectionLabel.setTag("studio_selection");selectionLabel.setSingleLine(true);selectionLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        HorizontalScrollView strip=new HorizontalScrollView(this);contextStrip=strip;strip.setHorizontalScrollBarEnabled(false);root.addView(strip,new LinearLayout.LayoutParams(-1,dp(62)));tools=new LinearLayout(this);strip.addView(tools);
        HorizontalScrollView navigation=new HorizontalScrollView(this);mainNavigation=navigation;navigation.setHorizontalScrollBarEnabled(false);root.addView(navigation,new LinearLayout.LayoutParams(-1,dp(62)));
        mainTools=new LinearLayout(this);navigation.addView(mainTools);
        mainTool(mainTools,"Éditer","scissors",()->showToolPage("Éditer"));mainTool(mainTools,"Audio","audio",()->showToolPage("Audio"));mainTool(mainTools,"Texte","text",()->showToolPage("Texte"));mainTool(mainTools,"Effets","effects",()->showToolPage("Effets"));mainTool(mainTools,"Calques","layers",()->showToolPage("Calques"));mainTool(mainTools,"Format","crop",()->ratioMenu());mainTool(mainTools,"Exports","queue",()->exportQueue());mainTool(mainTools,"Analyse","layers",()->showToolPage("Analyse"));
        updateSelectionTools();
        status=MobileUi.text(this,"Montage local",11,MobileUi.MUTED);status.setPadding(dp(10),dp(2),dp(10),dp(2));status.setMaxLines(2);FrameLayout.LayoutParams statusLayout=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);canvas.addView(status,statusLayout);status.setBackgroundColor(0xdd15202d);status.setOnClickListener(v->{if(renderPreview!=null&&renderPreview.busy()){renderPreview.cancel();previewHint.setVisibility(View.GONE);status.setVisibility(View.GONE);}else if(subtitleJob!=null){subtitleJob.set(true);subtitleJob=null;toast("Analyse des sous-titres arrêtée.");}else outputMenu();});scope.setOnClickListener(v->{if(selectedAudio>=0)trim(true,selectedAudio);else editMenu();});
        engine.command(this,"video_editor_project_read",new JSONObject(),(ok,res)->runOnUiThread(()->{try{if(ok){project=new JSONObject(res);revision=project.optInt("revision",0);draw();preview();String launch=getIntent().getStringExtra("add_path");String jsonPath=getIntent().getStringExtra("project_path");if(jsonPath!=null)addFile("project",jsonPath);else if(launch!=null)addFile("video",launch);}else toast(res);}catch(Exception e){toast(e.getMessage());}}));ui.post(ticker);
    }
    private int dp(int n){return MobileUi.dp(this,n);}
    private TextView chip(String text){TextView v=MobileUi.text(this,text,15,MobileUi.TEXT);v.setPadding(dp(6),0,dp(6),0);v.setMinHeight(dp(48));v.setMinWidth(dp(48));v.setGravity(Gravity.CENTER);v.setFocusable(true);return v;}
    private void mainTool(LinearLayout parent,String label,String icon,Runnable action){TextView view=UiIcons.button(this,label,icon,0xfff4f4ff);view.setTag("studio_page_"+label);parent.addView(view,new LinearLayout.LayoutParams(dp(76),-1));view.setOnClickListener(v->action.run());}
    private void editMenu(){if(!editable())return;new AgentDialog.Builder(this).setTitle("Éditer le plan "+(selected+1)).setItems(new String[]{"Découper avec les curseurs","Diviser au curseur","Vitesse","Rotation","Son du plan"},(d,n)->{switch(n){case 0:trim(false,selected);break;case 1:split();break;case 2:speed();break;case 3:rotation();break;case 4:mute();break;}}).show();}
    private void audioMenu(){new AgentDialog.Builder(this).setTitle("Audio").setItems(new String[]{"Ajouter un audio","Son original","Modifier les pistes","Transcrire les paroles · IA locale"},(d,n)->{if(n==0)addMenu("audio");else if(n==1)mute();else if(n==3)transcribeAudio();else{String[] names=new String[audio().length()];for(int i=0;i<names.length;i++)names[i]=name(audio().optJSONObject(i).optString("path"));new AgentDialog.Builder(this).setTitle("Pistes audio").setItems(names,(x,i)->new AgentDialog.Builder(this).setItems(new String[]{"Découper","Supprimer"},(y,j)->{if(j==0)trim(true,i);else delete(true,i);}).show()).show();}}).show();}
    /** Vosk inference runs on-device. The model may require one initial installation. */
    private void transcribeAudio(){
        if(audio().length()==0){toast("Ajoute d'abord une piste audio");return;}
        String path=audio().optJSONObject(0).optString("path");
        if(path.isEmpty())return;
        new AgentDialog.Builder(this).setTitle("Paroles · transcription locale")
            .setMessage("Reconnaissance Vosk en français, exécutée sur le téléphone. "
                +"Le modèle doit être installé localement au préalable (téléchargement initial possible). "
                +"Le rap peut produire des mots incorrects : vérifie les paroles. "
                +"Aucune piste audio n'est envoyée à un serveur.")
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Analyser",(d,w)->{
                toast("Transcription locale en cours…");
                MediaTranscriber.IO.execute(()->{
                    try{
                        JSONObject result=MediaTranscriber.transcribe(this,path,"fr",180);
                        ui.post(()->{if(!alive)return;
                            String text=result.optString("text","");
                            new AgentDialog.Builder(this).setTitle("Paroles reconnues · à vérifier")
                                .setMessage(text.isEmpty()?"Aucune parole intelligible reconnue.":
                                    text+"\n\nRésultat automatique, non synchronisé syllabe par syllabe.")
                                .setPositiveButton("Fermer",null).show();
                        });
                    }catch(Exception e){ui.post(()->toast("Transcription locale : "+e.getMessage()));}
                });
            }).show();
    }

    private void generateSubtitles(){
        if(subtitleJob!=null){toast("Une analyse des sous-titres est déjà en cours.");return;}
        if(project==null||clips().length()==0||saving||importing){toast("Ouvre un projet avec une vidéo et attends sa sauvegarde.");return;}
        final String snapshot=project.toString();final int base=revision;final String id=project.optString("project_id");
        String replace=StudioSubtitles.items(project).length()>0?" Les sous-titres actuels seront remplacés si l'analyse réussit.":"";
        new AgentDialog.Builder(this).setTitle("Sous-titres automatiques · STT")
            .setMessage("La parole des pistes audio est analysée sur ton téléphone. En l'absence de piste séparée, Studio utilise le son des vidéos. Les temps reconnus sont placés sur la timeline."+replace+"\n\nLe rap et la musique peuvent entraîner des erreurs : relis et corrige les textes et les temps. Le modèle français peut nécessiter un premier téléchargement.")
            .setNegativeButton("Annuler",null).setPositiveButton("Générer",(d,n)->{
                java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
                subtitleJob=cancelled;subtitleProgress="Préparation des sous-titres · toucher pour arrêter";
                status.setVisibility(View.VISIBLE);status.setText(subtitleProgress);
                AgentClient.ResultCallback active=new AgentClient.ResultCallback(){public boolean isActive(){return !cancelled.get()&&alive;}public void finish(boolean ok,String text){}};
                MediaTranscriber.IO.execute(()->{try{
                    JSONArray cues=StudioSubtitles.generate(getApplicationContext(),new JSONObject(snapshot),"fr",active,(at,total)->ui.post(()->{if(alive&&!cancelled.get()&&subtitleJob==cancelled){subtitleProgress="Sous-titres · piste "+at+" / "+total+" · toucher pour arrêter";status.setText(subtitleProgress);}}));
                    ui.post(()->{if(!alive||subtitleJob!=cancelled)return;subtitleJob=null;if(cancelled.get())return;
                        if(project==null||saving||importing||revision!=base||!id.equals(project.optString("project_id"))||!snapshot.equals(project.toString())){toast("Le projet a changé pendant l'analyse : aucun sous-titre ajouté.");return;}
                        try{remember();project.put("subtitles",cues);toolPage="Texte";save();toast("Reconnaissance terminée : enregistrement des sous-titres…");}catch(Exception e){toast(e.getMessage());}
                    });
                }catch(Exception e){ui.post(()->{if(alive&&subtitleJob==cancelled){subtitleJob=null;if(!cancelled.get())toast("Sous-titres : "+e.getMessage());}});}});
            }).show();
    }
    private void editSubtitles(){
        if(project==null||saving||importing)return;JSONArray cues=StudioSubtitles.items(project);
        if(cues.length()==0){toast("Génère d'abord des sous-titres");return;}
        String[] names=new String[cues.length()];for(int i=0;i<cues.length();i++){JSONObject c=cues.optJSONObject(i);names[i]=time(c.optLong("start_ms"))+" · "+c.optString("text");}
        new AgentDialog.Builder(this).setTitle("Modifier un sous-titre").setItems(names,(d,index)->editSubtitle(index)).show();
    }
    private void editSubtitle(int index){
        if(project==null||saving||importing)return;JSONObject c=StudioSubtitles.items(project).optJSONObject(index);if(c==null)return;final int base=revision;final String snapshot=project.toString();
        LinearLayout box=MobileUi.column(this);box.setPadding(dp(16),dp(8),dp(16),dp(8));
        EditText text=new EditText(this),start=new EditText(this),end=new EditText(this);text.setText(c.optString("text"));text.setHint("Texte");start.setHint("Début en secondes");end.setHint("Fin en secondes");
        start.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);end.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        start.setText(String.format(Locale.ROOT,"%.3f",c.optLong("start_ms")/1000.0));end.setText(String.format(Locale.ROOT,"%.3f",c.optLong("end_ms")/1000.0));box.addView(text);box.addView(start);box.addView(end);
        AlertDialog dialog=new AgentDialog.Builder(this).setTitle("Sous-titre "+(index+1)).setView(box).setNegativeButton("Annuler",null).setNeutralButton("Supprimer",null).setPositiveButton("Enregistrer",null).create();
        dialog.setOnShowListener(ignored->{
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{try{
                if(revision!=base||!snapshot.equals(project.toString()))throw new Exception("Projet modifié : rouvre le sous-titre.");
                long a=Math.round(Double.parseDouble(start.getText().toString().replace(',','.'))*1000),b=Math.round(Double.parseDouble(end.getText().toString().replace(',','.'))*1000);
                if(b>total())throw new Exception("Le sous-titre dépasse la durée du montage.");
                JSONObject copy=new JSONObject(project.toString());copy.getJSONArray("subtitles").getJSONObject(index).put("text",text.getText().toString().trim()).put("start_ms",a).put("end_ms",b);
                StudioSubtitles.validate(copy,StudioEdits.total(copy));remember();project=copy;save();dialog.dismiss();
            }catch(Exception e){toast(e.getMessage()==null?"Temps ou texte invalide":e.getMessage());}});
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v->{try{
                if(revision!=base||!snapshot.equals(project.toString()))throw new Exception("Projet modifié : rouvre le sous-titre.");remember();project.getJSONArray("subtitles").remove(index);save();dialog.dismiss();
            }catch(Exception e){toast(e.getMessage());}});
        });dialog.show();
    }

    private void clipMenu(){if(!editable())return;new AgentDialog.Builder(this).setTitle("Plans du montage").setItems(new String[]{"Ajouter des vidéos ou photos","Dupliquer le plan","Déplacer le plan","Supprimer le plan"},(d,n)->{switch(n){case 0:addMenu("video");break;case 1:duplicate();break;case 2:move();break;case 3:delete(false,selected);break;}}).show();}
    private void showToolPage(String page){toolPage=page;updateSelectionTools();}
    private void updateSelectionTools(){
        if(tools==null||selectionLabel==null)return;
        if(selectedAudio>=audio().length())selectedAudio=-1;
        String name=selectedAudio>=0?"Audio "+(selectedAudio+1)+" · "+name(audio().optJSONObject(selectedAudio).optString("path")):
            clip()==null?"Ajoute une vidéo ou une photo":"Plan "+(selected+1)+" / "+clips().length()+" · "+name(clip().optString("path"))+" · "+String.format(Locale.FRANCE,"%.2f s",length(clip())/1000.0);
        selectionLabel.setText(name);tools.removeAllViews();
        contextStrip.setVisibility(toolPage.equals("Accueil")?View.GONE:View.VISIBLE);
        mainNavigation.setVisibility(toolPage.equals("Accueil")?View.VISIBLE:View.GONE);
        TextView back=chip("‹\nOutils");back.setTextSize(12);back.setTag("studio_tools_back");tools.addView(back,new LinearLayout.LayoutParams(dp(52),-1));back.setOnClickListener(v->showToolPage("Accueil"));
        if(mainTools!=null)for(int i=0;i<mainTools.getChildCount();i++){
            View v=mainTools.getChildAt(i);v.setBackground(MobileUi.bg(this,("studio_page_"+toolPage).equals(v.getTag())?0xff29433f:0xff111214));
        }
        if(toolPage.equals("Audio")){
            tool("＋","Ajouter",()->{parallelImport=false;addMenu("audio");});tool("♫","Piste parallèle",()->{parallelImport=true;addMenu("audio");});tool("☷","Mixeur",()->{pausePreview();StudioAudioPanel.show(this,()->lastProjectCheck=0);});tool("◉","Volume",()->volume());
            tool("✂","Découper",()->{if(selectedAudio>=0)trim(true,selectedAudio);else mute();});
            tool("♫","Son original",()->mute());tool("T","Transcrire",()->transcribeAudio());
            if(selectedAudio>=0)tool("×","Supprimer",()->delete(true,selectedAudio));
        }else if(toolPage.equals("Calques")){
            tool("＋","Superposer",()->addMenu("layer"));tool("▣","Calques",()->layersMenu());tool("▶","Aperçu rendu",()->renderedPreview());
        }else if(toolPage.equals("Texte")){
            tool("T","Texte du plan",()->caption());tool("♫","Sous-titres auto",()->generateSubtitles());tool("✎","Modifier sous-titres",()->editSubtitles());tool("×","Retirer",()->{if(clip()!=null)put(clip(),"text","");});
        }else if(toolPage.equals("Effets")){
            tool("▶","Aperçu rendu",()->renderedPreview());tool("✦","Filtres",()->filter());tool("◉","Ajuster",()->gradeManual());tool("↗","Animation",()->photoAnimation());tool("↻","Rotation",()->rotation());
        }else if(toolPage.equals("Analyse")){
            tool("◎","Audit local",()->offlineAudit());tool("◉","Étalonnage",()->gradeMenu());tool("▣","File exports",()->exportQueue());
        }else{
            tool("✂","Diviser",()->split());tool("◷","Durée",()->trim(false,selected));tool("▷","Vitesse",()->speed());
            if(clip()!=null)tool("▣","Recadrer",()->cropEditor());
            tool("◉","Volume",()->{selectedAudio=-1;volume();});tool("▣","Dupliquer",()->duplicate());tool("×","Supprimer",()->delete(false,selected));
            tool("⇄","Déplacer",()->move());tool("◁","−1 image",()->stepFrame(-1));tool("▷","+1 image",()->stepFrame(1));
        }
    }
    private void tool(String glyph,String label,Runnable action){
        String icon=label.equals("Diviser")||label.equals("Découper")?"scissors":label.equals("Vitesse")||label.equals("Durée")?"speed":label.equals("Volume")||label.equals("Son original")?"audio":label.equals("Dupliquer")||label.equals("Calques")?"layers":label.equals("Supprimer")||label.equals("Retirer")?"trash":label.equals("Ajuster")||label.equals("Étalonnage")?"sliders":label.contains("Texte")||label.equals("Transcrire")?"text":label.equals("Ajouter")||label.equals("Superposer")?"plus":label.equals("Aperçu rendu")?"play":label.equals("Animation")?"animation":label.equals("Rotation")?"rotation":label.equals("Déplacer")?"move":label.contains("image")?"play":label.contains("exports")?"download":label.contains("Audit")?"search":"effects";
        TextView v=UiIcons.button(this,label,icon,0xffedf5ff);v.setTag("studio_tool_"+label);v.setSingleLine(true);v.setEllipsize(android.text.TextUtils.TruncateAt.END);v.setPadding(dp(4),dp(5),dp(4),dp(5));tools.addView(v,new LinearLayout.LayoutParams(dp(82),-1));v.setOnClickListener(w->{if(project!=null&&!saving&&!importing){if(renderedMode&&!label.equals("Aperçu rendu")){preparedEditorAction=action;preview();}else action.run();}});
    }
    private void toast(String text){if(alive)Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private JSONArray clips(){return project==null?new JSONArray():project.optJSONArray("clips");}
    private JSONArray audio(){if(project==null)return new JSONArray();JSONArray a=project.optJSONArray("audio");return a==null?new JSONArray():a;}
    private JSONObject clip(){return clips().optJSONObject(selected);}
    private long length(JSONObject c){return Math.round(c.optLong("duration_ms",10000)/c.optDouble("speed",1));}
    private long total(){long sum=0;for(int i=0;i<clips().length();i++)sum+=length(clips().optJSONObject(i));return sum;}
    private long start(int index){long sum=0;for(int i=0;i<index;i++)sum+=length(clips().optJSONObject(i));return sum;}
    private String time(long ms){return String.format(Locale.ROOT,"%02d:%02d.%01d",ms/60000,(ms/1000)%60,(ms%1000)/100);}
    private String name(String path){return path.substring(path.lastIndexOf('/')+1);}
    private boolean editable(){if(project==null||saving||importing){toast("Sauvegarde en cours…");return false;}String s=engine.status().optString("state");if(s.equals("running")||s.equals("preparing")){toast("Attends la fin de l’export ou annule-le");return false;}if(clip()==null){toast("Ajoute puis sélectionne une vidéo");return false;}return true;}
    private void remember(){undo.addLast(project.toString());while(undo.size()>30)undo.removeFirst();redo.clear();}
    private void draw(){if(!alive||project==null)return;applyPreviewFrame();title.setText(project.optString("name","Studio")+" ▾");format.setText(project.optString("aspect_ratio","source").replace("source","Original"));quality.setText(project.optInt("resolution",720)+"p ▾");scope.setText("✓ Projet complet · "+time(total()));selected=Math.max(0,Math.min(selected,clips().length()-1));timeline.getLayoutParams().height=dp(StudioLayers.items(project).length()>0?232:192);timeline.requestLayout();timeline.setProject(project,selected);if(timeline.getLayoutParams()!=null){timeline.getLayoutParams().height=dp(StudioAudio.enabled(project)?270:192);timeline.requestLayout();}timeline.audioSelection(selectedAudio);updateSelectionTools();clock.setText(time(timelinePosition())+" / "+time(total()));previewCanvas.findViewWithTag("empty").setVisibility(clips().length()==0?View.VISIBLE:View.GONE);}
    private void save(){
        if(project==null)return;
        if(saving){saveQueued=true;return;}
        String fallback=undo.peekLast();JSONObject snapshot;
        try{snapshot=new JSONObject(project.toString());}catch(Exception e){toast("Projet illisible");return;}
        try{StudioEdits.conform(project);snapshot=new JSONObject(project.toString());}catch(Exception e){toast(e.getMessage());return;}
        saving=true;saveQueued=false;
        engine.command(this,"video_editor_project_save",new JSONObjectSafe("project",snapshot).withRevision(revision),(ok,res)->ui.post(()->{
            saving=false;if(!alive)return;
            if(ok){
                try{revision=new JSONObject(res).getJSONObject("project").optInt("revision");project.put("revision",revision);}catch(Exception ignored){}
                if(saveQueued){save();return;}
                draw();if(resumed)preview();
                if(closeAfterSave)finish();
            }else{
                saveQueued=false;closeAfterSave=false;toast(res);
                try{if(fallback!=null)project=new JSONObject(fallback);}catch(Exception ignored){}
                draw();
            }
        }));
    }
    /** Fit a true output-aspect canvas inside the available phone screen area. */
    private void applyPreviewFrame(){
        if(viewer==null||project==null||!(viewer.getParent() instanceof FrameLayout))return;
        FrameLayout canvas=(FrameLayout)viewer.getParent();
        int availableWidth=canvas.getWidth(),availableHeight=canvas.getHeight();
        float ratio=0;
        try{ratio=VideoEditorEngine.ratio(project.optString("aspect_ratio","source"));}
        catch(Exception ignored){}
        int targetWidth=-1,targetHeight=-1;
        if(ratio>0&&availableWidth>0&&availableHeight>0){
            targetWidth=Math.min(availableWidth,Math.max(1,Math.round(availableHeight*ratio)));
            targetHeight=Math.min(availableHeight,Math.max(1,Math.round(targetWidth/ratio)));
        }
        FrameLayout.LayoutParams current=(FrameLayout.LayoutParams)viewer.getLayoutParams();
        if(current.width!=targetWidth||current.height!=targetHeight||current.gravity!=Gravity.CENTER){
            viewer.setLayoutParams(new FrameLayout.LayoutParams(targetWidth,targetHeight,Gravity.CENTER));
        }
        viewer.setResizeMode(ratio>0&&"crop".equals(project.optString("aspect_mode","fit"))
            ?AspectRatioFrameLayout.RESIZE_MODE_ZOOM:AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }
    /** Fullscreen reuses the SAME ExoPlayer and PlayerView. No second decoder, no lost position. */
    private void openFullscreen(){
        if(fullscreenDialog!=null&&fullscreenDialog.isShowing())return;
        if(viewer==null||player==null||project==null)return;
        pausePreview();
        if(viewer.getParent() instanceof ViewGroup)((ViewGroup)viewer.getParent()).removeView(viewer);
        Dialog dialog=AgentDialog.watch(new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen));
        fullscreenDialog=dialog;
        LinearLayout screen=new LinearLayout(this);screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(android.graphics.Color.BLACK);
        FrameLayout stage=new FrameLayout(this);
        screen.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        stage.addView(viewer,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        stage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->applyPreviewFrame());
        TextView close=chip("×  Quitter le plein écran");close.setTag("studio_fullscreen_close");
        close.setContentDescription("Quitter le plein écran");
        close.setTextSize(15);close.setBackground(MobileUi.bg(this,0x99000000));
        FrameLayout.LayoutParams closePosition=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.LEFT);
        closePosition.setMargins(dp(10),dp(8),0,0);
        stage.addView(close,closePosition);
        close.setOnClickListener(v->dialog.dismiss());
        LinearLayout controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(12),dp(8),dp(12),dp(16));
        controls.setBackgroundColor(0xff15161a);
        screen.addView(controls,new LinearLayout.LayoutParams(-1,dp(118)));
        fullscreenSeek=new SeekBar(this);fullscreenSeek.setTag("studio_fullscreen_seek");
        fullscreenSeek.setMax((int)Math.min(Integer.MAX_VALUE,total()));
        fullscreenSeek.setProgress((int)timelinePosition());
        controls.addView(fullscreenSeek,new LinearLayout.LayoutParams(-1,dp(48)));
        fullscreenSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar b){fullscreenScrubbing=true;pausePreview();}
            @Override public void onProgressChanged(SeekBar b,int progress,boolean fromUser){
                if(fromUser){seekPreview(progress);if(fullscreenClock!=null)fullscreenClock.setText(time(progress)+" / "+time(total()));}
            }
            @Override public void onStopTrackingTouch(SeekBar b){fullscreenScrubbing=false;}
        });
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(actions,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView prev=chip("⏮");prev.setContentDescription("Plan précédent");actions.addView(prev,new LinearLayout.LayoutParams(dp(54),-1));
        prev.setOnClickListener(v->{pausePreview();seekPreview(start(Math.max(0,selected-1)));});
        fullscreenPlay=chip("▶");fullscreenPlay.setTag("studio_fullscreen_play");
        fullscreenPlay.setContentDescription("Lire ou mettre en pause");actions.addView(fullscreenPlay,new LinearLayout.LayoutParams(dp(54),-1));
        fullscreenPlay.setOnClickListener(v->{if(player.isPlaying())pausePreview();else playPreview();});
        TextView next=chip("⏭");next.setContentDescription("Plan suivant");actions.addView(next,new LinearLayout.LayoutParams(dp(54),-1));
        next.setOnClickListener(v->{pausePreview();seekPreview(start(Math.min(clips().length()-1,selected+1)));});
        fullscreenClock=MobileUi.text(this,time(timelinePosition())+" / "+time(total()),13,0xffffffff);
        fullscreenClock.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        actions.addView(fullscreenClock,new LinearLayout.LayoutParams(0,-1,1));
        dialog.setContentView(screen);
        dialog.setOnDismissListener(d->{
            if(viewer.getParent() instanceof ViewGroup)((ViewGroup)viewer.getParent()).removeView(viewer);
            previewCanvas.addView(viewer,0,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
            fullscreenDialog=null;fullscreenSeek=null;fullscreenClock=null;fullscreenPlay=null;fullscreenScrubbing=false;
            applyPreviewFrame();
        });
        dialog.show();
        Window window=dialog.getWindow();
        if(window!=null){
            window.setLayout(-1,-1);
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        applyPreviewFrame();
    }
    /** Reliable source-frame preview. The native export remains the final authority for effects. */
    private void preview(){
        invalidateRenderedPreview();
        if(clips().length()==0){player.stop();player.clearMediaItems();audioPlayer.stop();audioPlayer.clearMediaItems();previewHint.setVisibility(View.GONE);return;}
        int generation=++previewGeneration;long position=editSeek==null?timelinePosition():editSeek;editSeek=null;boolean playing=player.isPlaying();
        String snapshot=project.toString();previewReady=false;previewHint.setText("Chargement des vidéos…");previewHint.setVisibility(View.VISIBLE);
        WorkspaceCommands.IO.execute(()->{
            try{
                JSONObject p=new JSONObject(snapshot);WorkspaceStore store=new WorkspaceStore(this);
                JSONArray video=p.getJSONArray("clips"),audio=p.optJSONArray("audio");
                ArrayList<MediaItem> videos=new ArrayList<>(),sounds=new ArrayList<>();
                for(int i=0;i<video.length();i++){
                    JSONObject c=video.getJSONObject(i);videos.add(mediaForPreview(store,c));String path=c.getString("path");
                    if(!sourceLengths.containsKey(path))sourceLengths.put(path,StudioImages.isImage(store.file(path))?60000L:MediaInspection.info(this,path).optLong("duration_ms"));
                }
                if(audio!=null)for(int i=0;i<audio.length();i++)sounds.add(mediaForPreview(store,audio.getJSONObject(i)));
                ui.post(()->{
                    if(!alive||generation!=previewGeneration)return;
                    try{
                        pausePreview();
                        applyPreviewFrame();
                        for(String path:sourceLengths.keySet())timeline.sourceDuration(path,sourceLengths.get(path));
                        player.setMediaItems(videos);player.prepare();
                        audioPlayer.setMediaItems(sounds);
                        if(!sounds.isEmpty())audioPlayer.prepare();
                        seekPreview(Math.min(position,Math.max(0,total()-1)));
                        if(playing)playPreview();
                    }catch(Exception e){previewHint.setText("Aperçu : "+e.getMessage());previewHint.setVisibility(View.VISIBLE);}
                });
            }catch(Exception e){ui.post(()->{if(alive&&generation==previewGeneration){
                previewHint.setText("Impossible de charger les médias : "+e.getMessage());
                previewHint.setVisibility(View.VISIBLE);}});}
        });
    }
    private MediaItem mediaForPreview(WorkspaceStore store,JSONObject c)throws Exception{
        java.io.File file=store.file(c.getString("path"));
        if(!file.isFile()||file.length()==0)throw new java.io.IOException("Fichier absent : "+c.getString("path"));
        if(StudioImages.isImage(file))return StudioImages.media(file,c.optLong("duration_ms",5000));
        long begin=c.optLong("start_ms",0),end=begin+c.optLong("duration_ms",10000);
        return new MediaItem.Builder().setUri(android.net.Uri.fromFile(file))
            .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(begin).setEndPositionMs(end).build()).build();
    }
    private long timelinePosition(){
        if(renderedMode&&player!=null)return Math.max(0,Math.min(total(),player.getCurrentPosition()));
        if(player==null||project==null||clips().length()==0)return 0;
        int index=Math.max(0,Math.min(player.getCurrentMediaItemIndex(),clips().length()-1));
        JSONObject c=clips().optJSONObject(index);double speed=auditionSpeed!=null?auditionSpeed:c==null?1:c.optDouble("speed",1);
        return Math.max(0,Math.min(total(),start(index)+Math.round(player.getCurrentPosition()/Math.max(.25,speed))));
    }
    private int indexFor(long absolute,JSONArray items,boolean video){
        long offset=0;
        for(int i=0;i<items.length();i++){
            JSONObject c=items.optJSONObject(i);
            long length=video?length(c):c.optLong("duration_ms",10000);
            if(absolute<offset+length||i==items.length()-1)return i;
            offset+=length;
        }
        return 0;
    }
    private void seekPreview(long absolute){
        if(renderedMode){absolute=Math.max(0,Math.min(Math.max(0,total()-1),absolute));selected=indexFor(absolute,clips(),true);player.seekTo(absolute);timeline.position(absolute);return;}
        if(player==null||project==null||clips().length()==0)return;
        absolute=Math.max(0,Math.min(Math.max(0,total()-1),absolute));
        int index=indexFor(absolute,clips(),true);selected=index;
        JSONObject c=clips().optJSONObject(index);double speed=c.optDouble("speed",1);
        long local=Math.max(0,Math.round((absolute-start(index))*speed));
        player.setPlaybackSpeed((float)speed);
        player.seekTo(index,local);
        timeline.position(absolute);updateCaption();configureCurrentClip();
        seekAudio(absolute);
    }
    private long audioTimelinePosition(){
        if(audioPlayer==null||audio().length()==0||audioPlayer.getMediaItemCount()==0)return 0;
        int index=Math.max(0,Math.min(audioPlayer.getCurrentMediaItemIndex(),audio().length()-1));
        long at=0;for(int i=0;i<index;i++)at+=audio().optJSONObject(i).optLong("duration_ms",0);
        return at+audioPlayer.getCurrentPosition();
    }
    private void seekAudio(long position){
        if(audioPlayer==null||audio().length()==0||audioPlayer.getMediaItemCount()==0)return;
        long sum=0;int i=indexFor(position,audio(),false);
        for(int j=0;j<i;j++)sum+=audio().optJSONObject(j).optLong("duration_ms",0);
        if(position>=sum+audio().optJSONObject(i).optLong("duration_ms")){audioPlayer.pause();return;}
        audioPlayer.seekTo(i,Math.max(0,position-sum));configureAudio();
    }
    private void configureCurrentClip(){
        if(renderedMode)return;
        if(player==null||project==null||clips().length()==0)return;
        int i=Math.max(0,Math.min(player.getCurrentMediaItemIndex(),clips().length()-1));
        JSONObject c=clips().optJSONObject(i);
        if(c==null)return;
        player.setPlaybackSpeed((float)c.optDouble("speed",1));
        player.setVolume(project.optBoolean("mute_original",false)||c.optBoolean("mute",false)?0:(float)c.optDouble("volume",1));
        boolean changed=selected!=i;selected=i;updateCaption();if(changed&&selectedAudio<0)updateSelectionTools();
    }
    private void updateCaption(){
        if(renderedMode){previewCaption.setText("");return;}
        if(previewCaption==null)return;
        JSONObject c=clip();String sub=project==null?"":StudioSubtitles.at(project,timelinePosition());
        String value=!sub.isEmpty()?sub:c==null?"":c.optString("text","");if(!previewCaption.getText().toString().equals(value))previewCaption.setText(value);
    }
    private void pausePreview(){if(player!=null)player.pause();if(audioPlayer!=null)audioPlayer.pause();}
    private void playPreview(){
        if(project!=null&&StudioAudio.enabled(project)&&!renderedMode){if(!renderPreview.busy())renderedPreview();return;}
        if(player==null||player.getMediaItemCount()==0)return;
        if(player.getPlaybackState()==Player.STATE_ENDED)seekPreview(0);
        if(!renderedMode&&audioPlayer!=null&&audioPlayer.getMediaItemCount()>0){seekAudio(timelinePosition());long audioEnd=0;for(int i=0;i<audio().length();i++)audioEnd+=audio().optJSONObject(i).optLong("duration_ms");if(timelinePosition()<audioEnd)audioPlayer.play();}
        player.play();
    }
    private void history(boolean forward){if(saving||importing||project==null)return;try{engine.command(this,"video_editor_history",new JSONObject().put("redo",forward).put("expected_revision",revision),(ok,value)->ui.post(()->{if(!alive)return;try{if(!ok){toast(value);return;}project=new JSONObject(value).getJSONObject("project");revision=project.getInt("revision");draw();preview();}catch(Exception e){toast(e.getMessage());}}));}catch(Exception e){toast(e.getMessage());}}

    private void put(JSONObject object,String key,Object value){try{remember();object.put(key,value);save();}catch(Exception e){toast(e.getMessage());}}
    private void split(){if(!editable())return;JSONObject c=clip();long cut=Math.round((timelinePosition()-start(selected))*c.optDouble("speed",1)),duration=c.optLong("duration_ms");if(cut<(StudioImages.isImage(new java.io.File(c.optString("path")))?1000:500)||duration-cut<(StudioImages.isImage(new java.io.File(c.optString("path")))?1000:500)){toast("Place le curseur au milieu du plan (minimum 0,5 s par partie)");return;}
        try{remember();JSONObject second=new JSONObject(c.toString());second.remove("studio_id");second.put("start_ms",StudioImages.isImage(new java.io.File(c.optString("path")))?0:c.optLong("start_ms")+cut).put("duration_ms",duration-cut);c.put("duration_ms",cut);JSONArray out=new JSONArray();for(int i=0;i<clips().length();i++){out.put(clips().get(i));if(i==selected)out.put(second);}project.put("clips",out);save();}catch(Exception e){toast(e.getMessage());}}
    private void trim(boolean sound,int index){JSONObject c=(sound?audio():clips()).optJSONObject(index);if(c==null||saving||importing)return;String path=c.optString("path");if(!sound&&StudioImages.isImage(new java.io.File(path))){EditText seconds=new EditText(this);seconds.setInputType(2);seconds.setText(String.valueOf(c.optLong("duration_ms",5000)/1000));new AgentDialog.Builder(this).setTitle("Durée photo · 1 à 60 secondes").setView(seconds).setPositiveButton("Appliquer",(d,n)->{try{long value=Long.parseLong(seconds.getText().toString());if(value<1||value>60)throw new Exception("Durée de 1 à 60 secondes requise");remember();c.put("start_ms",0).put("duration_ms",value*1000);save();}catch(Exception e){toast(e.getMessage());}}).setNegativeButton("Annuler",null).show();return;}WorkspaceCommands.IO.execute(()->{try{long sourceDuration=MediaInspection.info(this,path).optLong("duration_ms");ui.post(()->{if(!alive)return;LinearLayout box=MobileUi.column(this);box.setPadding(dp(20),dp(10),dp(20),dp(12));TextView bounds=MobileUi.text(this,"",14,MobileUi.TEXT);box.addView(bounds);SeekBar begin=new SeekBar(this),end=new SeekBar(this);begin.setMax((int)sourceDuration);end.setMax((int)sourceDuration);begin.setProgress((int)c.optLong("start_ms"));end.setProgress((int)(c.optLong("start_ms")+c.optLong("duration_ms")));box.addView(MobileUi.text(this,"Début",12,MobileUi.MUTED));box.addView(begin);box.addView(MobileUi.text(this,"Fin",12,MobileUi.MUTED));box.addView(end);Runnable update=()->bounds.setText(time(begin.getProgress())+" → "+time(end.getProgress()));SeekBar.OnSeekBarChangeListener listener=new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int progress,boolean user){update.run();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}};begin.setOnSeekBarChangeListener(listener);end.setOnSeekBarChangeListener(listener);update.run();
            AlertDialog dialog=new AgentDialog.Builder(this).setTitle("Découper · "+name(path)).setView(box).setNegativeButton("Annuler",null).setNeutralButton("Tout le média",(d,n)->{remember();try{c.put("start_ms",0).put("duration_ms",sourceDuration);save();}catch(Exception e){toast(e.getMessage());}}).setPositiveButton("Appliquer",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{if(end.getProgress()-begin.getProgress()<500){toast("Garde au moins 0,5 seconde");return;}try{remember();c.put("start_ms",begin.getProgress()).put("duration_ms",end.getProgress()-begin.getProgress());save();dialog.dismiss();}catch(Exception e){toast(e.getMessage());}}));dialog.show();
        });}catch(Exception e){ui.post(()->toast(e.getMessage()));}});}
    /** All heavy calculations stay on the device's disk executor. */
    private void offlineAudit(){
        if(auditing||project==null)return;
        auditing=true;
        toast("Audit local : audio, luminosité, doublons et sources…");
        engine.command(this,"video_editor_offline_audit",new JSONObject(),(ok,value)->ui.post(()->{
            auditing=false;
            if(!alive)return;
            if(!ok){toast("Analyse impossible : "+value);return;}
            try{
                JSONObject data=new JSONObject(value),beat=data.optJSONObject("audio")==null
                    ?null:data.getJSONObject("audio").optJSONObject("beat");
                StringBuilder summary=new StringBuilder();
                summary.append("Analyse sur téléphone uniquement. Aucune API.\n\n");
                summary.append(data.optInt("clip_count")).append(" segments · ")
                    .append(data.optInt("frames_sampled")).append(" images examinées\n");
                summary.append("Fichiers réellement identiques : ")
                    .append(data.optInt("identical_separate_files")).append("\n");
                summary.append("Réutilisations intentionnelles de source : ")
                    .append(data.optInt("source_reuses")).append("\n");
                if(beat!=null&&beat.optBoolean("available"))
                    summary.append("Tempo indicatif : ").append(beat.optInt("bpm_estimated"))
                        .append(" BPM (confiance indicative : ")
                        .append(beat.optDouble("confidence_indicator")).append(")\n");
                else if(beat!=null)summary.append("BPM : ")
                    .append(beat.optString("reason","indisponible")).append("\n");
                JSONArray alerts=data.optJSONArray("warnings");
                summary.append("\nPoints à vérifier :\n");
                if(alerts==null||alerts.length()==0)summary.append("Aucune alerte détectée.\n");
                else for(int i=0;i<Math.min(alerts.length(),14);i++)
                    summary.append("• ").append(alerts.optString(i)).append("\n");
                summary.append("\nL'audit ne modifie pas le montage et ne garantit pas le lip-sync.");
                new AgentDialog.Builder(this).setTitle("CHK Réalisateur · Audit local")
                    .setMessage(summary.toString()).setPositiveButton("Terminer",null).show();
            }catch(Exception e){toast("Résultat d'audit illisible : "+e.getMessage());}
        }));
    }

    /** Snap frame steps to the actual project cadence without accumulated rounding drift. */
    private void stepFrame(int direction){
        if(project==null||clips().length()==0)return;
        pausePreview();
        long target=StudioEdits.framePosition(timelinePosition(),direction,project.optInt("fps",30),total());
        seekPreview(target);
        clock.setText(time(target)+" / "+time(total()));
    }

    private void gradeMenu(){
        if(clip()==null)return;
        new AgentDialog.Builder(this).setTitle("Étalonnage local · GPU")
            .setItems(new String[]{
                "Réglage manuel (exposition, couleur, saturation)",
                "Lumière progressive (keyframes)",
                "Refroidir le plan",
                "Réchauffer le plan",
                "Réinitialiser l'étalonnage"
            },(dialog,index)->{
                if(index==0)gradeManual();
                else try{
                    remember();
                    JSONObject c=clip();
                    if(index==1){
                        c.put("grade_exposure",-.12).put("grade_exposure_end",.35)
                          .put("grade_warmth",-.10).put("grade_warmth_end",.20)
                          .put("grade_saturation",.92).put("grade_saturation_end",1.08);
                    }else if(index==2){
                        c.put("grade_exposure",.08).put("grade_warmth",-.22)
                          .put("grade_saturation",.90);
                        c.remove("grade_exposure_end");c.remove("grade_warmth_end");
                        c.remove("grade_saturation_end");
                    }else if(index==3){
                        c.put("grade_exposure",.08).put("grade_warmth",.22)
                          .put("grade_saturation",1.02);
                        c.remove("grade_exposure_end");c.remove("grade_warmth_end");
                        c.remove("grade_saturation_end");
                    }else for(String key:new String[]{
                        "grade_exposure","grade_exposure_end","grade_warmth",
                        "grade_warmth_end","grade_saturation","grade_saturation_end"})c.remove(key);
                    save();
                }catch(Exception e){toast(e.getMessage());}
            }).show();
    }
    private void gradeManual(){
        if(clip()==null)return;
        LinearLayout layout=MobileUi.column(this);
        layout.setPadding(dp(18),dp(16),dp(18),dp(12));
        TextView info=MobileUi.text(this,"Réglages GPU appliqués au MP4 exporté. L'aperçu reste brut.",
            12,MobileUi.MUTED);layout.addView(info);
        SeekBar exposure=new SeekBar(this),warmth=new SeekBar(this),sat=new SeekBar(this);
        exposure.setMax(100);warmth.setMax(100);sat.setMax(200);
        exposure.setProgress((int)Math.round((clip().optDouble("grade_exposure",0)+.5)*100));
        warmth.setProgress((int)Math.round((clip().optDouble("grade_warmth",0)+.5)*100));
        sat.setProgress((int)Math.round(clip().optDouble("grade_saturation",1)*100));
        layout.addView(MobileUi.text(this,"Exposition (-0,5 à +0,5 EV)",13,MobileUi.TEXT));
        layout.addView(exposure);
        layout.addView(MobileUi.text(this,"Température froide ↔ chaude",13,MobileUi.TEXT));
        layout.addView(warmth);
        layout.addView(MobileUi.text(this,"Saturation (0 % à 200 %)",13,MobileUi.TEXT));
        layout.addView(sat);
        new AgentDialog.Builder(this).setTitle("Étalonnage du plan "+(selected+1))
            .setView(layout).setNegativeButton("Annuler",null)
            .setPositiveButton("Appliquer",(d,w)->{
                try{
                    remember();
                    JSONObject c=clip();
                    c.put("grade_exposure",(exposure.getProgress()-50)/100.0);
                    c.put("grade_warmth",(warmth.getProgress()-50)/100.0);
                    c.put("grade_saturation",sat.getProgress()/100.0);
                    c.remove("grade_exposure_end");c.remove("grade_warmth_end");
                    c.remove("grade_saturation_end");
                    save();
                }catch(Exception e){toast(e.getMessage());}
            }).show();
    }

    private void filter(){
        if(!editable())return;
        String[] labels={"Original","Noir et blanc","Cinéma","Chaud","Froid",
            "Contraste","Nuit","Vintage","Vibrant (couleurs +)","Cinéma doux",
            "Désaturé","Sépia","Fondu noir 0,18 s","Fondu noir 0,35 s","Retirer le fondu"};
        String[] keys={"aucun","noir","cinema","chaud","froid","contraste",
            "nuit","vintage","vibrant","doux","desature","sepia"};
        new AgentDialog.Builder(this).setTitle("Filtres · GPU Media3")
            .setItems(labels,(d,n)->{
                if(n<keys.length)put(clip(),"filter",keys[n]);
                else put(clip(),"fade_ms",n==12?180:n==13?350:0);
            }).show();
    }
    private void caption(){if(!editable())return;prompt("Texte du plan",clip().optString("text",""),text->put(clip(),"text",text));}
    private void speed(){
        if(!editable())return;pausePreview();JSONObject target=clip();int base=revision;
        LinearLayout box=MobileUi.column(this);TextView readout=MobileUi.text(this,"",16,MobileUi.TEXT);box.addView(readout);
        SeekBar slider=new SeekBar(this);slider.setTag("studio_speed_slider");slider.setMax(375);slider.setProgress((int)Math.round(target.optDouble("speed",1)*100)-25);box.addView(slider);
        Runnable update=()->{double speed=(slider.getProgress()+25)/100.0;auditionSpeed=(float)speed;readout.setText(String.format(Locale.FRANCE,"%.2f× · %.2f s → %.2f s",speed,length(target)/1000.0,target.optLong("duration_ms")/speed/1000));player.setPlaybackSpeed((float)speed);};
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int n,boolean user){update.run();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        LinearLayout presets=new LinearLayout(this);box.addView(presets);
        for(double value:new double[]{.25,.5,1,2,4}){TextView item=chip(value+"×");presets.addView(item,new LinearLayout.LayoutParams(0,dp(44),1));item.setOnClickListener(v->slider.setProgress((int)(value*100)-25));}
        TextView listen=chip("▶ Écouter / pause");box.addView(listen);listen.setOnClickListener(v->{if(player.isPlaying())pausePreview();else{playPreview();update.run();}});
        update.run();StudioSheet.show(this,"Vitesse du plan",box,()->{pausePreview();auditionSpeed=null;if(revision!=base||saving){configureCurrentClip();toast("Projet modifié : rouvre le réglage");return;}put(target,"speed",(slider.getProgress()+25)/100.0);},()->{pausePreview();auditionSpeed=null;configureCurrentClip();});
    }
    private void volume(){
        if(project==null||saving||importing)return;if(StudioAudio.enabled(project)){pausePreview();StudioAudioPanel.show(this,()->lastProjectCheck=0);return;}JSONObject target=selectedAudio>=0?audio().optJSONObject(selectedAudio):clip();if(target==null)return;
        boolean sound=selectedAudio>=0;int base=revision;LinearLayout box=MobileUi.column(this);TextView value=MobileUi.text(this,"",16,MobileUi.TEXT);box.addView(value);
        SeekBar slider=new SeekBar(this);slider.setTag("studio_volume_slider");slider.setMax(200);slider.setProgress(!sound&&(project.optBoolean("mute_original",false)||target.optBoolean("mute",false))?0:(int)Math.round(target.optDouble("volume",1)*100));box.addView(slider);
        Runnable update=()->{double gain=slider.getProgress()/100.0;value.setText("Volume · "+slider.getProgress()+" % · "+(gain<=0?"−∞ dB":String.format(java.util.Locale.FRANCE,"%+.1f dB",20*Math.log10(gain))));if(sound)audioPlayer.setVolume(slider.getProgress()/100f);else player.setVolume(slider.getProgress()/100f);};
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int n,boolean user){update.run();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        TextView listen=chip("▶ Écouter / pause");box.addView(listen);listen.setOnClickListener(v->{if(player.isPlaying())pausePreview();else{playPreview();update.run();}});
        update.run();StudioSheet.show(this,sound?"Volume de la piste audio":"Volume du plan",box,()->{pausePreview();if(revision!=base||saving){configureCurrentClip();configureAudio();toast("Projet modifié : rouvre le réglage");return;}try{remember();if(slider.getProgress()>100)StudioAudio.enable(project);if(sound)target.put("volume",slider.getProgress()/100.0);else StudioEdits.clipVolume(project,target,slider.getProgress()/100.0);save();}catch(Exception e){toast(e.getMessage());}},()->{pausePreview();configureCurrentClip();configureAudio();});
    }
    private void configureAudio(){if(audioPlayer==null||audio().length()==0)return;JSONObject c=audio().optJSONObject(Math.min(audio().length()-1,Math.max(0,audioPlayer.getCurrentMediaItemIndex())));if(c!=null)audioPlayer.setVolume((float)c.optDouble("volume",1));}
    /** Cropping the selected clip does not alter its source, duration or other tracks. */
    private void cropEditor(){
        if(!editable()||clip()==null)return;
        pausePreview();
        final int base=revision;
        final JSONObject selectedClip=clip();
        final JSONObject currentCrop;
        try{currentCrop=StudioCrop.read(selectedClip);}
        catch(Exception e){toast(e.getMessage());return;}
        LinearLayout box=MobileUi.column(this);
        StudioCropView editor=new StudioCropView(this);
        box.addView(editor,new LinearLayout.LayoutParams(-1,dp(255)));
        try{
            java.io.File input=new WorkspaceStore(this).file(selectedClip.getString("path"));
            android.graphics.Bitmap frame=null;
            if(StudioImages.isImage(input))frame=android.graphics.BitmapFactory.decodeFile(input.getAbsolutePath());
            else{
                android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever();
                try{retriever.setDataSource(input.getAbsolutePath());
                    frame=retriever.getFrameAtTime(selectedClip.optLong("start_ms")*1000);
                }finally{retriever.release();}
            }
            if(frame!=null){
                if(frame.getWidth()>960||frame.getHeight()>960){
                    float shrink=Math.min(960f/frame.getWidth(),960f/frame.getHeight());
                    frame=android.graphics.Bitmap.createScaledBitmap(frame,Math.max(1,Math.round(frame.getWidth()*shrink)),Math.max(1,Math.round(frame.getHeight()*shrink)),true);
                }
                editor.source(frame);
            }
            editor.setCrop(currentCrop.getDouble("x"),currentCrop.getDouble("y"),
                currentCrop.getDouble("width"),currentCrop.getDouble("height"));
        }catch(Exception e){toast("Miniature inaccessible : "+e.getMessage());}
        LinearLayout ratios=new LinearLayout(this);
        box.addView(ratios);
        for(String r:new String[]{"Libre","9:16","16:9","1:1","4:5"}){
            TextView btn=chip(r);btn.setTextSize(11);
            ratios.addView(btn,new LinearLayout.LayoutParams(0,dp(44),1));
            btn.setOnClickListener(v->{
                float aspect=0;
                if(!r.equals("Libre")){String[] parts=r.split(":");aspect=Float.parseFloat(parts[0])/Float.parseFloat(parts[1]);}
                editor.setRatio(aspect);
            });
        }
        box.addView(MobileUi.text(this,"Rotation précise · degrés",14,MobileUi.TEXT));
        EditText readout=new EditText(this);readout.setText(String.valueOf(currentCrop.optDouble("degrees",0)));readout.setTextColor(MobileUi.TEXT);readout.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);box.addView(readout);
        SeekBar rotation=new SeekBar(this);rotation.setMax(360);
        rotation.setProgress(180+(int)Math.round(currentCrop.optDouble("degrees",0)));
        box.addView(rotation);
        rotation.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int n,boolean user){if(user)readout.setText(String.valueOf(n-180));}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
        TextView quarter=chip("↺ −90°     /     +90° ↻");box.addView(quarter);quarter.setOnClickListener(v->{try{double angle=Double.parseDouble(readout.getText().toString().replace(',','.'))+90;while(angle>180)angle-=360;readout.setText(String.valueOf(angle));rotation.setProgress((int)Math.round(angle)+180);}catch(Exception e){toast("Angle invalide");}});
        android.widget.CheckBox horizontal=new android.widget.CheckBox(this);
        horizontal.setText("Inverser horizontalement");horizontal.setChecked(currentCrop.optBoolean("flip_horizontal"));
        box.addView(horizontal);
        android.widget.CheckBox vertical=new android.widget.CheckBox(this);
        vertical.setText("Inverser verticalement");vertical.setChecked(currentCrop.optBoolean("flip_vertical"));
        box.addView(vertical);
        TextView reset=chip("Réinitialiser le cadre");box.addView(reset);
        reset.setOnClickListener(v->{editor.setRatio(0);editor.setCrop(0,0,1,1);rotation.setProgress(180);readout.setText("0");horizontal.setChecked(false);vertical.setChecked(false);});
        TextView realPreview=chip("▶ Voir le rendu réel");box.addView(realPreview);
        ImageView rendered=new ImageView(this);rendered.setAdjustViewBounds(true);rendered.setVisibility(View.GONE);box.addView(rendered,new LinearLayout.LayoutParams(-1,dp(210)));
        StudioRenderPreview cropPreview=new StudioRenderPreview(this);
        realPreview.setOnClickListener(v->{try{
            JSONObject snapshot=new JSONObject(project.toString());snapshot.getJSONArray("clips").getJSONObject(selected).put("crop",new JSONObject().put("x",editor.left()).put("y",editor.top()).put("width",editor.widthNorm()).put("height",editor.heightNorm()).put("degrees",Double.parseDouble(readout.getText().toString().replace(',','.'))).put("flip_horizontal",horizontal.isChecked()).put("flip_vertical",vertical.isChecked()));
            realPreview.setText("Rendu en cours…");long at=start(selected)+length(selectedClip)/2;
            cropPreview.start(snapshot,new StudioRenderPreview.Result(){public void failed(String e){realPreview.setText("▶ Réessayer le rendu");toast(e);}public void ready(java.io.File file){VideoEditorEngine.DISK.execute(()->{try{android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever();android.graphics.Bitmap image;try{retriever.setDataSource(file.getAbsolutePath());image=retriever.getFrameAtTime(at*1000,android.media.MediaMetadataRetriever.OPTION_CLOSEST);}finally{retriever.release();}ui.post(()->{if(!alive||!realPreview.isAttachedToWindow()){if(image!=null)image.recycle();return;}rendered.setImageBitmap(image);rendered.setVisibility(View.VISIBLE);realPreview.setText("Rendu réel · 480p · recalculer après modification");});}catch(Exception e){ui.post(()->toast(e.getMessage()));}});}});
        }catch(Exception e){toast(e.getMessage());}});
        StudioSheet.show(this,"Recadrer le plan "+(selected+1)+" · poignées aux 4 coins",box,()->{
            if(base!=revision||saving){toast("Projet modifié : rouvrir Recadrer");return;}
            try{
                JSONObject crop=new JSONObject().put("x",editor.left()).put("y",editor.top())
                    .put("width",editor.widthNorm()).put("height",editor.heightNorm())
                    .put("degrees",Double.parseDouble(readout.getText().toString().replace(',','.')))
                    .put("flip_horizontal",horizontal.isChecked()).put("flip_vertical",vertical.isChecked());
                StudioCrop.validate(crop);
                cropPreview.cancel();remember();selectedClip.put("crop",crop);save();
            }catch(Exception e){toast("Recadrage non sauvegardé : "+e.getMessage());}
        },cropPreview::cancel);
    }
    private void rotation(){if(!editable())return;new AgentDialog.Builder(this).setTitle("Rotation du plan").setItems(new String[]{"0°","90°","180°","−90°"},(d,n)->put(clip(),"rotation",new int[]{0,90,180,-90}[n])).show();}
    private void mute(){if(project==null)return;new AgentDialog.Builder(this).setTitle("Son").setItems(new String[]{"Conserver le son des vidéos","Couper le son des vidéos","Couper ce plan","Rétablir ce plan"},(d,n)->{if(n==0){try{remember();project.put("mute_original",false);for(int i=0;i<clips().length();i++)clips().getJSONObject(i).put("mute",false);save();}catch(Exception e){toast(e.getMessage());}}else if(n==1)put(project,"mute_original",true);else if(clip()!=null)put(clip(),"mute",n==2);}).show();}
    private void move(){new AgentDialog.Builder(this).setTitle("Déplacer le plan").setItems(new String[]{"Vers la gauche","Vers la droite"},(d,n)->{int to=selected+(n==0?-1:1);if(to<0||to>=clips().length())return;try{remember();Object a=clips().get(selected),b=clips().get(to);clips().put(selected,b);clips().put(to,a);selected=to;editSeek=start(to);save();}catch(Exception ignored){}}).show();}
    private void duplicate(){if(!editable())return;try{remember();StudioEdits.duplicate(project,selected);selected++;editSeek=start(selected);save();}catch(Exception e){toast(e.getMessage());}}
    private void delete(boolean sound,int index){if(project==null||saving)return;try{remember();JSONArray src=sound?audio():clips(),out=new JSONArray();for(int i=0;i<src.length();i++)if(i!=index)out.put(src.get(i));project.put(sound?"audio":"clips",out);save();}catch(Exception e){toast(e.getMessage());}}
    private void ratioMenu(){if(project==null)return;String[] ratios={"source","9:16","16:9","1:1","4:3","3:4","4:5","2:1","2.35:1","custom"};new AgentDialog.Builder(this).setTitle("Format du montage").setItems(new String[]{"Original","9:16 · Téléphone","16:9 · Paysage","1:1 · Carré","4:3","3:4","4:5","2:1","2,35:1 · Cinéma","Ratio personnalisé"},(d,n)->{if(n==9)prompt("Largeur:hauteur","9:16",r->setRatio(r));else setRatio(ratios[n]);}).show();}
    private void setRatio(String ratio){new AgentDialog.Builder(this).setTitle("Adapter l’image").setItems(new String[]{"Tout conserver (bandes noires)","Remplir (recadrage centré)"},(d,n)->{try{remember();project.put("aspect_ratio",ratio).put("aspect_mode",n==0?"fit":"crop");save();}catch(Exception ignored){}}).show();}
    private void photoAnimation(){if(!editable())return;new AgentDialog.Builder(this).setTitle("Mouvement du plan").setItems(new String[]{"Fixe","Zoom lent","Dézoom lent","Panoramique gauche → droite"},(d,n)->{try{remember();JSONObject c=clip();c.put("zoom",n==2?1.18:1).put("zoom_end",n==1?1.18:n==3?1.18:1).put("pan_x",n==3?-.08:0).put("pan_x_end",n==3?.08:0);save();}catch(Exception ex){toast(ex.getMessage());}}).show();}
    private void exportQueue(){if(saving||importing||project==null)return;engine.command(this,"video_editor_queue_status",new JSONObject(),(ok,value)->ui.post(()->{if(!alive)return;try{if(!ok){toast(value);return;}JSONObject q=new JSONObject(value);JSONArray jobs=q.getJSONArray("jobs");StringBuilder body=new StringBuilder();for(int i=0;i<jobs.length();i++){JSONObject j=jobs.getJSONObject(i);body.append(j.optString("name")).append(" · ").append(j.optString("state")).append("\n");}if(jobs.length()==0)body.append("Aucun export en attente");new AgentDialog.Builder(this).setTitle("File des exports").setMessage(body.toString()).setPositiveButton("Actualiser",(d,n)->exportQueue()).setNegativeButton(q.optBoolean("paused")?"Reprendre":"Pause",(d,n)->engine.command(this,q.optBoolean("paused")?"video_editor_queue_resume":"video_editor_queue_pause",new JSONObject(),(success,result)->{})).setNeutralButton("Ajouter ce projet",(d,n)->{try{engine.command(this,"video_editor_queue_add",new JSONObject().put("request_id",java.util.UUID.randomUUID().toString()).put("projects",new JSONArray().put(new JSONObject().put("project",new JSONObject(project.toString())))),(success,result)->ui.post(()->{if(!success)toast(result);else exportQueue();}));}catch(Exception ex){toast(ex.getMessage());}}).show();}catch(Exception ex){toast(ex.getMessage());}}));}
    private void qualityMenu(){if(project==null||saving)return;new AgentDialog.Builder(this).setTitle("Qualité vidéo").setItems(new String[]{"Résolution · "+project.optInt("resolution",720)+"p","Cadence · "+project.optInt("fps",30)+" images/s"},(d,choice)->{if(choice==0)new AgentDialog.Builder(this).setTitle("Résolution").setSingleChoiceItems(new String[]{"480p · Léger","720p · Standard","1080p · Haute définition"},project.optInt("resolution",720)==480?0:project.optInt("resolution",720)==720?1:2,(dialog,n)->{put(project,"resolution",new int[]{480,720,1080}[n]);dialog.dismiss();}).show();else new AgentDialog.Builder(this).setTitle("Images par seconde").setSingleChoiceItems(new String[]{"24 fps · Cinéma","30 fps · Standard","60 fps · Fluide, selon le téléphone"},project.optInt("fps",30)==24?0:project.optInt("fps",30)==30?1:2,(dialog,n)->{put(project,"fps",new int[]{24,30,60}[n]);dialog.dismiss();}).show();}).setNegativeButton("Fermer",null).show();}

    private void export(){
        if(renderPreview!=null&&renderPreview.busy()){toast("Termine ou annule l’aperçu rendu");return;}
        if(project==null||saving||importing){toast("Attends la fin de l’importation ou de la sauvegarde");return;}if(clips().length()==0){toast("Ajoute une vidéo avant d’exporter");return;}
        String snapshot=project.toString();WorkspaceCommands.IO.execute(()->{try{JSONObject p=new JSONObject(snapshot);boolean exists=new WorkspaceStore(this).file(p.getString("output")).exists();ui.post(()->{if(!alive)return;String message=clips().length()+" plans · "+time(total())+"\n"+project.optInt("resolution",720)+"p · "+project.optString("aspect_ratio","source").replace("source","Original")+"\n"+name(project.optString("output"));
            if(exists)new AgentDialog.Builder(this).setTitle("Exporter · "+time(total()))
                .setMessage(message+"\n\nUn MP4 existe déjà. Choisis l'action voulue.")
                .setItems(new String[]{"Lire le MP4 existant","Enregistrer sur le téléphone",
                    "Partager le MP4"},(d,n)->openExportFile(project.optString("output"),n))
                .setNeutralButton("Remplacer",(d,n)->startExport(true,false))
                .setPositiveButton("Nouvelle copie",(d,n)->startExport(false,true))
                .setNegativeButton("Annuler",null).show();
            else new AgentDialog.Builder(this).setTitle("Exporter le projet complet").setMessage(message).setNegativeButton("Annuler",null).setPositiveButton("Exporter",(d,n)->startExport(false,false)).show();
        });}catch(Exception e){ui.post(()->toast(e.getMessage()));}});
    }
    private void startExport(boolean replace,boolean copy){
        if(saving||importing)return;saving=true;pausePreview();
        try{JSONObject snapshot=new JSONObject(project.toString());if(copy){String path=snapshot.getString("output");snapshot.put("output",path.substring(0,path.length()-4)+"-"+System.currentTimeMillis()+".mp4");}
            engine.command(this,"video_editor_project_save",new JSONObjectSafe("project",snapshot).withRevision(revision),(ok,res)->ui.post(()->{saving=false;if(!alive)return;if(!ok){toast(res);return;}try{project=new JSONObject(res).getJSONObject("project");revision=project.optInt("revision");draw();engine.command(this,"video_editor_export",new JSONObject().put("replace",replace),(success,result)->ui.post(()->{if(!success&&alive)new AgentDialog.Builder(this).setTitle("Export impossible").setMessage(result).setPositiveButton("OK",null).show();}));}catch(Exception e){toast(e.getMessage());}}));
        }catch(Exception e){saving=false;toast(e.getMessage());}
    }

    /** Header Export and bottom finished export always share the same file actions. */
    private void openExportFile(String output,int action){
        try{
            WorkspaceStore store=new WorkspaceStore(this);
            java.io.File file=store.file(output);
            if(!file.isFile()||file.length()<1000){toast("Le MP4 n'existe plus dans Fichiers CHK");return;}
            if(action==0){
                startActivity(new Intent(this,MediaPreviewActivity.class).putExtra("path",output));
            }else if(action==1){
                exportCopyPath=output;
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("video/mp4")
                    .putExtra(Intent.EXTRA_TITLE,name(output)),811);
            }else if(action==2){
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                    .setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM,store.uri(output))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Partager le montage"));
            }
        }catch(Exception e){toast(e.getMessage());}
    }
    private void outputMenu(){
        String state=engine.status().optString("state");
        if(state.equals("running")||state.equals("preparing")){
            new AgentDialog.Builder(this).setTitle("Export en cours")
                .setMessage("Le rendu continue dans l'application. Tu peux quitter cet écran.")
                .setNegativeButton("Continuer",null)
                .setPositiveButton("Annuler l’export",(d,n)->
                    engine.command(this,"video_editor_cancel",new JSONObject(),(ok,res)->{})).show();
            return;
        }
        String output=state.equals("completed")?engine.status().optString("output")
            :project==null?"":project.optString("output");
        if(output.isEmpty())return;
        new AgentDialog.Builder(this).setTitle("Montage MP4")
            .setItems(new String[]{"Lire","Enregistrer sur le téléphone","Partager",
                "Vérifier la durée, les pistes et les images"},(d,n)->{
                if(n<3)openExportFile(output,n);
                else engine.command(this,"video_editor_verify_output",new JSONObject(),
                    (ok,res)->ui.post(()->{
                        if(!alive||!resumed)return;
                        if(!ok){toast(res);return;}
                        try{
                            JSONObject v=new JSONObject(res);
                            int count=v.optInt("verified_video_segments");
                            int unverified=v.optInt("unverified_video_segments");
                            new AgentDialog.Builder(this)
                                .setTitle(v.optBoolean("valid")?"MP4 contrôlé":"MP4 à vérifier")
                                .setMessage("Durée : "+time(v.optLong("duration_ms"))+
                                    "\nAttendue : "+time(v.optLong("expected_duration_ms"))+
                                    "\nImage : "+v.optString("width_px")+" × "+v.optString("height_px")+
                                    "\nAudio : "+(v.optBoolean("has_audio")?"présent":"absent")+
                                    "\nPlans vérifiés : "+count+
                                    (unverified>0?"\nPlans non vérifiés : "+unverified:"")+
                                    "\nPlans noirs : "+v.optJSONArray("black_video_segments"))
                                .setPositiveButton("OK",null).show();
                        }catch(Exception e){toast(e.getMessage());}
                    }));
            }).setNegativeButton("Fermer",null).show();
    }
    private interface TextResult{void done(String text);}
    private void prompt(String label,String initial,TextResult result){EditText e=new EditText(this);e.setText(initial);new AgentDialog.Builder(this).setTitle(label).setView(e).setNegativeButton("Annuler",null).setPositiveButton("Appliquer",(d,n)->result.done(e.getText().toString().trim())).show();}
    private void showProjects(){
        startActivity(new Intent(this,StudioProjectsActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
    private void replaceProjectFromCommand(boolean ok,String response){
        ui.post(()->{
            if(!alive||!resumed)return;
            if(!ok){toast(response);return;}
            try{
                JSONObject data=new JSONObject(response),fresh=data.optJSONObject("project");
                if(fresh==null)throw new IllegalArgumentException("Projet inaccessible");
                project=fresh;revision=project.optInt("revision",0);
                undo.clear();redo.clear();selected=0;
                draw();preview();
            }catch(Exception e){toast(e.getMessage());}
        });
    }
    private void projectMenu(){
        if(project==null)return;
        new AgentDialog.Builder(this).setTitle("Studio · Projets")
            .setItems(new String[]{"Tous mes projets","Renommer ce projet","Destination MP4",
                "Nouveau montage (conserver l'ancien)","Dupliquer ce projet",
                "Sauvegarder une copie JSON","Ouvrir un projet JSON","Charger Alpha Omega"},
                (d,n)->{
                    switch(n){
                        case 0:showProjects();break;
                        case 1:prompt("Nom",project.optString("name"),t->put(project,"name",t));break;
                        case 2:prompt("Destination dans Fichiers",project.optString("output"),t->put(project,"output",t));break;
                        case 3:engine.command(this,"video_editor_project_new",new JSONObject(),
                            this::replaceProjectFromCommand);break;
                        case 4:engine.command(this,"video_editor_project_duplicate",
                            new JSONObjectSafe("id",project.optString("project_id")),
                            this::replaceProjectFromCommand);break;
                        case 5:WorkspaceCommands.IO.execute(()->{
                            try{new WorkspaceStore(this).writeText("Projet-"+System.currentTimeMillis()+".json",project.toString(2),false);
                                ui.post(()->toast("Copie JSON enregistrée dans Fichiers"));
                            }catch(Exception e){ui.post(()->toast(e.getMessage()));}
                        });break;
                        case 6:chooseFolder("project","",0);break;
                        case 7:engine.command(this,"video_editor_preset_alpha_omega",new JSONObject(),
                            this::replaceProjectFromCommand);break;
                    }
                }).show();
    }
    private void addMenu(String kind){if(project==null||saving||importing)return;if(kind.equals("layer")){pickWorkspace(kind);return;}importKind=kind;new AgentDialog.Builder(this).setTitle("Ajouter "+(!kind.equals("audio")?"des vidéos ou photos":"un audio")).setItems(new String[]{"Depuis le téléphone","Depuis Fichiers CHK"},(d,n)->{if(n==0)startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(!kind.equals("audio")?"*/*":"audio/*").putExtra(Intent.EXTRA_MIME_TYPES,!kind.equals("audio")?new String[]{"video/*","image/png","image/jpeg","image/webp"}:new String[]{"audio/*"}).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),810);else pickWorkspace(kind);}).show();}
    private void invalidateRenderedPreview(){
        if(renderedMode&&player!=null&&editSeek==null)editSeek=player.getCurrentPosition();renderedMode=false;
        if(renderPreview!=null)renderPreview.cancel();if(renderBadge!=null)renderBadge.setText(StudioLayers.items(project==null?new JSONObject():project).length()>0?"Source sans calques · toucher pour l’aperçu rendu":"Source · toucher pour voir les effets rendus");
    }
    private void renderedPreview(){
        if(renderedMode){toast("Aperçu déjà rendu. Touche le bandeau pour revenir à la source.");return;}
        if(project==null||saving||importing||clips().length()==0)return;String state=engine.status().optString("state");if(state.equals("running")||state.equals("preparing")){toast("Attends la fin de l’export");return;}
        pausePreview();long at=timelinePosition();int requestedRevision=revision;previewHint.setText("Préparation de l’aperçu rendu sur Android…");previewHint.setVisibility(View.VISIBLE);
        renderPreview.start(project,new StudioRenderPreview.Result(){
            public void ready(java.io.File file){if(!alive||revision!=requestedRevision){renderPreview.cancel();return;}
                renderedMode=true;audioPlayer.stop();audioPlayer.clearMediaItems();previewCaption.setText("");player.setPlaybackSpeed(1);player.setVolume(1);player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)));player.prepare();player.seekTo(Math.min(at,Math.max(0,total()-1)));if(StudioAudio.enabled(project))player.play();renderBadge.setText("Aperçu rendu · toucher pour éditer la source");previewHint.setVisibility(View.GONE);status.setVisibility(View.GONE);
            }
            public void failed(String error){if(!alive)return;previewHint.setVisibility(View.GONE);status.setVisibility(View.GONE);toast("Aperçu rendu : "+error);}
        });
    }
    private void addLayers(List<String> paths){
        if(!editable())return;if(StudioLayers.items(project).length()+paths.size()>StudioLayers.MAX){toast("3 calques maximum");return;}
        importing=true;long at=Math.min(timelinePosition(),Math.max(0,total()-1000));String snapshot=project.toString();
        WorkspaceCommands.IO.execute(()->{try{JSONObject copy=new JSONObject(snapshot);JSONArray layers=StudioLayers.items(copy);
            for(String path:paths){JSONObject info=MediaInspection.info(this,path);long available=info.optBoolean("image")?5000:info.optLong("duration_ms");long duration=Math.min(available,StudioEdits.total(copy)-at);
                layers.put(new JSONObject().put("path",path).put("start_ms",0).put("offset_ms",at).put("duration_ms",duration).put("scale",.35).put("x",.5).put("y",-.5).put("opacity",1));}
            copy.put("layers",layers);engine.validate(this,copy);ui.post(()->{importing=false;if(!alive)return;remember();project=copy;toolPage="Calques";save();});
        }catch(Exception e){ui.post(()->{importing=false;toast("Calque : "+e.getMessage());});}});
    }
    private void layersMenu(){
        if(project==null||saving||importing)return;JSONArray layers=StudioLayers.items(project);if(layers.length()==0){addMenu("layer");return;}
        String[] names=new String[layers.length()];for(int i=0;i<names.length;i++)names[i]=(i+1)+" · "+name(layers.optJSONObject(i).optString("path"));
        new AgentDialog.Builder(this).setTitle("Calques · premier au-dessus").setItems(names,(d,index)->new AgentDialog.Builder(this).setTitle(names[index]).setItems(new String[]{"Position, taille et durée","Monter","Descendre","Supprimer ce calque"},(dialog,action)->{
            if(action==0){layerSettings(index);return;}if(saving)return;try{remember();JSONArray current=StudioLayers.items(project);
                if(action==3)current.remove(index);else{int other=index+(action==1?-1:1);if(other<0||other>=current.length())return;Object item=current.get(index);current.put(index,current.get(other));current.put(other,item);}project.put("layers",current);save();
            }catch(Exception e){toast(e.getMessage());}
        }).show()).setNeutralButton("Ajouter",(d,n)->addMenu("layer")).setNegativeButton("Fermer",null).show();
    }
    private SeekBar layerSlider(LinearLayout box,String label,int max,int value,String tag){box.addView(MobileUi.text(this,label,13,MobileUi.TEXT));SeekBar slider=new SeekBar(this);slider.setMax(max);slider.setProgress(value);slider.setTag(tag);box.addView(slider);return slider;}
    private void layerSettings(int index){
        if(project==null||saving||importing)return;JSONObject layer=StudioLayers.items(project).optJSONObject(index);if(layer==null)return;int expected=revision;
        WorkspaceCommands.IO.execute(()->{try{JSONObject info=MediaInspection.info(this,layer.getString("path"));ui.post(()->{if(!alive||revision!=expected)return;
            LinearLayout box=MobileUi.column(this);box.addView(MobileUi.text(this,"Voir le résultat avec Aperçu rendu. Le son du calque est coupé.",12,MobileUi.MUTED));
            SeekBar x=layerSlider(box,"Horizontal · gauche / droite",200,(int)((layer.optDouble("x")+1)*100),"studio_layer_x");
            SeekBar y=layerSlider(box,"Vertical · haut / bas",200,(int)((layer.optDouble("y")+1)*100),"studio_layer_y");
            SeekBar scale=layerSlider(box,"Largeur · 10 à 100 %",90,(int)(layer.optDouble("scale",.35)*100)-10,"studio_layer_scale");
            SeekBar opacity=layerSlider(box,"Opacité · 0 à 100 %",100,(int)(layer.optDouble("opacity",1)*100),"studio_layer_opacity");
            SeekBar offset=layerSlider(box,"Début dans le montage",(int)Math.max(0,total()-1000),(int)layer.optLong("offset_ms"),"studio_layer_offset");
            int maximum=(int)Math.min(total(),info.optBoolean("image")?60000:info.optLong("duration_ms")-layer.optLong("start_ms"));int minimum=info.optBoolean("image")?1000:500;
            SeekBar duration=layerSlider(box,"Durée du calque",Math.max(0,maximum-minimum),(int)layer.optLong("duration_ms")-minimum,"studio_layer_duration");
            TextView bounds=MobileUi.text(this,"",13,MobileUi.ACCENT);box.addView(bounds);Runnable update=()->bounds.setText(time(offset.getProgress())+" · "+String.format(Locale.FRANCE,"%.2f s",(duration.getProgress()+minimum)/1000.0));
            SeekBar.OnSeekBarChangeListener change=new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int progress,boolean user){update.run();}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}};offset.setOnSeekBarChangeListener(change);duration.setOnSeekBarChangeListener(change);update.run();
            StudioSheet.show(this,"Réglages du calque "+(index+1),box,()->{if(revision!=expected||saving){toast("Projet modifié : rouvre le calque");return;}try{JSONObject value=new JSONObject(layer.toString());value.put("x",x.getProgress()/100.0-1).put("y",y.getProgress()/100.0-1).put("scale",(scale.getProgress()+10)/100.0).put("opacity",opacity.getProgress()/100.0).put("offset_ms",offset.getProgress()).put("duration_ms",Math.min(duration.getProgress()+minimum,total()-offset.getProgress()));remember();StudioLayers.items(project).put(index,value);save();}catch(Exception e){toast(e.getMessage());}},()->{});
        });}catch(Exception e){ui.post(()->toast(e.getMessage()));}});
    }
    private void pickWorkspace(String kind){
        importKind=kind;
        try{studioPicker=WorkspaceMediaChooser.show(this,new android.webkit.WebChromeClient.FileChooserParams(){
            public int getMode(){return MODE_OPEN_MULTIPLE;}public String[] getAcceptTypes(){return kind.equals("audio")?new String[]{"audio/*"}:new String[]{"video/*","image/png","image/jpeg","image/webp"};}
            public boolean isCaptureEnabled(){return false;}public CharSequence getTitle(){return "Ajouter au montage";}public String getFilenameHint(){return "";}public Intent createIntent(){return new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*");}
        },uris->{if(uris==null||uris.length==0)return;ArrayList<String> paths=new ArrayList<>();for(android.net.Uri uri:uris)paths.add(android.text.TextUtils.join("/",uri.getPathSegments().subList(1,uri.getPathSegments().size())));addWorkspaceBatch(kind,paths);},()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(kind.equals("audio")?"audio/*":"*/*").putExtra(Intent.EXTRA_MIME_TYPES,kind.equals("audio")?new String[]{"audio/*"}:new String[]{"video/*","image/png","image/jpeg","image/webp"}).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),810));}catch(Exception e){toast(e.getMessage());}
    }
    private void addWorkspaceBatch(String kind,List<String> paths){
        if(kind.equals("layer")){addLayers(paths);return;}

        if(project==null||saving||importing)return;
        if(kind.equals("audio")&&!parallelImport&&!StudioAudio.enabled(project)){long remaining=total();for(int i=0;i<audio().length();i++)remaining-=audio().optJSONObject(i).optLong("duration_ms");if(remaining<500){toast(total()==0?"Ajoute d’abord une vidéo ou une photo":"La piste audio couvre déjà le montage. Raccourcis-la avant d’ajouter un audio.");return;}}
        importing=true;boolean parallel=parallelImport||StudioAudio.enabled(project);String snapshot=project.toString();
        WorkspaceCommands.IO.execute(()->{try{
            JSONObject copy=new JSONObject(snapshot);if(parallel&&kind.equals("audio"))StudioAudio.enable(copy);JSONArray target=copy.getJSONArray(kind.equals("audio")?"audio":"clips");int maximum=kind.equals("audio")?40:100;
            if(target.length()+paths.size()>maximum)throw new Exception(maximum+" médias maximum");
            long audioRemaining=StudioEdits.total(copy);for(int i=0;i<copy.getJSONArray("audio").length();i++)audioRemaining-=copy.getJSONArray("audio").getJSONObject(i).optLong("duration_ms");
            for(String path:paths){JSONObject info=MediaInspection.info(this,path);boolean image=info.optBoolean("image");if(kind.equals("audio")&&image)throw new Exception("Choisis un fichier audio");long duration=image?5000:info.optLong("duration_ms");if(duration<500)throw new Exception("Média trop court : "+path);if(kind.equals("audio")){if(!parallel&&audioRemaining<500)throw new Exception("Le montage est trop court pour tous les audios sélectionnés. Réduis la sélection.");if(!parallel){duration=Math.min(duration,audioRemaining);audioRemaining-=duration;}}JSONObject added=new JSONObject().put("path",path).put("start_ms",0).put("duration_ms",duration);if(kind.equals("audio")&&parallel)StudioAudio.attach(copy,added);target.put(added);}
            if(kind.equals("audio"))StudioEdits.fitAudio(copy);engine.validate(this,copy);
            ui.post(()->{importing=false;if(!alive)return;remember();project=copy;selected=clips().length()-1;selectedAudio=kind.equals("audio")?audio().length()-1:-1;save();});
        }catch(Exception e){ui.post(()->{importing=false;toast("Importation : "+e.getMessage());});}});
    }
    private void chooseFolder(String kind,String folder,int page){WorkspaceCommands.IO.execute(()->{try{WorkspaceStore store=new WorkspaceStore(this);JSONObject batch=store.list(folder,"",page);JSONArray rows=batch.getJSONArray("items");ArrayList<String> names=new ArrayList<>(),paths=new ArrayList<>();if(!folder.isEmpty()){names.add("‹ Parent");paths.add("..");}for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String p=row.getString("path");if(row.getBoolean("directory")||kind.equals("project")&&p.endsWith(".json")||row.getString("mime_type").startsWith(kind+"/")||kind.equals("video")&&p.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(png|jpe?g|webp)$")){names.add((row.getBoolean("directory")?"▱ ":"")+row.getString("name"));paths.add(p);}}if(batch.optInt("next_offset",-1)>=0){names.add("Suite ›");paths.add("#next");}ui.post(()->{if(!alive)return;new AgentDialog.Builder(this).setTitle("Fichiers / "+folder).setItems(names.toArray(new String[0]),(d,n)->{String p=paths.get(n);if(p.equals(".."))chooseFolder(kind,folder.contains("/")?folder.substring(0,folder.lastIndexOf('/')):"",0);else if(p.equals("#next"))chooseFolder(kind,folder,page+50);else{try{if(store.file(p).isDirectory())chooseFolder(kind,p,0);else addFile(kind,p);}catch(Exception e){toast(e.getMessage());}}}).setNegativeButton("Fermer",null).show();});}catch(Exception e){ui.post(()->toast(e.getMessage()));}});}
    private void addFile(String kind,String path){if(saving||importing)return;
        if(kind.equals("project")){try{engine.command(this,"video_editor_project_import",new JSONObject().put("path",path),(ok,result)->ui.post(()->{if(!alive)return;try{if(!ok){toast(result);return;}project=new JSONObject(result).getJSONObject("project");revision=project.getInt("revision");undo.clear();redo.clear();selected=0;draw();preview();}catch(Exception e){toast(e.getMessage());}}));}catch(Exception e){toast(e.getMessage());}return;}
        WorkspaceCommands.IO.execute(()->{try{JSONObject metadata=MediaInspection.info(this,path);long duration=metadata.optBoolean("image")?5000:metadata.optLong("duration_ms",0);if(duration<500)throw new Exception("Durée du média indisponible ou trop courte");ui.post(()->{try{if(kind.equals("video")){for(int j=0;j<clips().length();j++)
                    if(path.equals(clips().getJSONObject(j).optString("path"))){
                        toast("Ce fichier est déjà dans le montage. Aucun doublon ajouté.");
                        return;
                    }}
                    remember();JSONObject c=new JSONObject().put("path",path).put("start_ms",0).put("duration_ms",duration);if(kind.equals("video"))clips().put(c);else{if(parallelImport||StudioAudio.enabled(project)){StudioAudio.attach(project,c);audio().put(c);selected=clips().length()-1;save();return;}long remaining=total();for(int i=0;i<audio().length();i++)remaining-=audio().getJSONObject(i).optLong("duration_ms");if(remaining<500){toast("Ajoute une vidéo ou raccourcis l’audio précédent");return;}c.put("duration_ms",Math.min(duration,remaining));JSONArray a=audio();a.put(c);project.put("audio",a);}selected=clips().length()-1;save();}catch(Exception e){toast(e.getMessage());}});}catch(Exception e){ui.post(()->toast(e.getMessage()));}});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==811&&result==RESULT_OK&&data!=null&&data.getData()!=null){android.net.Uri target=data.getData();String path=exportCopyPath;WorkspaceCommands.IO.execute(()->{try(java.io.InputStream in=new java.io.FileInputStream(new WorkspaceStore(this).file(path));java.io.OutputStream out=getContentResolver().openOutputStream(target,"w")){if(out==null)throw new java.io.IOException("Destination indisponible");byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);ui.post(()->toast("Montage complet enregistré sur le téléphone"));}catch(Exception e){ui.post(()->toast(e.getMessage()));}});return;}
        if(request!=810||result!=RESULT_OK||data==null||project==null)return;String kind=importKind;int selectedCount=data.getClipData()==null?1:data.getClipData().getItemCount();if(selectedCount>100||kind.equals("video")&&clips().length()+selectedCount>100){toast("Le montage accepte 100 plans maximum. Aucun média ajouté.");return;}ArrayList<android.net.Uri> uris=new ArrayList<>();if(data.getClipData()!=null)for(int i=0;i<Math.min(100,data.getClipData().getItemCount());i++)uris.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)uris.add(data.getData());if(uris.isEmpty())return;importing=true;status.setText("Importation de "+uris.size()+" média(s)…");
        WorkspaceCommands.IO.execute(()->{JSONArray batch=new JSONArray();ArrayList<String> errors=new ArrayList<>();for(android.net.Uri uri:uris)try{String filename="Media-"+System.nanoTime();try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())filename=cursor.getString(0);}WorkspaceStore store=new WorkspaceStore(this);if(store.file(filename).exists())filename=System.currentTimeMillis()+"-"+filename;store.importUri(uri,filename);
                 java.io.File imported=store.file(filename);
                 boolean duplicate=false;
                 JSONArray existing=kind.equals("layer")?new JSONArray():kind.equals("video")?clips():audio();
                 for(int j=0;j<existing.length();j++){
                     String other=existing.getJSONObject(j).optString("path","");
                     if(!other.isEmpty()&&OfflineStudioAudit.sameContent(imported,store.file(other))){duplicate=true;break;}
                 }
                 if(!duplicate)for(int j=0;j<batch.length();j++){
                     String other=batch.getJSONObject(j).optString("path","");
                     if(!other.isEmpty()&&OfflineStudioAudit.sameContent(imported,store.file(other))){duplicate=true;break;}
                 }
                 if(duplicate){
                     imported.delete(); // Only the newly created CHK copy; the owner source is untouched.
                     errors.add("Vidéo déjà présente : copie ignorée ("+filename+")");
                     continue;
                 }
                 JSONObject metadata=MediaInspection.info(this,filename);long duration=metadata.optBoolean("image")?5000:metadata.optLong("duration_ms");
                 if(duration<500)throw new Exception("Média trop court ou durée indisponible");
                 batch.put(new JSONObject().put("path",filename).put("start_ms",0).put("duration_ms",duration));}catch(Exception e){errors.add(e.getMessage());}
            ui.post(()->{importing=false;if(!alive)return;if(kind.equals("layer")){ArrayList<String> paths=new ArrayList<>();for(int i=0;i<batch.length();i++)paths.add(batch.optJSONObject(i).optString("path"));if(!paths.isEmpty())addLayers(paths);if(!errors.isEmpty())toast(android.text.TextUtils.join("\n",errors));return;}if(batch.length()>0)try{remember();boolean parallel=parallelImport||StudioAudio.enabled(project);if(kind.equals("audio")&&parallel)StudioAudio.enable(project);JSONArray target=kind.equals("video")?clips():audio();long remaining=total();for(int i=0;i<audio().length();i++)remaining-=audio().optJSONObject(i).optLong("duration_ms");for(int i=0;i<batch.length();i++){JSONObject c=batch.getJSONObject(i);if(kind.equals("audio")){if(parallel){StudioAudio.attach(project,c);target.put(c);continue;}if(remaining<500){errors.add("Audio supplémentaire ignoré : le montage vidéo est trop court");break;}long length=Math.min(c.getLong("duration_ms"),remaining);c.put("duration_ms",length);remaining-=length;}target.put(c);}if(kind.equals("audio"))project.put("audio",target);selected=clips().length()-1;save();}catch(Exception e){toast(e.getMessage());}if(!errors.isEmpty())new AgentDialog.Builder(this).setTitle("Importation à vérifier").setMessage(android.text.TextUtils.join("\n",errors)).setPositiveButton("OK",null).show();});
        });
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("export_copy_path",exportCopyPath);if(timeline!=null){state.putLong("studio_position",timeline.playhead());state.putFloat("studio_zoom",timeline.zoomLevel());}state.putInt("studio_selected",selected);state.putInt("studio_audio",selectedAudio);state.putString("studio_tool_page",toolPage);}
    @Override protected void onResume(){super.onResume();resumed=true;ui.removeCallbacks(ticker);ui.post(ticker);}
    @Override public void onBackPressed(){
        if(fullscreenDialog!=null){fullscreenDialog.dismiss();return;}
        if(saving){closeAfterSave=true;toast("Fin de la sauvegarde avant fermeture…");return;}
        if(importing){toast("Attends la fin de l’importation");return;}
        super.onBackPressed();
    }
    @Override protected void onPause(){resumed=false;ui.removeCallbacks(ticker);pausePreview();super.onPause();}
    @Override protected void onDestroy(){if(subtitleJob!=null)subtitleJob.set(true);if(renderPreview!=null)renderPreview.cancel();if(studioPicker!=null)studioPicker.cancel();alive=false;previewGeneration++;ui.removeCallbacksAndMessages(null);if(fullscreenDialog!=null)fullscreenDialog.dismiss();if(player!=null)player.release();if(audioPlayer!=null)audioPlayer.release();super.onDestroy();}
    private static final class JSONObjectSafe extends JSONObject{JSONObjectSafe(String key,Object value){try{put(key,value);}catch(Exception ignored){}}JSONObjectSafe withRevision(int revision){try{put("expected_revision",revision);}catch(Exception ignored){}return this;}}
}
