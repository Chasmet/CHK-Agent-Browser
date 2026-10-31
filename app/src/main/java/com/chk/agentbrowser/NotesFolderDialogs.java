package com.chk.agentbrowser;

import android.app.Activity;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.HashSet;

/** Native folder editor and path-aware picker; never modifies note content. */
public final class NotesFolderDialogs {
    private static final String[] COLORS={"#C97821","#AF5734","#B54239","#647C3E","#3C8F75","#923A58","#62519D","#247D91"};
    private static final String[] NAMES={"Ambre","Cuivre","Terracotta","Olive","Sauge","Bordeaux","Violet","Turquoise"};
    private NotesFolderDialogs(){}
    public interface Choice {void picked(String folderId);}

    public static void edit(Activity a,JSONObject existing,Runnable onSaved){
        edit(a,existing,null,onSaved);
    }
    /** For new folders, parentId is the current directory. Null preserves an edited folder's parent. */
    public static void edit(Activity a,JSONObject existing,String parentId,Runnable onSaved){
        final String oldId=existing==null?"":existing.optString("id","");
        LinearLayout form=new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(MobileUi.dp(a,18),MobileUi.dp(a,8),MobileUi.dp(a,18),MobileUi.dp(a,8));
        EditText name=new EditText(a);
        name.setSingleLine(true);name.setHint("Nom du dossier");
        name.setText(existing==null?"":existing.optString("name",""));
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
        form.addView(name,new LinearLayout.LayoutParams(-1,MobileUi.dp(a,54)));
        EditText emoji=new EditText(a);
        emoji.setSingleLine(true);emoji.setHint("Emoji : 🎬");
        emoji.setText(existing==null?"📁":existing.optString("emoji","📁"));
        emoji.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(12)});
        form.addView(emoji,new LinearLayout.LayoutParams(-1,MobileUi.dp(a,52)));
        TextView hint=new TextView(a);hint.setText("Couleur du dossier");
        hint.setTextColor(0xffe3bb87);hint.setTextSize(13);
        form.addView(hint);
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
                String n=name.getText().toString().trim(),icon=emoji.getText().toString().trim();
                if(n.isEmpty()||icon.isEmpty()){Toast.makeText(a,"Nom et emoji obligatoires",Toast.LENGTH_LONG).show();return;}
                String shade=COLORS[color.getSelectedItemPosition()];
                WorkspaceCommands.IO.execute(()->{
                    String error=null;
                    try{new WorkspaceStore(a).saveNoteFolder(oldId,n,icon,shade,parentId);}
                    catch(Exception e){error=e.getMessage();}
                    String failure=error;
                    WorkspaceCommands.UI.post(()->{
                        if(a.isFinishing()||a.isDestroyed())return;
                        Toast.makeText(a,failure==null?"Dossier enregistré":failure,Toast.LENGTH_SHORT).show();
                        if(failure==null&&onSaved!=null)onSaved.run();
                    });
                });
            }).show();
    }

    public static String path(JSONArray folders,String id){
        if(id==null||id.isEmpty())return "Sans dossier";
        HashMap<String,JSONObject> lookup=new HashMap<>();
        for(int i=0;i<folders.length();i++){
            JSONObject row=folders.optJSONObject(i);
            if(row!=null)lookup.put(row.optString("id"),row);
        }
        StringBuilder result=new StringBuilder();
        HashSet<String> visited=new HashSet<>();
        String pointer=id;int depth=0;
        while(!pointer.isEmpty()&&depth++<50&&visited.add(pointer)){
            JSONObject folder=lookup.get(pointer);
            if(folder==null)break;
            String segment=folder.optString("name");
            result.insert(0,result.length()==0?segment:segment+" › ");
            pointer=folder.optString("parent_id","");
        }
        return result.length()==0?"Dossier inconnu":result.toString();
    }

    public static void choose(Activity a,String selected,Choice onChosen){
        WorkspaceCommands.IO.execute(()->{
            try{
                JSONArray folders=new WorkspaceStore(a).noteFolders();
                String[] labels=new String[folders.length()+1],ids=new String[labels.length];
                ids[0]="";labels[0]=(selected.isEmpty()?"✓  ":"")+"Sans dossier";
                for(int i=0;i<folders.length();i++){
                    JSONObject row=folders.getJSONObject(i);
                    ids[i+1]=row.getString("id");
                    labels[i+1]=(ids[i+1].equals(selected)?"✓  ":"")+
                        row.getString("emoji")+" "+path(folders,ids[i+1]);
                }
                WorkspaceCommands.UI.post(()->{
                    if(a.isFinishing()||a.isDestroyed())return;
                    new AgentDialog.Builder(a).setTitle("Choisir un dossier")
                        .setItems(labels,(d,index)->onChosen.picked(ids[index]))
                        .setNegativeButton("Annuler",null).show();
                });
            }catch(Exception e){
                WorkspaceCommands.UI.post(()->Toast.makeText(a,"Dossiers indisponibles",Toast.LENGTH_SHORT).show());
            }
        });
    }
}
