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
    private LinearLayout list,legacyList,fileHeader,fileFooter;
    private View legacyScroller,filePane;
    private androidx.recyclerview.widget.RecyclerView recycler;
    private WorkspaceFileAdapter fileAdapter;
    private TextView selectionIndicator;
    private TextView location;
    private EditText search;
    private String folder="",space="files",exportPath="",importFolder="";
    private volatile int generation;
    private int offset,filesOffset,notesOffset;
    private String category="all",sort="name";
    private boolean grid=true,selectMode;
    private boolean batchRunning;
    private String filesQuery="",notesQuery="";
    private boolean filesSearch,notesSearch,disposed;
    private final java.util.Set<String> selected=new java.util.LinkedHashSet<>();
    private LinearLayout gridRow;private int gridColumn;
    private final android.os.Handler handler=new android.os.Handler();
    private final Runnable filter=()->{offset=0;refresh();};
    public WorkspacePanel(Activity a,FrameLayout host){
        this.a=a;this.host=host;
        android.content.SharedPreferences prefs=a.getSharedPreferences("workspace_ui",Activity.MODE_PRIVATE);
        grid=prefs.getBoolean("grid",true);sort=prefs.getString("sort","name");
    }
    private void rememberSearch(){
        if(search==null)return;
        if(space.equals("files")){filesQuery=search.getText().toString();filesSearch=search.getVisibility()==View.VISIBLE;filesOffset=offset;}
        else{notesQuery=search.getText().toString();notesSearch=search.getVisibility()==View.VISIBLE;notesOffset=offset;}
    }
    private void rememberDisplay(){a.getSharedPreferences("workspace_ui",Activity.MODE_PRIVATE).edit().putBoolean("grid",grid).putString("sort",sort).apply();}
    void saveState(android.os.Bundle out){
        rememberSearch();out.putString("workspace_folder",folder);out.putString("workspace_export",exportPath);out.putString("workspace_import",importFolder);out.putString("workspace_import_category",importCategory);out.putString("workspace_category",category);
        out.putString("workspace_files_query",filesQuery);out.putString("workspace_notes_query",notesQuery);
        out.putBoolean("workspace_files_search",filesSearch);out.putBoolean("workspace_notes_search",notesSearch);
        out.putInt("workspace_files_offset",filesOffset);out.putInt("workspace_notes_offset",notesOffset);
        out.putBoolean("workspace_grid",grid);out.putString("workspace_sort",sort);
    }
    void restoreState(android.os.Bundle state){
        if(state==null)return;folder=state.getString("workspace_folder","");exportPath=state.getString("workspace_export","");importFolder=state.getString("workspace_import",folder);importCategory=state.getString("workspace_import_category","all");category=state.getString("workspace_category","all");
        filesQuery=state.getString("workspace_files_query","");notesQuery=state.getString("workspace_notes_query","");
        filesSearch=state.getBoolean("workspace_files_search",false);notesSearch=state.getBoolean("workspace_notes_search",false);
        filesOffset=Math.max(0,state.getInt("workspace_files_offset",0));notesOffset=Math.max(0,state.getInt("workspace_notes_offset",0));
        grid=state.getBoolean("workspace_grid",grid);sort=state.getString("workspace_sort",sort);
    }
    private int ink(){return space.equals("files")?0xff202124:MobileUi.TEXT;}
    private int muted(){return space.equals("files")?0xff687080:MobileUi.MUTED;}
    private TextView label(String value,int size){return MobileUi.text(a,value,size,ink());}
    private TextView action(String value){TextView v=MobileUi.action(a,value);if(space.equals("files")){v.setTextColor(0xff2468cb);v.setBackground(MobileUi.bg(a,0xffedf3fd));}return v;}
    public void show(String value){
        rememberSearch();handler.removeCallbacks(filter);space=value;offset=space.equals("files")?filesOffset:notesOffset;generation++;selected.clear();selectMode=false;host.removeAllViews();
        FrameLayout frame=new FrameLayout(a);frame.setBackgroundColor(space.equals("files")?0xfffafbff:0xff202124);host.addView(frame,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout root=MobileUi.column(a);frame.addView(root,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout toolbar=new LinearLayout(a);toolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);toolbar.setBackgroundColor(space.equals("files")?0xfffafbff:0xff202124);root.addView(toolbar,new LinearLayout.LayoutParams(-1,MobileUi.dp(a,56)));
        TextView menu=UiIcons.button(a,"", "menu",ink());toolbar.addView(menu,new LinearLayout.LayoutParams(MobileUi.dp(a,48),-1));menu.setContentDescription("Réglages et espaces");menu.setOnClickListener(v->new AgentDialog.Builder(a).setTitle("CHK").setItems(new String[]{"Réglages","Corbeille","Téléchargements"},(d,n)->{if(n==0)a.startActivity(new Intent(a,SettingsActivity.class));else if(n==1)trash();else downloads();}).show());
        location=MobileUi.text(a,space.equals("files")?"Mes fichiers":"Mes notes",18,ink());location.setSingleLine(true);location.setPadding(0,0,0,0);toolbar.addView(location,new LinearLayout.LayoutParams(0,-2,1));
        TextView find=UiIcons.button(a,"","search",ink());find.setContentDescription("Rechercher");toolbar.addView(find,new LinearLayout.LayoutParams(MobileUi.dp(a,48),-1));
        TextView more=UiIcons.button(a,"","sort",ink());more.setContentDescription("Options et tri");toolbar.addView(more,new LinearLayout.LayoutParams(MobileUi.dp(a,48),-1));more.setOnClickListener(v->options());
        search=new EditText(a);search.setTag("workspace_search");search.setSingleLine(true);search.setTextColor(ink());search.setHintTextColor(muted());search.setBackgroundColor(space.equals("files")?0xffedf0f5:0xff30333b);search.setTextSize(16);search.setPadding(MobileUi.dp(a,16),0,MobileUi.dp(a,16),0);search.setText(space.equals("files")?filesQuery:notesQuery);search.setVisibility((space.equals("files")?filesSearch:notesSearch)?View.VISIBLE:View.GONE);root.addView(search,new LinearLayout.LayoutParams(-1,MobileUi.dp(a,52)));
        find.setOnClickListener(v->{search.setVisibility(search.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);if(search.getVisibility()==View.VISIBLE){search.requestFocus();((android.view.inputmethod.InputMethodManager)a.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(search,0);}else{search.setText("");((android.view.inputmethod.InputMethodManager)a.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);}});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int aft){}public void onTextChanged(CharSequence s,int st,int before,int count){handler.removeCallbacks(filter);handler.postDelayed(filter,180);}public void afterTextChanged(Editable e){}});
        FrameLayout contentHost=new FrameLayout(a);
        root.addView(contentHost,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView sc=new ScrollView(a);
        legacyScroller=sc;
        contentHost.addView(sc,new FrameLayout.LayoutParams(-1,-1));
        list=MobileUi.column(a);
        legacyList=list;
        list.setTag("workspace_content");
        list.addView(label("Chargement…",14));
        list.setPadding(MobileUi.dp(a,10),0,MobileUi.dp(a,10),MobileUi.dp(a,76));
        sc.addView(list);
        LinearLayout fileLayout=MobileUi.column(a);
        filePane=fileLayout;
        fileLayout.setVisibility(View.GONE);
        contentHost.addView(fileLayout,new FrameLayout.LayoutParams(-1,-1));
        fileHeader=MobileUi.column(a);
        fileLayout.addView(fileHeader,new LinearLayout.LayoutParams(-1,-2));
        recycler=new androidx.recyclerview.widget.RecyclerView(a);
        recycler.setTag("workspace_file_list");
        recycler.setHasFixedSize(false);
        recycler.setItemAnimator(null);
        recycler.setClipToPadding(false);
        recycler.setPadding(MobileUi.dp(a,8),0,MobileUi.dp(a,8),MobileUi.dp(a,6));
        androidx.recyclerview.widget.GridLayoutManager manager=
            new androidx.recyclerview.widget.GridLayoutManager(a,3);
        fileAdapter=new WorkspaceFileAdapter(a,selected,new WorkspaceFileAdapter.Actions(){
            public void openFolder(String path){
                folder=path;offset=0;search.setText("");refresh();
            }
            public void openFile(String path){WorkspacePanel.this.openFile(path,false);}
            public void menu(String path){fileMenu(path);}
            public void selectedChanged(){
                if(selectionIndicator!=null)
                    selectionIndicator.setText("✓ "+selected.size()+" sélectionné(s) · actions");
            }
        });
        manager.setSpanSizeLookup(new androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup(){
            @Override public int getSpanSize(int position){
                return fileAdapter.fullSpan(position)?3:1;
            }
        });
        recycler.setLayoutManager(manager);
        recycler.setAdapter(fileAdapter);
        fileLayout.addView(recycler,new LinearLayout.LayoutParams(-1,0,1));
        fileFooter=MobileUi.column(a);
        fileFooter.setPadding(MobileUi.dp(a,10),0,MobileUi.dp(a,10),MobileUi.dp(a,75));
        fileLayout.addView(fileFooter,new LinearLayout.LayoutParams(-1,-2));
        TextView add=MobileUi.action(a,space.equals("files")?"＋  Créer / Importer":"＋  Nouvelle note");add.setTextColor(0xffffffff);add.setBackground(MobileUi.bg(a,0xff087cf0));add.setElevation(MobileUi.dp(a,4));FrameLayout.LayoutParams floating=new FrameLayout.LayoutParams(-2,MobileUi.dp(a,50),android.view.Gravity.BOTTOM|android.view.Gravity.END);floating.setMargins(0,0,MobileUi.dp(a,14),MobileUi.dp(a,14));frame.addView(add,floating);add.setOnClickListener(v->{if(space.equals("files"))createMenu();else openNote("");});refresh();
    }
    private void showFileRows(){
        legacyScroller.setVisibility(View.GONE);
        filePane.setVisibility(View.VISIBLE);
        list=fileHeader;
    }
    private void showLegacyRows(){
        filePane.setVisibility(View.GONE);
        legacyScroller.setVisibility(View.VISIBLE);
        list=legacyList;
    }
    public void refresh(){
        if(list==null||disposed||a.isFinishing()||a.isDestroyed())return;final int ticket=++generation;String mode=space,p=folder,q=search.getText().toString(),requestedCategory=category,order=sort;int page=offset;
        search.setHint(mode.equals("files")?(p.isEmpty()&&category.equals("all")?"Rechercher un fichier ou un dossier":p.isEmpty()?"Rechercher dans "+categoryName(category):"Rechercher ici et dans les sous-dossiers"):"Rechercher dans les notes");
        if(mode.equals("files")&&p.isEmpty()&&category.equals("all")&&q.isEmpty()&&!selectMode){
            showLegacyRows();list.removeAllViews();location.setText("Mes fichiers");dashboard();return;
        }
        WorkspaceCommands.IO.execute(()->{try{WorkspaceStore s=new WorkspaceStore(a);JSONObject files=mode.equals("files")?WorkspaceCategoryView.query(a,p,requestedCategory,q,order,page):null;JSONArray notes=files==null?s.notes(q,false,page):null;
            WorkspaceCommands.UI.post(()->{if(disposed||a.isFinishing()||a.isDestroyed()||ticket!=generation)return;try{
                if(files!=null){
                    showFileRows();
                    fileHeader.removeAllViews();
                    fileFooter.removeAllViews();
                    selectionIndicator=null;
                    location.setText(category.equals("all")?(p.isEmpty()?"Mes fichiers":p):categoryName(category));
                    if(selectMode)selectionBar();
                    breadcrumbs();
                    JSONArray rows=files.getJSONArray("items");
                    fileAdapter.submit(rows,grid,selectMode,ticket);
                    list=fileFooter;
                    if(files.optBoolean("truncated"))empty("Affichage limité aux 5 000 premiers éléments analysés.");
                    if(rows.length()==0)empty(q.isEmpty()?"Aucun élément ici. Appuie sur Créer / Importer pour ajouter un dossier ou un fichier.":"Aucun résultat dans cette catégorie.");
                    pager(files.getInt("next_offset"));
                    list=fileHeader;
                }else{
                    showLegacyRows();
                    list.removeAllViews();
                    for(int i=0;i<notes.length();i++)noteRow(notes.getJSONObject(i));
                    if(notes.length()==0)empty(q.isEmpty()?"Une idée à garder ? Crée ta première note.":"Aucune note ne correspond à ta recherche.");
                    pager(notes.length()==50?page+50:-1);
                }
            }catch(Exception e){showLegacyRows();list.removeAllViews();empty("Affichage impossible");}});
        }catch(Exception e){WorkspaceCommands.UI.post(()->{if(ticket==generation){list.removeAllViews();empty(e.getMessage());}});}});
    }
    private void pager(int next){if(offset>0){TextView prev=action("‹ Éléments précédents");list.addView(prev);prev.setOnClickListener(v->{offset=Math.max(0,offset-50);refresh();});}if(next>=0){TextView v=action("Éléments suivants ›");list.addView(v);v.setOnClickListener(w->{offset=next;refresh();});}}
    private void empty(String text){list.addView(MobileUi.text(a,text,16,muted()));}
    private String categoryName(String c){switch(c){case "video":return "Vidéos";case "image":return "Images";case "audio":return "Audio";case "document":return "Documents";case "archive":return "Archives";case "recent":return "Récents";default:return "Autres fichiers";}}
    private void breadcrumbs(){
        LinearLayout line=new LinearLayout(a);line.setGravity(android.view.Gravity.CENTER_VERTICAL);list.addView(line,new LinearLayout.LayoutParams(-1,MobileUi.dp(a,48)));
        TextView home=label("⌂  Accueil",14);home.setMinHeight(MobileUi.dp(a,48));line.addView(home);home.setOnClickListener(v->{folder="";category="all";offset=0;search.setText("");refresh();});
        TextView path=label(folder.isEmpty()?(category.equals("all")?"":"› "+categoryName(category)):
            "› "+categoryName(category)+" / "+folder.substring(folder.lastIndexOf('/')+1),13);
        path.setSingleLine(true);line.addView(path,new LinearLayout.LayoutParams(0,-2,1));
        path.setOnClickListener(v->upFolder());
        TextView toggle=UiIcons.button(a,"",grid?"grid":"sort",0xff2b6fd6);toggle.setContentDescription("Basculer entre grille et liste");line.addView(toggle,new LinearLayout.LayoutParams(MobileUi.dp(a,48),-1));toggle.setOnClickListener(v->{grid=!grid;rememberDisplay();refresh();});
    }
    private void dashboard(){
        // Accueil : les six catégories uniquement, sans dossiers ni liste brute.
        String[] keys={"image","video","audio","document","downloads","recent"};String[] names={"Images","Vidéos","Audio","Documents","Téléchargements","Récents"};String[] icons={"image","video","audio","document","download","history"};int[] colors={0xff16ad4e,0xfffc3856,0xff9c5bdb,0xff2684f4,0xff11b559,0xffff9a1f};
        LinearLayout row=null;for(int i=0;i<keys.length;i++){if(i%3==0){row=new LinearLayout(a);list.addView(row);}String key=keys[i];TextView cell=UiIcons.button(a,names[i],icons[i],colors[i]);cell.setTag("files_category_"+key);cell.setTextColor(ink());cell.setBackground(MobileUi.bg(a,0xfff2f4f9));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,MobileUi.dp(a,76),1);lp.setMargins(MobileUi.dp(a,3),MobileUi.dp(a,5),MobileUi.dp(a,3),MobileUi.dp(a,5));row.addView(cell,lp);cell.setOnClickListener(v->{if(key.equals("downloads")){downloads();return;}category=key;folder="";offset=0;search.setText("");refresh();});}
    }
    private void fileRow(JSONObject row)throws Exception{
        String path=row.getString("path"),name=row.getString("name"),mime=row.getString("mime_type");boolean dir=row.getBoolean("directory");
        boolean tile=grid&&!dir;LinearLayout card=new LinearLayout(a);card.setOrientation(tile?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);card.setGravity(android.view.Gravity.CENTER_VERTICAL);card.setPadding(MobileUi.dp(a,4),MobileUi.dp(a,4),MobileUi.dp(a,4),MobileUi.dp(a,4));card.setBackground(MobileUi.bg(a,selected.contains(path)?0xffd7eaff:0xffffffff));card.setContentDescription((dir?"Dossier ":"Fichier ")+name);card.setFocusable(true);
        ImageView thumb=new ImageView(a);thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);thumb.setImageDrawable(UiIcons.icon(a,dir?"folder":mime.startsWith("video/")?"video":mime.startsWith("image/")?"image":mime.startsWith("audio/")?"audio":"document",dir?0xfffbbc04:0xff4e83c2,dir?34:28));thumb.setContentDescription(name);card.addView(thumb,new LinearLayout.LayoutParams(tile?-1:MobileUi.dp(a,46),MobileUi.dp(a,tile?76:44)));
        LinearLayout info=MobileUi.column(a);card.addView(info,new LinearLayout.LayoutParams(tile?-1:0,tile?-2:-1,tile?0:1));
        TextView text=label((selected.contains(path)?"✓ ":"")+name,tile?12:15);text.setMaxLines(tile?2:1);text.setPadding(MobileUi.dp(a,tile?1:8),MobileUi.dp(a,3),0,0);info.addView(text);
        TextView detail=MobileUi.text(a,dir?"Dossier":android.text.format.Formatter.formatShortFileSize(a,row.getLong("size")),11,muted());detail.setPadding(MobileUi.dp(a,tile?1:8),MobileUi.dp(a,2),0,0);info.addView(detail);
        if(tile){if(gridRow==null||gridColumn==3){gridRow=new LinearLayout(a);list.addView(gridRow);gridColumn=0;}LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,MobileUi.dp(a,132),1);lp.setMargins(MobileUi.dp(a,3),MobileUi.dp(a,4),MobileUi.dp(a,3),MobileUi.dp(a,4));gridRow.addView(card,lp);gridColumn++;}
        else{if(gridRow!=null&&gridColumn<3){for(int i=gridColumn;i<3;i++)gridRow.addView(new View(a),new LinearLayout.LayoutParams(0,1,1));}gridRow=null;gridColumn=0;if(dir){TextView next=label("›",24);card.addView(next);}LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,MobileUi.dp(a,62));lp.bottomMargin=MobileUi.dp(a,2);list.addView(card,lp);}
        card.setOnClickListener(v->{
            if(selectMode){
                if(selected.contains(path))selected.remove(path);else selected.add(path);
                // Update the affected card only: do not reset scrolling or decode thumbnails again.
                boolean checked=selected.contains(path);
                card.setBackground(MobileUi.bg(a,checked?0xffd7eaff:0xffffffff));
                text.setText((checked?"✓ ":"")+name);
                if(selectionIndicator!=null)selectionIndicator.setText("✓ "+selected.size()+" sélectionné(s) · actions");
            }else if(dir){
                folder=path;offset=0;search.setText("");refresh();
            }else openFile(path,false);
        });card.setOnLongClickListener(v->{fileMenu(path);return true;});
        if(!dir&&(mime.startsWith("image/")||mime.startsWith("video/"))){final int ticket=generation;ThumbnailLoader.load(a,path,0,mime.startsWith("video/"),new ThumbnailLoader.Result(){public boolean current(){return ticket==generation&&!a.isFinishing();}public void ready(android.graphics.Bitmap b){thumb.setImageBitmap(b);}});}
    }
    private void selectionBar(){
        TextView action=action("✓ "+selected.size()+" sélectionné(s) · actions");list.addView(action);
        selectionIndicator=action;
        action.setOnClickListener(v->{
            if(selected.isEmpty()){toast("Sélectionne un fichier ou un dossier");return;}
            if(batchRunning){toast("Opération en cours…");return;}
            new AgentDialog.Builder(a).setTitle("Sélection").setItems(new String[]{"Copier les fichiers dans un dossier","Déplacer dans un dossier","Mettre à la corbeille","Terminer la sélection"},(d,n)->{
                java.util.List<String> paths=new java.util.ArrayList<>(selected);
                if(n==3){selectMode=false;selected.clear();refresh();}
                else if(n==2)confirm("Mettre les éléments sélectionnés à la corbeille ?",()->selectionBatch(paths,n,""));
                else prompt("Dossier de destination (vide = racine)","",target->selectionBatch(paths,n,target));
            }).show();
        });
    }
    private void selectionBatch(java.util.List<String> paths,int operation,String destination){
        if(batchRunning)return;batchRunning=true;toast("En cours…");
        WorkspaceCommands.IO.execute(()->{
            JSONObject report;
            try{report=WorkspaceBatch.run(a.getApplicationContext(),paths,operation,destination);}
            catch(Exception e){WorkspaceCommands.UI.post(()->{batchRunning=false;if(!disposed&&!a.isFinishing()&&!a.isDestroyed())toast(e.getMessage());});return;}
            WorkspaceCommands.UI.post(()->{
                batchRunning=false;if(disposed||a.isFinishing()||a.isDestroyed())return;
                JSONArray succeeded=report.optJSONArray("succeeded"),failed=report.optJSONArray("failed");
                if(space.equals("files")){for(int i=0;i<succeeded.length();i++)selected.remove(succeeded.optString(i));selectMode=!selected.isEmpty();}
                toast(succeeded.length()+" / "+paths.size()+" élément(s) traité(s)"+
                    (failed.length()==0?"":" · "+failed.length()+" échec(s) : "+failed.optJSONObject(0).optString("error")));
                refresh();
            });
        });
    }
    private void noteRow(JSONObject n)throws Exception{
        String id=n.getString("id");TextView v=action((n.getBoolean("pinned")?"★  ":"▤  ")+n.getString("title")+"\n"+android.text.format.DateFormat.format("dd MMM · HH:mm",n.getLong("updated")));v.setTextColor(ink());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=MobileUi.dp(a,8);list.addView(v,p);v.setOnClickListener(w->openNote(id));
        v.setOnLongClickListener(w->{new AgentDialog.Builder(a).setTitle(n.optString("title")).setItems(new String[]{"Ouvrir","Mettre à la corbeille"},(d,i)->{if(i==0)openNote(id);else confirm("Mettre cette note à la corbeille ?",()->work(()->{new WorkspaceStore(a).deleteNote(id,true,n.getInt("revision"));return "Note dans la corbeille";}));}).show();return true;});
    }
    private void openNote(String id){a.startActivity(new Intent(a,NoteEditorActivity.class).putExtra("id",id));}
    private void chooseCategory(Runnable next){
        new AgentDialog.Builder(a).setTitle("Dans quelle catégorie ?")
            .setItems(new String[]{"Images","Vidéos","Audio","Documents"},(d,i)->{
                category=new String[]{"image","video","audio","document"}[i];
                folder="";offset=0;search.setText("");refresh();next.run();
            }).setNegativeButton("Annuler",null).show();
    }
    private boolean needsCategory(){
        return folder.isEmpty()&&!WorkspaceCategoryView.isContainerCategory(category);
    }
    private void createMenu(){
        if(needsCategory()){chooseCategory(this::createMenu);return;}
        String targetFolder=folder,targetCategory=category;
        new AgentDialog.Builder(a).setTitle("Ajouter dans "+(folder.isEmpty()?categoryName(category):folder))
            .setItems(new String[]{"Importer depuis le téléphone","Créer un dossier","Créer un document texte"},
                (d,i)->{
                    if(i==0){importPicker();return;}
                    if(i==1)prompt("Nom du dossier","",name->work(()->{
                        WorkspaceStore s=new WorkspaceStore(a);
                        String dest=WorkspaceCategoryView.destination(s,targetCategory,targetFolder);
                        s.mkdir(WorkspacePaths.child(dest,name));return "Dossier créé dans "+categoryName(targetCategory);
                    }));
                    if(i==2)prompt("Nom du document","Document.txt",name->work(()->{
                        WorkspaceStore s=new WorkspaceStore(a);
                        String dest=WorkspaceCategoryView.destination(s,targetCategory,targetFolder);
                        s.writeText(WorkspacePaths.child(dest,name),"",false);return "Document créé";
                    }));
                }).show();
    }
    private void options(){
        boolean home=space.equals("files")&&folder.isEmpty()&&"all".equals(category);
        String[] choices=!space.equals("files")?new String[]{"Actualiser","Corbeille"}:
            home?new String[]{"Actualiser","Téléchargements","Corbeille"}:
            new String[]{"Actualiser","Téléchargements","Corbeille",
                grid?"Afficher en liste":"Afficher en grille","Trier","Sélectionner plusieurs"};
        new AgentDialog.Builder(a).setTitle("Options").setItems(choices,(d,i)->{
            if(i==0)refresh();
            else if(!space.equals("files"))trash();
            else if(i==1)downloads();
            else if(i==2)trash();
            else if(i==3){grid=!grid;rememberDisplay();refresh();}
            else if(i==4)new AgentDialog.Builder(a).setTitle("Trier par")
                .setItems(new String[]{"Nom","Plus récents","Plus volumineux"},(x,n)->{
                    sort=new String[]{"name","date","size"}[n];rememberDisplay();offset=0;refresh();
                }).show();
            else{selectMode=true;selected.clear();refresh();}
        }).show();
    }
    private void fileMenu(String path){boolean directory=false;try{directory=new WorkspaceStore(a).file(path).isDirectory();}catch(Exception ignored){}final boolean dir=directory;new AgentDialog.Builder(a).setTitle(path.substring(path.lastIndexOf('/')+1)).setItems(dir?new String[]{"Renommer","Déplacer","Mettre à la corbeille"}:new String[]{"Ouvrir","Partager","Exporter vers le téléphone","Renommer","Déplacer","Modifier le texte","Mettre à la corbeille","Copier","Monter dans Studio","Informations du média"},(d,i)->{int choice=dir?new int[]{3,4,6}[i]:i;switch(choice){
        case 0:openFile(path,false);break;case 1:openFile(path,true);break;case 2:exportPicker(path);break;
        case 3:prompt("Nouveau nom",path.substring(path.lastIndexOf('/')+1),name->work(()->{String parent=path.contains("/")?path.substring(0,path.lastIndexOf('/')):"";new WorkspaceStore(a).move(path,WorkspacePaths.child(parent,name));return "Élément renommé";}));break;
        case 4:prompt("Chemin de destination","",name->work(()->{new WorkspaceStore(a).move(path,name);return "Élément déplacé";}));break;
        case 5:editText(path);break;
        case 7:prompt("Destination de la copie","",destination->work(()->{WorkspaceCatalog.copy(a,path,destination);return "Fichier copié";}));break;
        case 8:a.startActivity(new Intent(a,VideoEditorActivity.class).putExtra("add_path",path));break;
        case 9:WorkspaceCommands.IO.execute(()->{try{String info=MediaInspection.info(a,path).toString(2);WorkspaceCommands.UI.post(()->new AgentDialog.Builder(a).setTitle("Informations").setMessage(info).setPositiveButton("OK",null).show());}catch(Exception e){WorkspaceCommands.UI.post(()->toast(e.getMessage()));}});break;
        case 6:confirm("Mettre cet élément à la corbeille ?",()->work(()->{new WorkspaceStore(a).trash(path);return "Élément dans la corbeille";}));break;
    }}).setNegativeButton("Fermer",null).show();}
    private void openFile(String path,boolean share){try{WorkspaceStore s=new WorkspaceStore(a);if(!share&&(s.mime(path).startsWith("image/")||s.mime(path).startsWith("video/")||s.mime(path).startsWith("audio/"))){a.startActivity(new Intent(a,MediaPreviewActivity.class).putExtra("path",path));return;}Uri u=s.uri(path);Intent intent=new Intent(share?Intent.ACTION_SEND:Intent.ACTION_VIEW);if(share){intent.setType(s.mime(path));intent.putExtra(Intent.EXTRA_STREAM,u);}else intent.setDataAndType(u,s.mime(path));intent.setClipData(ClipData.newRawUri("Fichier",u));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);a.startActivity(Intent.createChooser(intent,share?"Partager le fichier":"Ouvrir le fichier"));}catch(Exception e){toast("Aucune application compatible ou fichier inaccessible");}}
    private String importCategory="all";
    private void importPicker(){
        if(needsCategory()){chooseCategory(this::importPicker);return;}
        importFolder=folder;importCategory=category;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType(folder.isEmpty()&&"video".equals(category)?"video/*":
                folder.isEmpty()&&"image".equals(category)?"image/*":
                folder.isEmpty()&&"audio".equals(category)?"audio/*":"*/*")
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        try{a.startActivityForResult(i,IMPORT);}catch(Exception e){toast("Sélecteur Android indisponible");}
    }
    private void exportPicker(String path){exportPath=path;try{WorkspaceStore s=new WorkspaceStore(a);Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(s.mime(path)).putExtra(Intent.EXTRA_TITLE,path.substring(path.lastIndexOf('/')+1));a.startActivityForResult(i,EXPORT);}catch(Exception e){toast(e.getMessage());}}
    public boolean result(int request,int result,Intent data){if(request!=IMPORT&&request!=EXPORT)return false;if(result!=Activity.RESULT_OK||data==null)return true;
        if(request==IMPORT){java.util.List<Uri> uris=new java.util.ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<Math.min(50,data.getClipData().getItemCount());i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());String requestedFolder=importFolder,requestedCategory=importCategory;
            work(()->{WorkspaceStore s=new WorkspaceStore(a);String destination=WorkspaceCategoryView.destination(s,requestedCategory,requestedFolder);int count=0;java.util.List<String> failures=new java.util.ArrayList<>();
                for(Uri u:uris){try{String name="Fichier-"+System.nanoTime();try(Cursor c=a.getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}
                    File parent=WorkspacePaths.resolve(new File(a.getFilesDir(),"workspace"),destination,true);
                    String path=WorkspacePaths.child(destination,FileNames.available(parent,name,null));s.importUri(u,path);count++;
                }catch(Exception e){failures.add(e.getMessage()==null?"fichier inaccessible":e.getMessage());}}
                return count+" fichier(s) importé(s)"+(failures.isEmpty()?"":" · "+failures.size()+" échec(s) : "+failures.get(0));});
        }else {Uri target=data.getData();String path=exportPath;work(()->{try(InputStream in=new FileInputStream(new WorkspaceStore(a).file(path));OutputStream out=a.getContentResolver().openOutputStream(target)){if(out==null)throw new IOException("Destination inaccessible");byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}return "Fichier exporté";});}return true;
    }
    private void editText(String path){WorkspaceCommands.IO.execute(()->{try{JSONObject o=new WorkspaceStore(a).readText(path,0);if(o.getInt("next_offset")!=-1)throw new IOException("Édition locale limitée à 12 000 caractères");String body=o.getString("text");WorkspaceCommands.UI.post(()->{if(a.isFinishing())return;EditText input=new EditText(a);input.setText(body);input.setMinLines(8);input.setMaxLines(14);input.setGravity(android.view.Gravity.TOP);new AgentDialog.Builder(a).setTitle("Modifier "+path).setView(input).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(d,i)->work(()->{WorkspaceStore s=new WorkspaceStore(a);if(!s.readText(path,0).getString("text").equals(body))throw new IOException("Fichier modifié ailleurs : rouvre-le");s.writeText(path,input.getText().toString(),true);return "Document enregistré";})).show();});}catch(Exception e){WorkspaceCommands.UI.post(()->toast(e.getMessage()));}});}
    private void trash(){String mode=space;WorkspaceCommands.IO.execute(()->{try{WorkspaceStore s=new WorkspaceStore(a);JSONArray rows=mode.equals("files")?s.trashList():s.notes("",true,0);String[] names=new String[rows.length()];for(int i=0;i<rows.length();i++)names[i]=rows.getJSONObject(i).optString(mode.equals("files")?"path":"title");WorkspaceCommands.UI.post(()->{if(a.isFinishing())return;if(names.length==0){toast("La corbeille est vide");return;}new AgentDialog.Builder(a).setTitle("Toucher pour restaurer").setItems(names,(d,i)->work(()->{JSONObject row=rows.getJSONObject(i);WorkspaceStore store=new WorkspaceStore(a);if(mode.equals("files"))store.restore(row.getString("id"));else store.deleteNote(row.getString("id"),false,row.getInt("revision"));return "Élément restauré";})).setNegativeButton("Fermer",null).show();});}catch(Exception e){WorkspaceCommands.UI.post(()->toast(e.getMessage()));}});}
    private void downloads(){
        WorkspaceCommands.IO.execute(()->{
            final java.util.List<String> names=new java.util.ArrayList<>();
            final java.util.List<String> labels=new java.util.ArrayList<>();
            try{
                JSONArray files=new WorkspaceStore(a).downloads();
                for(int i=0;i<files.length();i++){
                    JSONObject file=files.getJSONObject(i);names.add(file.getString("name"));
                    labels.add(file.getString("name")+" · "+android.text.format.Formatter.formatShortFileSize(a,file.getLong("size")));
                }
            }catch(Exception e){WorkspaceCommands.UI.post(()->{if(!disposed&&!a.isFinishing()&&!a.isDestroyed())toast(e.getMessage());});return;}
            WorkspaceCommands.UI.post(()->{
                if(disposed||a.isFinishing()||a.isDestroyed())return;
                if(names.isEmpty()){toast("Aucun téléchargement terminé. Les fichiers en cours apparaîtront à la fin.");return;}
                new AgentDialog.Builder(a).setTitle("Téléchargements terminés · importer")
                    .setItems(labels.toArray(new String[0]),(d,i)->{
                        String name=names.get(i);
                        if(needsCategory())chooseCategory(()->importSelectedDownload(name));
                        else importSelectedDownload(name);
                    }).setNegativeButton("Fermer",null).show();
            });
        });
    }
    private void importSelectedDownload(String name){
        String targetFolder=folder,targetCategory=category;
        work(()->{
            WorkspaceStore s=new WorkspaceStore(a);
            String dest=WorkspaceCategoryView.destination(s,targetCategory,targetFolder);
            String path=WorkspacePaths.child(dest,FileNames.available(s.file(dest),name,null));
            return s.importDownload(name,path)+" importé";
        });
    }

    private void upFolder(){
        if(!folder.isEmpty()){
            String parent=folder.contains("/")?folder.substring(0,folder.lastIndexOf('/')):"";
            folder=parent.equals(WorkspaceCategoryView.isContainerCategory(category)?
                WorkspaceCategoryView.storageRoot(category):"")?"":parent;
        }else{category="all";}
        offset=0;search.setText("");refresh();
    }
    public boolean back(){
        if(search!=null&&(search.getVisibility()==View.VISIBLE||search.length()>0)){
            search.setText("");handler.removeCallbacks(filter);search.setVisibility(View.GONE);search.clearFocus();offset=0;
            ((android.view.inputmethod.InputMethodManager)a.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);
            refresh();return true;
        }
        if(!space.equals("files"))return false;
        if(selectMode){selectMode=false;selected.clear();refresh();return true;}
        if(folder.isEmpty()&&"all".equals(category))return false;
        upFolder();return true;
    }
    public interface Job{String run()throws Exception;}
    private void work(Job job){toast("En cours…");WorkspaceCommands.IO.execute(()->{String result;try{result=job.run();}catch(Exception e){result=e.getMessage();}String message=result;WorkspaceCommands.UI.post(()->{if(disposed||a.isFinishing()||a.isDestroyed())return;toast(message);refresh();});});}
    private interface Name{void done(String text);}
    private void prompt(String title,String value,Name cb){EditText input=new EditText(a);input.setSingleLine(true);input.setText(value);input.selectAll();new AgentDialog.Builder(a).setTitle(title).setView(input).setNegativeButton("Annuler",null).setPositiveButton("Créer / enregistrer",(d,i)->cb.done(input.getText().toString())).show();}
    private void confirm(String title,Runnable action){new AgentDialog.Builder(a).setTitle(title).setMessage("Tu pourras le restaurer depuis Options > Corbeille.").setNegativeButton("Annuler",null).setPositiveButton("Mettre à la corbeille",(d,i)->action.run()).show();}
    private void toast(String s){Toast.makeText(a,s==null?"Opération impossible":s,Toast.LENGTH_SHORT).show();}
    public void destroy(){
        disposed=true;generation++;handler.removeCallbacksAndMessages(null);
        if(recycler!=null)recycler.setAdapter(null);
    }
}
