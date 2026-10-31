package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;


/** Folder controls shared by Notes home and the note editor. No filesystem changes. */
public final class NotesFolderDialogs {
    private static final String[] COLORS={"#3B82F6","#9B6DE3","#22B98B","#F59E50","#E66A9B","#64748B"};
    private static final String[] NAMES={"Bleu","Violet","Vert","Orange","Rose","Ardoise"};
    private NotesFolderDialogs(){}
    public interface Choice{void picked(String folderId);}

    public static void edit(Activity a,JSONObject existing,Runnable onSaved){
        final String oldId=existing==null?"":existing.optString("id","");
        LinearLayout form=new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad=MobileUi.dp(a,18);
        form.setPadding(pad,MobileUi.dp(a,8),pad,MobileUi.dp(a,8));
        EditText name=new EditText(a);
        name.setSingleLine(true);name.setHint("Nom du dossier");
        name.setMaxLines(1);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
        name.setText(existing==null?"":existing.optString("name",""));
        form.addView(name,new LinearLayout.LayoutParams(-1,MobileUi.dp(a,54)));
        EditText emoji=new EditText(a);
        emoji.setSingleLine(true);emoji.setHint("Emoji : 🎬");
        emoji.setText(existing==null?"📁":existing.optString("emoji","📁"));
        emoji.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(12)});
        form.addView(emoji,new LinearLayout.LayoutParams(-1,MobileUi.dp(a,52)));
        Spinner color=new Spinner(a);
        color.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,NAMES));
        if(existing!=null){
            String prior=existing.optString("color",COLORS[0]);
            for(int i=0;i<COLORS.length;i++)if(prior.equalsIgnoreCase(COLORS[i])){color.setSelection(i);break;}
        }
        form.addView(color,new LinearLayout.LayoutParams(-1,MobileUi.dp(a,54)));
        new AgentDialog.Builder(a).setTitle(existing==null?"Nouveau dossier":"Modifier le dossier")
            .setView(form).setNegativeButton("Annuler",null)
            .setPositiveButton("Enregistrer",(d,w)->{
                final String n=name.getText().toString().trim(),icon=emoji.getText().toString().trim();
                if(n.isEmpty()||icon.isEmpty()){
                    Toast.makeText(a,"Nom et emoji obligatoires",Toast.LENGTH_LONG).show();
                    return;
                }
                String value=COLORS[color.getSelectedItemPosition()];
                WorkspaceCommands.IO.execute(()->{
                    String failure=null;
                    try{new WorkspaceStore(a).saveNoteFolder(oldId,n,icon,value);}
                    catch(Exception e){failure=e.getMessage();}
                    final String error=failure;
                    WorkspaceCommands.UI.post(()->{
                        if(a.isFinishing()||a.isDestroyed())return;
                        Toast.makeText(a,error==null?"Dossier enregistré":error,Toast.LENGTH_SHORT).show();
                        if(error==null&&onSaved!=null)onSaved.run();
                    });
                });
            }).show();
    }

    public static void choose(Activity a,String selected,Choice onChosen){
        WorkspaceCommands.IO.execute(()->{
            try{
                JSONArray folders=new WorkspaceStore(a).noteFolders();
                String[] labels=new String[folders.length()+1];
                String[] ids=new String[labels.length];
                ids[0]="";labels[0]=(selected.isEmpty()?"✓  ":"")+"Sans dossier";
                for(int i=0;i<folders.length();i++){
                    JSONObject f=folders.getJSONObject(i);
                    ids[i+1]=f.getString("id");
                    labels[i+1]=(ids[i+1].equals(selected)?"✓  ":"")+
                        f.getString("emoji")+" "+f.getString("name");
                }
                WorkspaceCommands.UI.post(()->{
                    if(a.isFinishing()||a.isDestroyed())return;
                    new AgentDialog.Builder(a).setTitle("Choisir un dossier")
                        .setItems(labels,(d,index)->onChosen.picked(ids[index]))
                        .setNegativeButton("Annuler",null).show();
                });
            }catch(Exception e){WorkspaceCommands.UI.post(()->Toast.makeText(a,"Dossiers indisponibles",Toast.LENGTH_SHORT).show());}
        });
    }
}
