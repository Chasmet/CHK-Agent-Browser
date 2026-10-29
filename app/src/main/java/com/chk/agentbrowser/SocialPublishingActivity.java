package com.chk.agentbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native social publishing launcher; uses the SAME browser engine and saved
 * sessions as CHK Agent Browser. The remote platform is always the authority
 * for upload acceptance and actual scheduled publication.
 *
 * This intentionally does not embed another installed Android app, request
 * platform passwords or claim a prepared journal is a confirmed post.
 */
public final class SocialPublishingActivity extends Activity {
    private static final int BG = 0xff121a27, SURFACE = 0xff223047;
    private static final int FG = 0xfff2f6fe, DIM = 0xffb6c8dc, TEAL = 0xff69e2c6;
    private LinearLayout jobs;
    private TextView count;
    private int dp(int n) { return (int)(n*getResources().getDisplayMetrics().density+0.5f); }
    private TextView text(String content,int sp,int color) {
        TextView t=new TextView(this);t.setText(content);t.setTextSize(sp);t.setTextColor(color);
        t.setPadding(dp(10),dp(10),dp(10),dp(10));return t;
    }
    private LinearLayout group(LinearLayout parent) {
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(9),dp(7),dp(9),dp(7));box.setBackgroundColor(SURFACE);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(6),0,dp(9));parent.addView(box,lp);return box;
    }
    private TextView action(LinearLayout parent,String title,Runnable task) {
        TextView btn=text(title,16,FG);btn.setMinHeight(dp(48));
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setBackgroundColor(0xff304860);parent.addView(btn);
        btn.setOnClickListener(v->task.run());return btn;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("CHK Réseaux");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(8),dp(14),dp(18));root.setBackgroundColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
        root.addView(text("RÉSEAUX · CHK",24,FG));
        root.addView(text("TikTok, YouTube et Instagram dans le navigateur CHK. Les comptes et les fichiers restent dans ton environnement.",13,DIM));
        LinearLayout sites=group(root);
        sites.addView(text("OUVRIR UN STUDIO",15,TEAL));
        action(sites,"TikTok Studio · importer et programmer",()->open("https://www.tiktok.com/tiktokstudio/upload?from=creator_center&tab=video"));
        divider(sites);
        action(sites,"YouTube Studio · vidéos et Shorts",()->open("https://studio.youtube.com/"));
        divider(sites);
        action(sites,"Instagram · créer une publication",()->open("https://www.instagram.com/"));
        LinearLayout help=group(root);
        help.addView(text("Importation : depuis le studio, utilise « Sélectionner une vidéo » puis le sélecteur CHK pour retrouver tes MP4. Le site doit accepter le fichier avant de programmer.",13,DIM));
        help.addView(text("Une fiche préparée par ChatGPT n'est PAS une programmation confirmée. Vérifie toujours la date et l'heure affichées par la plateforme.",13,TEAL));
        LinearLayout planned=group(root);
        planned.addView(text("PUBLICATIONS PRÉPARÉES ET STATUTS",15,TEAL));
        count=text("Chargement des fiches locales…",12,DIM);planned.addView(count);
        action(planned,"Actualiser les fiches",this::load);
        jobs=new LinearLayout(this);jobs.setOrientation(LinearLayout.VERTICAL);
        planned.addView(jobs);
        load();
    }
    private void divider(LinearLayout group) {
        View v=new View(this);v.setBackgroundColor(BG);group.addView(v,new LinearLayout.LayoutParams(-1,dp(5)));
    }
    private void open(String url) {
        Intent intent=new Intent(this,MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("open_url",url);
        startActivity(intent);
    }
    private void load() {
        if(jobs==null)return;
        jobs.removeAllViews();
        try {
            JSONObject listing=PublicationJobs.command(this,PublicationJobs.ACTION_LIST,new JSONObject());
            JSONArray items=listing.optJSONArray("items");
            int total=items==null?0:items.length();
            count.setText(total+" fiches locales · statut publié uniquement après vérification réelle");
            if(total==0) {
                jobs.addView(text("Aucune fiche préparée. Utilise ChatGPT ou Cut pour préparer une publication.",13,DIM));
                return;
            }
            for(int i=0;i<Math.min(30,total);i++) {
                JSONObject entry=items.optJSONObject(i);if(entry==null)continue;
                String id=entry.optString("id"),platform=entry.optString("platform");
                TextView row=text(platform.toUpperCase()+" · "+entry.optString("title","Sans titre")
                    +"\n"+label(entry.optString("status","unknown")),13,FG);
                row.setBackgroundColor((i%2)==0?0xff29394e:0xff25364b);
                row.setOnClickListener(v->details(id));
                jobs.addView(row);
            }
        } catch(Exception e) {
            count.setText("Lecture des fiches impossible : "+e.getMessage());
        }
    }
    private static String label(String s) {
        if("prepared".equals(s))return "PRÉPARÉ · pas encore programmé";
        if("uncertain".equals(s)||"click_pending".equals(s))return "À VÉRIFIER SUR LA PLATEFORME";
        if("schedule_browser_confirmed".equals(s))return "Programmation indiquée par le navigateur · à contrôler";
        if("browser_confirmed".equals(s))return "Publication indiquée par le navigateur · à contrôler";
        if("cancelled".equals(s))return "Annulé";
        return s;
    }
    private void details(String id) {
        try {
            JSONObject job=PublicationJobs.command(this,PublicationJobs.ACTION_STATUS,
                    new JSONObject().put("id",id));
            String title=job.optString("title","Publication");
            String planned=job.optString("schedule_at","");
            JSONArray files=job.optJSONArray("media");
            StringBuilder fileList=new StringBuilder();
            if(files!=null)for(int i=0;i<files.length();i++) {
                JSONObject f=files.optJSONObject(i);if(f!=null)
                    fileList.append("\n").append(f.optString("path"));
            }
            String message="Plateforme : "+job.optString("platform","")
                +"\nStatut : "+label(job.optString("status",""))
                +"\nDate prévue : "+(planned.isEmpty()?"Publication immédiate":planned)
                +"\nFichiers CHK : "+fileList
                +"\n\n"+job.optString("caption","")
                +"\n\nLa confirmation doit venir du site distant, pas uniquement de cette fiche.";
            String caption=job.optString("caption","");
            String destination=job.optString("destination_url","");
            new AlertDialog.Builder(this)
                .setTitle(title).setMessage(message)
                .setPositiveButton("Ouvrir le studio",(dialog,which)->{
                    if(PublicationJobs.validDestination(job.optString("platform",""),destination))open(destination);
                    else Toast.makeText(this,"Adresse du studio invalide",Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("Copier la légende",(dialog,which)->{
                    ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("CHK légende",caption));
                    Toast.makeText(this,"Légende copiée",Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Fermer",null).show();
        } catch(Exception e) {
            new AlertDialog.Builder(this).setMessage("Fiche indisponible : "+e.getMessage())
                .setPositiveButton("Fermer",null).show();
        }
    }
}
