package com.chk.agentbrowser;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Durable private workspace. Writes are atomic; delete means recoverable trash. */
public final class WorkspaceStore {
    public static final long MAX_IMPORT=TransferLimits.MAX_FILE_BYTES;
    private final Context app;
    private final File root,trash;
    private final SQLiteDatabase db;
    public WorkspaceStore(Context c)throws IOException{
        app=c.getApplicationContext();root=new File(app.getFilesDir(),"workspace");trash=new File(app.getFilesDir(),"workspace_trash");
        if((!root.isDirectory()&&!root.mkdirs())||(!trash.isDirectory()&&!trash.mkdirs()))throw new IOException("Stockage indisponible");
        db=Database.get(app).getWritableDatabase();
    }
    private static final class Database extends SQLiteOpenHelper{
        private static Database instance;
        static synchronized Database get(Context app){if(instance==null)instance=new Database(app);return instance;}
        Database(Context c){super(c,"workspace.db",null,3);}
        @Override public void onCreate(SQLiteDatabase d){
            d.execSQL("CREATE TABLE notes (id TEXT PRIMARY KEY,title TEXT NOT NULL,body TEXT NOT NULL,source_url TEXT NOT NULL,pinned INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL,revision INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,folder_id TEXT NOT NULL DEFAULT '')");
            d.execSQL("CREATE TABLE trash (id TEXT PRIMARY KEY,path TEXT NOT NULL,updated INTEGER NOT NULL)");
            d.execSQL("CREATE TABLE note_folders (id TEXT PRIMARY KEY,name TEXT NOT NULL,emoji TEXT NOT NULL,color TEXT NOT NULL,updated INTEGER NOT NULL,parent_id TEXT NOT NULL DEFAULT '')");
        }
        @Override public void onUpgrade(SQLiteDatabase d,int old,int next){
            // Only additive migrations: old notes and trash are never rebuilt.
            if(old<2){
                d.execSQL("ALTER TABLE notes ADD COLUMN folder_id TEXT NOT NULL DEFAULT ''");
                d.execSQL("CREATE TABLE IF NOT EXISTS note_folders (id TEXT PRIMARY KEY,name TEXT NOT NULL,emoji TEXT NOT NULL,color TEXT NOT NULL,updated INTEGER NOT NULL)");
            }
            if(old<3){
                d.execSQL("ALTER TABLE note_folders ADD COLUMN parent_id TEXT NOT NULL DEFAULT ''");
            }
        }
    }
    public File file(String path)throws IOException{return WorkspacePaths.resolve(root,path,false);}
    public Uri uri(String path)throws IOException{
        file(path);Uri.Builder b=new Uri.Builder().scheme("content").authority(app.getPackageName()+".browserfiles").appendPath("workspace");
        for(String part:path.split("/"))b.appendPath(part);return b.build();
    }
    public String mime(String path){String name=path.toLowerCase(Locale.ROOT);int dot=name.lastIndexOf('.');String ext=dot<0?"":name.substring(dot+1);String m=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if(m==null)switch(ext){case "mkv":m="video/x-matroska";break;case "mov":m="video/quicktime";break;case "ts":m="video/mp2t";break;case "md":m="text/markdown";break;case "json":m="application/json";break;case "m4a":m="audio/mp4";break;default:m=java.net.URLConnection.guessContentTypeFromName(path);}
        return m==null?"application/octet-stream":m;
    }
    public JSONObject list(String path,String query,int offset)throws Exception{
        File dir=WorkspacePaths.resolve(root,path,true);if(!dir.isDirectory())throw new IOException("Dossier introuvable");
        File[] found=dir.listFiles();if(found==null)throw new IOException("Lecture du dossier impossible");
        List<File> rows=new ArrayList<>();String q=query==null?"":query.toLowerCase(Locale.ROOT);
        for(File f:found)if(!f.getName().startsWith(".")&&f.getName().toLowerCase(Locale.ROOT).contains(q))rows.add(f);
        Collections.sort(rows,(a,b)->a.isDirectory()!=b.isDirectory()?(a.isDirectory()?-1:1):a.getName().compareToIgnoreCase(b.getName()));
        int start=Math.max(0,offset);JSONArray items=new JSONArray();for(int i=start;i<Math.min(start+50,rows.size());i++){
            File f=rows.get(i);String p=WorkspacePaths.child(path,f.getName());JSONObject row=new JSONObject();
            row.put("name",f.getName()).put("path",p).put("directory",f.isDirectory()).put("size",f.length()).put("updated",f.lastModified()).put("mime_type",f.isDirectory()?"inode/directory":mime(p));items.put(row);
        }
        JSONObject answer=new JSONObject().put("path",path).put("items",items).put("total",rows.size()).put("next_offset",start+50<rows.size()?start+50:-1);
        if(path==null||path.isEmpty()){
            // Files' home screen is virtual, so expose its navigable aliases to
            // MCP clients without changing the existing root listing contract.
            JSONArray shortcuts=new JSONArray();
            String[] names={"Documents","Images","Vidéos","Audio"};
            String[] targets={"Categories/Documents","Categories/Images","Categories/Videos","Categories/Audio"};
            for(int i=0;i<names.length;i++)if(WorkspacePaths.resolve(root,targets[i],false).isDirectory())
                shortcuts.put(new JSONObject().put("name",names[i]).put("path",names[i])
                    .put("canonical_path",targets[i]).put("directory",true));
            answer.put("visible_categories",shortcuts);
        }
        return answer;
    }
    public void mkdir(String path)throws IOException{File f=file(path);if(f.exists()||!f.getParentFile().isDirectory()||!f.mkdir())throw new IOException("Dossier existant ou parent absent");}
    public JSONObject writeText(String path,String text,boolean overwrite)throws Exception{
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>1048576)throw new IOException("Document limité à 1 Mo");
        File f=file(path);if(f.isDirectory()||(!overwrite&&f.exists())||!f.getParentFile().isDirectory())throw new IOException("Fichier existant ou parent absent");
        AtomicFile a=new AtomicFile(f);FileOutputStream out=null;try{out=a.startWrite();out.write(bytes);a.finishWrite(out);}catch(Exception e){if(out!=null)a.failWrite(out);throw e;}
        String expected=WorkspaceTransfer.hash(new ByteArrayInputStream(bytes)),actual=WorkspaceTransfer.hash(f);
        if(!expected.equals(actual))throw new IOException("Écriture terminée mais SHA-256 non confirmé.");
        return new JSONObject().put("path",path).put("size",f.length()).put("sha256",actual).put("exists",true).put("verified",true);
    }
    public JSONObject readText(String path,int offset)throws Exception{
        return FileContent.page(app,path,offset);
    }
    public JSONObject editText(String path,String text,String expected)throws Exception{
        if(!expected.matches("[a-f0-9]{64}")||text.getBytes(StandardCharsets.UTF_8).length>1048576)throw new IOException("SHA-256 attendu requis et texte limité à 1 Mo.");
        File source=file(path);if(!source.isFile()||!WorkspaceTransfer.stat(this,path,true).getString("sha256").equals(expected))throw new IOException("Fichier modifié depuis la lecture : recharger avant de modifier.");
        String backup=trash(path);
        try{return writeText(path,text,false).put("backup_trash_id",backup);}
        catch(Exception e){
            if(!source.exists())try{restore(backup);}catch(Exception recovery){throw new IOException("Écriture interrompue ; sauvegarde récupérable en corbeille : "+backup,e);}
            throw new IOException("Écriture interrompue ; vérifier le fichier. Identifiant de sauvegarde : "+backup,e);
        }
    }
    public JSONObject readBytes(String path,long offset)throws Exception{
        File f=file(path);if(!f.isFile())throw new IOException("Fichier introuvable");
        if(offset<0||offset>f.length())throw new IOException("Position invalide");byte[] buf=new byte[8192];int n;
        try(RandomAccessFile in=new RandomAccessFile(f,"r")){in.seek(offset);n=in.read(buf);}
        byte[] chunk=n<0?new byte[0]:Arrays.copyOf(buf,n);
        return new JSONObject().put("path",path).put("mime_type",mime(path)).put("size",f.length()).put("base64",android.util.Base64.encodeToString(chunk,android.util.Base64.NO_WRAP)).put("next_offset",offset+chunk.length<f.length()?offset+chunk.length:-1);
    }
    public void move(String from,String to)throws IOException{
        File a=file(from),b=file(to);if(!a.exists()||b.exists()||!b.getParentFile().isDirectory()||b.getPath().startsWith(a.getPath()+File.separator)||!a.renameTo(b))throw new IOException("Déplacement impossible : destination existante ou invalide");
    }
    public String trash(String path)throws Exception{
        File f=file(path);if(!f.exists())throw new IOException("Élément introuvable");String id=UUID.randomUUID().toString();
        ContentValues v=new ContentValues();v.put("id",id);v.put("path",path);v.put("updated",System.currentTimeMillis());
        db.insertOrThrow("trash",null,v);if(!f.renameTo(new File(trash,id))){db.delete("trash","id=?",new String[]{id});throw new IOException("Mise en corbeille impossible");}return id;
    }
    public JSONArray trashList()throws Exception{
        JSONArray rows=new JSONArray();try(Cursor c=db.query("trash",null,null,null,null,null,"updated DESC","100")){while(c.moveToNext())rows.put(new JSONObject().put("id",str(c,"id")).put("path",str(c,"path")));}return rows;
    }
    public void restore(String id)throws Exception{
        if(!id.matches("[a-f0-9-]{36}"))throw new IOException("Identifiant invalide");String path;
        try(Cursor c=db.query("trash",null,"id=?",new String[]{id},null,null,null)){if(!c.moveToFirst())throw new IOException("Élément introuvable");path=str(c,"path");}
        File f=file(path);if(f.exists()||!f.getParentFile().isDirectory()||!new File(trash,id).renameTo(f))throw new IOException("Restauration impossible : libère la destination et restaure le parent");db.delete("trash","id=?",new String[]{id});
    }
    public String importUri(Uri source,String path)throws Exception{
        File target=file(path);if(target.exists()||!target.getParentFile().isDirectory())throw new IOException("Destination existante ou parent absent");
        File temp=new File(target.getParentFile(),".import-"+UUID.randomUUID());long total=0;
        try(InputStream in=app.getContentResolver().openInputStream(source);FileOutputStream out=new FileOutputStream(temp)){
            if(in==null)throw new IOException("Fichier inaccessible");byte[] buf=new byte[32768];int n;
            while((n=in.read(buf))!=-1){total+=n;if(total% (16L*1024*1024)<n)TransferLimits.requireSpace(temp,n);if(total>MAX_IMPORT)throw new IOException("Fichier supérieur à 6 Go");out.write(buf,0,n);}out.getFD().sync();
        }catch(Exception e){temp.delete();throw e;}
        if(target.exists()||!temp.renameTo(target)){temp.delete();throw new IOException("Enregistrement impossible");}return path;
    }
    public JSONArray downloads()throws Exception{
        File dir=app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);JSONArray rows=new JSONArray();
        Set<String> unavailable=CompletedDownloads.unavailable(app);File[] files=dir==null?null:dir.listFiles();
        if(files!=null){java.util.Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));for(File f:files){if(rows.length()>=100)break;if(CompletedDownloads.ready(f,unavailable))rows.put(new JSONObject().put("name",f.getName()).put("size",f.length()));}}return rows;
    }
    public String importDownload(String name,String destination)throws Exception{
        if(name.contains("/"))throw new IOException("Nom invalide");File dir=app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);if(dir==null)throw new IOException("Stockage indisponible");File source=WorkspacePaths.resolve(dir,name,false);
        if(!CompletedDownloads.ready(source,CompletedDownloads.unavailable(app)))throw new IOException("Téléchargement non terminé ou fichier inaccessible");
        return importUri(Uri.fromFile(source),destination);
    }
    public JSONObject preview(String path)throws Exception{
        if(!AgentClient.get(app).isPreviewAllowed())throw new IOException("Active le partage d’aperçus dans Réglages");
        File f=file(path);android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(f.getPath(),bounds);if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("Ce fichier n’est pas une image lisible");
        android.graphics.BitmapFactory.Options opts=new android.graphics.BitmapFactory.Options();opts.inSampleSize=1;while(bounds.outWidth/opts.inSampleSize>720||bounds.outHeight/opts.inSampleSize>960)opts.inSampleSize*=2;
        android.graphics.Bitmap b=android.graphics.BitmapFactory.decodeFile(f.getPath(),opts);if(b==null)throw new IOException("Image inaccessible");byte[] bytes=null;try{for(int quality=75;quality>=15;quality-=15){ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(android.graphics.Bitmap.CompressFormat.JPEG,quality,out);bytes=out.toByteArray();if(bytes.length<=150000)break;}}finally{b.recycle();}if(bytes==null||bytes.length>150000)throw new IOException("Aperçu trop volumineux");return new JSONObject().put("path",path).put("jpeg_base64",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP));
    }
    /** Original public API preserved for existing MCP callers and Android tests. */
    public JSONArray notes(String query,boolean deleted,int offset)throws Exception{
        return notesFiltered(query,deleted,null,false,offset);
    }
    /** folderId null=all, empty=unfiled, UUID=folder. */
    public JSONArray notesFiltered(String query,boolean deleted,String folderId,boolean pinnedOnly,int offset)throws Exception{
        String q="%"+(query==null?"":query)+"%";
        String where="deleted=? AND (title LIKE ? OR body LIKE ?)";
        ArrayList<String> values=new ArrayList<>(Arrays.asList(deleted?"1":"0",q,q));
        if(folderId!=null){where+=" AND folder_id=?";values.add(folderId);}
        if(pinnedOnly)where+=" AND pinned=1";
        JSONArray rows=new JSONArray();
        try(Cursor c=db.query("notes",
                new String[]{"id","title","source_url","pinned","updated","revision","folder_id","substr(body,1,160) AS preview"},
                where,values.toArray(new String[0]),null,null,"pinned DESC, updated DESC",Math.max(0,offset)+",50")){
            while(c.moveToNext())rows.put(noteRow(c,false));
        }
        return rows;
    }
    public JSONObject note(String id)throws Exception{
        try(Cursor c=db.query("notes",null,"id=?",new String[]{id},null,null,null)){
            if(!c.moveToFirst())throw new IOException("Note introuvable");
            return noteRow(c,true);
        }
    }
    private JSONObject noteRow(Cursor c,boolean full)throws Exception{
        JSONObject o=new JSONObject().put("id",str(c,"id")).put("title",str(c,"title"))
            .put("source_url",str(c,"source_url"))
            .put("pinned",c.getInt(c.getColumnIndexOrThrow("pinned"))==1)
            .put("updated",c.getLong(c.getColumnIndexOrThrow("updated")))
            .put("revision",c.getInt(c.getColumnIndexOrThrow("revision")))
            .put("folder_id",str(c,"folder_id"));
        int preview=c.getColumnIndex("preview");
        if(preview>=0)o.put("preview",c.getString(preview));
        if(full)o.put("body",str(c,"body")).put("deleted",c.getInt(c.getColumnIndexOrThrow("deleted"))==1);
        return o;
    }
    /** Old callers never alter the assigned folder. */
    public JSONObject saveNote(String id,String title,String body,String source,boolean pinned,int revision)throws Exception{
        return saveNote(id,title,body,source,pinned,revision,null);
    }
    public JSONObject saveNote(String id,String title,String body,String source,boolean pinned,int revision,String folderId)throws Exception{
        if(title.length()>120||body.length()>12000||source.length()>2000)
            throw new IOException("Note trop longue (titre 120, texte 12 000 caractères)");
        if(folderId!=null&&!folderId.isEmpty()&&!folderExists(folderId))
            throw new IOException("Dossier de notes introuvable");
        ContentValues v=new ContentValues();
        v.put("title",title.trim().isEmpty()?"Sans titre":title.trim());
        v.put("body",body);v.put("source_url",source);v.put("pinned",pinned?1:0);
        v.put("updated",System.currentTimeMillis());
        if(folderId!=null)v.put("folder_id",folderId);
        if(id.isEmpty()){
            id=UUID.randomUUID().toString();v.put("id",id);v.put("revision",1);
            db.insertOrThrow("notes",null,v);
        }else{
            v.put("revision",revision+1);
            if(db.update("notes",v,"id=? AND revision=? AND deleted=0",
                    new String[]{id,String.valueOf(revision)})!=1)
                throw new IOException("La note a changé ailleurs. Recharge-la ou conserve une copie");
        }
        return note(id);
    }
    public JSONArray noteFolders()throws Exception{
        JSONArray rows=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT f.id,f.name,f.emoji,f.color,f.updated,f.parent_id,"+
                "(SELECT COUNT(*) FROM notes n WHERE n.folder_id=f.id AND n.deleted=0) AS note_count "+
                "FROM note_folders f ORDER BY f.name COLLATE NOCASE ASC",null)){
            while(c.moveToNext())rows.put(new JSONObject().put("id",str(c,"id"))
                .put("name",str(c,"name")).put("emoji",str(c,"emoji"))
                .put("color",str(c,"color")).put("parent_id",str(c,"parent_id"))
                .put("updated",c.getLong(c.getColumnIndexOrThrow("updated")))
                .put("note_count",c.getInt(c.getColumnIndexOrThrow("note_count"))));
        }
        return rows;
    }
    private boolean folderExists(String id){
        try(Cursor c=db.rawQuery("SELECT 1 FROM note_folders WHERE id=? LIMIT 1",new String[]{id})){
            return c.moveToFirst();
        }
    }
    /** Old callers can change a folder's color/name without moving it. */
    public JSONObject saveNoteFolder(String id,String name,String emoji,String color)throws Exception{
        return saveNoteFolder(id,name,emoji,color,null);
    }
    /** null parent preserves hierarchy on edit; empty parent moves to the root. */
    public JSONObject saveNoteFolder(String id,String name,String emoji,String color,String parentId)throws Exception{
        name=name==null?"":name.trim();emoji=emoji==null?"":emoji.trim();
        if(name.isEmpty()||name.length()>60||emoji.isEmpty()||emoji.length()>12)
            throw new IOException("Nom (1–60) et emoji (1–12) obligatoires");
        if(color==null||!color.matches("#[A-Fa-f0-9]{6}"))
            throw new IOException("Couleur hexadécimale invalide");
        if(id==null)id="";
        String destination=parentId;
        if(destination==null)destination=id.isEmpty()?"":folderParent(id);
        if(destination==null)throw new IOException("Dossier introuvable");
        if(!destination.isEmpty()&&!folderExists(destination))
            throw new IOException("Dossier parent introuvable");
        if(!id.isEmpty()){
            if(id.equals(destination))throw new IOException("Un dossier ne peut pas être son propre parent");
            String walker=destination;
            HashSet<String> seen=new HashSet<>();
            while(!walker.isEmpty()){
                if(walker.equals(id)||!seen.add(walker)||seen.size()>200)
                    throw new IOException("Déplacement impossible : boucle entre dossiers");
                String next=folderParent(walker);
                if(next==null)throw new IOException("Hiérarchie de dossiers invalide");
                walker=next;
            }
        }
        ContentValues v=new ContentValues();v.put("name",name);v.put("emoji",emoji);
        v.put("color",color);v.put("parent_id",destination);
        v.put("updated",System.currentTimeMillis());
        if(id.isEmpty()){
            id=UUID.randomUUID().toString();v.put("id",id);
            db.insertOrThrow("note_folders",null,v);
        }else if(db.update("note_folders",v,"id=?",new String[]{id})!=1)
            throw new IOException("Dossier introuvable");
        return new JSONObject().put("id",id).put("name",name).put("emoji",emoji)
            .put("color",color).put("parent_id",destination);
    }
    /** Missing id returns null, root has an empty parent id. */
    private String folderParent(String id){
        try(Cursor c=db.rawQuery("SELECT parent_id FROM note_folders WHERE id=? LIMIT 1",new String[]{id})){
            return c.moveToFirst()?c.getString(0):null;
        }
    }
    /** No hard deletion of notes or child folders: reparent both transactionally. */
    public void deleteNoteFolder(String id)throws Exception{
        if(id==null||id.isEmpty())throw new IOException("Dossier invalide");
        db.beginTransaction();
        try{
            String parent=folderParent(id);
            if(parent==null)throw new IOException("Dossier introuvable");
            ContentValues children=new ContentValues();children.put("parent_id",parent);
            db.update("note_folders",children,"parent_id=?",new String[]{id});
            db.execSQL("UPDATE notes SET folder_id=?, revision=revision+1 WHERE folder_id=?",
                new Object[]{parent,id});
            if(db.delete("note_folders","id=?",new String[]{id})!=1)
                throw new IOException("Dossier introuvable");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public JSONObject moveNote(String noteId,String folderId,int expectedRevision)throws Exception{
        if(folderId==null)throw new IOException("Dossier requis");
        if(!folderId.isEmpty()&&!folderExists(folderId))throw new IOException("Dossier introuvable");
        ContentValues values=new ContentValues();values.put("folder_id",folderId);
        values.put("revision",expectedRevision+1);values.put("updated",System.currentTimeMillis());
        if(db.update("notes",values,"id=? AND revision=? AND deleted=0",
                new String[]{noteId,String.valueOf(expectedRevision)})!=1)
            throw new IOException("Note modifiée ailleurs : recharge-la avant de déplacer");
        return note(noteId);
    }
    public void deleteNote(String id,boolean deleted,int revision)throws IOException{
        ContentValues v=new ContentValues();v.put("deleted",deleted?1:0);
        v.put("revision",revision+1);v.put("updated",System.currentTimeMillis());
        if(db.update("notes",v,"id=? AND revision=?",new String[]{id,String.valueOf(revision)})!=1)
            throw new IOException("Note modifiée ailleurs : recharge-la");
    }
    private static String str(Cursor c,String col){return c.getString(c.getColumnIndexOrThrow(col));}
}
