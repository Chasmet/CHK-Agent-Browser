package com.chk.agentbrowser;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLConnection;

/** Read-only provider for files explicitly staged by an owner-authorized MCP upload. */
public final class BrowserFileProvider extends ContentProvider {
    private File resolve(Uri uri) throws FileNotFoundException {
        if(getContext()==null || !(getContext().getPackageName()+".browserfiles").equals(uri.getAuthority())
            || uri.getPathSegments().size()!=1) throw new FileNotFoundException("URI invalide");
        String name=uri.getLastPathSegment();
        if(name==null || !name.matches("[A-Za-z0-9._-]{1,180}")) throw new FileNotFoundException("Nom interdit");
        File root=new File(getContext().getCacheDir(),"agent_uploads");
        try{
            File file=new File(root,name).getCanonicalFile();
            if(!root.getCanonicalFile().equals(file.getParentFile()) || !file.isFile())
                throw new FileNotFoundException("Fichier introuvable");
            return file;
        }catch(IOException e){throw new FileNotFoundException("Chemin invalide");}
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){
        try{
            String type=URLConnection.guessContentTypeFromName(resolve(uri).getName());
            return type==null?"application/octet-stream":type;
        }catch(Exception e){return "application/octet-stream";}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(!"r".equals(mode))throw new FileNotFoundException("Lecture seule");
        return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){
        try{
            File file=resolve(uri);
            MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
            c.addRow(new Object[]{file.getName(),file.length()});
            return c;
        }catch(FileNotFoundException e){return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
