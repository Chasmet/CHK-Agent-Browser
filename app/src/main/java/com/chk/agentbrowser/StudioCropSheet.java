package com.chk.agentbrowser;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.common.MediaItem;

/** Four-corner source-space handles with an actual Transformer preview before commit. */
final class StudioCropSheet {
    interface Apply{void apply(JSONObject candidate);}
    private StudioCropSheet(){}
    static void open(Activity activity,JSONObject project,int index,Apply apply){
        try{JSONObject candidate=new JSONObject(project.toString()),clip=candidate.getJSONArray("clips").getJSONObject(index);WorkspaceCommands.IO.execute(()->{MediaMetadataRetriever decoder=new MediaMetadataRetriever();try{
            JSONObject size=StudioCrop.dimensions(activity,clip);decoder.setDataSource(new WorkspaceStore(activity).file(clip.getString("path")).getAbsolutePath());long at=(clip.optLong("start_ms")+clip.optLong("duration_ms")/2)*1000;Bitmap frame;
            if(Build.VERSION.SDK_INT>=27)frame=decoder.getScaledFrameAtTime(at,MediaMetadataRetriever.OPTION_CLOSEST,720,720);else frame=decoder.getFrameAtTime(at,MediaMetadataRetriever.OPTION_CLOSEST);
            if(frame==null)throw new IOException("Image source indisponible");final Bitmap bitmap=frame;
            WorkspaceCommands.UI.post(()->{if(activity.isFinishing()){bitmap.recycle();return;}try{show(activity,candidate,index,size,bitmap,apply);}catch(Exception e){bitmap.recycle();Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show();}});
        }catch(Exception e){WorkspaceCommands.UI.post(()->Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show());}finally{decoder.release();}});
        }catch(Exception e){Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private static void show(Activity a,JSONObject candidate,int index,JSONObject size,Bitmap bitmap,Apply apply)throws Exception{
        JSONObject clip=candidate.getJSONArray("clips").getJSONObject(index),crop=StudioCrop.value(clip);Dialog dialog=new Dialog(a);LinearLayout root=MobileUi.column(a);root.setPadding(12,8,12,8);root.setBackgroundColor(0xff17181c);dialog.setContentView(root);
        TextView title=MobileUi.text(a,"Recadrer · sans modifier la durée",18,MobileUi.TEXT);root.addView(title);TextView coordinates=MobileUi.text(a,"",12,MobileUi.MUTED);coordinates.setTag("studio_crop_coordinates");root.addView(coordinates);
        CropView view=new CropView(a,bitmap,crop,size.getInt("width"),size.getInt("height"));view.setTag("studio_crop_handles");root.addView(view,new LinearLayout.LayoutParams(-1,0,1));view.changed=()->coordinates.setText(view.description());view.changed.run();
        HorizontalScrollView ratios=new HorizontalScrollView(a);ratios.setFillViewport(true);LinearLayout ratioRow=new LinearLayout(a);ratios.addView(ratioRow);root.addView(ratios);
        for(String value:new String[]{"free","9:16","16:9","1:1","4:5"})button(a,ratioRow,value.equals("free")?"Libre":value,()->{try{view.ratio(value);}catch(Exception e){toast(a,e);}});
        ScrollView scroll=new ScrollView(a);LinearLayout controls=MobileUi.column(a);scroll.addView(controls);root.addView(scroll,new LinearLayout.LayoutParams(-1,MobileUi.dp(a,230)));
        EditText rotation=new EditText(a);rotation.setTextColor(MobileUi.TEXT);rotation.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);rotation.setText(String.valueOf(crop.optDouble("rotation")));rotation.setHint("Rotation en degrés · -180 à 180");rotation.setContentDescription("Rotation précise en degrés");controls.addView(rotation);
        LinearLayout transforms=new LinearLayout(a);controls.addView(transforms);button(a,transforms,"↻ 90°",()->{try{double v=Double.parseDouble(rotation.getText().toString().replace(',','.'));rotation.setText(String.valueOf(((v+270)%360)-180));}catch(Exception e){toast(a,e);}});
        button(a,transforms,"↔",()->{try{crop.put("flip_x",!crop.optBoolean("flip_x"));view.refresh();}catch(Exception e){toast(a,e);}});button(a,transforms,"↕",()->{try{crop.put("flip_y",!crop.optBoolean("flip_y"));view.refresh();}catch(Exception e){toast(a,e);}});
        slider(a,controls,"Zoom",100,400,(int)(crop.optDouble("zoom",1)*100),v->{try{crop.put("zoom",v/100d);view.refresh();}catch(Exception e){toast(a,e);}});
        slider(a,controls,"Déplacement image X",-100,100,(int)(crop.optDouble("pan_x")*100),v->{try{crop.put("pan_x",v/100d);view.refresh();}catch(Exception e){toast(a,e);}});
        slider(a,controls,"Déplacement image Y",-100,100,(int)(crop.optDouble("pan_y")*100),v->{try{crop.put("pan_y",v/100d);view.refresh();}catch(Exception e){toast(a,e);}});
        LinearLayout bottom=new LinearLayout(a);root.addView(bottom);
        Runnable capture=()->{try{crop.put("rotation",Double.parseDouble(rotation.getText().toString().replace(',','.')));clip.put("crop",new JSONObject(crop.toString()));StudioCrop.validate(clip);}catch(Exception e){throw new IllegalArgumentException(e.getMessage());}};
        button(a,bottom,"Réinitialiser",()->{try{JSONObject fresh=StudioCrop.reset();for(Iterator<String> it=fresh.keys();it.hasNext();){String k=it.next();crop.put(k,fresh.get(k));}rotation.setText("0");for(int i=0;i<controls.getChildCount();i++)if(controls.getChildAt(i) instanceof SeekBar){SeekBar bar=(SeekBar)controls.getChildAt(i);bar.setProgress("Zoom".contentEquals(bar.getContentDescription())?0:100);}view.refresh();}catch(Exception e){toast(a,e);}});
        button(a,bottom,"Aperçu rendu",()->{try{capture.run();preview(a,candidate,index);}catch(Exception e){toast(a,e);}});
        LinearLayout actions=new LinearLayout(a);root.addView(actions);button(a,actions,"Annuler",dialog::dismiss);button(a,actions,"Valider",()->{try{capture.run();apply.apply(candidate);dialog.dismiss();}catch(Exception e){toast(a,e);}});
        dialog.setOnDismissListener(d->bitmap.recycle());dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-1);
    }
    interface Number{void set(int value);}
    private static void slider(Activity a,LinearLayout box,String label,int min,int max,int initial,Number change){TextView value=MobileUi.text(a,label+" · "+initial,13,MobileUi.TEXT);box.addView(value);SeekBar bar=new SeekBar(a);bar.setContentDescription(label);bar.setMax(max-min);bar.setProgress(initial-min);box.addView(bar);bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int v,boolean user){value.setText(label+" · "+(v+min));change.set(v+min);}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});}
    private static void button(Activity a,LinearLayout row,String text,Runnable action){Button b=new Button(a);b.setText(text);b.setTextSize(12);row.addView(b,new LinearLayout.LayoutParams(0,MobileUi.dp(a,50),1));b.setOnClickListener(v->action.run());}
    private static void toast(Activity a,Exception e){Toast.makeText(a,e.getMessage(),Toast.LENGTH_LONG).show();}
    private static void preview(Activity a,JSONObject p,int index)throws Exception{
        JSONObject copy=new JSONObject(p.toString()),clip=new JSONObject(copy.getJSONArray("clips").getJSONObject(index).toString());clip.put("duration_ms",Math.min(3000,clip.optLong("duration_ms")));copy.put("clips",new JSONArray().put(clip)).put("audio",new JSONArray()).put("layers",new JSONArray()).put("subtitles",new JSONArray()).put("mute_original",true);copy.remove("audio_mix_version");
        StudioRenderPreview render=new StudioRenderPreview(a);Toast.makeText(a,"Préparation du rendu réel…",Toast.LENGTH_SHORT).show();render.start(copy,new StudioRenderPreview.Result(){public void failed(String error){Toast.makeText(a,error,Toast.LENGTH_LONG).show();}public void ready(File file){if(a.isFinishing()){render.cancel();return;}Dialog d=new Dialog(a);PlayerView view=new PlayerView(a);ExoPlayer player=new ExoPlayer.Builder(a).build();view.setPlayer(player);d.setContentView(view);d.setOnDismissListener(x->{player.release();render.cancel();});d.show();d.getWindow().setLayout(-1,-1);player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)));player.prepare();player.play();}});
    }
    static final class CropView extends View {
        final Bitmap image;final JSONObject crop;final int sourceW,sourceH;final Paint paint=new Paint(3);final RectF bounds=new RectF();Runnable changed=()->{};float lastX,lastY;int handle=-1;final ScaleGestureDetector scale;
        CropView(Context c,Bitmap b,JSONObject json,int w,int h){super(c);image=b;crop=json;sourceW=w;sourceH=h;setContentDescription("Cadre de recadrage à quatre poignées");scale=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){public boolean onScale(ScaleGestureDetector d){try{crop.put("zoom",Math.max(1,Math.min(4,crop.optDouble("zoom",1)*d.getScaleFactor())));refresh();}catch(Exception ignored){}return true;}});}
        String description(){return String.format(Locale.FRANCE,"x %.0f · y %.0f · %.0f × %.0f px · %s · zoom %.2f×",crop.optDouble("x")*sourceW,crop.optDouble("y")*sourceH,crop.optDouble("width",1)*sourceW,crop.optDouble("height",1)*sourceH,crop.optString("ratio","free"),crop.optDouble("zoom",1));}
        void refresh(){invalidate();changed.run();}
        void ratio(String ratio)throws Exception{crop.put("ratio",ratio);if(!ratio.equals("free")){double w=crop.optDouble("width",1),h=w*sourceW/sourceH/VideoEditorEngine.ratio(ratio);if(h>1-crop.optDouble("y")){h=1-crop.optDouble("y");w=h*sourceH/sourceW*VideoEditorEngine.ratio(ratio);}crop.put("width",Math.min(1-crop.optDouble("x"),w)).put("height",h);}refresh();}
        private RectF rectangle(){return new RectF(bounds.left+(float)crop.optDouble("x")*bounds.width(),bounds.top+(float)crop.optDouble("y")*bounds.height(),bounds.left+(float)(crop.optDouble("x")+crop.optDouble("width",1))*bounds.width(),bounds.top+(float)(crop.optDouble("y")+crop.optDouble("height",1))*bounds.height());}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float pad=24,fit=Math.min((getWidth()-pad*2)/sourceW,(getHeight()-pad*2)/sourceH);bounds.set((getWidth()-sourceW*fit)/2,(getHeight()-sourceH*fit)/2,(getWidth()+sourceW*fit)/2,(getHeight()+sourceH*fit)/2);paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.FILL);c.drawBitmap(image,null,bounds,paint);RectF r=rectangle();paint.setColor(0x99000000);c.drawRect(bounds.left,bounds.top,bounds.right,r.top,paint);c.drawRect(bounds.left,r.bottom,bounds.right,bounds.bottom,paint);c.drawRect(bounds.left,r.top,r.left,r.bottom,paint);c.drawRect(r.right,r.top,bounds.right,r.bottom,paint);paint.setColor(0xffc5bfff);paint.setStrokeWidth(4);paint.setStyle(Paint.Style.STROKE);c.drawRect(r,paint);paint.setStyle(Paint.Style.FILL);for(float x:new float[]{r.left,r.right})for(float y:new float[]{r.top,r.bottom})c.drawCircle(x,y,10,paint);try{JSONObject e=StudioCrop.effective(crop);paint.setColor(0xff69e2c6);paint.setStyle(Paint.Style.STROKE);c.drawRect(bounds.left+(float)e.getDouble("x")*bounds.width(),bounds.top+(float)e.getDouble("y")*bounds.height(),bounds.left+(float)(e.getDouble("x")+e.getDouble("width"))*bounds.width(),bounds.top+(float)(e.getDouble("y")+e.getDouble("height"))*bounds.height(),paint);}catch(Exception ignored){}paint.setStyle(Paint.Style.FILL);}
        @Override public boolean onTouchEvent(MotionEvent event){scale.onTouchEvent(event);if(scale.isInProgress())return true;if(bounds.width()<=0)return false;float x=event.getX(),y=event.getY();RectF r=rectangle();if(event.getActionMasked()==MotionEvent.ACTION_DOWN){handle=-1;float[] xs={r.left,r.right,r.left,r.right},ys={r.top,r.top,r.bottom,r.bottom};for(int i=0;i<4;i++)if(Math.hypot(x-xs[i],y-ys[i])<48)handle=i;lastX=x;lastY=y;return true;}if(event.getActionMasked()==MotionEvent.ACTION_MOVE){try{double left=crop.optDouble("x"),top=crop.optDouble("y"),right=left+crop.optDouble("width",1),bottom=top+crop.optDouble("height",1),dx=(x-lastX)/bounds.width(),dy=(y-lastY)/bounds.height();if(handle<0){double w=right-left,h=bottom-top;left=Math.max(0,Math.min(1-w,left+dx));top=Math.max(0,Math.min(1-h,top+dy));right=left+w;bottom=top+h;}else{if((handle&1)==0)left=Math.max(0,Math.min(right-.02,left+dx));else right=Math.min(1,Math.max(left+.02,right+dx));if(handle<2)top=Math.max(0,Math.min(bottom-.02,top+dy));else bottom=Math.min(1,Math.max(top+.02,bottom+dy));}crop.put("x",left).put("y",top).put("width",right-left).put("height",bottom-top);ratio(crop.optString("ratio","free"));lastX=x;lastY=y;refresh();}catch(Exception ignored){}return true;}if(event.getActionMasked()==MotionEvent.ACTION_UP){performClick();return true;}return true;}
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
