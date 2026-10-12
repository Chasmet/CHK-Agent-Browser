package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Cut-only settings: keeps the browser's established signed update manager. */
public final class CutStudioSettingsActivity extends Activity {
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout host=new LinearLayout(this);host.setOrientation(1);host.setBackgroundColor(0xff111414);
        ScrollView scroll=new ScrollView(this);scroll.addView(host);setContentView(scroll);
        TextView title=text("‹   RÉGLAGES CUT VIDÉO",21,Color.WHITE);title.setPadding(dp(18),dp(20),dp(18),dp(20));
        title.setBackgroundColor(0xff00796b);host.addView(title);title.setOnClickListener(v->finish());
        box(host,"Version intégrée","Cut Vidéo intégré à CHK Agent Browser. Ses vidéos et fiches restent indépendantes de l'autre application Cut Vidéo.");
        box(host,"Données locales","Classements, blocs-notes et cases de suivi sont conservés localement après une mise à jour signée avec la même clé.");
        action(host,"OUVRIR LA BIBLIOTHÈQUE",()->startActivity(new Intent(this,CutStudioLibraryActivity.class)));
        action(host,"VÉRIFIER LES MISES À JOUR CHK",()->startActivity(new Intent(this,SettingsActivity.class)));
        box(host,"Réseaux sociaux","Les programmations créent des rappels Android. Une publication distante doit être confirmée sur le réseau.");
        box(host,"Confidentialité","Aucune vidéo n'est envoyée à un service externe lors du découpage local.");
    }
    private TextView text(String s,int sz,int color){TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(sz);return t;}
    private void box(LinearLayout host,String title,String subtitle){
        LinearLayout p=new LinearLayout(this);p.setOrientation(1);p.setPadding(dp(15),dp(14),dp(15),dp(14));
        p.setBackground(CutStudioHomeActivity.surface(0xff252b2b,dp(10)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(14),dp(12),dp(14),0);host.addView(p,lp);
        p.addView(text(title,16,0xff00c7b0));TextView info=text(subtitle,13,0xffb7c0bf);info.setPadding(0,dp(6),0,0);p.addView(info);
    }
    private void action(LinearLayout host,String title,Runnable callback){
        TextView button=text(title,14,Color.WHITE);button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12),dp(16),dp(12),dp(16));button.setBackground(CutStudioHomeActivity.surface(0xff00796b,dp(9)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(14),dp(14),dp(14),0);host.addView(button,lp);
        button.setOnClickListener(v->callback.run());
    }
}
