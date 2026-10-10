package com.chk.agentbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Mobile-first, CapCut-inspired local project library. No online account needed. */
public final class StudioProjectsActivity extends Activity {
    private static final int INK=0xff191b22,SOFT=0xff727783,LINE=0xffe8eaef,TEAL=0xff13aeb7;
    private final VideoEditorEngine engine=VideoEditorEngine.get();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final JSONArray[] cached={new JSONArray(),new JSONArray()};
    private LinearLayout rows;
    private TextView count,localTab,recentTab,trashTab;
    private EditText search;
    private boolean alive,loading;
    private int tab;
    private String activeId="";
    @Override public void onCreate(Bundle state){
        super.onCreate(state);alive=true;
        getWindow().setStatusBarColor(0xfffbfcff);
        getWindow().setNavigationBarColor(0xfffbfcff);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfffbfcff);setContentView(root);
        LinearLayout header=line();header.setPadding(dp(10),dp(8),dp(12),dp(6));
        root.addView(header,new LinearLayout.LayoutParams(-1,dp(58)));
        TextView back=label("‹",32,INK,true);back.setGravity(Gravity.CENTER);header.addView(back,new LinearLayout.LayoutParams(dp(48),-1));
        back.setOnClickListener(v->finish());
        TextView title=label("Projets",23,INK,true);title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        TextView find=label("⌕",30,INK,false);find.setContentDescription("Rechercher un projet");
        find.setGravity(Gravity.CENTER);header.addView(find,new LinearLayout.LayoutParams(dp(48),-1));
        TextView more=label("⋮",26,INK,false);more.setGravity(Gravity.CENTER);
        header.addView(more,new LinearLayout.LayoutParams(dp(42),-1));
        more.setOnClickListener(v->new AlertDialog.Builder(this).setItems(
            new String[]{"Actualiser","Ouvrir le montage actif","Restaurer un projet supprimé"},
            (d,i)->{if(i==0)refresh();else if(i==1)startEditor();else{tab=2;renderTabs();render();}}).show());
        find.setOnClickListener(v->{search.setVisibility(search.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);
            if(search.getVisibility()==View.VISIBLE)search.requestFocus();});
        search=new EditText(this);search.setSingleLine(true);search.setTextSize(15);
        search.setHint("Rechercher parmi mes projets");search.setPadding(dp(14),0,dp(14),0);
        search.setBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));
        search.setVisibility(View.GONE);
        root.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){render();}
            public void afterTextChanged(Editable s){}
        });
        LinearLayout tabs=line();tabs.setPadding(dp(12),dp(2),dp(12),0);
        root.addView(tabs,new LinearLayout.LayoutParams(-1,dp(47)));
        localTab=tabLabel("Locaux",tabs,0);recentTab=tabLabel("Récents",tabs,1);trashTab=tabLabel("Corbeille",tabs,2);
        View border=new View(this);border.setBackgroundColor(LINE);root.addView(border,new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout countBar=line();countBar.setPadding(dp(20),dp(6),dp(16),dp(2));
        root.addView(countBar,new LinearLayout.LayoutParams(-1,dp(46)));
        count=label("Chargement des projets…",13,INK,true);
        count.setGravity(Gravity.CENTER_VERTICAL);countBar.addView(count,new LinearLayout.LayoutParams(0,-1,1));
        TextView sort=label("Plus récents ▾",12,SOFT,false);
        sort.setGravity(Gravity.CENTER_VERTICAL);countBar.addView(sort);
        sort.setOnClickListener(v->new AlertDialog.Builder(this).setItems(new String[]{"Plus récents","Plus anciens"},(d,n)->{
            newestFirst=n==0;render();}).show());
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(12),dp(3),dp(12),dp(80));
        rows.setTag("studio_projects_list");scroll.addView(rows);
        FrameLayout bottom=new FrameLayout(this);bottom.setPadding(dp(12),dp(4),dp(14),dp(10));
        root.addView(bottom,new LinearLayout.LayoutParams(-1,dp(72)));
        TextView create=label("＋  Créer un projet",16,0xffffffff,true);
        create.setGravity(Gravity.CENTER);create.setBackground(MobileUi.bg(this,TEAL));
        FrameLayout.LayoutParams c=new FrameLayout.LayoutParams(dp(190),dp(52),Gravity.RIGHT|Gravity.TOP);
        bottom.addView(create,c);create.setTag("studio_new_project");
        create.setOnClickListener(v->command("video_editor_project_new",new JSONObject(),true));
        renderTabs();
    }
    private boolean newestFirst=true;
    @Override protected void onResume(){super.onResume();refresh();}
    @Override protected void onDestroy(){alive=false;super.onDestroy();}
    private int dp(int n){return MobileUi.dp(this,n);}
    private TextView label(String text,int size,int color,boolean bold){
        TextView t=MobileUi.text(this,text,size,color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private LinearLayout line(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView tabLabel(String name,LinearLayout parent,int type){
        TextView t=label(name,14,INK,false);t.setGravity(Gravity.CENTER);
        parent.addView(t,new LinearLayout.LayoutParams(0,-1,1));
        t.setOnClickListener(v->{tab=type;renderTabs();render();});return t;
    }
    private void renderTabs(){
        if(localTab==null)return;
        TextView[] all={localTab,recentTab,trashTab};
        for(int i=0;i<all.length;i++){
            all[i].setTypeface(Typeface.DEFAULT,i==tab?Typeface.BOLD:Typeface.NORMAL);
            all[i].setTextColor(i==tab?INK:SOFT);
            all[i].setBackgroundColor(i==tab?0xffe5f8fa:0x00ffffff);
        }
    }
    private void refresh(){
        if(loading)return;loading=true;
        engine.command(this,"video_editor_project_list",new JSONObject(),(ok,res)->main.post(()->{
            if(!alive)return;
            try{if(ok){JSONObject data=new JSONObject(res);cached[0]=data.getJSONArray("items");
                activeId=data.optString("active_id");}else toast(res);}catch(Exception e){toast(e.getMessage());}
            engine.command(this,"video_editor_project_list",new JSONObjectSafe("trash",true),(yes,value)->main.post(()->{
                loading=false;if(!alive)return;
                try{if(yes)cached[1]=new JSONObject(value).getJSONArray("items");
                    else toast(value);}catch(Exception e){toast(e.getMessage());}
                render();
            }));
        }));
    }
    private void render(){
        if(rows==null)return;rows.removeAllViews();
        JSONArray entries=cached[tab==2?1:0];String query=search==null?"":search.getText().toString().toLowerCase(Locale.ROOT).trim();
        long week=System.currentTimeMillis()-7L*24*60*60*1000;
        int[] order=new int[entries.length()];for(int i=0;i<order.length;i++)order[i]=newestFirst?i:order.length-1-i;
        int total=0;
        for(int at:order){
            JSONObject item=entries.optJSONObject(at);if(item==null)continue;
            if(tab==1&&item.optLong("updated_at")<week)continue;
            if(!item.optString("name").toLowerCase(Locale.ROOT).contains(query))continue;
            addRow(item,tab==2);total++;
        }
        count.setText(total+" projet"+(total==1?"":"s")+(tab==2?" dans la corbeille":tab==1?" récent"+(total==1?"":"s"):" local"+(total==1?"":"aux")));
        if(total==0){
            TextView empty=label(tab==2?"Corbeille vide":"Aucun projet trouvé.\nCrée un montage pour commencer.",15,SOFT,false);
            empty.setGravity(Gravity.CENTER);empty.setPadding(dp(12),dp(70),dp(12),dp(40));
            rows.addView(empty,new LinearLayout.LayoutParams(-1,-2));
        }
    }
    private void addRow(JSONObject item,boolean deleted){
        String id=item.optString("id");LinearLayout row=line();
        row.setPadding(dp(4),dp(9),dp(4),dp(9));
        row.setTag("studio_project_row_"+id);
        rows.addView(row,new LinearLayout.LayoutParams(-1,dp(92)));
        FrameLayout frame=new FrameLayout(this);frame.setBackground(MobileUi.bg(this,0xffdedfe5));
        row.addView(frame,new LinearLayout.LayoutParams(dp(82),dp(72)));
        TextView placeholder=label(item.optInt("clips")>0?"▶":"＋",25,SOFT,false);
        placeholder.setGravity(Gravity.CENTER);frame.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
        String cover=item.optString("cover_path");
        if(!cover.isEmpty()){
            ImageView poster=new ImageView(this);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);frame.addView(poster,new FrameLayout.LayoutParams(-1,-1));
            poster.setTag(id);
            ThumbnailLoader.load(this,cover,0,true,new ThumbnailLoader.Result(){
                public boolean current(){return alive&&id.equals(poster.getTag());}
                public void ready(Bitmap bitmap){if(alive&&id.equals(poster.getTag()))poster.setImageBitmap(bitmap);}
            });
        }
        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12),dp(2),0,0);
        row.addView(info,new LinearLayout.LayoutParams(0,-1,1));
        TextView name=label(item.optString("name","Montage"),14,INK,true);
        name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);
        String date=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,Locale.FRANCE)
            .format(new Date(item.optLong("updated_at")));
        info.addView(label(date,11,SOFT,false));
        long duration=item.optLong("duration_ms");
        String time=String.format(Locale.FRANCE,"%02d:%02d",duration/60000,(duration/1000)%60);
        info.addView(label(item.optInt("clips")+" plan(s) · "+time
            +(item.optBoolean("active")?" · En cours":""),11,SOFT,false));
        TextView menu=label("⋯",25,SOFT,false);menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("Options du projet "+item.optString("name"));
        row.addView(menu,new LinearLayout.LayoutParams(dp(45),-1));
        menu.setOnClickListener(v->projectOptions(item,deleted));
        row.setOnClickListener(v->{if(deleted)projectOptions(item,true);else open(id);});
        View divider=new View(this);divider.setBackgroundColor(LINE);
        rows.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
    }
    private void projectOptions(JSONObject item,boolean deleted){
        String id=item.optString("id");
        if(deleted){
            new AlertDialog.Builder(this).setTitle(item.optString("name"))
                .setItems(new String[]{"Restaurer le projet"},(d,n)->command("video_editor_project_restore",new JSONObjectSafe("id",id),false))
                .show();return;
        }
        new AlertDialog.Builder(this).setTitle(item.optString("name")).setItems(
            new String[]{"Ouvrir","Renommer","Dupliquer","Mettre à la corbeille"},
            (d,n)->{switch(n){
                case 0:open(id);break;
                case 1:rename(item);break;
                case 2:command("video_editor_project_duplicate",new JSONObjectSafe("id",id),true);break;
                case 3:new AlertDialog.Builder(this).setTitle("Mettre à la corbeille ?")
                    .setMessage("Le projet pourra être restauré. Les vidéos et les exports restent intacts.")
                    .setNegativeButton("Annuler",null).setPositiveButton("Mettre à la corbeille",
                        (a,b)->command("video_editor_project_trash",new JSONObjectSafe("id",id),false)).show();
                    break;
            }}).show();
    }
    private void rename(JSONObject item){
        EditText input=new EditText(this);input.setSingleLine(true);
        input.setText(item.optString("name"));input.selectAll();
        new AlertDialog.Builder(this).setTitle("Renommer le projet").setView(input)
            .setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(d,n)->{
                try{JSONObject args=new JSONObjectSafe("id",item.optString("id"));
                    args.put("name",input.getText().toString().trim());
                    command("video_editor_project_rename",args,false);
                }catch(Exception e){toast(e.getMessage());}
            }).show();
    }
    private void open(String id){command("video_editor_project_open",new JSONObjectSafe("id",id),true);}
    private void command(String action,JSONObject args,boolean launch){
        engine.command(this,action,args,(ok,res)->main.post(()->{
            if(!alive)return;
            if(!ok){toast(res);return;}
            if(launch)startEditor();else refresh();
        }));
    }
    private void startEditor(){
        startActivity(new Intent(this,VideoEditorActivity.class)); 
    }
    private void toast(String message){android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).show();}
    private static final class JSONObjectSafe extends JSONObject {
        JSONObjectSafe(String name,Object value){try{put(name,value);}catch(Exception ignored){}}
    }
}
