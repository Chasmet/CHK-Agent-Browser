package com.chk.agentbrowser;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.Locale;

/**
 * Compact readable file-name editor. Only the stem is editable: the real
 * extension is always shown separately and retained, even for a long name.
 */
final class WorkspaceRenameDialog {
    interface Saved {void accept(String name);}
    private WorkspaceRenameDialog(){}

    static String protectedExtension(String fullName,boolean directory){
        if(directory||fullName==null)return "";
        String lower=fullName.toLowerCase(Locale.ROOT);
        for(String suffix:new String[]{".tar.gz",".tar.bz2",".tar.xz",".tar.zst"}){
            if(lower.endsWith(suffix)&&fullName.length()>suffix.length())
                return fullName.substring(fullName.length()-suffix.length());
        }
        int dot=fullName.lastIndexOf('.');
        if(dot<=0||dot>=fullName.length()-1)return "";
        return fullName.substring(dot);
    }
    static String editableStem(String fullName,boolean directory){
        String extension=protectedExtension(fullName,directory);
        return extension.isEmpty()?fullName:fullName.substring(0,fullName.length()-extension.length());
    }
    static String resultName(String original,boolean directory,String typed)throws IOException {
        if(typed==null)throw new IOException("Saisis un nom.");
        String stem=typed.trim();
        String extension=protectedExtension(original,directory);
        // Pasting an entire filename should never yield video.mp4.mp4.
        if(!extension.isEmpty()&&stem.length()>extension.length()&&
            stem.regionMatches(true,stem.length()-extension.length(),extension,0,extension.length()))
            stem=stem.substring(0,stem.length()-extension.length()).trim();
        if(stem.isEmpty())throw new IOException("Le nom ne peut pas être vide.");
        String finalName=stem+extension;
        WorkspacePaths.child("",finalName);
        return finalName;
    }
    static Dialog rename(Activity activity,String fullName,boolean directory,Saved callback){
        String suffix=protectedExtension(fullName,directory);
        return render(activity,directory?"Renommer le dossier":"Renommer le fichier",
            editableStem(fullName,directory),suffix,fullName,directory,callback);
    }
    static Dialog text(Activity activity,String title,String original,Saved callback){
        return render(activity,title,original,"",null,false,callback);
    }
    private static int dp(Activity a,int size){return MobileUi.dp(a,size);}
    private static GradientDrawable rounded(Activity a,int bg,int border,int radius){
        GradientDrawable drawable=new GradientDrawable();
        drawable.setColor(bg);
        drawable.setCornerRadius(dp(a,radius));
        if(border!=0)drawable.setStroke(dp(a,1),border);
        return drawable;
    }
    private static TextView textView(Activity a,String text,int sp,int color){
        TextView label=new TextView(a);
        label.setText(text);label.setTextSize(sp);label.setTextColor(color);
        return label;
    }
    private static Dialog render(Activity a,String heading,String initial,String extension,
                                 String fullName,boolean directory,Saved callback){
        final Dialog dialog=AgentDialog.watch(new Dialog(a));
        final int dark=0xff172537,muted=0xff52647b,accent=0xff126bc3;
        LinearLayout root=new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(a,20),dp(a,20),dp(a,20),dp(a,14));
        root.setBackground(rounded(a,0xffffffff,0,18));

        TextView header=textView(a,heading,20,dark);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header,new LinearLayout.LayoutParams(-1,dp(a,38)));

        TextView instruction=textView(a,fullName==null?"Saisis le nouveau nom :":
            directory?"Nom du dossier :":"Nom du fichier (sans extension) :",14,muted);
        LinearLayout.LayoutParams hintParams=new LinearLayout.LayoutParams(-1,-2);
        hintParams.topMargin=dp(a,8);
        root.addView(instruction,hintParams);

        EditText input=new EditText(a);
        input.setTag("workspace_rename_name");
        input.setTextColor(dark);
        input.setHintTextColor(muted);
        input.setTextSize(16);
        input.setGravity(Gravity.TOP|Gravity.START);
        input.setPadding(dp(a,12),dp(a,12),dp(a,12),dp(a,12));
        input.setBackground(rounded(a,0xfff2f6fc,0xffb9cce3,10));
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHorizontallyScrolling(false);
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams fieldParams=new LinearLayout.LayoutParams(-1,-2);
        fieldParams.topMargin=dp(a,8);
        root.addView(input,fieldParams);

        if(!extension.isEmpty()){
            TextView suffix=textView(a,"Extension protégée : "+extension,14,accent);
            suffix.setTag("workspace_rename_extension");
            suffix.setPadding(dp(a,2),dp(a,12),0,0);
            suffix.setMaxLines(2);
            root.addView(suffix,new LinearLayout.LayoutParams(-1,-2));
        }
        TextView error=textView(a,"",13,0xffaa2233);
        error.setTag("workspace_rename_error");
        error.setVisibility(View.GONE);
        LinearLayout.LayoutParams err=new LinearLayout.LayoutParams(-1,-2);
        err.topMargin=dp(a,6);
        root.addView(error,err);
        input.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){
                error.setText("");error.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(Editable e){}
        });

        LinearLayout actions=new LinearLayout(a);
        actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row=new LinearLayout.LayoutParams(-1,dp(a,56));
        row.topMargin=dp(a,12);
        root.addView(actions,row);
        TextView cancel=action(a,"Annuler",muted);
        cancel.setTag("workspace_rename_cancel");
        actions.addView(cancel,new LinearLayout.LayoutParams(0,-1,1));
        cancel.setOnClickListener(v->dialog.dismiss());
        TextView save=action(a,fullName==null?"Valider":"Renommer",accent);
        save.setTag("workspace_rename_save");
        actions.addView(save,new LinearLayout.LayoutParams(0,-1,1));
        save.setOnClickListener(v->{
            try{
                String proposed=fullName==null?input.getText().toString().trim():
                    resultName(fullName,directory,input.getText().toString());
                if(proposed.isEmpty())throw new IOException("Saisis un nom.");
                if(fullName!=null&&proposed.equals(fullName)){dialog.dismiss();return;}
                callback.accept(proposed);
                dialog.dismiss();
            }catch(IOException ex){
                error.setText(ex.getMessage());
                error.setVisibility(View.VISIBLE);
            }
        });

        dialog.setContentView(root);
        Window window=dialog.getWindow();
        if(window!=null){
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            window.setGravity(Gravity.CENTER);
        }
        dialog.show();
        if(window!=null){
            int max=dp(a,460);
            int available=Resources.getSystem().getDisplayMetrics().widthPixels-dp(a,32);
            window.setLayout(Math.min(max,available),WindowManager.LayoutParams.WRAP_CONTENT);
        }
        input.requestFocus();
        input.selectAll();
        return dialog;
    }
    private static TextView action(Activity a,String caption,int color){
        TextView button=textView(a,caption,15,color);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setContentDescription(caption);
        button.setBackground(rounded(a,0xffedf4fe,0,9));
        return button;
    }
}
