package com.chk.agentbrowser;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class GuestImage {
    static final String URL = "https://github.com/jqssun/android-lineage-qemu/releases/download/v2026.10.18/UTM-VM-lineage-23.2-20261019-jqssun-virtio_arm64only.zip";
    private static final String SHA256 = "171c0d215899d68f070fd45aea05ddb25737cf4ff16a25468e7cafbc65f4d8c9";
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final Set<String> FILES = new HashSet<>(Arrays.asList("efi_vars.fd", "vda.qcow2", "vdb.qcow2"));

    private GuestImage() { }

    static File parent(Context context) {
        return new File(context.getNoBackupFilesDir(), "guest");
    }

    static File installed(Context context) {
        return new File(parent(context), "android");
    }

    static boolean exists(Context context) {
        File image = installed(context);
        for (String name : FILES) if (!new File(image, name).isFile()) return false;
        return true;
    }

    static void install(Context context, Uri uri) throws Exception {
        File parent = parent(context);
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Stockage Android CHK inaccessible.");
        if (new StatFs(parent.getPath()).getAvailableBytes() < 5 * GIB) {
            throw new IOException("Libère au moins 5 Gio sur le téléphone pour importer Android.");
        }
        if (installed(context).exists()) throw new IOException("Une image Android est déjà installée.");
        File archive = new File(parent, "download.zip");
        File pending = new File(parent, "import");
        if (pending.isDirectory()) clear(pending);
        if (!pending.mkdir()) throw new IOException("Impossible de préparer l’image Android.");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(archive)) {
                if (input == null) throw new IOException("Archive Android inaccessible.");
                copy(input, output, 2 * GIB, digest);
            }
            byte[] expected = new byte[32];
            for (int i = 0; i < expected.length; i++) expected[i] = (byte) Integer.parseInt(SHA256.substring(2 * i, 2 * i + 2), 16);
            if (!MessageDigest.isEqual(expected, digest.digest())) throw new IOException("L’archive ne correspond pas à l’image Android ARM64 indiquée.");
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(archive)))) {
                Set<String> found = new HashSet<>();
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String path = entry.getName();
                    int start = path.lastIndexOf("/Data/");
                    String name = start < 0 ? "" : path.substring(start + 6);
                    if (!entry.isDirectory() && FILES.contains(name)) {
                        if (!found.add(name)) throw new IOException("Image Android invalide.");
                        long limit = name.endsWith(".fd") ? 64 * 1024L * 1024L : 4 * GIB;
                        try (FileOutputStream output = new FileOutputStream(new File(pending, name))) {
                            if (copy(zip, output, limit, null) == 0) throw new IOException("Image Android vide.");
                        }
                    }
                    zip.closeEntry();
                }
                if (!found.equals(FILES)) throw new IOException("Disques Android manquants dans l’archive.");
            }
            if (!pending.renameTo(installed(context))) throw new IOException("Impossible d’enregistrer l’image Android.");
        } finally {
            archive.delete();
            if (pending.isDirectory()) clear(pending);
        }
    }

    private static void clear(File folder) throws IOException {
        File[] entries = folder.listFiles();
        if (entries == null) throw new IOException("Dossier temporaire Android inaccessible.");
        for (File file : entries) if (!file.delete()) throw new IOException("Impossible de nettoyer l’import Android.");
        if (!folder.delete()) throw new IOException("Impossible de fermer l’import Android.");
    }

    static long copy(InputStream input, FileOutputStream output, long limit, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("Fichier Android trop volumineux.");
            output.write(buffer, 0, read);
            if (digest != null) digest.update(buffer, 0, read);
        }
        output.getFD().sync();
        return total;
    }
}
