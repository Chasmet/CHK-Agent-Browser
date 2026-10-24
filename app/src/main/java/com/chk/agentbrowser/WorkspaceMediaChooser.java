package com.chk.agentbrowser;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One persistent picker for the same virtual folders as Fichiers. No media copies. */
public final class WorkspaceMediaChooser {
    public interface Selection { void finish(Uri[] uris); }
    private static final ExecutorService IO=Executors.newFixedThreadPool(2);
    private final Activity activity;
    private final WebChromeClient.FileChooserParams params;
    private final Selection completion;
    private final Runnable openAndroid;
    private final List<String> types;
    private final LinkedHashSet<String> selected=new LinkedHashSet<>();
    private final ArrayDeque<String[]> history=new ArrayDeque<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Dialog dialog;
    private final LinearLayout root;
    private final TextView breadcrumb,status,importButton,selectionText,viewButton;
    private final EditText search;
    private final RecyclerView list;
    private final Adapter adapter=new Adapter();
    private final CheckBox showAll;
    private String category="all",folder="";
    private int generation;
    private boolean finished,grid=true,busy;
    private Runnable pendingSearch;

    private WorkspaceMediaChooser(Activity activity,WebChromeClient.FileChooserParams params,
                                  Selection completion,Runnable openAndroid){
        this.activity=activity;this.params=params;this.completion=completion;this.openAndroid=openAndroid;
        types=WorkspaceImportIndex.types(params.getAcceptTypes());
        dialog=AgentDialog.watch(new Dialog(activity));dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        root=MobileUi.column(activity);root.setPadding(dp(10),dp(8),dp(10),dp(8));
        root.setBackgroundColor(0xfff7f9fc);root.setTag("import_picker");
        dialog.setContentView(root);
        LinearLayout title=row();root.addView(title);
        TextView back=button("‹ Retour","import_back");title.addView(back);back.setOnClickListener(v->back());
        TextView heading=text("Importer un fichier",19,0xff18243a);title.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        TextView close=button("Fermer","import_cancel");title.addView(close);close.setOnClickListener(v->cancel());
        String format=types.isEmpty()||types.contains("*/*")?"Tous les formats":TextUtils.join(", ",types);
        TextView requested=text((multiple()?"Sélection multiple":"Un fichier")+" · Formats du site : "+format,12,0xff566274);
        requested.setMaxLines(2);root.addView(requested);
        search=new EditText(activity);search.setSingleLine(true);search.setTextSize(15);
        search.setHint("Rechercher un fichier ou un dossier");search.setTag("import_search");
        search.setContentDescription("Rechercher dans les fichiers CHK");root.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));
        HorizontalScrollView categories=new HorizontalScrollView(activity);categories.setHorizontalScrollBarEnabled(false);
        LinearLayout chips=row();categories.addView(chips);root.addView(categories);
        for(String key:new String[]{"all","image","video","audio","document","recent"}){
            TextView chip=button(WorkspaceImportIndex.label(key),"import_category_"+key);chips.addView(chip);
            chip.setOnClickListener(v->navigate(key,""));
        }
        HorizontalScrollView crumbs=new HorizontalScrollView(activity);crumbs.setHorizontalScrollBarEnabled(false);
        breadcrumb=text("Fichiers CHK",13,0xff265c9e);breadcrumb.setTag("import_breadcrumb");crumbs.addView(breadcrumb);root.addView(crumbs);
        LinearLayout options=row();root.addView(options);
        showAll=new CheckBox(activity);showAll.setText("Tous les formats");showAll.setTextColor(0xff28384d);showAll.setTextSize(13);
        showAll.setChecked(true);showAll.setTag("import_show_all");options.addView(showAll,new LinearLayout.LayoutParams(0,-2,1));
        showAll.setOnCheckedChangeListener((v,checked)->refresh());
        TextView sort=button("Trier","import_sort");options.addView(sort);
        sort.setOnClickListener(v->new AgentDialog.Builder(activity).setTitle("Trier les fichiers")
            .setItems(new String[]{"Nom","Plus récents","Taille"},(d,n)->{order=new String[]{"name","date","size"}[n];refresh();}).show());
        viewButton=button("Liste","import_view");options.addView(viewButton);
        viewButton.setOnClickListener(v->{grid=!grid;viewButton.setText(grid?"Liste":"Grille");layoutList();adapter.notifyDataSetChanged();});
        status=text("Chargement…",12,0xff566274);status.setTag("import_status");root.addView(status);
        list=new RecyclerView(activity);list.setTag("import_list");list.setAdapter(adapter);layoutList();
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        selectionText=text("Aucun fichier sélectionné",12,0xff28384d);selectionText.setTag("import_selection");selectionText.setMaxLines(2);root.addView(selectionText);
        selectionText.setOnClickListener(v->{if(!selected.isEmpty())new AgentDialog.Builder(activity)
            .setTitle("Sélection · toucher pour retirer")
            .setItems(selected.toArray(new String[0]),(d,n)->{selected.remove(new ArrayList<>(selected).get(n));updateSelection();adapter.notifyDataSetChanged();}).show();});
        LinearLayout footer=row();root.addView(footer);
        TextView android=button("Fichiers Android","import_android");footer.addView(android,new LinearLayout.LayoutParams(0,dp(50),1));
        android.setOnClickListener(v->{if(busy||finished)return;if(!selected.isEmpty())new AgentDialog.Builder(activity)
            .setTitle("Changer de source ?").setMessage("La sélection CHK sera annulée. Le sélecteur Android permet aussi d’ouvrir Photos, Téléchargements et les dossiers autorisés.")
            .setNegativeButton("Garder CHK",null).setPositiveButton("Fichiers Android",(d,w)->android()).show();else android();});
        importButton=button("Importer","import_confirm");importButton.setTextColor(0xffffffff);
        importButton.setBackground(MobileUi.bg(activity,0xff176cce));footer.addView(importButton,new LinearLayout.LayoutParams(0,dp(50),1));
        importButton.setOnClickListener(v->complete());updateSelection();
        // Keep results and confirmation reachable when the search keyboard reduces the window.
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            boolean compact=b-t<dp(500);int visibility=compact?View.GONE:View.VISIBLE;
            if(categories.getVisibility()!=visibility){categories.setVisibility(visibility);options.setVisibility(visibility);requested.setVisibility(visibility);}
        });
        dialog.setOnCancelListener(d->cancel());
        dialog.setOnKeyListener((d,key,event)->{if(key==KeyEvent.KEYCODE_BACK&&event.getAction()==KeyEvent.ACTION_UP){back();return true;}return key==KeyEvent.KEYCODE_BACK;});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){if(pendingSearch!=null)main.removeCallbacks(pendingSearch);pendingSearch=()->refresh();main.postDelayed(pendingSearch,250);}
            public void afterTextChanged(Editable e){} });
    }
    private String order="name";
    public static WorkspaceMediaChooser show(Activity activity,WebChromeClient.FileChooserParams params,
                            Selection selection,Runnable openAndroid)throws IOException{
        WorkspaceMediaChooser picker=new WorkspaceMediaChooser(activity,params,selection,openAndroid);
        picker.dialog.show();
        Window window=picker.dialog.getWindow();if(window!=null){window.setLayout(-1,-1);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);}
        IO.execute(()->{String first="all";try{first=WorkspaceImportIndex.initialCategory(new WorkspaceStore(activity),picker.types);}catch(Exception ignored){}
            String initial=first;picker.main.post(()->{if(!picker.finished){picker.category=initial;picker.refresh();}});});
        return picker;
    }
    private int dp(int n){return MobileUi.dp(activity,n);}
    private TextView text(String value,int size,int color){TextView v=MobileUi.text(activity,value,size,color);v.setPadding(dp(8),dp(6),dp(8),dp(6));return v;}
    private TextView button(String value,String tag){TextView v=text(value,14,0xff1762b0);v.setMinHeight(dp(48));v.setGravity(Gravity.CENTER);v.setFocusable(true);v.setTag(tag);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private boolean multiple(){return params.getMode()==WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;}
    private boolean alive(){return !finished&&!activity.isFinishing()&&!activity.isDestroyed();}
    private void layoutList(){GridLayoutManager layout=new GridLayoutManager(activity,grid?2:1);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup(){public int getSpanSize(int position){return grid&&adapter.rows.get(position).optBoolean("directory")?2:1;}});list.setLayoutManager(layout);}
    private void navigate(String key,String path){if(busy||finished)return;
        history.push(new String[]{category,folder,search.getText().toString()});category=key;folder=path;search.setText("");refresh();}
    private void back(){if(busy)return;if(history.isEmpty()){cancel();return;}String[] previous=history.pop();category=previous[0];folder=previous[1];search.setText(previous[2]);refresh();}
    private void refresh(){
        if(!alive()||busy)return;if(pendingSearch!=null){main.removeCallbacks(pendingSearch);pendingSearch=null;}
        status.setOnClickListener(null);final int ticket=++generation;final String key=category,path=folder,q=search.getText().toString(),sort=order;
        final boolean all=showAll.isChecked();status.setText("Chargement…");adapter.rows.clear();adapter.notifyDataSetChanged();
        breadcrumb.setText("Accueil"+(key.equals("all")?"":" › "+WorkspaceImportIndex.label(key))+(path.isEmpty()?"":" › "+WorkspaceImportIndex.visiblePath(path)));
        IO.execute(()->{try{
            JSONObject page=WorkspaceImportIndex.query(activity.getApplicationContext(),key,path,q,sort,types,all);
            main.post(()->{if(!alive()||ticket!=generation)return;
                JSONArray items=page.optJSONArray("items");int files=0,folders=0;
                for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);adapter.rows.add(item);if(item.optBoolean("directory"))folders++;else files++;}
                adapter.notifyDataSetChanged();list.scrollToPosition(0);
                int hidden=page.optInt("hidden");
                status.setText((items.length()==0?(q.isEmpty()?"Ce dossier ne contient aucun fichier compatible.":"Aucun résultat pour cette recherche."):
                    files+" fichier(s) · "+folders+" dossier(s)")+(hidden>0?" · "+hidden+" masqué(s) par le filtre":"")+
                    (page.optBoolean("truncated")?" · Liste limitée : ouvre un dossier pour continuer.":"")+
                    (items.length()==0?" Essaie une autre catégorie ou Fichiers Android.":""));
            });
        }catch(Exception e){main.post(()->{if(alive()&&ticket==generation){status.setText("Dossier inaccessible. Actualise ou choisis Fichiers Android.");
            status.setOnClickListener(v->refresh());}});}});
    }
    private void choose(JSONObject entry){
        if(busy||finished)return;String path=entry.optString("path");
        if(entry.optBoolean("virtual")){navigate(path.substring(1),"");return;}
        if(entry.optBoolean("directory")){navigate(category,path);return;}
        if(!entry.optBoolean("compatible")){Toast.makeText(activity,"Le site n’accepte pas ce format : "+entry.optString("mime_type"),Toast.LENGTH_LONG).show();return;}
        if(selected.contains(path))selected.remove(path);else{if(!multiple())selected.clear();selected.add(path);}
        adapter.notifyDataSetChanged();updateSelection();
    }
    private void updateSelection(){
        importButton.setEnabled(!busy&&!selected.isEmpty());importButton.setAlpha(importButton.isEnabled()?1f:0.45f);
        importButton.setText(busy?"Vérification…":"Importer"+(selected.isEmpty()?"":" ("+selected.size()+")"));
        selectionText.setText(selected.isEmpty()?"Aucun fichier sélectionné":selected.size()+" sélectionné(s) · "+WorkspaceImportIndex.visiblePath(new ArrayList<>(selected).get(0)));
        selectionText.setContentDescription("Sélection : "+TextUtils.join(", ",selected));
    }
    private void complete(){
        if(busy||finished||selected.isEmpty())return;busy=true;generation++;
        if(pendingSearch!=null){main.removeCallbacks(pendingSearch);pendingSearch=null;}updateSelection();
        List<String> paths=new ArrayList<>(selected);
        IO.execute(()->{try{
            WorkspaceStore store=new WorkspaceStore(activity.getApplicationContext());Uri[] uris=new Uri[paths.size()];
            for(int i=0;i<paths.size();i++){String p=paths.get(i);
                if(!store.file(p).isFile()||!store.file(p).canRead())throw new IOException("Fichier déplacé ou inaccessible : "+WorkspaceImportIndex.visiblePath(p));
                if(!WorkspaceImportIndex.accepts(store,p,types))throw new IOException("Format refusé par le site : "+WorkspaceImportIndex.visiblePath(p));uris[i]=store.uri(p);
            }
            main.post(()->{if(!alive())return;try{
                if(Build.VERSION.SDK_INT>=26){android.content.pm.PackageInfo pkg=WebView.getCurrentWebViewPackage();
                    if(pkg!=null)for(Uri uri:uris)activity.grantUriPermission(pkg.packageName,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
                finished=true;dispose();completion.finish(uris);
            }catch(Exception e){busy=false;updateSelection();status.setText("Importation impossible : "+e.getMessage());}});
        }catch(Exception e){main.post(()->{if(alive()){busy=false;updateSelection();status.setText(e.getMessage());}});}});
    }
    private void android(){if(finished)return;finished=true;dispose();openAndroid.run();}
    public void cancel(){if(finished)return;finished=true;dispose();completion.finish(null);}
    private void dispose(){generation++;main.removeCallbacksAndMessages(null);dialog.dismiss();}

    private final class Adapter extends RecyclerView.Adapter<Holder>{
        final List<JSONObject> rows=new ArrayList<>();
        public int getItemCount(){return rows.size();}
        public int getItemViewType(int n){return grid&&!rows.get(n).optBoolean("directory")?1:0;}
        @NonNull public Holder onCreateViewHolder(@NonNull ViewGroup parent,int type){
            boolean tile=type==1;LinearLayout card=tile?MobileUi.column(activity):row();card.setPadding(dp(6),dp(6),dp(6),dp(6));
            RecyclerView.LayoutParams lp=new RecyclerView.LayoutParams(-1,dp(tile?208:80));lp.setMargins(dp(3),dp(3),dp(3),dp(3));card.setLayoutParams(lp);
            ImageView icon=new ImageView(activity);icon.setScaleType(ImageView.ScaleType.CENTER_CROP);card.addView(icon,new LinearLayout.LayoutParams(tile?-1:dp(58),dp(tile?112:58)));
            LinearLayout info=MobileUi.column(activity);card.addView(info,new LinearLayout.LayoutParams(tile?-1:0,tile?0:-2,1));
            TextView name=text("",14,0xff202d42);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);info.addView(name);
            TextView detail=text("",11,0xff58667a);detail.setMaxLines(2);detail.setEllipsize(TextUtils.TruncateAt.MIDDLE);info.addView(detail);
            return new Holder(card,icon,name,detail);
        }
        public void onBindViewHolder(@NonNull Holder holder,int n){
            JSONObject entry=rows.get(n);String path=entry.optString("path"),mime=entry.optString("mime_type");boolean directory=entry.optBoolean("directory"),compatible=entry.optBoolean("compatible");
            boolean checked=selected.contains(path);holder.path=path;holder.card.setTag("import_file:"+path);
            holder.card.setBackground(MobileUi.bg(activity,checked?0xffd5e9ff:0xffffffff));holder.card.setAlpha(compatible?1f:0.5f);
            holder.name.setText((checked?"✓ ":"")+entry.optString("name"));
            String parent=path.contains("/")?WorkspaceImportIndex.visiblePath(path.substring(0,path.lastIndexOf('/'))):"Fichiers CHK";
            holder.detail.setText(directory?"Ouvrir le dossier":(compatible?android.text.format.Formatter.formatShortFileSize(activity,entry.optLong("size")):"Format refusé par le site")+" · "+parent);
            holder.card.setContentDescription((directory?"Dossier ":"Fichier ")+entry.optString("name")+(checked?" sélectionné":"")+(!compatible?" incompatible":""));
            holder.icon.setImageDrawable(UiIcons.icon(activity,directory?"folder":mime.startsWith("image/")?"image":mime.startsWith("video/")?"video":mime.startsWith("audio/")?"audio":"document",directory?0xffefb521:0xff4e83c2,34));
            holder.card.setOnClickListener(v->choose(entry));
            if(!directory&&(mime.startsWith("image/")||mime.startsWith("video/"))){int ticket=generation;
                ThumbnailLoader.load(activity,path,0,mime.startsWith("video/"),new ThumbnailLoader.Result(){
                    public boolean current(){return alive()&&ticket==generation&&path.equals(holder.path);}
                    public void ready(android.graphics.Bitmap bitmap){holder.icon.setImageBitmap(bitmap);}
                });}
        }
        public void onViewRecycled(@NonNull Holder h){h.path="";h.icon.setImageDrawable(null);super.onViewRecycled(h);}
    }
    private static final class Holder extends RecyclerView.ViewHolder{
        final LinearLayout card;final ImageView icon;final TextView name,detail;String path="";
        Holder(LinearLayout c,ImageView i,TextView n,TextView d){super(c);card=c;icon=i;name=n;detail=d;}
    }
}
