package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Small reader modules share the same bounded dispatcher for Android UI and Local 2. */
final class UniversalFiles {
    static JSONObject preview(Context app,String path,JSONObject args)throws Exception{
        JSONObject info=FileFormats.detect(app,path);String kind=info.getString("kind"),format=info.getString("format");WorkspaceStore s=new WorkspaceStore(app);
        JSONObject result;
        if(kind.equals("text")){
            result=FileContent.page(app,path,args.optInt("offset",0));
            if((format.equals("json")||format.equals("jsonl")||format.equals("ndjson"))&&s.file(path).length()<=1048576){
                String text=FileContent.full(app,path,1048576);try{if(format.equals("json")){StrictJson.validate(text);org.json.JSONTokener parser=new org.json.JSONTokener(text);Object value=parser.nextValue();if(!(value instanceof JSONObject)&&!(value instanceof JSONArray)||parser.nextClean()!=0)throw new JSONException("Objet ou tableau JSON attendu, sans texte supplémentaire");result.put("json_valid",true);if(args.optBoolean("pretty")&&args.optInt("offset",0)==0&&result.optInt("next_offset",-1)==-1)result.put("text",value instanceof JSONObject?((JSONObject)value).toString(2):((JSONArray)value).toString(2));}
                    else{int count=0;for(String line:text.split("\\r?\\n")){if(line.trim().isEmpty())continue;StrictJson.validate(line);Object value=new JSONTokener(line).nextValue();if(!(value instanceof JSONObject)&&!(value instanceof JSONArray))throw new JSONException("Ligne "+(count+1)+" invalide");count++;}result.put("json_valid",true).put("records",count);}}
                catch(Exception ex){result.put("json_valid",false).put("validation_error",ex.getMessage());}
            }
            if(format.equals("csv")||format.equals("tsv"))try{result.put("rows",table(result.getString("text"),format.equals("tsv")?'\t':','));}catch(IOException ex){result.put("table_warning",ex.getMessage()+" ; aperçu texte conservé");}
        }else if(format.equals("pdf"))result=DocumentReader.pageText(DocumentReader.readPdf(app,s.file(path),args.optInt("page",1),args.optInt("page_count",8)),args.optInt("offset",0));
        else if(format.equals("docx"))result=DocumentReader.pageText(DocumentReader.readDocx(s.file(path)),args.optInt("offset",0));
        else if(format.equals("xlsx")||format.equals("pptx"))result=DocumentReader.pageText(OfficeDocumentReader.read(s.file(path),args.optInt("page",1),args.optInt("page_count",8),format.equals("xlsx")),args.optInt("offset",0));
        else if(format.equals("opendocument"))result=DocumentReader.pageText(openDocument(s.file(path)),args.optInt("offset",0));
        else if(kind.equals("archive")||kind.equals("package"))result=archiveList(app,path,args.optInt("offset",0));
        else if(kind.equals("database"))result=database(app,path,args);
        else if(kind.equals("image")){BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;BitmapFactory.decodeFile(s.file(path).getPath(),b);result=new JSONObject().put("width",b.outWidth).put("height",b.outHeight).put("decoder_available",b.outWidth>0).put("text",b.outWidth>0?b.outWidth+" × "+b.outHeight+" pixels":"Codec image absent ou fichier corrompu");}
        else if(kind.equals("audio")||kind.equals("media"))result=MediaInspection.infoFile(s.file(path));
        else result=FileContent.hex(app,path,args.optLong("byte_offset",0));
        return result.put("path",path).put("detected",info);
    }
    static JSONArray table(String text,char separator)throws Exception{
        JSONArray rows=new JSONArray(),row=new JSONArray();StringBuilder field=new StringBuilder();boolean quoted=false;
        for(int i=0;i<text.length();i++){char ch=text.charAt(i);
            if(ch=='"'){if(quoted&&i+1<text.length()&&text.charAt(i+1)=='"'){field.append('"');i++;}else quoted=!quoted;}
            else if(ch==separator&&!quoted){row.put(field.toString());field.setLength(0);if(row.length()>100)throw new IOException("Tableau limité à 100 colonnes par aperçu");}
            else if((ch=='\n'||ch=='\r')&&!quoted){if(ch=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;row.put(field.toString());field.setLength(0);rows.put(row);if(rows.length()>2000)throw new IOException("Tableau limité à 2000 lignes");row=new JSONArray();}
            else field.append(ch);
        }
        if(quoted)throw new IOException("CSV : guillemets non fermés dans cette portion");
        if(field.length()>0||row.length()>0){row.put(field.toString());rows.put(row);}if(rows.length()>2000)throw new IOException("Tableau limité à 2000 lignes");return rows;
    }
    private static JSONObject openDocument(File file)throws Exception{
        try(ZipFile zip=FileFormats.zip(file)){String xml=OfficeDocumentReader.xml(zip,"content.xml");org.xmlpull.v1.XmlPullParser parser=OfficeDocumentReader.parser(xml);StringBuilder text=new StringBuilder();int type;
            while((type=parser.next())!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT&&text.length()<100000){if(type==org.xmlpull.v1.XmlPullParser.TEXT)text.append(parser.getText());else if(type==org.xmlpull.v1.XmlPullParser.END_TAG&&Arrays.asList("p","h","table-row").contains(parser.getName()))text.append('\n');}
            return new JSONObject().put("format","opendocument").put("text",text.toString()).put("extraction","text_only");}
    }
    static JSONObject archiveList(Context app,String path,int offset)throws Exception{
        if(offset<0)throw new IOException("Position archive invalide");File file=new WorkspaceStore(app).file(path);if(file.length()>WorkspaceStore.MAX_IMPORT)throw new IOException("Archive supérieure à 512 Mo");JSONArray entries=new JSONArray();
        try(ZipFile zip=FileFormats.zip(file)){if(zip.size()>5000)throw new IOException("Archive limitée à 5000 entrées");Enumeration<? extends ZipEntry> it=zip.entries();int index=0;long total=0;
            while(it.hasMoreElements()){ZipEntry entry=it.nextElement();if(entry.getSize()>0){total+=entry.getSize();if(total>WorkspaceStore.MAX_IMPORT)throw new IOException("Archive décompressée supérieure à 512 Mo");}if(index>=offset&&index<offset+100)entries.put(new JSONObject().put("name",entry.getName()).put("directory",entry.isDirectory()).put("size_bytes",entry.getSize()).put("compressed_bytes",entry.getCompressedSize()));index++;}
            return new JSONObject().put("entries",entries).put("total",index).put("next_offset",offset+100<index?offset+100:-1).put("text",entries.toString(2));
        }catch(ZipException ex){throw new IOException("ZIP corrompu, chiffré ou algorithme non pris en charge",ex);}
    }
    static JSONObject archiveText(Context app,String path,String entry)throws Exception{
        try(ZipFile zip=FileFormats.zip(new WorkspaceStore(app).file(path))){ZipEntry found=zip.getEntry(entry);if(found==null||found.isDirectory())throw new IOException("Entrée archive absente");byte[] bytes=bounded(zip.getInputStream(found),1048576);String encoding=FileFormats.encoding(bytes);if(encoding.isEmpty())throw new IOException("Entrée binaire : extraire une copie pour l’ouvrir");String text=new String(bytes,java.nio.charset.Charset.forName(encoding));if(text.length()>200000)throw new IOException("Entrée trop longue pour cet aperçu");return new JSONObject().put("entry",entry).put("text",text);}
    }
    static JSONObject extract(Context app,String path,String destination,JSONArray selected,AgentClient.ResultCallback active)throws Exception{
        WorkspaceStore s=new WorkspaceStore(app);File source=s.file(path),target=s.file(destination);if(target.exists()||!target.getParentFile().isDirectory())throw new IOException("Destination existante ou parent absent");
        File stage=new File(target.getParentFile(),".extract-"+UUID.randomUUID());if(!stage.mkdir())throw new IOException("Création du dossier temporaire impossible");
        try(ZipFile zip=FileFormats.zip(source)){
            if(source.length()>WorkspaceStore.MAX_IMPORT||zip.size()>5000)throw new IOException("Archive trop volumineuse ou trop complexe");Set<String> wanted=new HashSet<>();if(selected!=null)for(int i=0;i<selected.length();i++)wanted.add(selected.getString(i));Set<String> names=new HashSet<>();long total=0;int count=0;
            Enumeration<? extends ZipEntry> it=zip.entries();while(it.hasMoreElements()){
                if(active!=null&&!active.isActive())throw new IOException("Extraction arrêtée");ZipEntry entry=it.nextElement();String name=entry.getName();if(selected!=null&&!wanted.contains(name))continue;
                if(name.endsWith("/"))name=name.substring(0,name.length()-1);if(name.isEmpty()||name.split("/",-1).length>24||!names.add(name))throw new IOException("Entrée ZIP invalide, répétée ou trop profonde");
                File output=WorkspacePaths.resolve(stage,name,false);if(!output.getParentFile().isDirectory()&&!output.getParentFile().mkdirs())throw new IOException("Parent d’entrée invalide");
                if(entry.isDirectory()){if(!output.isDirectory()&&!output.mkdir())throw new IOException("Dossier ZIP invalide");}
                else{if(output.exists())throw new IOException("Conflit entre entrées ZIP");try(InputStream in=zip.getInputStream(entry);FileOutputStream out=new FileOutputStream(output)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){if(active!=null&&!active.isActive())throw new IOException("Extraction arrêtée");total+=n;if(total>WorkspaceStore.MAX_IMPORT)throw new IOException("Archive décompressée supérieure à 512 Mo");out.write(b,0,n);}out.getFD().sync();}}
                count++;
            }
            if(count==0||selected!=null&&count!=wanted.size())throw new IOException("Entrées sélectionnées absentes de l’archive");
            if(active!=null&&!active.isActive())throw new IOException("Extraction arrêtée");if(target.exists()||!stage.renameTo(target))throw new IOException("Publication de l’extraction impossible");return new JSONObject().put("path",destination).put("entries",count).put("uncompressed_bytes",total).put("verified",true);
        }finally{remove(stage);}
    }
    static JSONObject database(Context app,String path,JSONObject args)throws Exception{
        File source=new WorkspaceStore(app).file(path);JSONArray tables=new JSONArray(),rows=new JSONArray(),columns=new JSONArray();
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(source.getPath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS,(broken)->{})){
            try(Cursor c=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name LIMIT 201",null)){while(c.moveToNext())tables.put(c.getString(0));}
            if(tables.length()>200)throw new IOException("Base limitée à 200 tables pour cet aperçu");String table=args.optString("table","");if(table.isEmpty()&&tables.length()>0)table=tables.getString(0);boolean exists=false;for(int i=0;i<tables.length();i++)if(table.equals(tables.getString(i)))exists=true;
            int offset=args.optInt("offset",0);if(offset<0||offset>1000000)throw new IOException("Position de table invalide");boolean more=false;
            if(!table.isEmpty()){
                if(!exists)throw new IOException("Table absente");String quoted="\""+table.replace("\"","\"\"")+"\"";
                try(Cursor c=db.rawQuery("SELECT * FROM "+quoted+" LIMIT 101 OFFSET "+offset,null)){if(c.getColumnCount()>100)throw new IOException("Table limitée à 100 colonnes");for(String column:c.getColumnNames())columns.put(column);
                    while(c.moveToNext()){if(rows.length()==100){more=true;break;}JSONArray row=new JSONArray();for(int i=0;i<c.getColumnCount();i++){Object value=JSONObject.NULL;if(c.getType(i)==Cursor.FIELD_TYPE_BLOB)value="[binaire]";else if(!c.isNull(i)){value=c.getString(i);if(((String)value).length()>2000)value=((String)value).substring(0,2000)+"…";}row.put(value);}rows.put(row);}}
            }
            return new JSONObject().put("format","sqlite").put("tables",tables).put("table",table).put("columns",columns).put("rows",rows).put("next_offset",more?offset+100:-1).put("text",columns.toString()+"\n"+rows.toString(2)).put("read_only",true);
        }
    }
    static JSONObject convert(Context app,JSONObject args,AgentClient.ResultCallback active)throws Exception{
        WorkspaceStore s=new WorkspaceStore(app);String path=args.getString("path"),destination=args.getString("destination"),format=args.getString("format").toLowerCase(Locale.ROOT);JSONObject detected=FileFormats.detect(app,path);
        File target=s.file(destination);if(target.exists()||!target.getParentFile().isDirectory()||!FileFormats.extension(destination).equals(format))throw new IOException("Nouvelle destination avec l’extension du format requise");
        if(detected.getString("kind").equals("image")&&Arrays.asList("png","jpg","webp").contains(format)){
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(s.file(path).getPath(),bounds);if(bounds.outWidth<=0)throw new IOException("Codec image absent ou image corrompue");
            int maximum=args.optInt("max_dimension",2048),quality=args.optInt("quality",90);if(maximum<16||maximum>4096||quality<1||quality>100)throw new IOException("Dimension 16..4096 et qualité 1..100 requises");
            BitmapFactory.Options options=new BitmapFactory.Options();while(Math.max(bounds.outWidth,bounds.outHeight)/Math.max(1,options.inSampleSize)>maximum*2)options.inSampleSize=Math.max(1,options.inSampleSize)*2;
            Bitmap bitmap=BitmapFactory.decodeFile(s.file(path).getPath(),options);if(bitmap==null)throw new IOException("Image indécodable");File stage=new File(target.getParentFile(),".convert-"+UUID.randomUUID());
            try{float scale=Math.min(1,(float)maximum/Math.max(bitmap.getWidth(),bitmap.getHeight()));if(scale<1){Bitmap small=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true);bitmap.recycle();bitmap=small;}
                if(format.equals("jpg")){Bitmap opaque=Bitmap.createBitmap(bitmap.getWidth(),bitmap.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(opaque);canvas.drawColor(Color.WHITE);canvas.drawBitmap(bitmap,0,0,null);bitmap.recycle();bitmap=opaque;}
                try(FileOutputStream out=new FileOutputStream(stage)){if(!bitmap.compress(format.equals("png")?Bitmap.CompressFormat.PNG:format.equals("jpg")?Bitmap.CompressFormat.JPEG:Bitmap.CompressFormat.WEBP,quality,out))throw new IOException("Encodage image refusé");out.getFD().sync();}
                BitmapFactory.Options check=new BitmapFactory.Options();check.inJustDecodeBounds=true;BitmapFactory.decodeFile(stage.getPath(),check);if(check.outWidth<=0)throw new IOException("Image convertie non lisible");if(!active.isActive())throw new IOException("Conversion arrêtée");if(target.exists()||!stage.renameTo(target))throw new IOException("Publication de la conversion impossible");return WorkspaceTransfer.stat(s,destination,true).put("verified",true);
            }finally{bitmap.recycle();stage.delete();}
        }
        String text=FileContent.full(app,path,60000);JSONObject request=new JSONObject().put("path",destination).put("format",format).put("text",text).put("title",s.file(path).getName());
        if(Arrays.asList("csv","xlsx").contains(format)){String ext=detected.optString("format");if(!ext.equals("csv")&&!ext.equals("tsv"))throw new IOException("Conversion en tableau : source CSV ou TSV requise");request.put("rows",table(text,ext.equals("tsv")?'\t':','));}
        else if(format.equals("json")){StrictJson.validate(text);request.put("data",new JSONTokener(text).nextValue());}
        else if(format.equals("pptx"))request.put("slides",new JSONArray().put(new JSONObject().put("title",s.file(path).getName()).put("text",text)));
        else if(!Arrays.asList("txt","pdf","docx").contains(format))throw new IOException("Conversion locale non disponible pour ce couple de formats");
        return WorkspaceDocuments.create(app,request,active);
    }
    private static byte[] bounded(InputStream input,int maximum)throws Exception{try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>maximum)throw new IOException("Entrée décompressée trop volumineuse");out.write(b,0,n);}return out.toByteArray();}}
    private static void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
}
