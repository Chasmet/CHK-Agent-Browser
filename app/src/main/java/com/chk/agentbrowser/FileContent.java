package com.chk.agentbrowser;

import android.content.Context;
import java.io.*;
import java.nio.charset.*;
import org.json.*;

/** Progressive, strict Unicode reader. The old character-offset protocol remains compatible. */
final class FileContent {
    static Reader reader(File file,String encoding)throws Exception{
        if(encoding.isEmpty())throw new IOException("Ce fichier n’est pas un texte Unicode reconnu");
        InputStream input=new FileInputStream(file);
        try{
            PushbackInputStream in=new PushbackInputStream(input,3);byte[] first=new byte[3];int n=in.read(first),skip=0;
            if(n>=3&&(first[0]&255)==239&&(first[1]&255)==187&&(first[2]&255)==191)skip=3;
            else if(n>=2&&((first[0]&255)==255&&(first[1]&255)==254||(first[0]&255)==254&&(first[1]&255)==255))skip=2;
            if(n>skip)in.unread(first,skip,n-skip);
            return new BufferedReader(new InputStreamReader(in,Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)),8192);
        }catch(Exception ex){input.close();throw ex;}
    }
    static JSONObject page(Context app,String path,int offset)throws Exception{
        if(offset<0)throw new IOException("Position texte invalide");JSONObject info=FileFormats.detect(app,path);if(!info.getString("kind").equals("text"))throw new IOException("Format binaire : utiliser files_preview ou files_detect_format");
        File f=new WorkspaceStore(app).file(path);long size=f.length(),updated=f.lastModified();StringBuilder text=new StringBuilder();int skipped=0;boolean more;
        try(Reader in=reader(f,info.getString("encoding"))){char[] buffer=new char[8192];while(skipped<offset){int n=in.read(buffer,0,Math.min(buffer.length,offset-skipped));if(n<0)break;skipped+=n;}
            while(text.length()<12000){int n=in.read(buffer,0,Math.min(buffer.length,12000-text.length()));if(n<0)break;text.append(buffer,0,n);}
            if(text.length()>0&&Character.isHighSurrogate(text.charAt(text.length()-1))){int low=in.read();if(low>=0)text.append((char)low);}
            more=in.read()!=-1;
        }
        if(size!=f.length()||updated!=f.lastModified())throw new IOException("Le fichier a changé pendant la lecture");
        return new JSONObject().put("path",path).put("text",text.toString()).put("offset",skipped).put("next_offset",more?skipped+text.length():-1)
            .put("size_bytes",size).put("updated",updated).put("encoding",info.getString("encoding")).put("format",info.getString("format"));
    }
    static String full(Context app,String path,int maximum)throws Exception{
        JSONObject info=FileFormats.detect(app,path);if(!info.getString("kind").equals("text"))throw new IOException("Texte non disponible pour ce format");StringBuilder text=new StringBuilder();
        try(Reader in=reader(new WorkspaceStore(app).file(path),info.getString("encoding"))){char[] b=new char[8192];int n;while((n=in.read(b))!=-1){if(text.length()+n>maximum)throw new IOException("Texte trop long pour cette opération ; utiliser les pages ou Partager");text.append(b,0,n);}}return text.toString();
    }
    static JSONObject hex(Context app,String path,long offset)throws Exception{
        File f=new WorkspaceStore(app).file(path);if(offset<0||offset>f.length())throw new IOException("Position binaire invalide");StringBuilder out=new StringBuilder();byte[] b=new byte[(int)Math.min(512,f.length()-offset)];
        try(RandomAccessFile in=new RandomAccessFile(f,"r")){in.seek(offset);in.readFully(b);}
        for(int at=0;at<b.length;at+=16){out.append(String.format(java.util.Locale.ROOT,"%08x  ",offset+at));for(int j=0;j<16;j++)out.append(at+j<b.length?String.format(java.util.Locale.ROOT,"%02x ",b[at+j]&255):"   ");out.append(" ");for(int j=at;j<Math.min(b.length,at+16);j++){int x=b[j]&255;out.append(x>=32&&x<=126?(char)x:'.');}out.append('\n');}
        return new JSONObject().put("path",path).put("text",out.toString()).put("format","hex").put("offset",offset).put("next_byte_offset",offset+b.length<f.length()?offset+b.length:-1).put("read_only",true);
    }
}
