package com.chk.agentbrowser;

import android.app.Activity;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * Only visible rows own Android views and thumbnail requests.
 * File paths stay in CHK's sandbox; never prefetch binary media via Render.
 */
final class WorkspaceFileAdapter extends RecyclerView.Adapter<WorkspaceFileAdapter.Holder> {
    interface Actions {
        void openFolder(String path);
        void openFile(String path);
        void menu(String path);
        void selectedChanged();
    }
    static final class Entry {
        final String path,name,mime;
        final boolean folder;
        final long size;
        Entry(JSONObject json){
            path=json.optString("path");
            name=json.optString("name");
            mime=json.optString("mime_type");
            folder=json.optBoolean("directory");
            size=json.optLong("size");
        }
        boolean same(Entry other){
            return path.equals(other.path) && name.equals(other.name) &&
                mime.equals(other.mime) && folder==other.folder && size==other.size;
        }
    }
    static final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout card;
        final ImageView icon;
        final TextView title,detail;
        String boundPath="";
        Holder(LinearLayout card,ImageView icon,TextView title,TextView detail){
            super(card);this.card=card;this.icon=icon;this.title=title;this.detail=detail;
        }
    }
    private final Activity context;
    private final Set<String> selection;
    private final Actions actions;
    private final List<Entry> rows=new ArrayList<>();
    private final Set<String> renderedSelection=new HashSet<>();
    private boolean grid=true,selectMode;
    private int generation;
    WorkspaceFileAdapter(Activity context,Set<String> selection,Actions actions){
        this.context=context;this.selection=selection;this.actions=actions;
        setHasStableIds(true);
    }
    boolean fullSpan(int position){
        return !grid || rows.get(position).folder;
    }
    void submit(JSONArray data,boolean grid,boolean selectMode,int generation){
        List<Entry> incoming=new ArrayList<>();
        for(int i=0;i<data.length();i++){
            JSONObject item=data.optJSONObject(i);
            if(item!=null)incoming.add(new Entry(item));
        }
        final boolean oldGrid=this.grid;
        final boolean oldMode=this.selectMode;
        final boolean selectionChanged=!renderedSelection.equals(selection);
        final List<Entry> before=new ArrayList<>(rows);
        this.grid=grid;this.selectMode=selectMode;this.generation=generation;
        renderedSelection.clear();renderedSelection.addAll(selection);
        DiffUtil.DiffResult diff=DiffUtil.calculateDiff(new DiffUtil.Callback(){
            @Override public int getOldListSize(){return before.size();}
            @Override public int getNewListSize(){return incoming.size();}
            @Override public boolean areItemsTheSame(int a,int b){
                return before.get(a).path.equals(incoming.get(b).path);
            }
            @Override public boolean areContentsTheSame(int a,int b){
                return before.get(a).same(incoming.get(b));
            }
        },false);
        rows.clear();rows.addAll(incoming);
        if(oldGrid!=grid||oldMode!=selectMode||selectionChanged)notifyDataSetChanged();
        else diff.dispatchUpdatesTo(this);
    }
    void selectionMode(boolean enabled){
        selectMode=enabled;
        notifyItemRangeChanged(0,rows.size());
    }
    @Override public long getItemId(int position){return rows.get(position).path.hashCode();}
    @Override public int getItemViewType(int position){
        return grid && !rows.get(position).folder?1:0;
    }
    private int dp(int units){return MobileUi.dp(context,units);}
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent,int viewType){
        boolean tile=viewType==1;
        LinearLayout card=new LinearLayout(context);
        card.setOrientation(tile?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(5),dp(5),dp(5),dp(5));
        RecyclerView.LayoutParams layout=new RecyclerView.LayoutParams(-1,dp(tile?136:66));
        layout.setMargins(dp(3),dp(3),dp(3),dp(3));
        card.setLayoutParams(layout);
        ImageView icon=new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setContentDescription("Aperçu du fichier");
        card.addView(icon,new LinearLayout.LayoutParams(tile?-1:dp(46),dp(tile?77:46)));
        LinearLayout info=new LinearLayout(context);info.setOrientation(LinearLayout.VERTICAL);
        card.addView(info,new LinearLayout.LayoutParams(tile?-1:0,-2,tile?0:1));
        TextView title=MobileUi.text(context,"",tile?12:15,0xff202124);
        title.setMaxLines(tile?2:1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);
        TextView detail=MobileUi.text(context,"",11,0xff687080);
        detail.setSingleLine(true);info.addView(detail);
        return new Holder(card,icon,title,detail);
    }
    @Override public void onBindViewHolder(@NonNull Holder holder,int position){
        Entry entry=rows.get(position);
        final String path=entry.path;
        holder.boundPath=path;
        boolean checked=selection.contains(path);
        holder.card.setBackground(MobileUi.bg(context,checked?0xffd7eaff:0xffffffff));
        holder.title.setText((checked?"✓ ":"")+entry.name);
        holder.detail.setText(entry.folder?"Dossier":
            android.text.format.Formatter.formatShortFileSize(context,entry.size));
        holder.card.setContentDescription((entry.folder?"Dossier ":"Fichier ")+entry.name);
        holder.icon.setImageDrawable(UiIcons.icon(context,entry.folder?"folder":
            entry.mime.startsWith("video/")?"video":
            entry.mime.startsWith("image/")?"image":
            entry.mime.startsWith("audio/")?"audio":"document",
            entry.folder?0xfffbbc04:0xff4e83c2,entry.folder?34:28));
        holder.card.setOnClickListener(v->{
            if(selectMode){
                if(selection.contains(path))selection.remove(path);else selection.add(path);
                renderedSelection.clear();renderedSelection.addAll(selection);
                int index=holder.getBindingAdapterPosition();
                if(index!=RecyclerView.NO_POSITION)notifyItemChanged(index);
                actions.selectedChanged();
            }else if(entry.folder)actions.openFolder(path);
            else actions.openFile(path);
        });
        holder.card.setOnLongClickListener(v->{actions.menu(path);return true;});
        if(!entry.folder&&(entry.mime.startsWith("image/")||entry.mime.startsWith("video/"))){
            int ticket=generation;
            ThumbnailLoader.load(context,path,0,entry.mime.startsWith("video/"),
                new ThumbnailLoader.Result(){
                    public boolean current(){return ticket==generation &&
                        path.equals(holder.boundPath)&&!context.isFinishing()&&!context.isDestroyed();}
                    public void ready(android.graphics.Bitmap bitmap){holder.icon.setImageBitmap(bitmap);}
                });
        }
    }
    @Override public void onViewRecycled(@NonNull Holder holder){
        holder.boundPath="";holder.icon.setImageDrawable(null);
        super.onViewRecycled(holder);
    }
    @Override public int getItemCount(){return rows.size();}
}
