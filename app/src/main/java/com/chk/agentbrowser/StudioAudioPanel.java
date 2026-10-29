package com.chk.agentbrowser;

import android.app.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Touch controls use the exact native command handlers, revision guards and receipts used by MCP. */
final class StudioAudioPanel {
    private final Activity activity;private final Runnable changed;private JSONObject project;
    private StudioAudioPanel(Activity a,Runnable c){activity=a;changed=c;}
    static void show(Activity activity,Runnable changed){new StudioAudioPanel(activity,changed).load();}
    void load(){VideoEditorEngine.DISK.execute(()->{try{JSONObject p=VideoEditorEngine.get().load(activity);WorkspaceCommands.UI.post(()->{if(activity.isFinishing())return;project=p;menu();});}catch(Exception e){error(e);}});}
    void menu(){try{
        ArrayList<String> labels=new ArrayList<>();ArrayList<JSONObject> targets=new ArrayList<>();JSONArray tracks=project.optJSONArray("audio_tracks");
        if(tracks==null)tracks=new JSONArray().put(StudioAudio.track("original","Voix originale")).put(StudioAudio.track("audio","Audio séquentiel existant"));
        for(int i=0;i<tracks.length();i++){JSONObject t=tracks.getJSONObject(i);labels.add("Piste · "+t.optString("name",t.getString("id"))+" · "+Math.round(t.optDouble("volume",1)*100)+" %"+(t.optBoolean("mute")?" · Muet":"")+(t.optBoolean("solo")?" · Solo":""));targets.add(new JSONObject().put("scope","track").put("track_id",t.getString("id")));}
        JSONArray clips=StudioAudio.sources(project);for(int i=0;i<clips.length();i++){JSONObject c=clips.getJSONObject(i);String path=c.getString("path");labels.add("Clip · "+path.substring(path.lastIndexOf('/')+1)+" · "+Math.round(c.optDouble("volume",1)*100)+" % · "+c.getLong("timeline_ms")+" ms");targets.add(new JSONObject().put("scope","clip").put("clip_id",c.getString("studio_id")));}
        new AgentDialog.Builder(activity).setTitle("Mixeur · pistes et clips")
            .setItems(labels.toArray(new String[0]),(d,index)->edit(targets.get(index)))
            .setNeutralButton("Nouvelle piste",(d,w)->{LinearLayout box=column();EditText name=field(box,"Nom de la piste","Musique");confirm("Nouvelle piste",box,()->{JSONObject args=request("audio_track_create");args.put("track_id",UUID.randomUUID().toString()).put("name",name.getText().toString());apply(args);});})
            .setNegativeButton("Fermer",null).show();
    }catch(Exception e){error(e);}}
    void edit(JSONObject selector){try{
        JSONObject target=StudioMcp.target(project,selector);String[] choices=selector.optString("scope").equals("track")?new String[]{"Volume, Mute, Solo et fondus","Automatisation du volume","Ducking automatique"}:new String[]{"Volume, Mute et fondus","Automatisation du volume","Position et vitesse"};
        new AgentDialog.Builder(activity).setTitle(selector.optString("scope").equals("track")?"Réglages de piste":"Réglages de clip").setItems(choices,(d,n)->{if(n==0)volume(selector,target);else if(n==1)keyframes(selector,target);else if(selector.optString("scope").equals("track"))ducking(selector,target);else position(selector,target);}).setNegativeButton("Fermer",null).show();
    }catch(Exception e){error(e);}}
    void volume(JSONObject selector,JSONObject target){try{
        LinearLayout box=column();Spinner units=new Spinner(activity);units.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"Pourcentage","Décibels"}));box.addView(units);
        EditText level=number(box,"Gain · 0 à 200 % ou dB",String.valueOf(target.optDouble("volume",1)*100));SeekBar slider=new SeekBar(activity);slider.setMax(200);slider.setProgress((int)Math.round(target.optDouble("volume",1)*100));box.addView(slider);
        TextView readout=MobileUi.text(activity,"0 % = silence ; 100 % = 0 dB ; 200 % = +6,02 dB",12,MobileUi.MUTED);box.addView(readout);
        units.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> a){}public void onItemSelected(android.widget.AdapterView<?> a,android.view.View v,int pos,long id){level.setText(pos==0?String.valueOf(target.optDouble("volume",1)*100):String.valueOf(StudioAudio.db(target.optDouble("volume",1))));}});
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int v,boolean user){if(user)level.setText(units.getSelectedItemPosition()==0?String.valueOf(v):String.valueOf(StudioAudio.db(v/100d)));}});
        CheckBox mute=check(box,"Mute · couper cette cible",target.optBoolean("mute"));CheckBox solo=selector.optString("scope").equals("track")?check(box,"Solo · écouter cette piste",target.optBoolean("solo")):null;
        EditText fadeIn=number(box,"Fondu d'entrée · ms",String.valueOf(target.optLong("audio_fade_in_ms"))),fadeOut=number(box,"Fondu de sortie · ms",String.valueOf(target.optLong("audio_fade_out_ms")));
        confirm("Volume et fondus",box,()->{JSONObject args=select(request("audio_set_volume"),selector).put(units.getSelectedItemPosition()==0?"percent":"db",units.getSelectedItemPosition()==1&&level.getText().toString().equals("-Infinity")?"-Infinity":Double.parseDouble(level.getText().toString().replace(',','.')))
            .put("mute",mute.isChecked()).put("audio_fade_in_ms",Long.parseLong(fadeIn.getText().toString())).put("audio_fade_out_ms",Long.parseLong(fadeOut.getText().toString()));if(solo!=null)args.put("solo",solo.isChecked());apply(args);});
    }catch(Exception e){error(e);}}
    void keyframes(JSONObject selector,JSONObject target){try{
        JSONArray points=target.optJSONArray("gain_keyframes");if(points==null)points=new JSONArray();final JSONArray list=points;String[] labels=new String[list.length()+1];labels[0]="＋ Ajouter un point";
        for(int i=0;i<list.length();i++)labels[i+1]=list.getJSONObject(i).getLong("time_ms")+" ms · "+Math.round(list.getJSONObject(i).getDouble("gain")*100)+" %";
        new AgentDialog.Builder(activity).setTitle("Automatisation · interpolation linéaire").setItems(labels,(d,index)->{try{
            JSONObject point=index==0?new JSONObject().put("id",UUID.randomUUID().toString()).put("time_ms",0).put("gain",1):list.getJSONObject(index-1);LinearLayout box=column();
            box.addView(MobileUi.text(activity,"Temps local du clip ; pour une piste, temps du montage. Le gain du point multiplie le volume de base.",13,MobileUi.TEXT));
            EditText time=number(box,"Position · ms",String.valueOf(point.getLong("time_ms"))),gain=number(box,"Gain · %",String.valueOf(point.getDouble("gain")*100));
            AgentDialog.Builder dialog=new AgentDialog.Builder(activity).setTitle("Point de volume").setView(box).setPositiveButton("Enregistrer",(x,w)->{try{apply(select(request("audio_keyframes"),selector).put("mode","upsert").put("point_id",point.getString("id")).put("time_ms",Long.parseLong(time.getText().toString())).put("percent",Double.parseDouble(gain.getText().toString().replace(',','.'))));}catch(Exception e){error(e);}}).setNegativeButton("Annuler",null);
            if(index>0)dialog.setNeutralButton("Supprimer",(x,w)->{try{apply(select(request("audio_keyframes"),selector).put("mode","delete").put("point_id",point.getString("id")));}catch(Exception e){error(e);}});dialog.show();
        }catch(Exception e){error(e);}}).setNegativeButton("Fermer",null).show();
    }catch(Exception e){error(e);}}
    void ducking(JSONObject selector,JSONObject target){try{
        JSONObject d=target.optJSONObject("ducking");if(d==null)d=new JSONObject();LinearLayout box=column();box.addView(MobileUi.text(activity,"Vosk détecte les mots de la piste voix. Le premier usage télécharge le modèle vocal. Vérifie le résultat à l'écoute, notamment sur le rap.",13,MobileUi.TEXT));
        CheckBox enabled=check(box,"Activer le ducking",d.optBoolean("enabled"));Spinner voice=new Spinner(activity);ArrayList<String> ids=new ArrayList<>(),names=new ArrayList<>();JSONArray tracks=project.optJSONArray("audio_tracks");if(tracks==null)tracks=new JSONArray().put(StudioAudio.track("original","Voix originale")).put(StudioAudio.track("audio","Audio"));for(int i=0;i<tracks.length();i++){JSONObject t=tracks.getJSONObject(i);if(!selector.getString("track_id").equals(t.getString("id"))){ids.add(t.getString("id"));names.add(t.optString("name",t.getString("id")));}}voice.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,names));box.addView(voice);int selected=ids.indexOf(d.optString("voice_track_id","original"));if(selected>=0)voice.setSelection(selected);
        EditText reduction=number(box,"Réduction · dB (−60 à 0)",String.valueOf(d.optDouble("reduction_db",-18))),threshold=number(box,"Seuil voix · dBFS",String.valueOf(d.optDouble("threshold_dbfs",-40))),attack=number(box,"Attaque · ms",String.valueOf(d.optInt("attack_ms",100))),release=number(box,"Relâchement · ms",String.valueOf(d.optInt("release_ms",500)));
        confirm("Ducking automatique",box,()->{if(ids.isEmpty())throw new Exception("Ajoute une piste voix différente");apply(select(request("audio_ducking"),selector).put("config",new JSONObject().put("enabled",enabled.isChecked()).put("voice_track_id",ids.get(voice.getSelectedItemPosition())).put("reduction_db",Double.parseDouble(reduction.getText().toString().replace(',','.'))).put("threshold_dbfs",Double.parseDouble(threshold.getText().toString().replace(',','.'))).put("attack_ms",Integer.parseInt(attack.getText().toString())).put("release_ms",Integer.parseInt(release.getText().toString()))));});
    }catch(Exception e){error(e);}}
    void position(JSONObject selector,JSONObject target){try{
        JSONObject source=StudioMcp.source(project,target);if(!source.getString("lane").equals("audio"))throw new Exception("Pour une vidéo, utilise Durée et Vitesse dans la barre d'outils");
        LinearLayout box=column();EditText at=number(box,"Position dans le montage · ms",String.valueOf(source.getLong("timeline_ms"))),speed=number(box,"Vitesse · 0,25 à 4",String.valueOf(target.optDouble("speed",1)));Spinner track=new Spinner(activity);ArrayList<String> ids=new ArrayList<>();JSONArray tracks=project.optJSONArray("audio_tracks");if(tracks==null)tracks=new JSONArray().put(StudioAudio.track("original","Voix originale")).put(StudioAudio.track("audio","Audio"));for(int i=0;i<tracks.length();i++)ids.add(tracks.getJSONObject(i).getString("id"));track.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,ids));box.addView(track);int selected=ids.indexOf(source.getString("track_id"));if(selected>=0)track.setSelection(selected);
        box.addView(MobileUi.text(activity,"Le changement de vitesse rééchantillonne le son et modifie sa hauteur.",12,MobileUi.MUTED));
        confirm("Position du clip audio",box,()->apply(select(request("audio_clip_set"),selector).put("offset_ms",Long.parseLong(at.getText().toString())).put("speed",Double.parseDouble(speed.getText().toString().replace(',','.'))).put("track_id",ids.get(track.getSelectedItemPosition()))));
    }catch(Exception e){error(e);}}
    JSONObject request(String op)throws Exception{return new JSONObject().put("operation",op).put("project_id",project.getString("project_id")).put("expected_revision",project.getInt("revision")).put("request_id",UUID.randomUUID().toString());}
    JSONObject select(JSONObject args,JSONObject selector)throws Exception{for(Iterator<String> keys=selector.keys();keys.hasNext();){String key=keys.next();args.put(key,selector.get(key));}return args;}
    void apply(JSONObject args){VideoEditorEngine.DISK.execute(()->{try{StudioMcp.dispatch(activity,args);WorkspaceCommands.UI.post(()->{changed.run();load();});}catch(Exception e){error(e);}});}
    interface Action {void run()throws Exception;}
    void confirm(String title,LinearLayout box,Action apply){ScrollView scroll=new ScrollView(activity);scroll.addView(box);new AgentDialog.Builder(activity).setTitle(title).setView(scroll).setPositiveButton("Appliquer",(d,w)->{try{apply.run();}catch(Exception e){error(e);}}).setNegativeButton("Annuler",null).show();}
    LinearLayout column(){LinearLayout box=MobileUi.column(activity);box.setPadding(24,12,24,12);return box;}
    EditText field(LinearLayout box,String label,String value){box.addView(MobileUi.text(activity,label,13,MobileUi.TEXT));EditText e=new EditText(activity);e.setText(value);e.setTextColor(MobileUi.TEXT);box.addView(e);return e;}
    EditText number(LinearLayout box,String label,String value){EditText e=field(box,label,value);e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);return e;}
    CheckBox check(LinearLayout box,String label,boolean value){CheckBox c=new CheckBox(activity);c.setText(label);c.setTextColor(MobileUi.TEXT);c.setChecked(value);box.addView(c);return c;}
    void error(Exception e){WorkspaceCommands.UI.post(()->{if(!activity.isFinishing())Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show();});}
}
