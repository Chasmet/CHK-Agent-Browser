package com.chk.agentbrowser;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.*;

final class MobileUi {
    static final int TEXT=Color.rgb(241,245,255),MUTED=Color.rgb(148,168,193),ACCENT=Color.rgb(105,226,198),CARD=Color.rgb(24,39,58);
    static int dp(Context c,int value){return Math.round(c.getResources().getDisplayMetrics().density*value);}
    static GradientDrawable bg(Context c,int color){GradientDrawable b=new GradientDrawable();b.setColor(color);b.setCornerRadius(dp(c,16));return b;}
    static TextView text(Context c,String text,int size,int color){TextView v=new TextView(c);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setPadding(dp(c,16),dp(c,10),dp(c,16),dp(c,10));return v;}
    static TextView action(Context c,String text){TextView v=text(c,text,15,ACCENT);v.setMinHeight(dp(c,48));v.setGravity(Gravity.CENTER_VERTICAL);v.setBackground(bg(c,CARD));v.setFocusable(true);v.setContentDescription(text);return v;}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
}
