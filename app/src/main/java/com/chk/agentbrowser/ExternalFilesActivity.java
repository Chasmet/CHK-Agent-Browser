package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/** The Android picker is the only authority for access outside CHK. */
public final class ExternalFilesActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);show();if(state==null&&getIntent().getBooleanExtra("pick",false))pick();}
    private void pick(){try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),741);}catch(Exception e){toast("Sélecteur Android indisponible.");}}
    private void show(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(android.graphics.Color.rgb(13,20,35));LinearLayout box=MobileUi.column(this);box.setPadding(24,24,24,24);scroll.addView(box);setContentView(scroll);
        box.addView(MobileUi.text(this,"Dossiers autorisés à l’agent",22,MobileUi.TEXT));
        box.addView(MobileUi.text(this,"Choisis les dossiers externes accessibles à CHK. Tu peux retirer chaque accès ici.",16,MobileUi.TEXT));
        TextView add=MobileUi.action(this,"Autoriser un dossier Android");box.addView(add);add.setOnClickListener(v->pick());
        try{JSONArray roots=ExternalFiles.grants(this);for(int i=0;i<roots.length();i++){JSONObject root=roots.getJSONObject(i);TextView row=MobileUi.action(this,root.getString("name")+"\nRetirer cet accès");box.addView(row);row.setOnClickListener(v->{try{ExternalFiles.revoke(this,root.optString("id"));show();}catch(Exception e){toast(e.getMessage());}});}}
        catch(Exception e){toast(e.getMessage());}
        TextView close=MobileUi.action(this,"Retour");box.addView(close);close.setOnClickListener(v->finish());
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==741&&result==RESULT_OK&&data!=null&&data.getData()!=null){
        try{int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getContentResolver().takePersistableUriPermission(data.getData(),flags);toast("Dossier autorisé.");show();}catch(Exception e){toast("Autorisation persistante indisponible : "+e.getMessage());}
    }}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
}
