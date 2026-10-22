package com.chk.agentbrowser;

import android.content.Context;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Content-first detection. No preview executes HTML, scripts, macros, or binaries. */
final class FileFormats {
    static final Set<String> TEXT=new HashSet<>(Arrays.asList(("txt log ini cfg conf md markdown json jsonl ndjson xml yaml yml toml csv tsv html htm css js jsx ts tsx py java kt kts c h cpp hpp cs sql sh bash bat cmd ps1 srt vtt ass ssa lrc eml vcf ics svg obj mtl gltf stl dxf rtf properties gradle gitignore env cue m3u m3u8 pls").split(" ")));
    static JSONObject detect(Context app,String path)throws Exception{
        WorkspaceStore store=new WorkspaceStore(app);File file=store.file(path);
        if(!file.isFile())throw new IOException(file.exists()?"Le chemin désigne un dossier":"Fichier introuvable : "+path);
        if(!file.canRead())throw new IOException("Autorisation de lecture manquante");
        byte[] head=new byte[(int)Math.min(8192,file.length())];try(DataInputStream in=new DataInputStream(new FileInputStream(file))){in.readFully(head);}
        String ext=extension(path),format="binary",mime="application/octet-stream",kind="binary",encoding="",warning="";
        if(starts(head,new int[]{0x89,80,78,71,13,10,26,10})){format="png";mime="image/png";kind="image";}
        else if(starts(head,new int[]{255,216,255})){format="jpeg";mime="image/jpeg";kind="image";}
        else if(ascii(head,0,"GIF87a")||ascii(head,0,"GIF89a")){format="gif";mime="image/gif";kind="image";}
        else if(ascii(head,0,"RIFF")&&ascii(head,8,"WEBP")){format="webp";mime="image/webp";kind="image";}
        else if(ascii(head,0,"BM")){format="bmp";mime="image/bmp";kind="image";}
        else if(ascii(head,0,"%PDF-")){format="pdf";mime="application/pdf";kind="document";}
        else if(ascii(head,0,"SQLite format 3\0")){format="sqlite";mime="application/vnd.sqlite3";kind="database";}
        else if(starts(head,new int[]{80,75,3,4})||starts(head,new int[]{80,75,5,6})){
            format="zip";mime="application/zip";kind="archive";
            try(ZipFile zip=zip(file)){
                if(zip.size()>5000)warning="Archive de plus de 5000 entrées : aperçu limité";
                if(zip.getEntry("word/document.xml")!=null){format="docx";kind="document";mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document";}
                else if(zip.getEntry("xl/workbook.xml")!=null){format="xlsx";kind="document";mime="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";}
                else if(zip.getEntry("ppt/presentation.xml")!=null){format="pptx";kind="document";mime="application/vnd.openxmlformats-officedocument.presentationml.presentation";}
                else if(zip.getEntry("AndroidManifest.xml")!=null&&ext.equals("apk")){format="apk";kind="package";mime="application/vnd.android.package-archive";}
                else if(zip.getEntry("content.xml")!=null){format="opendocument";kind="document";}
                else if(zip.getEntry("META-INF/container.xml")!=null){format="epub";kind="archive";}
            }catch(ZipException ex){warning="Archive ZIP corrompue ou chiffrée";kind="binary";}
        }
        else if(ascii(head,0,"RIFF")&&ascii(head,8,"WAVE")){format="wav";kind="audio";mime="audio/wav";}
        else if(ascii(head,0,"fLaC")){format="flac";kind="audio";mime="audio/flac";}
        else if(ascii(head,0,"OggS")){format="ogg";kind="audio";mime="audio/ogg";}
        else if(ascii(head,0,"ID3")||head.length>1&&(head[0]&255)==255&&((head[1]&0xe0)==0xe0)&&(head[1]&255)!=254){format="mp3";kind="audio";mime="audio/mpeg";}
        else if(ascii(head,4,"ftyp")){format="mp4";kind="media";mime="video/mp4";if(ascii(head,8,"avif")){format="avif";kind="image";mime="image/avif";}else if(ascii(head,8,"heic")||ascii(head,8,"heif")||ascii(head,8,"mif1")){format="heif";kind="image";mime="image/heif";}}
        else if(starts(head,new int[]{0x1a,0x45,0xdf,0xa3})){format="mkv";kind="media";mime="video/x-matroska";}
        else if(ascii(head,0,"RIFF")&&ascii(head,8,"AVI ")){format="avi";kind="media";mime="video/x-msvideo";}
        else if(ascii(head,0,"MZ")||starts(head,new int[]{0x7f,69,76,70})){format="executable";warning="Binaire : aucune exécution dans CHK";}
        else if(starts(head,new int[]{0xd0,0xcf,0x11,0xe0,0xa1,0xb1,0x1a,0xe1})){format="ole";warning="Document Office ancien ou paquet Office chiffré : lecteur externe nécessaire";}
        else if(starts(head,new int[]{0x1f,0x8b})){format="gzip";warning="Compression GZIP détectée ; aperçu binaire disponible";}
        else if(ascii(head,0,"Rar!")){format="rar";warning="RAR détecté ; extraction non disponible dans ce lecteur";}
        else if(starts(head,new int[]{0x37,0x7a,0xbc,0xaf,0x27,0x1c})){format="7z";warning="7Z détecté ; extraction non disponible dans ce lecteur";}
        else if(ascii(head,0,"glTF")){format="glb";warning="Modèle GLB : informations binaires ; pas de moteur 3D intégré";}
        else{
            encoding=encoding(head);
            if(!encoding.isEmpty()){kind="text";format=TEXT.contains(ext)?ext:"text";mime="text/plain";}
            else if(TEXT.contains(ext)||Arrays.asList("pdf","png","jpg","jpeg","webp","docx","xlsx","pptx").contains(ext))warning="Extension annoncée non confirmée : fichier binaire, corrompu ou chiffré";
        }
        if(!ext.isEmpty()&&!format.equals(ext)&&!Arrays.asList("text","binary","media").contains(format)&&!(format.equals("jpeg")&&ext.equals("jpg")))warning=(warning.isEmpty()?"":warning+" · ")+"Format réel : "+format+" ; extension : "+ext;
        boolean editable=kind.equals("text")&&file.length()<=1048576;
        return new JSONObject().put("path",path).put("name",file.getName()).put("format",format).put("kind",kind).put("mime_type",mime).put("extension",ext)
            .put("encoding",encoding).put("size_bytes",file.length()).put("updated",file.lastModified()).put("editable",editable).put("read_only_default",true)
            .put("warning",warning).put("safe_preview",true).put("executes_content",false);
    }
    static ZipFile zip(File file)throws Exception{
        if(file.length()>WorkspaceStore.MAX_IMPORT)throw new IOException("Archive supérieure à 512 Mo");
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){int size=(int)Math.min(65557,file.length());byte[] tail=new byte[size];in.seek(file.length()-size);in.readFully(tail);boolean found=false;
            for(int i=size-22;i>=0;i--)if(ascii(tail,i,"PK\005\006")){
                int comment=(tail[i+20]&255)|((tail[i+21]&255)<<8);if(i+22+comment!=size)continue;found=true;
                int count=(tail[i+10]&255)|((tail[i+11]&255)<<8);long directory=0;for(int n=0;n<4;n++)directory|=(long)(tail[i+12+n]&255)<<(8*n);
                if(count>5000||directory>8L*1024*1024)throw new IOException("Répertoire ZIP trop volumineux ou ZIP64 non pris en charge");break;
            }
            if(!found)throw new IOException("Répertoire ZIP absent ou archive corrompue");
        }
        return new ZipFile(file);
    }
    static String extension(String path){int slash=path.lastIndexOf('/'),dot=path.lastIndexOf('.');return dot>slash?path.substring(dot+1).toLowerCase(Locale.ROOT):"";}
    private static boolean starts(byte[] b,int[] values){if(b.length<values.length)return false;for(int i=0;i<values.length;i++)if((b[i]&255)!=values[i])return false;return true;}
    private static boolean ascii(byte[] b,int offset,String value){if(b.length<offset+value.length())return false;for(int i=0;i<value.length();i++)if((b[offset+i]&255)!=value.charAt(i))return false;return true;}
    static String encoding(byte[] b){
        if(starts(b,new int[]{255,254}))return "UTF-16LE";if(starts(b,new int[]{254,255}))return "UTF-16BE";
        int controls=0;for(byte x:b){int v=x&255;if(v==0)return "";if(v<32&&v!=9&&v!=10&&v!=13)controls++;}
        if(controls>0)return "";
        try{CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            CoderResult result=decoder.decode(ByteBuffer.wrap(b),CharBuffer.allocate(b.length+1),false);if(result.isError())return "";return "UTF-8";
        }catch(Exception ex){return "";}
    }
}
