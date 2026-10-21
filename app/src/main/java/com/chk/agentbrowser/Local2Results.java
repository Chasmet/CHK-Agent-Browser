package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Keep every response valid JSON under the legacy Windows 24,000-character result limit. */
final class Local2Results {
    private Local2Results(){}
    static String prepare(Context c,String message)throws Exception{
        if(message==null)return "";
        // Return an actual transport-sized preview, rather than a truncated Base64 string.
        try{
            JSONObject json=new JSONObject(message);String data=json.optString("jpeg_base64","");
            if(!data.isEmpty()&&data.length()>16000){
                byte[] encoded=Base64.decode(data,Base64.DEFAULT);Bitmap original=BitmapFactory.decodeByteArray(encoded,0,encoded.length);
                if(original!=null){
                    Bitmap small=null;try{
                        int edge=Math.max(original.getWidth(),original.getHeight());float scale=Math.min(1,360f/edge);
                        small=Bitmap.createScaledBitmap(original,Math.max(1,Math.round(original.getWidth()*scale)),Math.max(1,Math.round(original.getHeight()*scale)),true);
                        byte[] image=null;for(int quality=70;quality>=10;quality-=10){ByteArrayOutputStream out=new ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,quality,out);image=out.toByteArray();if(image.length<=11000)break;}
                        if(image!=null&&image.length<=11000){json.put("jpeg_base64",Base64.encodeToString(image,Base64.NO_WRAP)).put("preview_width",small.getWidth()).put("preview_height",small.getHeight()).put("preview_resized_for_transport",true);message=json.toString();}
                    }finally{if(small!=null&&small!=original)small.recycle();original.recycle();}
                }
            }
        }catch(org.json.JSONException ignored){}
        if(message.length()<=23000)return message;
        byte[] bytes=message.getBytes(StandardCharsets.UTF_8);if(bytes.length>2*1024*1024)throw new IOException("Résultat supérieur à 2 Mo : demander une page plus petite.");
        WorkspaceStore store=new WorkspaceStore(c);File directory=store.file("AgentResults");
        if(!directory.exists())store.mkdir("AgentResults");
        File stage=new File(directory,".result-"+UUID.randomUUID());String path="AgentResults/Result-"+UUID.randomUUID()+".json";File target=store.file(path);
        try{
            try(FileOutputStream out=new FileOutputStream(stage)){out.write(bytes);out.getFD().sync();}
            if(!stage.renameTo(target))throw new IOException("Sauvegarde du résultat impossible.");
            return new JSONObject().put("result_in_file",true).put("result_file",path).put("size",target.length()).put("updated",target.lastModified())
                .put("sha256",WorkspaceTransfer.hash(target)).put("read_action","files_transfer_read").put("truncated",false).toString();
        }finally{stage.delete();}
    }
}
