package com.chk.agentbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.io.*;

/** Native mobile file and note screens. All storage work is outside the UI thread. */
public final class WorkspacePanel {
    private static final int IMPORT=701,EXPORT=702;
    private final Activity a;
    private final FrameLayout host;
    private LinearLayout list;
    private TextView location;
    private EditText search;
    private String folder="",space="files",exportPath="";
    private int generation,offset;
    private final android.os.Handler handler=new android.os.Handler();
    private final Runnable filter=()->{offset=0;refresh();};
    public WorkspacePanel(Activity a,FrameLayout host){this.a=a;this.host=host;}
    public void show(String value){
        space=value;offset=0;generation++;host.removeAllViews();LinearLayout root=MobileUi.column(a);host.addView(root,new FrameLayout.LayoutParams(-1,-1));
        location=MobileUi.text(a,space.equals("files")?"Mes fichiers":"Mes notes",24,MobileUi.TEXT);root.addView(location);
        TextView hint=MobileUi.text(a,space.equals("files")?"Documents, images et vidéos, au même endroit":"Idées, listes et extraits de pages. Sauvegarde automatique.",14,MobileUi.MUTED);root.addView(hint);
        search=new EditText(a);search.setSingleLine(true);search.setTextColor(MobileUi.TEXT);search.setHintTextColor(MobileUi.MUTED);search.setHint(space.equals("files")?"Rechercher dans ce dossier":"Rechercher dans les notes");search.setTextSize(16);search.setPadding(MobileUi.dp(a,16),0,MobileUi.dp(a,16),0);root.addView(search,new LinearLayout.LayoutParams(-1,MobileUi.dp(a,52)));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int aft){}public void onTextChanged(CharSequence s,int st,int before,int count){handler.removeCallbacks(filter);handler.postDelayed(filter,180);}public void afterTextChanged(Editable e){}});
        LinearLayout actions=new LinearLayout(a);actions.setPadding(MobileUi.dp(a,12),MobileUi.dp(a,6),MobileUi.dp(a,12),MobileUi.dp(a,6));root.addView(actions);
        TextView add=MobileUi.action(a,space.equals("files")?"＋ Nouveau":"＋ Note");actions.addView(add,new LinearLayout.LayoutParams(0,-2,1));add.setOnClickListener(v->{if(space.equals("files"))createMenu();else openNote("");});
        TextView more=MobileUi.action(a,"⋯ Options");actions.addView(more,new LinearLayout.LayoutParams(0,-2,1));more.setOnClickListener(v->options());
        ScrollView sc=new ScrollView(a);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));list=MobileUi.column(a);list.setPadding(MobileUi.dp(a,12),0,MobileUi.dp(a,12),MobileUi.dp(a,24));sc.addView(list);refresh();
    }
    public void refresh(){
        if(list==null||a.isFinishing())return;final int ticket=++generation;String mode=space,p=folder,q=search.getText().toString();int page=offset;
        WorkspaceCommands.IO.execute(()->{try{WorkspaceStore s=new WorkspaceStore(a);JSONObject files=mode.equals("files")?s.list(p,q,page):null;JSONArray notes=files==null?s.notes(q,false,page):null;
            WorkspaceCommands.UI.post(()->{if(a.isFinishing()||ticket!=generation)return;try{list.removeAllViews();
                if(files!=null){location.setText(p.isEmpty()?"Mes fichiers":p);if(!p.isEmpty()){TextView up=MobileUi.action(a,"‹ Dossier parent");list.addView(up);up.setOnClickListener(v->back());}
                    JSONArray rows=files.getJSONArray("items");for(int i=0;i<rows.length();i++)fileRow(rows.getJSONObject(i));
                    if(rows.length()==0)empty(q.isEmpty()?"Ce dossier est prêt pour tes fichiers.\nAppuie sur + Nouveau pour importer ou créer.":"Aucun résultat dans ce dossier.");
                    int next=files.getInt("next_offset");pager(next);
                }else{for(int i=0;i<notes.length();i++)noteRow(notes.getJSONObject(i));if(notes.length()==0)empty("Une idée à garder ? Crée ta première note.");pager(notes.length()==50?page+50:-1);}
            }catch(Exception e){empty("Affichage impossible");}});
        }catch(Exception e){WorkspaceCommands.UI.post(()->{if(ticket==generation){list.removeAllViews();empty(e.getMessage());}});}});
    }
    private void pager(int next){if(offset>0){TextView prev=MobileUi.action(a,"‹ Éléments précédents");list.addView(prev);prev.setOnClickListener(v->{offset=Math.max(0,offset-50);refresh();});}if(next>=0){TextView v=MobileUi.action(a,"Éléments suivants ›");list.addView(v);v.setOnClickListener(w->{offset=next;refresh();});}}
    private void empty(String text){list.addView(MobileUi.text(a,text,16,MobileUi.MUTED));}
    private void fileRow(JSONObject row)throws Exception{
        String path=row.getString("path"),name=row.getString("name");boolean dir=row.getBoolean("directory");
        String detail=dir?"Dossier":android.text.format.Formatter.formatShortFileSize(a,row.getLong("size"))+" · "+row.getString("mime_type");
        TextView v=MobileUi.action(a,(dir?"▱  ":"▤  ")+name+"\n"+detail);v.setTextColor(MobileUi.TEXT);v.setMaxLines(3);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=MobileUi.dp(a,8);list.addView(v,params);
        v.setOnClickListener(w->{if(dir){folder=path;offset=0;search.setText("");refresh();}else fileMenu(path);});v.setOnLongClickListener(w->{fileMenu(path);return true;});
    }
    private void noteRow(JSONObject n)throws Exception{
        String id=n.getString("id");TextView v=MobileUi.action(a,(n.getBoolean("pinned")?"★  ":"▤  ")+n.getString("title")+"\n"+android.text.format.DateFormat.format("dd MMM · HH:mm",n.getLong("updated")));v.setTextColor(MobileUi.TEXT);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=MobileUi.dp(a,8);list.addView(v,p);v.setOnClickListener(w->openNote(id));
        v.setOnLongClickListener(w->{new AlertDialog.Builder(a).setTitle(n.optString("title")).setItems(new String[]{"Ouvrir","Mettre à la corbeille"},(d,i)->{if(i==0)openNote(id);else confirm("Mettre cette note à la corbeille ?",()->work(()->{new WorkspaceStore(a).deleteNote(id,true,n.getInt("revision"));return "Note dans la corbeille";}));}).show();return true;});
    }
    private void openNote(String id){a.startActivity(new Intent(a,NoteEditorActivity.class).putExtra("id",id));}
    private void createMenu(){new AlertDialog.Builder(a).setTitle("Ajouter dans ce dossier").setItems(new String[]{"Importer depuis le téléphone","Créer un dossier","Créer un document texte"},(d,i)->{if(i==0)importPicker();if(i==1)prompt("Nom du dossier","",name->work(()->{new WorkspaceStore(a).mkdir(WorkspacePaths.child(folder,name));return "Dossier créé";}));if(i==2)prompt("Nom du document","Document.txt",name->work(()->{new WorkspaceStore(a).writeText(WorkspacePaths.child(folder,name),"",false);return "Document créé";}));}).show();}
    private void options(){new AlertDialog.Builder(a).setTitle("Options").setItems(space.equals("files")?new String[]{"Actualiser","Téléchargements","Corbeille"}:new String[]{"Actualiser","Corbeille"},(d,i)->{if(i==0)refresh();else if(space.equals("files")&&i==1)downloads();else trash();}).show();}
    private void fileMenu(String path){boolean directory=false;try{directory=new WorkspaceStore(a).file(path).isDirectory();}catch(Exception ignored){}final boolean dir=directory;new AlertDialog.Builder(a).setTitle(path.substring(path.lastIndexOf('/')+1)).setItems(dir?new String[]{"Renommer","Déplacer","Mettre à la corbeille"}:new String[]{"Ouvrir","Partager","Exporter vers le téléphone","Renommer","Déplacer","Modifier le texte","Mettre à la corbeille"},(d,i)->{int choice=dir?new int[]{3,4,6}[i]:i;switch(choice){
        case 0:openFile(path,false);break;case 1:openFile(path,true);break;case 2:exportPicker(path);break;
        case 3:prompt("Nouveau nom",path.substring(path.lastIndexOf('/')+1),name->work(()->{String parent=path.contains("/")?path.substring(0,path.lastIndexOf('/')):"";new WorkspaceStore(a).move(path,WorkspacePaths.child(parent,name));return "Élément renommé";}));break;
        case 4:prompt("Chemin de destination","",name->work(()->{new WorkspaceStore(a).move(path,name);return "Élément déplacé";}));break;
        case 5:editText(path);break;
        case 6:confirm("Mettre cet élément à la corbeille ?",()->work(()->{new WorkspaceStore(a).trash(path);return "Élément dans la corbeille";}));break;
    }}).setNegativeButton("Fermer",null).show();}
    private void openFile(String path,boolean share){try{WorkspaceStore s=new WorkspaceStore(a);if(!share&&(s.mime(path).startsWith("image/")||s.mime(path).startsWith("video/")||s.mime(path).startsWith("audio/"))){a.startActivity(new Intent(a,MediaPreviewActivity.class).putExtra("path",path));return;}Uri u=s.uri(path);Intent intent=new Intent(share?Intent.ACTION_SEND:Intent.ACTION_VIEW);if(share){intent.setType(s.mime(path));intent.putExtra(Intent.EXTRA_STREAM,u);}else intent.setDataAndType(u,s.mime(path));intent.setClipData(ClipData.newRawUri("Fichier",u));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);a.startActivity(Intent.createChooser(intent,share?"Partager le fichier":"Ouvrir le fichier"));}catch(Exception e){toast("Aucune application compatible ou fichier inaccessible");}}
    private void importPicker(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);try{a.startActivityForResult(i,IMPORT);}catch(Exception e){toast("Sélecteur Android indisponible");}}
    private void exportPicker(String path){exportPath=path;try{WorkspaceStore s=new WorkspaceStore(a);Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(s.mime(path)).putExtra(Intent.EXTRA_TITLE,path.substring(path.lastIndexOf('/')+1));a.startActivityForResult(i,EXPORT);}catch(Exception e){toast(e.getMessage());}}
    public boolean result(int request,int result,Intent data){if(request!=IMPORT&&request!=EXPORT)return false;if(result!=Activity.RESULT_OK||data==null)return true;
        if(request==IMPORT){java.util.List<Uri> uris=new java.util.ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<Math.min(50,data.getClipData().getItemCount());i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());String destination=folder;
            work(()->{WorkspaceStore s=new WorkspaceStore(a);int count=0;for(Uri u:uris){String name="Fichier-"+System.nanoTime();try(Cursor c=a.getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}String path=WorkspacePaths.child(destination,name);if(s.file(path).exists())path=WorkspacePaths.child(destination,System.currentTimeMillis()+"-"+name);s.importUri(u,path);count++;}return count+" fichier(s) importé(s)";});
        }else {Uri target=data.getData();String path=exportPath;work(()->{try(InputStream in=new FileInputStream(new WorkspaceStore(a).file(path));OutputStream out=a.getContentResolver().openOutputStream(target)){if(out==null)throw new IOException("Destination inaccessible");byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}return "Fichier exporté";});}return true;
    }
    private void editText(String path){WorkspaceCommands.IO.execute(()->{try{JSONObject o=new WorkspaceStore(a).readText(path,0);if(o.getInt("next_offset")!=-1)throw new IOException("Édition locale limitée à 12 000 caractères");String body=o.getString("text");WorkspaceCommands.UI.post(()->{if(a.isFinishing())return;EditText input=new EditText(a);input.setText(body);input.setMinLines(8);input.setMaxLines(14);input.setGravity(android.view.Gravity.TOP);new AlertDialog.Builder(a).setTitle("Modifier "+path).setView(input).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(d,i)->work(()->{WorkspaceStore s=new WorkspaceStore(a);if(!s.readText(path,0).getString("text").equals(body))throw new IOException("Fichier modifié ailleurs : rouvre-le");s.writeText(path,input.getText().toString(),true);return "Document enregistré";})).show();});}catch(Exception e){WorkspaceCommands.UI.post(()->toast(e.getMessage()));}});}
    private void trash(){String mode=space;WorkspaceCommands.IO.execute(()->{try{WorkspaceStore s=new WorkspaceStore(a);JSONArray rows=mode.equals("files")?s.trashList():s.notes("",true,0);String[] names=new String[rows.length()];for(int i=0;i<rows.length();i++)names[i]=rows.getJSONObject(i).optString(mode.equals("files")?"path":"title");WorkspaceCommands.UI.post(()->{if(a.isFinishing())return;if(names.length==0){toast("La corbeille est vide");return;}new AlertDialog.Builder(a).setTitle("Toucher pour restaurer").setItems(names,(d,i)->work(()->{JSONObject row=rows.getJSONObject(i);WorkspaceStore store=new WorkspaceStore(a);if(mode.equals("files"))store.restore(row.getString("id"));else store.deleteNote(row.getString("id"),false,row.getInt("revision"));return "Élément restauré";})).setNegativeButton("Fermer",null).show();});}catch(Exception e){WorkspaceCommands.UI.post(()->toast(e.getMessage()));}});}
    private void downloads(){WorkspaceCommands.IO.execute(()->{File root=a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);File[] files=root==null?null:root.listFiles();WorkspaceCommands.UI.post(()->{if(a.isFinishing())return;if(files==null||files.length==0){toast("Aucun téléchargement");return;}String[] names=new String[files.length];for(int i=0;i<files.length;i++)names[i]=files[i].getName();String destination=folder;new AlertDialog.Builder(a).setTitle("Importer un téléchargement dans ce dossier").setItems(names,(d,i)->work(()->{WorkspaceStore s=new WorkspaceStore(a);String path=WorkspacePaths.child(destination,names[i]);return s.importDownload(names[i],path)+" importé";})).setNegativeButton("Fermer",null).show();});});}
    public boolean back(){if(!space.equals("files")||folder.isEmpty())return false;folder=folder.contains("/")?folder.substring(0,folder.lastIndexOf('/')):"";offset=0;search.setText("");refresh();return true;}
    public interface Job{String run()throws Exception;}
    private void work(Job job){toast("En cours…");WorkspaceCommands.IO.execute(()->{String result;try{result=job.run();}catch(Exception e){result=e.getMessage();}String message=result;WorkspaceCommands.UI.post(()->{if(a.isFinishing())return;toast(message);refresh();});});}
    private interface Name{void done(String text);}
    private void prompt(String title,String value,Name cb){EditText input=new EditText(a);input.setSingleLine(true);input.setText(value);input.selectAll();new AlertDialog.Builder(a).setTitle(title).setView(input).setNegativeButton("Annuler",null).setPositiveButton("Créer / enregistrer",(d,i)->cb.done(input.getText().toString())).show();}
    private void confirm(String title,Runnable action){new AlertDialog.Builder(a).setTitle(title).setMessage("Tu pourras le restaurer depuis Options > Corbeille.").setNegativeButton("Annuler",null).setPositiveButton("Mettre à la corbeille",(d,i)->action.run()).show();}
    private void toast(String s){Toast.makeText(a,s==null?"Opération impossible":s,Toast.LENGTH_SHORT).show();}
    public void destroy(){generation++;handler.removeCallbacksAndMessages(null);}
}
