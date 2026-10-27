package com.chk.agentbrowser;

import java.io.File;
import java.io.IOException;

/** File capacity, independent from bounded decoder and preview memory budgets. */
public final class TransferLimits {
    public static final long MAX_FILE_BYTES=6_000_000_000L;
    public static final long MAX_ACTIVE_BYTES=12_000_000_000L;
    public static final long FREE_RESERVE_BYTES=64L*1024*1024;
    private TransferLimits(){}
    public static boolean longOperation(String action){return java.util.Arrays.asList("files_copy","files_hash","files_import","files_import_download","files_transfer_commit","files_zip","files_unzip","files_extract_archive").contains(action);}
    public static void checkSize(long size)throws IOException{
        if(size<0||size>MAX_FILE_BYTES)throw new IOException("Fichier supérieur à 6 Go ou taille invalide.");
    }
    public static void requireSpace(File destination,long bytes)throws IOException{
        if(bytes<0||bytes>MAX_ACTIVE_BYTES)throw new IOException("Réservation de stockage invalide.");
        File folder=destination.isDirectory()?destination:destination.getParentFile();
        if(folder==null||!folder.isDirectory())throw new IOException("Dossier de stockage absent.");
        if(folder.getUsableSpace()<bytes+FREE_RESERVE_BYTES)
            throw new IOException("Espace libre insuffisant pour le transfert (marge de 64 Mo conservée).");
    }
}
