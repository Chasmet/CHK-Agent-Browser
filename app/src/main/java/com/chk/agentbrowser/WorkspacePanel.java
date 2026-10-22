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
    private static final int IMPORT=701,EXPORT=702,EXPORT_FOLDER=703;
    private final Activity a;
    private final FrameLayout host;
    private LinearLayout list,legacyList,fileHeader,fileFooter;
    private View legacyScroller,filePane;
    private androidx.recyclerview.widget.RecyclerView recycler;
    private WorkspaceFileAdapter fileAdapter;
    private WorkspaceAudioBar audioBar;
    private LinearLayout selectionToolbar;
    private TextView floatingButton;
    private FrameLayout.LayoutParams floatingParams;
    private TextView selectionIndicator;
    private TextView location;
    private EditText search;
    private String folder="",space="files",exportPath="",importFolder="";
    private java.util.List<String> pendingPhoneExport=new java.util.ArrayList<>();
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
        rememberSearch();handler.removeCallbacks(filter);
        if(audioBar!=null){audioBar.destroy();audioBar=null;}
        floatingButton=null;floatingParams=null;selectionToolbar=null;
        space=value;offset=space.equals("files")?filesOffset:notesOffset;generation++;selected.clear();selectMode=false;host.removeAllViews();
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
        if(space.equals("files")){
            LinearLayout controls=MobileUi.column(a);
            root.addView(controls,new LinearLayout.LayoutParams(-1,-2));
            audioBar=new WorkspaceAudioBar(a,this::updateFloating);
            controls.addView(audioBar.view(),new LinearLayout.LayoutParams(-1,-2));
            selectionToolbar=MobileUi.column(a);
            selectionToolbar.setTag("workspace_selection_toolbar");
            selectionToolbar.setVisibility(View.GONE);
            controls.addView(selectionToolbar,new LinearLayout.LayoutParams(-1,-2));
        }
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
            new androidx.recyclerview.widget.GridLayoutManager(a,2);
        fileAdapter=new WorkspaceFileAdapter(a,selected,new WorkspaceFileAdapter.Actions(){
            public void openFolder(String path){
                folder=path;offset=0;search.setText("");refresh();
            }
            public void openFile(String path){WorkspacePanel.this.openFile(path,false);}
            public void menu(String path){fileMenu(path);}
            public void startSelection(String path){
                if(!selectMode){selectMode=true;selected.clear();}
                selected.add(path);
                fileAdapter.selectionMode(true);
                updateSelectionToolbar();
            }
            public void selectedChanged(){updateSelectionToolbar();}
        });
        manager.setSpanSizeLookup(new androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup(){
            @Override public int getSpanSize(int position){
                return fileAdapter.fullSpan(position)?2:1;
            }
        });
        recycler.setLayoutManager(manager);
        recycler.setAdapter(fileAdapter);
        fileLayout.addView(recycler,new LinearLayout.LayoutParams(-1,0,1));
        fileFooter=MobileUi.column(a);
        fileFooter.setPadding(MobileUi.dp(a,10),0,MobileUi.dp(a,10),MobileUi.dp(a,75));
        fileLayout.addView(fileFooter,new LinearLayout.LayoutParams(-1,-2));
        TextView add=MobileUi.action(a,space.equals("files")?"＋  Créer / Importer":"＋  Nouvelle note");
        add.setTextColor(0xffffffff);add.setBackground(MobileUi.bg(a,0xff087cf0));add.setElevation(MobileUi.dp(a,4));
        FrameLayout.LayoutParams floating=new FrameLayout.LayoutParams(-2,MobileUi.dp(a,50),android.view.Gravity.BOTTOM|android.view.Gravity.END);
        floating.setMargins(0,0,MobileUi.dp(a,14),MobileUi.dp(a,14));
        floatingButton=add;floatingParams=floating;
        frame.addView(add,floating);
        add.setOnClickListener(v->{if(space.equals("files"))createMenu();else openNote("");});
        updateFloating();refresh();
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
                    if(selectMode)updateSelectionToolbar();
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
        LinearLayout line=new LinearLayout(a);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);
        list.addView(line,new LinearLayout.LayoutParams(-1,MobileUi.dp(a,48)));
        TextView home=label("⌂  Accueil",14);
        home.setMinHeight(MobileUi.dp(a,48));
        home.setGravity(android.view.Gravity.CENTER_VERTICAL);
        home.setPadding(MobileUi.dp(a,8),0,MobileUi.dp(a,6),0);
        line.addView(home);
        home.setOnClickListener(v->{folder="";category="all";offset=0;search.setText("");refresh();});
        HorizontalScrollView scroll=new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        line.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout segments=new LinearLayout(a);
        segments.setGravity(android.view.Gravity.CENTER_VERTICAL);
        scroll.addView(segments);
        if(!"all".equals(category)){
            TextView group=label(" › "+categoryName(category),13);
            group.setMinHeight(MobileUi.dp(a,46));
            group.setGravity(android.view.Gravity.CENTER_VERTICAL);
            segments.addView(group);
            group.setOnClickListener(v->{folder="";offset=0;search.setText("");refresh();});
        }
        String path=folder;
        if(!path.isEmpty()){
            String base=WorkspaceCategoryView.isContainerCategory(category)?
                WorkspaceCategoryView.storageRoot(category):"";
            if(!base.isEmpty()&&(path.equals(base)||path.startsWith(base+"/")))
                path=path.substring(base.length()).replaceFirst("^/","");
            else base="";
            String accumulating=base;
            for(String segment:path.split("/")){
                if(segment.isEmpty())continue;
                accumulating=accumulating.isEmpty()?segment:accumulating+"/"+segment;
                final String destination=accumulating;
                TextView crumb=label(" › "+segment,13);
                crumb.setMinHeight(MobileUi.dp(a,46));
                crumb.setGravity(android.view.Gravity.CENTER_VERTICAL);
                segments.addView(crumb);
                crumb.setOnClickListener(v->{folder=destination;offset=0;search.setText("");refresh();});
            }
            scroll.post(()->scroll.fullScroll(View.FOCUS_RIGHT));
        }
        TextView toggle=UiIcons.button(a,"",grid?"grid":"sort",0xff2b6fd6);
        toggle.setContentDescription("Basculer entre grille et liste");
        line.addView(toggle,new LinearLayout.LayoutParams(MobileUi.dp(a,48),-1));
        toggle.setOnClickListener(v->{grid=!grid;rememberDisplay();refresh();});
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
    private void updateFloating(){
        if(floatingButton==null||floatingParams==null)return;
        floatingButton.setVisibility(space.equals("files")&&selectMode?View.GONE:View.VISIBLE);
        floatingParams.bottomMargin=MobileUi.dp(a,
            space.equals("files")&&audioBar!=null&&audioBar.visible()?76:14);
        floatingButton.setLayoutParams(floatingParams);
    }
    private TextView batchButton(String caption,Runnable onClick){
        TextView button=action(caption);
        button.setGravity(android.view.Gravity.CENTER);
        button.setMinHeight(MobileUi.dp(a,46));
        button.setPadding(MobileUi.dp(a,13),0,MobileUi.dp(a,13),0);
        button.setOnClickListener(v->onClick.run());
        return button;
    }
    private void endSelection(){
        selected.clear();selectMode=false;
        if(fileAdapter!=null)fileAdapter.selectionMode(false);
        updateSelectionToolbar();
    }
    private void updateSelectionToolbar(){
        if(selectionToolbar==null)return;
        selectionToolbar.removeAllViews();
        selectionToolbar.setVisibility(selectMode?View.VISIBLE:View.GONE);
        updateFloating();
        if(!selectMode)return;
        selectionToolbar.setBackgroundColor(0xffe9f1ff);
        LinearLayout heading=new LinearLayout(a);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        heading.setPadding(MobileUi.dp(a,12),0,MobileUi.dp(a,8),0);
        selectionToolbar.addView(heading,new LinearLayout.LayoutParams(-1,MobileUi.dp(a,38)));
        TextView count=label(selected.size()+" sélectionné(s)",15);
        count.setTag("workspace_selected_count");
        heading.addView(count,new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(batchButton("✕  Fermer",this::endSelection));
        LinearLayout actions=new LinearLayout(a);
        actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
        selectionToolbar.addView(actions,new LinearLayout.LayoutParams(-1,MobileUi.dp(a,52)));
        HorizontalScrollView scroll=new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        actions.addView(scroll,new LinearLayout.LayoutParams(0,MobileUi.dp(a,52),1));
        LinearLayout buttons=new LinearLayout(a);
        buttons.setGravity(android.view.Gravity.CENTER_VERTICAL);
        buttons.setPadding(MobileUi.dp(a,7),0,MobileUi.dp(a,7),0);
        scroll.addView(buttons);
        buttons.addView(batchButton("Partager",this::shareSelected));
        buttons.addView(batchButton("Copier",()->batchAction(0)));
        buttons.addView(batchButton("Déplacer",()->batchAction(1)));
        TextView rename=batchButton("Renommer",()->{
            if(selected.size()==1)renameItem(selected.iterator().next());
        });
        rename.setAlpha(selected.size()==1?1f:0.4f);
        rename.setEnabled(selected.size()==1);
        buttons.addView(rename);
        buttons.addView(batchButton("Corbeille",()->batchAction(2)));
        TextView more=batchButton("⋮ Plus",this::selectionMore);
        more.setTag("workspace_selection_more");
        more.setContentDescription("Plus d'options : partager, enregistrer sur le téléphone, ouvrir avec et propriétés");
        actions.addView(more,new LinearLayout.LayoutParams(MobileUi.dp(a,87),MobileUi.dp(a,50)));
    }
    private void renameItem(String path){
        boolean directory=false;
        try{directory=new WorkspaceStore(a).file(path).isDirectory();}
        catch(Exception e){toast("Fichier inaccessible");return;}
        WorkspaceRenameDialog.rename(a,
            path.substring(path.lastIndexOf('/')+1),directory,
            name->work(()->{
                String parent=path.contains("/")?path.substring(0,path.lastIndexOf('/')):"";
                new WorkspaceStore(a).move(path,WorkspacePaths.child(parent,name));
                WorkspaceCommands.UI.post(()->{
                    selected.remove(path);
                    if(selected.isEmpty())selectMode=false;
                    updateSelectionToolbar();
                });
                return "Élément renommé";
            }));
    }
    /** Android Sharesheet with URI permissions for all selected files. */
    private void shareSelected(){
        if(selected.isEmpty()){toast("Sélectionne au moins un fichier");return;}
        java.util.ArrayList<Uri> uris=new java.util.ArrayList<>();
        java.util.ArrayList<String> types=new java.util.ArrayList<>();
        int skipped=0;
        try{
            WorkspaceStore store=new WorkspaceStore(a);
            for(String path:new java.util.ArrayList<>(selected)){
                if(store.file(path).isDirectory()){skipped++;continue;}
                uris.add(store.uri(path));
                types.add(store.mime(path));
            }
        }catch(Exception e){toast("Partage impossible : "+e.getMessage());return;}
        if(uris.isEmpty()){
            toast("Sélectionne un fichier : les dossiers ne sont pas partageables directement");
            return;
        }
        String mime=types.get(0);
        for(String type:types)if(!mime.equals(type)){mime="*/*";break;}
        boolean multiple=uris.size()>1;
        Intent intent=new Intent(multiple?Intent.ACTION_SEND_MULTIPLE:Intent.ACTION_SEND);
        intent.setType(mime);
        if(multiple)intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);
        else intent.putExtra(Intent.EXTRA_STREAM,uris.get(0));
        ClipData clip=ClipData.newRawUri("Fichier",uris.get(0));
        for(int i=1;i<uris.size();i++)clip.addItem(new ClipData.Item(uris.get(i)));
        intent.setClipData(clip);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{
            a.startActivity(Intent.createChooser(intent,
                multiple?"Partager "+uris.size()+" fichiers":"Partager le fichier"));
            if(skipped>0)toast(skipped+" dossier(s) ignoré(s) pour le partage");
        }catch(Exception e){toast("Aucune application disponible pour partager");}
    }
    /** Options remain reachable even when the horizontal action strip is scrolled. */
    private void selectionMore(){
        if(selected.isEmpty()){toast("Sélectionne un fichier");return;}
        final String[] items={"Partager…","Enregistrer sur le téléphone","Ouvrir avec…",
            "Propriétés / informations","Copier","Déplacer","Renommer","Mettre à la corbeille"};
        new AgentDialog.Builder(a).setTitle(selected.size()+" élément(s) sélectionné(s)")
            .setItems(items,(d,index)->{
                switch(index){
                    case 0:shareSelected();break;
                    case 1:saveSelectedToPhone();break;
                    case 2:
                        if(selected.size()==1)openWithExternal(selected.iterator().next());
                        else toast("Choisis un seul fichier pour l'ouvrir");
                        break;
                    case 3:
                        if(selected.size()==1)showSelectedInfo(selected.iterator().next());
                        else showBatchInfo();
                        break;
                    case 4:batchAction(0);break;
                    case 5:batchAction(1);break;
                    case 6:
                        if(selected.size()==1)renameItem(selected.iterator().next());
                        else toast("Le renommage demande un seul élément");
                        break;
                    case 7:batchAction(2);break;
                }
            }).setNegativeButton("Fermer",null).show();
    }
    private void showSelectedInfo(String path){
        WorkspaceCommands.IO.execute(()->{
            try{
                File file=new WorkspaceStore(a).file(path);
                if(!file.exists())throw new IOException("Fichier introuvable");
                String description="Nom : "+file.getName()+
                    "\\nType : "+(file.isDirectory()?"Dossier":new WorkspaceStore(a).mime(path))+
                    "\\nTaille : "+android.text.format.Formatter.formatShortFileSize(a,file.length())+
                    "\\nModifié : "+android.text.format.DateFormat.format("dd MMM yyyy HH:mm",file.lastModified())+
                    "\\nChemin : "+path;
                WorkspaceCommands.UI.post(()->{
                    if(!disposed&&!a.isFinishing()&&!a.isDestroyed())
                        new AgentDialog.Builder(a).setTitle("Propriétés").setMessage(description)
                            .setPositiveButton("OK",null).show();
                });
            }catch(Exception e){WorkspaceCommands.UI.post(()->toast(e.getMessage()));}
        });
    }
    private void showBatchInfo(){
        WorkspaceCommands.IO.execute(()->{
            int files=0,folders=0;long bytes=0;
            try{
                WorkspaceStore store=new WorkspaceStore(a);
                for(String path:new java.util.ArrayList<>(selected)){
                    File file=store.file(path);
                    if(file.isDirectory())folders++;
                    else{files++;bytes+=file.length();}
                }
                final String description=files+" fichier(s), "+folders+" dossier(s)"+
                    "\\nTaille des fichiers : "+android.text.format.Formatter.formatShortFileSize(a,bytes);
                WorkspaceCommands.UI.post(()->{
                    if(!disposed&&!a.isFinishing()&&!a.isDestroyed())
                        new AgentDialog.Builder(a).setTitle("Sélection").setMessage(description)
                            .setPositiveButton("OK",null).show();
                });
            }catch(Exception e){WorkspaceCommands.UI.post(()->toast(e.getMessage()));}
        });
    }
    /** Save single files with Android Save As, or multiple items to an Android folder. */
    private void saveSelectedToPhone(){
        if(selected.isEmpty()){toast("Sélectionne un fichier");return;}
        savePathsToPhone(new java.util.ArrayList<>(selected));
    }
    private void savePathsToPhone(java.util.List<String> snapshot){
        if(snapshot.isEmpty()){toast("Sélectionne un fichier");return;}
        try{
            if(snapshot.size()==1&&new WorkspaceStore(a).file(snapshot.get(0)).isFile()){
                exportPicker(snapshot.get(0));
                return;
            }
            Intent request=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            pendingPhoneExport=snapshot;
            a.startActivityForResult(request,EXPORT_FOLDER);
        }catch(Exception e){toast("Choix du dossier Android impossible");}
    }
    private void batchAction(int operation){
        if(batchRunning){toast("Opération en cours…");return;}
        if(selected.isEmpty()){toast("Sélectionne un fichier ou un dossier");return;}
        java.util.List<String> paths=new java.util.ArrayList<>(selected);
        if(operation==2)
            confirm("Mettre "+paths.size()+" élément(s) à la corbeille ?",
                ()->selectionBatch(paths,2,""));
        else {
            String first=folder;
            if(first.isEmpty()&&WorkspaceCategoryView.isContainerCategory(category))
                first=WorkspaceCategoryView.storageRoot(category);
            chooseDestinationAt(paths,operation,first);
        }
    }
    private void chooseDestinationAt(java.util.List<String> paths,int operation,String at){
        WorkspaceCommands.IO.execute(()->{
            String dest=at;
            try{
                WorkspaceStore store=new WorkspaceStore(a);
                if(!dest.isEmpty()&&!store.file(dest).isDirectory())dest="";
                JSONObject page=store.list(dest,"",0);
                java.util.List<String> destinations=new java.util.ArrayList<>();
                java.util.List<String> choices=new java.util.ArrayList<>();
                choices.add("✓ Ici : "+(dest.isEmpty()?"Mes fichiers":dest));
                choices.add("↑ Dossier parent");
                choices.add("Saisir un chemin…");
                JSONArray rows=page.getJSONArray("items");
                for(int i=0;i<rows.length();i++){
                    JSONObject entry=rows.getJSONObject(i);
                    if(entry.optBoolean("directory")){
                        choices.add("📁 "+entry.optString("name"));
                        destinations.add(entry.getString("path"));
                    }
                }
                final String current=dest;
                WorkspaceCommands.UI.post(()->{
                    if(disposed||a.isFinishing()||a.isDestroyed())return;
                    new AgentDialog.Builder(a)
                        .setTitle(operation==0?"Copier vers…":"Déplacer vers…")
                        .setItems(choices.toArray(new String[0]),(d,n)->{
                            if(n==0)selectionBatch(paths,operation,current);
                            else if(n==1){
                                String parent=current.contains("/")?
                                    current.substring(0,current.lastIndexOf('/')):"";
                                chooseDestinationAt(paths,operation,parent);
                            }else if(n==2)
                                prompt("Chemin du dossier",current,
                                    value->selectionBatch(paths,operation,value));
                            else chooseDestinationAt(paths,operation,destinations.get(n-3));
                        }).setNegativeButton("Annuler",null).show();
                });
            }catch(Exception e){WorkspaceCommands.UI.post(()->{
                if(!disposed&&!a.isFinishing()&&!a.isDestroyed())toast(e.getMessage());
            });}
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
                if(space.equals("files")){for(int i=0;i<succeeded.length();i++)selected.remove(succeeded.optString(i));selectMode=!selected.isEmpty();updateSelectionToolbar();}
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
    private void fileMenu(String path){boolean directory=false;try{directory=new WorkspaceStore(a).file(path).isDirectory();}catch(Exception ignored){}final boolean dir=directory;new AgentDialog.Builder(a).setTitle(path.substring(path.lastIndexOf('/')+1)).setItems(dir?new String[]{"Renommer","Déplacer","Mettre à la corbeille","Enregistrer sur le téléphone","Propriétés"}:new String[]{"Ouvrir","Partager","Enregistrer sur le téléphone","Renommer","Déplacer","Modifier le texte","Mettre à la corbeille","Copier","Monter dans Studio","Informations du média","Ouvrir avec…"},(d,i)->{int choice=dir?new int[]{3,4,6,11,9}[i]:i;switch(choice){
        case 0:openFile(path,false);break;case 1:openFile(path,true);break;case 2:savePathsToPhone(java.util.Collections.singletonList(path));break;
        case 3:renameItem(path);break;
        case 4:prompt("Chemin de destination","",name->work(()->{new WorkspaceStore(a).move(path,name);return "Élément déplacé";}));break;
        case 5:editText(path);break;
        case 7:prompt("Destination de la copie","",destination->work(()->{WorkspaceCatalog.copy(a,path,destination);return "Fichier copié";}));break;
        case 8:a.startActivity(new Intent(a,VideoEditorActivity.class).putExtra(path.toLowerCase(java.util.Locale.ROOT).endsWith(".json")?"project_path":"add_path",path));break;
        case 9:showSelectedInfo(path);break;
        case 10:openWithExternal(path);break;
        case 11:savePathsToPhone(java.util.Collections.singletonList(path));break;
        case 6:confirm("Mettre cet élément à la corbeille ?",()->work(()->{new WorkspaceStore(a).trash(path);return "Élément dans la corbeille";}));break;
    }}).setNegativeButton("Fermer",null).show();}
    private void openOtherFile(String path){try{WorkspaceStore store=new WorkspaceStore(a);Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(store.uri(path),store.mime(path)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);a.startActivity(Intent.createChooser(view,"Ouvrir le fichier"));}catch(Exception ex){toast("Fichier illisible");}}
    private void openFile(String path,boolean share){if(!share){WorkspaceCommands.IO.execute(()->{try{JSONObject info=FileFormats.detect(a,path);boolean sound=info.optString("kind").equals("audio")||info.optString("kind").equals("media")&&new WorkspaceStore(a).mime(path).startsWith("audio/");a.runOnUiThread(()->{if(a.isFinishing())return;if(sound&&audioBar!=null)audioBar.open(path);else a.startActivity(new Intent(a,FileViewerActivity.class).putExtra("path",path));});}catch(Exception ex){a.runOnUiThread(()->toast(ex.getMessage()));}});return;}try{WorkspaceStore s=new WorkspaceStore(a);Uri u=s.uri(path);Intent intent=new Intent(Intent.ACTION_SEND).setType(s.mime(path)).putExtra(Intent.EXTRA_STREAM,u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("Fichier",u));a.startActivity(Intent.createChooser(intent,"Partager le fichier"));}catch(Exception e){toast("Partage impossible : "+e.getMessage());}}

    private void openWithExternal(String path){
        try{
            WorkspaceStore store=new WorkspaceStore(a);
            if(store.file(path).isDirectory()){
                toast("Choisis un fichier pour l'ouvrir avec une application");return;
            }
            Uri uri=store.uri(path);
            Intent intent=new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri,store.mime(path));
            intent.setClipData(ClipData.newRawUri("Fichier",uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(intent,"Ouvrir avec"));
        }catch(Exception e){toast("Aucune application compatible");}
    }
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
    public boolean result(int request,int result,Intent data){
        if(request!=IMPORT&&request!=EXPORT&&request!=EXPORT_FOLDER)return false;
        if(result!=Activity.RESULT_OK||data==null){
            if(request==EXPORT_FOLDER)pendingPhoneExport.clear();
            return true;
        }
        if(request==EXPORT_FOLDER){
            Uri destination=data.getData();
            final java.util.List<String> paths=new java.util.ArrayList<>(pendingPhoneExport);
            pendingPhoneExport.clear();
            if(destination==null||paths.isEmpty()){toast("Aucun dossier choisi");return true;}
            work(()->WorkspacePhoneExport.save(a.getApplicationContext(),paths,destination));
            return true;
        }
        if(request==IMPORT){java.util.List<Uri> uris=new java.util.ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<Math.min(50,data.getClipData().getItemCount());i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());String requestedFolder=importFolder,requestedCategory=importCategory;
            work(()->{WorkspaceStore s=new WorkspaceStore(a);String destination=WorkspaceCategoryView.destination(s,requestedCategory,requestedFolder);int count=0;java.util.List<String> failures=new java.util.ArrayList<>();
                for(Uri u:uris){try{String name="Fichier-"+System.nanoTime();try(Cursor c=a.getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}
                    File parent=WorkspacePaths.resolve(new File(a.getFilesDir(),"workspace"),destination,true);
                    String path=WorkspacePaths.child(destination,FileNames.available(parent,name,null));s.importUri(u,path);count++;
                }catch(Exception e){failures.add(e.getMessage()==null?"fichier inaccessible":e.getMessage());}}
                return count+" fichier(s) importé(s)"+(failures.isEmpty()?"":" · "+failures.size()+" échec(s) : "+failures.get(0));});
        }else {Uri target=data.getData();String path=exportPath;work(()->{try(InputStream in=new FileInputStream(new WorkspaceStore(a).file(path));OutputStream out=a.getContentResolver().openOutputStream(target)){if(out==null)throw new IOException("Destination inaccessible");byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}return "Fichier exporté";});}return true;
    }
    private void editText(String path){a.startActivity(new Intent(a,FileViewerActivity.class).putExtra("path",path));}

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
    private void prompt(String title,String value,Name cb){
        WorkspaceRenameDialog.text(a,title,value,cb::done);
    }
    private void confirm(String title,Runnable action){new AgentDialog.Builder(a).setTitle(title).setMessage("Tu pourras le restaurer depuis Options > Corbeille.").setNegativeButton("Annuler",null).setPositiveButton("Mettre à la corbeille",(d,i)->action.run()).show();}
    private void toast(String s){Toast.makeText(a,s==null?"Opération impossible":s,Toast.LENGTH_SHORT).show();}
    public void destroy(){
        disposed=true;generation++;handler.removeCallbacksAndMessages(null);
        if(audioBar!=null){audioBar.destroy();audioBar=null;}
        if(recycler!=null)recycler.setAdapter(null);
    }
}
