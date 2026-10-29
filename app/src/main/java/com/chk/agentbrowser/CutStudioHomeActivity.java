package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Four-card home modelled on the original Cut Video application. */
public final class CutStudioHomeActivity extends Activity {
    private static final int BG=0xff111414, SURFACE=0xff252b2b, TEAL=0xff00c7b0;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    static GradientDrawable surface(int color,int radius){
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);shape.setCornerRadius(radius);return shape;
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(root);
        setContentView(scroll);
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(22),dp(24),dp(22),dp(18));
        top.setBackgroundColor(0xff00796b);root.addView(top);
        TextView title=label("CUT VIDÉO",27,Color.WHITE,true);title.setGravity(Gravity.CENTER);top.addView(title);
        TextView subtitle=label("Découpe, classement et publication",13,0xffd3fff3,false);
        subtitle.setGravity(Gravity.CENTER);top.addView(subtitle);
        LinearLayout cards=new LinearLayout(this);cards.setOrientation(LinearLayout.VERTICAL);cards.setPadding(dp(16),dp(20),dp(16),dp(8));root.addView(cards);
        card(cards,"✂","IMPORTER UNE VIDÉO","Choisir une vidéo du téléphone ou de Fichiers CHK",()->startActivity(new Intent(this,CutStudioActivity.class)));
        card(cards,"▣","MES VIDÉOS","Classements, dossiers, notes et publications",()->startActivity(new Intent(this,CutStudioLibraryActivity.class)));
        card(cards,"◎","ASSISTANT CHATGPT","Métadonnées, programmation et automatisations",()->startActivity(new Intent(this,CutStudioActivity.class).putExtra("cut_tab",2)));
        card(cards,"◎","RÉSEAUX SOCIAUX","TikTok, YouTube, Instagram et fiches de programmation",()->startActivity(new Intent(this,SocialPublishingActivity.class)));
        card(cards,"⚙","RÉGLAGES","Préférences et version de l'application",()->startActivity(new Intent(this,CutStudioSettingsActivity.class)));
        TextView footer=label("Fichiers conservés sur le téléphone · MCP CHK compatible",12,0xffb7c0bf,false);
        footer.setGravity(Gravity.CENTER);footer.setPadding(dp(12),dp(14),dp(12),dp(24));root.addView(footer);
    }
    private TextView label(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);
        if(bold)t.setTypeface(null, Typeface.BOLD);
        return t;
    }
    private void card(LinearLayout parent,String icon,String name,String sub,Runnable action){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(17),dp(18),dp(14),dp(18));
        row.setBackground(surface(SURFACE,dp(14)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(13);parent.addView(row,lp);
        TextView graphic=label(icon,28,TEAL,true);graphic.setGravity(Gravity.CENTER);
        row.addView(graphic,new LinearLayout.LayoutParams(dp(58),dp(48)));
        LinearLayout text=new LinearLayout(this);text.setOrientation(LinearLayout.VERTICAL);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        text.addView(label(name,15,Color.WHITE,true));
        TextView desc=label(sub,12,0xffb7c0bf,false);desc.setPadding(0,dp(5),0,0);text.addView(desc);
        TextView arrow=label("›",29,TEAL,false);row.addView(arrow);
        row.setOnClickListener(v->action.run());
    }
}
