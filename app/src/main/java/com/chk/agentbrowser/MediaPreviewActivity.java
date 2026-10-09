package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.File;

/** Image/video viewing stays on the phone; decoding is bounded and off the main thread. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class MediaPreviewActivity extends Activity {
    private ImageView image;
    private androidx.media3.exoplayer.ExoPlayer video;
    private Bitmap bitmap;
    private boolean closed;
    @Override public void onCreate(Bundle state){super.onCreate(state);String path=getIntent().getStringExtra("path");LinearLayout root=MobileUi.column(this);root.setBackgroundColor(0xFF0D1423);setContentView(root);
        TextView back=MobileUi.action(this,"‹ "+(path==null?"Fichier":path.substring(path.lastIndexOf('/')+1)));root.addView(back);back.setOnClickListener(v->finish());
        try{WorkspaceStore s=new WorkspaceStore(this);String mime=s.mime(path);
            if(mime.startsWith("video/")||mime.startsWith("audio/")){video=new androidx.media3.exoplayer.ExoPlayer.Builder(this).build();androidx.media3.ui.PlayerView view=new androidx.media3.ui.PlayerView(this);view.setPlayer(video);view.setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING);root.addView(view,new LinearLayout.LayoutParams(-1,0,1));video.setMediaItem(androidx.media3.common.MediaItem.fromUri(s.uri(path)));if(state!=null)video.seekTo(state.getInt("position",0));video.addListener(new androidx.media3.common.Player.Listener(){@Override public void onPlayerError(androidx.media3.common.PlaybackException error){new android.app.AlertDialog.Builder(MediaPreviewActivity.this).setTitle("Format indisponible").setMessage("Ce codec ne peut pas être lu sur ce téléphone. Tu peux ouvrir le fichier dans une autre application.").setNegativeButton("Fermer",null).setPositiveButton("Ouvrir ailleurs",(d,n)->{try{startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(s.uri(path),mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Ouvrir le média"));}catch(Exception e){Toast.makeText(MediaPreviewActivity.this,"Aucune application compatible",Toast.LENGTH_LONG).show();}}).show();}});video.prepare();video.play();}
            else{image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("Aperçu de "+path);root.addView(image,new LinearLayout.LayoutParams(-1,0,1));TextView hint=MobileUi.text(this,"Pince l’image pour zoomer · Double touche pour réinitialiser",14,MobileUi.MUTED);root.addView(hint);
                ScaleGestureDetector pinch=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){float scale=Math.max(1f,Math.min(4f,image.getScaleX()*d.getScaleFactor()));image.setScaleX(scale);image.setScaleY(scale);return true;}});
                GestureDetector gestures=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){@Override public boolean onDown(MotionEvent e){return true;}@Override public boolean onDoubleTap(MotionEvent e){image.setScaleX(1);image.setScaleY(1);image.setTranslationX(0);image.setTranslationY(0);return true;}@Override public boolean onScroll(MotionEvent e1,MotionEvent e2,float x,float y){if(image.getScaleX()>1){image.setTranslationX(image.getTranslationX()-x);image.setTranslationY(image.getTranslationY()-y);}return true;}});
                image.setOnTouchListener((v,e)->{pinch.onTouchEvent(e);if(!pinch.isInProgress())gestures.onTouchEvent(e);if(e.getAction()==MotionEvent.ACTION_UP)v.performClick();return true;});
                WorkspaceCommands.IO.execute(()->{Bitmap loaded=null;try{File f=s.file(path);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getPath(),bounds);if(bounds.outWidth<=0)throw new Exception("Image illisible");BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=1;while(bounds.outWidth/opts.inSampleSize>1600||bounds.outHeight/opts.inSampleSize>1600)opts.inSampleSize*=2;loaded=BitmapFactory.decodeFile(f.getPath(),opts);if(loaded==null)throw new Exception("Image illisible");
                    try{android.media.ExifInterface exif=new android.media.ExifInterface(f.getPath());int orientation=exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);Matrix m=new Matrix();if(orientation==6)m.postRotate(90);else if(orientation==3)m.postRotate(180);else if(orientation==8)m.postRotate(270);if(orientation==6||orientation==3||orientation==8){Bitmap rotated=Bitmap.createBitmap(loaded,0,0,loaded.getWidth(),loaded.getHeight(),m,true);if(rotated!=loaded){loaded.recycle();loaded=rotated;}}}catch(Exception ignored){}
                    Bitmap ready=loaded;WorkspaceCommands.UI.post(()->{if(closed){ready.recycle();return;}bitmap=ready;image.setImageBitmap(ready);});
                }catch(Exception e){if(loaded!=null)loaded.recycle();WorkspaceCommands.UI.post(()->{if(!closed)hint.setText("Aperçu impossible. Ouvre le fichier avec une application compatible.");});}});
            }
        }catch(Exception e){root.addView(MobileUi.text(this,"Fichier inaccessible",18,MobileUi.TEXT));}
    }
    @Override protected void onPause(){if(video!=null)video.pause();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(video!=null)out.putInt("position",(int)video.getCurrentPosition());}
    @Override protected void onDestroy(){closed=true;if(video!=null)video.release();if(image!=null)image.setImageDrawable(null);if(bitmap!=null)bitmap.recycle();super.onDestroy();}
}
