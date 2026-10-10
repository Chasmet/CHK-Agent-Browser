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
    private TextView quality,scope;
    private boolean importing;
    private boolean compact;
    private long lastProjectCheck;
    private boolean previewReady;
    private TextView previewHint,previewCaption;
    private TextView title,clock,status,play,format;

    private androidx.media3.ui.PlayerView viewer;
    private FrameLayout previewCanvas;
    private Dialog fullscreenDialog;
    private SeekBar fullscreenSeek;
    private TextView fullscreenClock, fullscreenPlay;
    private boolean fullscreenScrubbing;
    private ExoPlayer player, audioPlayer;
    private boolean alive,scrubbing,saving;
    private int selected,previewGeneration,revision;
    private String importKind="video",exportCopyPath="";
    private final Runnable ticker=new Runnable(){public void run(){
        if(!alive)return;
        if(player!=null&&!scrubbing){long pos=timelinePosition();timeline.position(pos);if(player.isPlaying()&&audioPlayer!=null&&audioPlayer.getMediaItemCount()>0&&Math.abs(audioTimelinePosition()-pos)>350)seekAudio(pos);String value=time(pos)+" / "+time(total());if(!clock.getText().toString().equals(value))clock.setText(value);String icon=player.isPlaying()?"Ⅱ":"▶";if(!play.getText().toString().equals(icon))play.setText(icon);}
        if(fullscreenDialog!=null&&fullscreenDialog.isShowing()&&player!=null){
            if(fullscreenClock!=null)fullscreenClock.setText(time(timelinePosition())+" / "+time(total()));
            if(fullscreenPlay!=null)fullscreenPlay.setText(player.isPlaying()?"Ⅱ":"▶");
            if(fullscreenSeek!=null&&!fullscreenScrubbing)fullscreenSeek.setProgress((int)Math.min(Integer.MAX_VALUE,timelinePosition()));
        }
        long now=android.os.SystemClock.elapsedRealtime();if(!saving&&!importing&&project!=null&&now-lastProjectCheck>2500){lastProjectCheck=now;engine.command(VideoEditorActivity.this,"video_editor_project_read",new JSONObject(),(ok,value)->ui.post(()->{if(!alive||saving||importing||!ok)return;try{JSONObject fresh=new JSONObject(value);if(fresh.optInt("revision")!=revision){project=fresh;revision=fresh.optInt("revision");undo.clear();redo.clear();draw();preview();}}catch(Exception ignored){}}));}
        JSONObject s=engine.status();String state=s.optString("state");
        status.setVisibility(importing||state.equals("running")||state.equals("preparing")||state.equals("failed")||state.equals("completed")?View.VISIBLE:View.GONE);
        status.setText(importing?"Importation des médias…":"running".equals(state)?"Export MP4 · "+s.optInt("progress_percent")+" %":"preparing".equals(state)?"Préparation du rendu…":"completed".equals(state)?"Export terminé · toucher pour lire / partager":"failed".equals(state)?"Export impossible : "+s.optString("error"):"Montage local · sauvegarde automatique");
        ui.postDelayed(this,player!=null&&player.isPlaying()?33:600);
    }};
    @Override public void onCreate(Bundle state){super.onCreate(state);alive=true;if(state!=null)exportCopyPath=state.getString("export_copy_path","");
        getWindow().setStatusBarColor(0xff111111);getWindow().setNavigationBarColor(0xff111111);
        compact=getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density<720;
        LinearLayout root=MobileUi.column(this);root.setBackgroundColor(0xff111214);setContentView(root);
        LinearLayout header=new LinearLayout(this);header.setPadding(dp(6),0,dp(8),0);header.setGravity(Gravity.CENTER_VERTICAL);root.addView(header,new LinearLayout.LayoutParams(-1,dp(compact?48:54)));
        TextView close=chip("×");close.setTextSize(26);close.setContentDescription("Fermer le montage");header.addView(close,new LinearLayout.LayoutParams(dp(42),-1));close.setOnClickListener(v->finish());
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
        player=new ExoPlayer.Builder(this).build();
        audioPlayer=new ExoPlayer.Builder(this).build();
        viewer.setPlayer(player);
        player.addListener(new Player.Listener(){
            @Override public void onRenderedFirstFrame(){previewReady=true;previewHint.setVisibility(View.GONE);}
            @Override public void onMediaItemTransition(MediaItem item,int reason){configureCurrentClip();}
            @Override public void onPlaybackStateChanged(int state){
                if(state==Player.STATE_READY && previewReady)previewHint.setVisibility(View.GONE);
                if(state==Player.STATE_ENDED && audioPlayer!=null)audioPlayer.pause();
            }
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error){
                previewHint.setText("Vidéo non lisible sur cet appareil : "+error.getErrorCodeName()+"\nVérifie le codec ou réimporte ce plan.");
                previewHint.setVisibility(View.VISIBLE);pausePreview();
            }
        });
        LinearLayout playback=new LinearLayout(this);playback.setPadding(dp(8),0,dp(4),0);playback.setGravity(Gravity.CENTER_VERTICAL);root.addView(playback,new LinearLayout.LayoutParams(-1,dp(48)));
        clock=MobileUi.text(this,"00:00 / 00:00",11,0xffc0c3cc);clock.setPadding(dp(4),0,0,0);playback.addView(clock,new LinearLayout.LayoutParams(0,-2,1));
        play=chip("▶");playback.addView(play,new LinearLayout.LayoutParams(dp(48),-1));play.setOnClickListener(v->{if(player.isPlaying())pausePreview();else playPreview();});
        format=chip("9:16");format.setTextSize(11);format.setContentDescription("Format du montage");playback.addView(format,new LinearLayout.LayoutParams(dp(44),dp(38)));format.setOnClickListener(v->ratioMenu());
        TextView un=chip("↶");un.setContentDescription("Annuler la dernière modification");playback.addView(un,new LinearLayout.LayoutParams(dp(40),-1));un.setOnClickListener(v->history(false));
        TextView re=chip("↷");re.setContentDescription("Rétablir la modification");playback.addView(re,new LinearLayout.LayoutParams(dp(40),-1));re.setOnClickListener(v->history(true));
        HorizontalScrollView tracks=new HorizontalScrollView(this);tracks.setHorizontalScrollBarEnabled(false);root.addView(tracks,new LinearLayout.LayoutParams(-1,dp(compact?120:156)));timeline=new StudioTimeline(this);tracks.addView(timeline,new android.widget.FrameLayout.LayoutParams(-2,-1));timeline.setListener(new StudioTimeline.Listener(){
            public void seek(long position,int clip){selected=clip;pausePreview();seekPreview(position);clock.setText(time(position)+" / "+time(total()));}
            public void scrubbing(boolean active){scrubbing=active;if(active)pausePreview();}
        });
        LinearLayout summary=new LinearLayout(this);summary.setGravity(Gravity.CENTER_VERTICAL);root.addView(summary,new LinearLayout.LayoutParams(-1,dp(38)));scope=MobileUi.text(this,"Projet complet",11,0xffaeb4c1);scope.setPadding(dp(10),0,0,0);summary.addView(scope,new LinearLayout.LayoutParams(0,-2,1));TextView add=chip("＋ Vidéo");add.setTextSize(12);summary.addView(add,new LinearLayout.LayoutParams(dp(82),-1));add.setOnClickListener(v->addMenu("video"));
        LinearLayout mainTools=new LinearLayout(this);root.addView(mainTools,new LinearLayout.LayoutParams(-1,dp(compact?56:64)));
        mainTool(mainTools,"Éditer","scissors",()->editMenu());mainTool(mainTools,"Audio","audio",()->audioMenu());mainTool(mainTools,"Texte","text",()->{if(editable())caption();});mainTool(mainTools,"Effets","effects",()->{if(editable())filter();});mainTool(mainTools,"Plans","layers",()->clipMenu());mainTool(mainTools,"Format","crop",()->ratioMenu());
        HorizontalScrollView strip=new HorizontalScrollView(this);strip.setHorizontalScrollBarEnabled(false);root.addView(strip,new LinearLayout.LayoutParams(-1,dp(compact?44:48)));tools=new LinearLayout(this);strip.addView(tools);
        tool("✂","Diviser",()->split());tool("◷","Vitesse",()->speed());tool("▣","Recadrer",()->ratioMenu());tool("↻","Rotation",()->rotation());tool("⇄","Déplacer",()->move());tool("▣","Dupliquer",()->duplicate());tool("×","Supprimer",()->delete(false,selected));
        status=MobileUi.text(this,"Montage local",11,MobileUi.MUTED);status.setPadding(dp(10),dp(2),dp(10),dp(2));status.setMaxLines(2);root.addView(status,new LinearLayout.LayoutParams(-1,dp(34)));status.setOnClickListener(v->outputMenu());scope.setOnClickListener(v->outputMenu());
        engine.command(this,"video_editor_project_read",new JSONObject(),(ok,res)->runOnUiThread(()->{try{if(ok){project=new JSONObject(res);revision=project.optInt("revision",0);draw();preview();String launch=getIntent().getStringExtra("add_path");if(launch!=null)addFile("video",launch);}else toast(res);}catch(Exception e){toast(e.getMessage());}}));ui.post(ticker);
    }
    private int dp(int n){return MobileUi.dp(this,n);}
    private TextView chip(String text){TextView v=MobileUi.text(this,text,15,MobileUi.TEXT);v.setMinHeight(dp(48));v.setMinWidth(dp(48));v.setGravity(Gravity.CENTER);v.setFocusable(true);return v;}
    private void mainTool(LinearLayout parent,String label,String icon,Runnable action){TextView view=UiIcons.button(this,label,icon,0xfff4f4ff);parent.addView(view,new LinearLayout.LayoutParams(0,-1,1));view.setOnClickListener(v->action.run());}
    private void editMenu(){if(!editable())return;new AlertDialog.Builder(this).setTitle("Éditer le plan "+(selected+1)).setItems(new String[]{"Découper avec les curseurs","Diviser au curseur","Vitesse","Rotation","Son du plan"},(d,n)->{switch(n){case 0:trim(false,selected);break;case 1:split();break;case 2:speed();break;case 3:rotation();break;case 4:mute();break;}}).show();}
    private void audioMenu(){new AlertDialog.Builder(this).setTitle("Audio").setItems(new String[]{"Ajouter un audio","Son original","Modifier les pistes"},(d,n)->{if(n==0)addMenu("audio");else if(n==1)mute();else{String[] names=new String[audio().length()];for(int i=0;i<names.length;i++)names[i]=name(audio().optJSONObject(i).optString("path"));new AlertDialog.Builder(this).setTitle("Pistes audio").setItems(names,(x,i)->new AlertDialog.Builder(this).setItems(new String[]{"Découper","Supprimer"},(y,j)->{if(j==0)trim(true,i);else delete(true,i);}).show()).show();}}).show();}
    private void clipMenu(){if(!editable())return;new AlertDialog.Builder(this).setTitle("Plans du montage").setItems(new String[]{"Ajouter une vidéo","Dupliquer le plan","Déplacer le plan","Supprimer le plan"},(d,n)->{switch(n){case 0:addMenu("video");break;case 1:duplicate();break;case 2:move();break;case 3:delete(false,selected);break;}}).show();}
    private void tool(String glyph,String label,Runnable action){TextView v=chip(glyph+"  "+label);v.setTextSize(12);v.setPadding(dp(10),0,dp(10),0);v.setMinHeight(dp(44));v.setSingleLine(true);tools.addView(v,new LinearLayout.LayoutParams(-2,dp(compact?44:48)));v.setOnClickListener(w->{if(editable())action.run();});}
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
    private void draw(){if(!alive||project==null)return;applyPreviewFrame();title.setText(project.optString("name","Studio")+" ▾");format.setText(project.optString("aspect_ratio","source").replace("source","Original"));quality.setText(project.optInt("resolution",720)+"p ▾");scope.setText("✓ Projet complet · "+time(total()));selected=Math.max(0,Math.min(selected,clips().length()-1));timeline.setProject(project,selected);clock.setText(time(timelinePosition())+" / "+time(total()));previewCanvas.findViewWithTag("empty").setVisibility(clips().length()==0?View.VISIBLE:View.GONE);}
    private void save(){saving=true;String fallback=undo.peekLast();JSONObject snapshot;try{snapshot=new JSONObject(project.toString());}catch(Exception e){return;}
        engine.command(this,"video_editor_project_save",new JSONObjectSafe("project",snapshot).withRevision(revision),(ok,res)->ui.post(()->{saving=false;if(!alive)return;if(ok){try{revision=new JSONObject(res).getJSONObject("project").optInt("revision");project.put("revision",revision);}catch(Exception ignored){}draw();preview();}else{toast(res);try{if(fallback!=null)project=new JSONObject(fallback);}catch(Exception ignored){}draw();}}));
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
        Dialog dialog=new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
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
        if(clips().length()==0){player.stop();player.clearMediaItems();audioPlayer.stop();audioPlayer.clearMediaItems();previewHint.setVisibility(View.GONE);return;}
        int generation=++previewGeneration;long position=timelinePosition();boolean playing=player.isPlaying();
        String snapshot=project.toString();previewReady=false;previewHint.setText("Chargement des vidéos…");previewHint.setVisibility(View.VISIBLE);
        WorkspaceCommands.IO.execute(()->{
            try{
                JSONObject p=new JSONObject(snapshot);WorkspaceStore store=new WorkspaceStore(this);
                JSONArray video=p.getJSONArray("clips"),audio=p.optJSONArray("audio");
                ArrayList<MediaItem> videos=new ArrayList<>(),sounds=new ArrayList<>();
                for(int i=0;i<video.length();i++)videos.add(mediaForPreview(store,video.getJSONObject(i)));
                if(audio!=null)for(int i=0;i<audio.length();i++)sounds.add(mediaForPreview(store,audio.getJSONObject(i)));
                ui.post(()->{
                    if(!alive||generation!=previewGeneration)return;
                    try{
                        pausePreview();
                        applyPreviewFrame();
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
        long begin=c.optLong("start_ms",0),end=begin+c.optLong("duration_ms",10000);
        return new MediaItem.Builder().setUri(android.net.Uri.fromFile(file))
            .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(begin).setEndPositionMs(end).build()).build();
    }
    private long timelinePosition(){
        if(player==null||project==null||clips().length()==0)return 0;
        int index=Math.max(0,Math.min(player.getCurrentMediaItemIndex(),clips().length()-1));
        JSONObject c=clips().optJSONObject(index);double speed=c==null?1:c.optDouble("speed",1);
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
        audioPlayer.seekTo(i,Math.max(0,position-sum));
    }
    private void configureCurrentClip(){
        if(player==null||project==null||clips().length()==0)return;
        int i=Math.max(0,Math.min(player.getCurrentMediaItemIndex(),clips().length()-1));
        JSONObject c=clips().optJSONObject(i);
        if(c==null)return;
        player.setPlaybackSpeed((float)c.optDouble("speed",1));
        player.setVolume(project.optBoolean("mute_original",audio().length()>0)||c.optBoolean("mute",false)?0:1);
        selected=i;updateCaption();
    }
    private void updateCaption(){
        if(previewCaption==null)return;
        JSONObject c=clip();previewCaption.setText(c==null?"":c.optString("text",""));
    }
    private void pausePreview(){if(player!=null)player.pause();if(audioPlayer!=null)audioPlayer.pause();}
    private void playPreview(){
        if(player==null||player.getMediaItemCount()==0)return;
        if(player.getPlaybackState()==Player.STATE_ENDED)seekPreview(0);
        if(audioPlayer!=null&&audioPlayer.getMediaItemCount()>0){seekAudio(timelinePosition());audioPlayer.play();}
        player.play();
    }
    private void history(boolean forward){if(saving||project==null)return;ArrayDeque<String> from=forward?redo:undo,to=forward?undo:redo;if(from.isEmpty())return;try{to.addLast(project.toString());project=new JSONObject(from.removeLast());save();}catch(Exception e){toast(e.getMessage());}}
    private void put(JSONObject object,String key,Object value){try{remember();object.put(key,value);save();}catch(Exception e){toast(e.getMessage());}}
    private void split(){if(clip()==null)return;JSONObject c=clip();long cut=Math.round((timelinePosition()-start(selected))*c.optDouble("speed",1)),duration=c.optLong("duration_ms");if(cut<500||duration-cut<500){toast("Place le curseur au milieu du plan (minimum 0,5 s par partie)");return;}
        try{remember();JSONObject second=new JSONObject(c.toString());second.put("start_ms",c.optLong("start_ms")+cut).put("duration_ms",duration-cut);c.put("duration_ms",cut);JSONArray out=new JSONArray();for(int i=0;i<clips().length();i++){out.put(clips().get(i));if(i==selected)out.put(second);}project.put("clips",out);save();}catch(Exception e){toast(e.getMessage());}}
    private void trim(boolean sound,int index){JSONObject c=(sound?audio():clips()).optJSONObject(index);if(c==null||saving||importing)return;String path=c.optString("path");WorkspaceCommands.IO.execute(()->{try{long sourceDuration=MediaInspection.info(this,path).optLong("duration_ms");ui.post(()->{if(!alive)return;LinearLayout box=MobileUi.column(this);box.setPadding(dp(20),dp(10),dp(20),dp(12));TextView bounds=MobileUi.text(this,"",14,MobileUi.TEXT);box.addView(bounds);SeekBar begin=new SeekBar(this),end=new SeekBar(this);begin.setMax((int)sourceDuration);end.setMax((int)sourceDuration);begin.setProgress((int)c.optLong("start_ms"));end.setProgress((int)(c.optLong("start_ms")+c.optLong("duration_ms")));box.addView(MobileUi.text(this,"Début",12,MobileUi.MUTED));box.addView(begin);box.addView(MobileUi.text(this,"Fin",12,MobileUi.MUTED));box.addView(end);Runnable update=()->bounds.setText(time(begin.getProgress())+" → "+time(end.getProgress()));SeekBar.OnSeekBarChangeListener listener=new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int progress,boolean user){update.run();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}};begin.setOnSeekBarChangeListener(listener);end.setOnSeekBarChangeListener(listener);update.run();
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Découper · "+name(path)).setView(box).setNegativeButton("Annuler",null).setNeutralButton("Tout le média",(d,n)->{remember();try{c.put("start_ms",0).put("duration_ms",sourceDuration);save();}catch(Exception e){toast(e.getMessage());}}).setPositiveButton("Appliquer",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{if(end.getProgress()-begin.getProgress()<500){toast("Garde au moins 0,5 seconde");return;}try{remember();c.put("start_ms",begin.getProgress()).put("duration_ms",end.getProgress()-begin.getProgress());save();dialog.dismiss();}catch(Exception e){toast(e.getMessage());}}));dialog.show();
        });}catch(Exception e){ui.post(()->toast(e.getMessage()));}});}
    private void filter(){String[] labels={"Original","Noir et blanc","Cinéma","Chaud","Froid","Contraste","Nuit","Vintage","Fondu noir 0,18 s","Retirer le fondu"};String[] keys={"aucun","noir","cinema","chaud","froid","contraste","nuit","vintage"};new AlertDialog.Builder(this).setTitle("Filtres du plan").setItems(labels,(d,n)->{if(n<8)put(clip(),"filter",keys[n]);else put(clip(),"fade_ms",n==8?180:0);}).show();}
    private void caption(){if(clip()==null)return;prompt("Texte du plan",clip().optString("text",""),text->put(clip(),"text",text));}
    private void speed(){new AlertDialog.Builder(this).setTitle("Vitesse du plan").setItems(new String[]{"0,25×","0,5×","1×","1,5×","2×","4×"},(d,n)->put(clip(),"speed",new double[]{.25,.5,1,1.5,2,4}[n])).show();}
    private void rotation(){new AlertDialog.Builder(this).setTitle("Rotation du plan").setItems(new String[]{"0°","90°","180°","−90°"},(d,n)->put(clip(),"rotation",new int[]{0,90,180,-90}[n])).show();}
    private void mute(){if(project==null)return;new AlertDialog.Builder(this).setTitle("Son").setItems(new String[]{"Conserver le son des vidéos","Couper le son des vidéos","Couper ce plan","Rétablir ce plan"},(d,n)->{if(n<2)put(project,"mute_original",n==1);else if(clip()!=null)put(clip(),"mute",n==2);}).show();}
    private void move(){new AlertDialog.Builder(this).setTitle("Déplacer le plan").setItems(new String[]{"Vers la gauche","Vers la droite"},(d,n)->{int to=selected+(n==0?-1:1);if(to<0||to>=clips().length())return;try{remember();Object a=clips().get(selected),b=clips().get(to);clips().put(selected,b);clips().put(to,a);selected=to;save();}catch(Exception ignored){}}).show();}
    private void duplicate(){try{remember();clips().put(new JSONObject(clip().toString()));save();}catch(Exception e){toast(e.getMessage());}}
    private void delete(boolean sound,int index){if(project==null||saving)return;try{remember();JSONArray src=sound?audio():clips(),out=new JSONArray();for(int i=0;i<src.length();i++)if(i!=index)out.put(src.get(i));project.put(sound?"audio":"clips",out);if(!sound&&out.length()==0)project.put("audio",new JSONArray());save();}catch(Exception e){toast(e.getMessage());}}
    private void ratioMenu(){if(project==null)return;String[] ratios={"source","9:16","16:9","1:1","4:3","3:4","4:5","2:1","2.35:1","custom"};new AlertDialog.Builder(this).setTitle("Format du montage").setItems(new String[]{"Original","9:16 · Téléphone","16:9 · Paysage","1:1 · Carré","4:3","3:4","4:5","2:1","2,35:1 · Cinéma","Ratio personnalisé"},(d,n)->{if(n==9)prompt("Largeur:hauteur","9:16",r->setRatio(r));else setRatio(ratios[n]);}).show();}
    private void setRatio(String ratio){new AlertDialog.Builder(this).setTitle("Adapter l’image").setItems(new String[]{"Tout conserver (bandes noires)","Remplir (recadrage centré)"},(d,n)->{try{remember();project.put("aspect_ratio",ratio).put("aspect_mode",n==0?"fit":"crop");save();}catch(Exception ignored){}}).show();}
    private void qualityMenu(){if(project==null||saving)return;new AlertDialog.Builder(this).setTitle("Qualité vidéo").setSingleChoiceItems(new String[]{"480p · Léger","720p · Standard","1080p · Haute définition"},project.optInt("resolution",720)==480?0:project.optInt("resolution",720)==720?1:2,(d,n)->{put(project,"resolution",new int[]{480,720,1080}[n]);d.dismiss();}).setNegativeButton("Fermer",null).show();}
    private void export(){
        if(project==null||saving||importing){toast("Attends la fin de l’importation ou de la sauvegarde");return;}if(clips().length()==0){toast("Ajoute une vidéo avant d’exporter");return;}
        String snapshot=project.toString();WorkspaceCommands.IO.execute(()->{try{JSONObject p=new JSONObject(snapshot);boolean exists=new WorkspaceStore(this).file(p.getString("output")).exists();ui.post(()->{if(!alive)return;String message=clips().length()+" plans · "+time(total())+"\n"+project.optInt("resolution",720)+"p · "+project.optString("aspect_ratio","source").replace("source","Original")+"\n"+name(project.optString("output"));
            if(exists)new AlertDialog.Builder(this).setTitle("Exporter · "+time(total()))
                .setMessage(message+"\n\nUn MP4 existe déjà. Tu peux l'ouvrir, l'enregistrer ou relancer le rendu.")
                .setItems(new String[]{"Lire le MP4 existant","Enregistrer sur le téléphone","Partager le MP4",
                    "Exporter une nouvelle copie","Réexporter et remplacer"},(d,n)->{
                    if(n<=2)openExportFile(project.optString("output"),n);
                    else if(n==3)startExport(false,true);
                    else startExport(true,false);
                }).setNegativeButton("Annuler",null).show();
            else new AlertDialog.Builder(this).setTitle("Exporter le projet complet").setMessage(message).setNegativeButton("Annuler",null).setPositiveButton("Exporter",(d,n)->startExport(false,false)).show();
        });}catch(Exception e){ui.post(()->toast(e.getMessage()));}});
    }
    private void startExport(boolean replace,boolean copy){
        if(saving||importing)return;saving=true;pausePreview();
        try{JSONObject snapshot=new JSONObject(project.toString());if(copy){String path=snapshot.getString("output");snapshot.put("output",path.substring(0,path.length()-4)+"-"+System.currentTimeMillis()+".mp4");}
            engine.command(this,"video_editor_project_save",new JSONObjectSafe("project",snapshot).withRevision(revision),(ok,res)->ui.post(()->{saving=false;if(!alive)return;if(!ok){toast(res);return;}try{project=new JSONObject(res).getJSONObject("project");revision=project.optInt("revision");draw();engine.command(this,"video_editor_export",new JSONObject().put("replace",replace),(success,result)->ui.post(()->{if(!success&&alive)new AlertDialog.Builder(this).setTitle("Export impossible").setMessage(result).setPositiveButton("OK",null).show();}));}catch(Exception e){toast(e.getMessage());}}));
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
            new AlertDialog.Builder(this).setTitle("Export en cours")
                .setMessage("Le rendu continue dans l'application. Tu peux quitter cet écran.")
                .setNegativeButton("Continuer",null)
                .setPositiveButton("Annuler l’export",(d,n)->
                    engine.command(this,"video_editor_cancel",new JSONObject(),(ok,res)->{})).show();
            return;
        }
        String output=state.equals("completed")?engine.status().optString("output")
            :project==null?"":project.optString("output");
        if(output.isEmpty())return;
        new AlertDialog.Builder(this).setTitle("Montage MP4")
            .setItems(new String[]{"Lire","Enregistrer sur le téléphone","Partager",
                "Vérifier la durée, les pistes et les images"},(d,n)->{
                if(n<3)openExportFile(output,n);
                else engine.command(this,"video_editor_verify_output",new JSONObject(),
                    (ok,res)->ui.post(()->{
                        if(!alive)return;
                        if(!ok){toast(res);return;}
                        try{
                            JSONObject v=new JSONObject(res);
                            int count=v.optInt("verified_video_segments");
                            int unverified=v.optInt("unverified_video_segments");
                            new AlertDialog.Builder(this)
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
    private void prompt(String label,String initial,TextResult result){EditText e=new EditText(this);e.setText(initial);new AlertDialog.Builder(this).setTitle(label).setView(e).setNegativeButton("Annuler",null).setPositiveButton("Appliquer",(d,n)->result.done(e.getText().toString().trim())).show();}
    private void showProjects(){
        startActivity(new Intent(this,StudioProjectsActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
    private void replaceProjectFromCommand(boolean ok,String response){
        ui.post(()->{
            if(!alive)return;
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
        new AlertDialog.Builder(this).setTitle("Studio · Projets")
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
    private void addMenu(String kind){if(project==null||saving||importing)return;importKind=kind;new AlertDialog.Builder(this).setTitle("Ajouter "+(kind.equals("video")?"une vidéo":"un audio")).setItems(new String[]{"Depuis le téléphone","Depuis Fichiers CHK"},(d,n)->{if(n==0)startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(kind+"/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),810);else chooseFolder(kind,"",0);}).show();}
    private void chooseFolder(String kind,String folder,int page){WorkspaceCommands.IO.execute(()->{try{WorkspaceStore store=new WorkspaceStore(this);JSONObject batch=store.list(folder,"",page);JSONArray rows=batch.getJSONArray("items");ArrayList<String> names=new ArrayList<>(),paths=new ArrayList<>();if(!folder.isEmpty()){names.add("‹ Parent");paths.add("..");}for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String p=row.getString("path");if(row.getBoolean("directory")||kind.equals("project")&&p.endsWith(".json")||row.getString("mime_type").startsWith(kind+"/")){names.add((row.getBoolean("directory")?"▱ ":"")+row.getString("name"));paths.add(p);}}if(batch.optInt("next_offset",-1)>=0){names.add("Suite ›");paths.add("#next");}ui.post(()->{if(!alive)return;new AlertDialog.Builder(this).setTitle("Fichiers / "+folder).setItems(names.toArray(new String[0]),(d,n)->{String p=paths.get(n);if(p.equals(".."))chooseFolder(kind,folder.contains("/")?folder.substring(0,folder.lastIndexOf('/')):"",0);else if(p.equals("#next"))chooseFolder(kind,folder,page+50);else{try{if(store.file(p).isDirectory())chooseFolder(kind,p,0);else addFile(kind,p);}catch(Exception e){toast(e.getMessage());}}}).setNegativeButton("Fermer",null).show();});}catch(Exception e){ui.post(()->toast(e.getMessage()));}});}
    private void addFile(String kind,String path){if(saving||importing)return;WorkspaceCommands.IO.execute(()->{try{if(kind.equals("project")){WorkspaceStore store=new WorkspaceStore(this);StringBuilder body=new StringBuilder();int offset=0;do{JSONObject page=store.readText(path,offset);body.append(page.getString("text"));offset=page.getInt("next_offset");}while(offset>=0);String json=body.toString();ui.post(()->{
                    try{
                        JSONObject imported=new JSONObject(json);
                        engine.command(this,"video_editor_project_new",new JSONObject(),(ok,result)->ui.post(()->{
                            if(!alive)return;
                            if(!ok){toast(result);return;}
                            try{
                                JSONObject fresh=new JSONObject(result).getJSONObject("project");
                                imported.put("project_id",fresh.getString("project_id"));
                                imported.put("revision",fresh.getInt("revision"));
                                imported.put("created_at",fresh.getLong("created_at"));
                                project=imported;revision=fresh.getInt("revision");
                                undo.clear();redo.clear();selected=0;save();
                            }catch(Exception e){toast(e.getMessage());}
                        }));
                    }catch(Exception e){toast("Fichier projet JSON invalide : "+e.getMessage());}
                });return;}JSONObject metadata=MediaInspection.info(this,path);long duration=metadata.optLong("duration_ms",0);if(duration<500)throw new Exception("Durée du média indisponible ou trop courte");ui.post(()->{try{remember();JSONObject c=new JSONObject().put("path",path).put("start_ms",0).put("duration_ms",duration);if(kind.equals("video"))clips().put(c);else{long remaining=total();for(int i=0;i<audio().length();i++)remaining-=audio().getJSONObject(i).optLong("duration_ms");if(remaining<500){toast("Ajoute une vidéo ou raccourcis l’audio précédent");return;}c.put("duration_ms",Math.min(duration,remaining));JSONArray a=audio();a.put(c);project.put("audio",a);}selected=clips().length()-1;save();}catch(Exception e){toast(e.getMessage());}});}catch(Exception e){ui.post(()->toast(e.getMessage()));}});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==811&&result==RESULT_OK&&data!=null&&data.getData()!=null){android.net.Uri target=data.getData();String path=exportCopyPath;WorkspaceCommands.IO.execute(()->{try(java.io.InputStream in=new java.io.FileInputStream(new WorkspaceStore(this).file(path));java.io.OutputStream out=getContentResolver().openOutputStream(target,"w")){if(out==null)throw new java.io.IOException("Destination indisponible");byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);ui.post(()->toast("Montage complet enregistré sur le téléphone"));}catch(Exception e){ui.post(()->toast(e.getMessage()));}});return;}
        if(request!=810||result!=RESULT_OK||data==null||project==null)return;String kind=importKind;ArrayList<android.net.Uri> uris=new ArrayList<>();if(data.getClipData()!=null)for(int i=0;i<Math.min(20,data.getClipData().getItemCount());i++)uris.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)uris.add(data.getData());if(uris.isEmpty())return;importing=true;status.setText("Importation de "+uris.size()+" média(s)…");
        WorkspaceCommands.IO.execute(()->{JSONArray batch=new JSONArray();ArrayList<String> errors=new ArrayList<>();for(android.net.Uri uri:uris)try{String filename="Media-"+System.nanoTime();try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())filename=cursor.getString(0);}WorkspaceStore store=new WorkspaceStore(this);if(store.file(filename).exists())filename=System.currentTimeMillis()+"-"+filename;store.importUri(uri,filename);long duration=MediaInspection.info(this,filename).optLong("duration_ms");if(duration<500)throw new Exception("Média trop court ou durée indisponible");batch.put(new JSONObject().put("path",filename).put("start_ms",0).put("duration_ms",duration));}catch(Exception e){errors.add(e.getMessage());}
            ui.post(()->{importing=false;if(!alive)return;if(batch.length()>0)try{remember();JSONArray target=kind.equals("video")?clips():audio();long remaining=total();for(int i=0;i<audio().length();i++)remaining-=audio().optJSONObject(i).optLong("duration_ms");for(int i=0;i<batch.length();i++){JSONObject c=batch.getJSONObject(i);if(kind.equals("audio")){if(remaining<500){errors.add("Audio supplémentaire ignoré : le montage vidéo est trop court");break;}long length=Math.min(c.getLong("duration_ms"),remaining);c.put("duration_ms",length);remaining-=length;}target.put(c);}if(kind.equals("audio"))project.put("audio",target);selected=clips().length()-1;save();}catch(Exception e){toast(e.getMessage());}if(!errors.isEmpty())new AlertDialog.Builder(this).setTitle("Importation à vérifier").setMessage(android.text.TextUtils.join("\n",errors)).setPositiveButton("OK",null).show();});
        });
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("export_copy_path",exportCopyPath);}
    @Override protected void onPause(){pausePreview();super.onPause();}
    @Override protected void onDestroy(){alive=false;previewGeneration++;ui.removeCallbacksAndMessages(null);if(fullscreenDialog!=null)fullscreenDialog.dismiss();if(player!=null)player.release();if(audioPlayer!=null)audioPlayer.release();super.onDestroy();}
    private static final class JSONObjectSafe extends JSONObject{JSONObjectSafe(String key,Object value){try{put(key,value);}catch(Exception ignored){}}JSONObjectSafe withRevision(int revision){try{put("expected_revision",revision);}catch(Exception ignored){}return this;}}
}
