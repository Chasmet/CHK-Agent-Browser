package com.chk.agentbrowser;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
public class ApkProvider extends ContentProvider {
    private File apk(Uri uri)throws FileNotFoundException{
        if(!"com.chk.agentbrowser.apks".equals(uri.getAuthority())||uri.getPathSegments().size()!=1)
            throw new FileNotFoundException("URI invalide");
        String name=uri.getLastPathSegment();
        if(name==null||!name.matches("CHK-Agent-Browser-[a-zA-Z0-9._-]+\\.apk"))
            throw new FileNotFoundException("Nom interdit");
        File root=getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if(root==null)throw new FileNotFoundException("Stockage indisponible");
        try{
            File file=new File(root,name).getCanonicalFile();
            if(!file.getParentFile().equals(root.getCanonicalFile())||!file.isFile())
                throw new FileNotFoundException("Fichier introuvable");
            return file;
        }catch(IOException e){throw new FileNotFoundException("Chemin invalide");}
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return "application/vnd.android.package-archive";}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(!"r".equals(mode))throw new FileNotFoundException("Lecture seule");
        return ParcelFileDescriptor.open(apk(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] columns,String selection,String[] args,String order){
        try{
            File file=apk(uri);
            MatrixCursor cursor=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
            cursor.addRow(new Object[]{file.getName(),file.length()});return cursor;
        }catch(FileNotFoundException e){return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
