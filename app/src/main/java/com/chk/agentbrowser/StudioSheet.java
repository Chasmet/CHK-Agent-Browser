package com.chk.agentbrowser;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;

/** Compact editing panel, keeping the actual preview visible above it. */
final class StudioSheet {
    static Dialog show(Activity activity,String name,android.view.View content,Runnable apply,Runnable restore){
        Dialog dialog=AgentDialog.watch(new Dialog(activity));dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=MobileUi.column(activity);root.setPadding(12,10,12,12);root.setBackgroundColor(0xff202631);
        root.addView(MobileUi.text(activity,name,17,MobileUi.TEXT));
        ScrollView scroll=new ScrollView(activity){@Override protected void onMeasure(int width,int height){int maximum=(int)(getResources().getDisplayMetrics().heightPixels*.42f);super.onMeasure(width,android.view.View.MeasureSpec.makeMeasureSpec(maximum,android.view.View.MeasureSpec.AT_MOST));}};scroll.setFillViewport(false);scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout footer=new LinearLayout(activity);root.addView(footer);
        TextView cancel=MobileUi.action(activity,"Annuler"),confirm=MobileUi.action(activity,"Appliquer");
        confirm.setTag("studio_sheet_apply");cancel.setTag("studio_sheet_cancel");
        footer.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));footer.addView(confirm,new LinearLayout.LayoutParams(0,-2,1));
        final boolean[] committed={false};confirm.setOnClickListener(v->{apply.run();committed[0]=true;dialog.dismiss();});cancel.setOnClickListener(v->dialog.dismiss());
        dialog.setOnDismissListener(d->{if(!committed[0])restore.run();});dialog.setContentView(root);dialog.show();
        Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xff202631));w.setGravity(Gravity.BOTTOM);w.setLayout(-1,-2);w.setDimAmount(.12f);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
        return dialog;
    }
}
