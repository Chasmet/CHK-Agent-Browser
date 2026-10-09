package com.chk.agentbrowser;
import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import android.text.*;
import org.json.JSONObject;

/** Debounced save, lifecycle flush and optimistic revisions prevent silent lost edits. */
public final class NoteEditorActivity extends Activity {
    private EditText title,body;
    private Switch pinned;
    private TextView status,sourceView;
    private String id="",source="";
    private int revision;
    private boolean loading=true,dirty,saving,conflict,leaveWhenSaved;
    private final android.os.Handler handler=new android.os.Handler();
    private final Runnable autosave=()->save();
    @Override public void onCreate(Bundle saved){super.onCreate(saved);setContentView(R.layout.activity_note_editor);
        title=findViewById(R.id.note_title);body=findViewById(R.id.note_body);pinned=findViewById(R.id.note_pinned);status=findViewById(R.id.note_status);sourceView=findViewById(R.id.note_source);
        findViewById(R.id.note_back).setOnClickListener(v->onBackPressed());findViewById(R.id.note_menu).setOnClickListener(v->menu());
        findViewById(R.id.note_checklist).setOnClickListener(v->{int pos=Math.max(0,body.getSelectionStart());body.getText().insert(pos,(pos>0?"\n":"")+"[ ] ");body.requestFocus();});
        TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){changed();}public void afterTextChanged(Editable e){}};
        title.addTextChangedListener(watcher);body.addTextChangedListener(watcher);pinned.setOnCheckedChangeListener((b,c)->changed());
        id=saved==null?getIntent().getStringExtra("id"):saved.getString("id","");if(id==null)id="";
        if(saved!=null){source=saved.getString("source","");revision=saved.getInt("revision");title.setText(saved.getString("title",""));body.setText(saved.getString("body",""));pinned.setChecked(saved.getBoolean("pinned"));loading=false;dirty=saved.getBoolean("dirty",true);showSource();if(dirty)handler.post(autosave);}
        else if(id.isEmpty()){title.setText(getIntent().getStringExtra("title"));body.setText(getIntent().getStringExtra("body"));source=getIntent().getStringExtra("source");if(source==null)source="";loading=false;dirty=true;showSource();handler.post(autosave);}
        else load();
    }
    private void load(){loading=true;title.setEnabled(false);body.setEnabled(false);pinned.setEnabled(false);String readId=id;WorkspaceCommands.IO.execute(()->{try{JSONObject n=new WorkspaceStore(this).note(readId);if(n.getBoolean("deleted"))throw new Exception("Cette note est dans la corbeille");WorkspaceCommands.UI.post(()->{if(isFinishing())return;try{id=n.getString("id");revision=n.getInt("revision");source=n.getString("source_url");title.setText(n.getString("title"));body.setText(n.getString("body"));pinned.setChecked(n.getBoolean("pinned"));loading=false;dirty=false;conflict=false;title.setEnabled(true);body.setEnabled(true);pinned.setEnabled(true);status.setText("Enregistrée sur ce téléphone");showSource();}catch(Exception e){status.setText(e.getMessage());}});}catch(Exception e){WorkspaceCommands.UI.post(()->{status.setText(e.getMessage());loading=false;});}});}
    private void showSource(){sourceView.setVisibility(source.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);sourceView.setText("↗ "+source);sourceView.setOnClickListener(v->{if(source.startsWith("https://"))startActivity(new Intent(this,MainActivity.class).putExtra("open_url",source));});}
    private void changed(){if(loading)return;dirty=true;status.setText(conflict?"Conflit : conserve une copie depuis le menu":"Modifications en cours…");handler.removeCallbacks(autosave);handler.postDelayed(autosave,650);}
    private void save(){handler.removeCallbacks(autosave);if(loading||saving||conflict||!dirty){if(leaveWhenSaved&&!dirty&&!saving&&!conflict)finish();return;}
        String saveId=id,t=title.getText().toString(),b=body.getText().toString(),url=source;boolean pin=pinned.isChecked();int expected=revision;
        saving=true;dirty=false;status.setText("Enregistrement…");WorkspaceCommands.IO.execute(()->{try{JSONObject n=new WorkspaceStore(this).saveNote(saveId,t,b,url,pin,expected);WorkspaceCommands.UI.post(()->{try{id=n.getString("id");revision=n.getInt("revision");saving=false;status.setText("Enregistrée sur ce téléphone");if(dirty)save();else if(leaveWhenSaved)finish();}catch(Exception e){failed(e.getMessage());}});}catch(Exception e){WorkspaceCommands.UI.post(()->failed(e.getMessage()));}});
    }
    private void failed(String message){saving=false;dirty=true;conflict=true;leaveWhenSaved=false;status.setText(message);if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("Modifications conservées dans l’éditeur").setMessage(message).setPositiveButton("Conserver une copie",(d,i)->copyDraft()).setNegativeButton("Continuer à lire",null).show();}
    private void copyDraft(){if(saving)return;id="";revision=0;conflict=false;dirty=true;title.setText((title.getText()+" (copie)").substring(0,Math.min(120,title.length()+8)));save();}
    private void menu(){new AlertDialog.Builder(this).setTitle("Ma note").setItems(new String[]{"Enregistrer","Partager le texte","Exporter en Markdown dans Fichiers","Conserver une copie","Recharger depuis le téléphone"},(d,i)->{switch(i){case 0:save();break;case 1:startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,title.getText()+"\n\n"+body.getText()+"\n"+source),"Partager la note"));break;case 2:export();break;case 3:copyDraft();break;case 4:if(saving){Toast.makeText(this,"Enregistrement en cours",Toast.LENGTH_SHORT).show();break;}new AlertDialog.Builder(this).setTitle("Recharger la note ?").setMessage("Le texte de l’éditeur sera remplacé par la dernière version enregistrée.").setNegativeButton("Annuler",null).setPositiveButton("Recharger",(dialog,w)->{handler.removeCallbacks(autosave);if(!id.isEmpty())load();}).show();break;}}).setNegativeButton("Fermer",null).show();}
    private void export(){EditText name=new EditText(this);name.setSingleLine(true);name.setText("Ma-note.md");new AlertDialog.Builder(this).setTitle("Nom du fichier Markdown").setView(name).setPositiveButton("Exporter",(d,i)->{String path=name.getText().toString(),text="# "+title.getText()+"\n\n"+body.getText()+"\n\n"+source;WorkspaceCommands.IO.execute(()->{String result;try{new WorkspaceStore(this).writeText(path,text,false);result="Note exportée dans Fichiers";}catch(Exception e){result=e.getMessage();}String m=result;WorkspaceCommands.UI.post(()->Toast.makeText(this,m,Toast.LENGTH_LONG).show());});}).setNegativeButton("Annuler",null).show();}
    @Override public void onBackPressed(){if(conflict){new AlertDialog.Builder(this).setTitle("Conserver tes modifications").setMessage("La note a changé pendant l’édition.").setPositiveButton("Enregistrer une copie et fermer",(d,i)->{leaveWhenSaved=true;copyDraft();}).setNegativeButton("Continuer l’édition",null).show();return;}leaveWhenSaved=true;save();}
    @Override protected void onPause(){save();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("id",id);out.putString("title",title.getText().toString());out.putString("body",body.getText().toString());out.putString("source",source);out.putInt("revision",revision);out.putBoolean("pinned",pinned.isChecked());out.putBoolean("dirty",dirty||saving);}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
