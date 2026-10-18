package com.chk.agentbrowser;

import android.app.Activity;
import android.media.MediaPlayer;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Compact on-screen audio player; switching folders never interrupts the track. */
final class WorkspaceAudioBar {
    private final Activity activity;
    private final LinearLayout bar;
    private final TextView title,play;
    private final Runnable visibilityChanged;
    private MediaPlayer player;
    private String path="";
    private boolean ready,disposed;

    WorkspaceAudioBar(Activity activity,Runnable visibilityChanged){
        this.activity=activity;
        this.visibilityChanged=visibilityChanged;
        bar=new LinearLayout(activity);
        bar.setTag("workspace_audio_bar");
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12),dp(4),dp(8),dp(4));
        bar.setBackgroundColor(0xffe5edfc);
        bar.setVisibility(View.GONE);
        title=MobileUi.text(activity,"",14,0xff24334a);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        bar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        play=control("▶","Lecture / pause");
        bar.addView(play,new LinearLayout.LayoutParams(dp(50),dp(48)));
        play.setOnClickListener(v->toggle());
        TextView close=control("×","Arrêter et fermer le lecteur");
        bar.addView(close,new LinearLayout.LayoutParams(dp(45),dp(48)));
        close.setOnClickListener(v->stop());
    }
    private int dp(int size){return MobileUi.dp(activity,size);}
    private TextView control(String text,String description){
        TextView control=MobileUi.text(activity,text,24,0xff2468cb);
        control.setGravity(Gravity.CENTER);
        control.setContentDescription(description);
        control.setFocusable(true);
        return control;
    }
    View view(){return bar;}
    boolean visible(){return bar.getVisibility()==View.VISIBLE;}
    String path(){return path;}
    void open(String relativePath){
        if(disposed)return;
        if(relativePath.equals(path)&&player!=null){toggle();return;}
        release();
        path=relativePath;
        ready=false;
        title.setText(relativePath.substring(relativePath.lastIndexOf('/')+1));
        play.setText("…");
        bar.setVisibility(View.VISIBLE);
        visibilityChanged.run();
        try{
            String absolute=new WorkspaceStore(activity).file(relativePath).getAbsolutePath();
            MediaPlayer next=new MediaPlayer();
            player=next;
            next.setDataSource(absolute);
            next.setOnPreparedListener(mp->{
                if(disposed||player!=mp)return;
                ready=true;
                mp.start();
                play.setText("Ⅱ");
            });
            next.setOnCompletionListener(mp->{if(player==mp)play.setText("▶");});
            next.setOnErrorListener((mp,what,extra)->{
                if(player==mp){
                    ready=false;
                    play.setText("!");
                    android.widget.Toast.makeText(activity,"Audio incompatible ou inaccessible",android.widget.Toast.LENGTH_SHORT).show();
                }
                return true;
            });
            next.prepareAsync();
        }catch(Exception e){
            stop();
            android.widget.Toast.makeText(activity,"Impossible de lire ce fichier audio",android.widget.Toast.LENGTH_SHORT).show();
        }
    }
    private void toggle(){
        if(!ready||player==null)return;
        try{
            if(player.isPlaying()){player.pause();play.setText("▶");}
            else{player.start();play.setText("Ⅱ");}
        }catch(IllegalStateException e){stop();}
    }
    private void release(){
        MediaPlayer previous=player;
        player=null;ready=false;
        if(previous!=null){try{previous.reset();}catch(Exception ignored){}previous.release();}
    }
    void stop(){
        release();path="";bar.setVisibility(View.GONE);
        if(!disposed)visibilityChanged.run();
    }
    void destroy(){disposed=true;release();path="";bar.setVisibility(View.GONE);}
}
