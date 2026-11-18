package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class EmulatorConnection {
    private static final int MAX_FRAME = 12 * 1024 * 1024;
    private static final long MAX_MEDIA = 2L * 1024 * 1024 * 1024;
    private static volatile long agentUntil;

    final URI endpoint;
    private final String token;
    private final SSLContext tls;

    static final class Frame {
        final Bitmap bitmap;
        final String id;

        Frame(Bitmap bitmap, String id) {
            this.bitmap = bitmap;
            this.id = id;
        }
    }

    private EmulatorConnection(String code) throws Exception {
        String[] fields = code.trim().split("\\|", -1);
        if (fields.length != 3 || !fields[1].matches("[0-9a-f]{64}")
                || !fields[2].matches("[A-Za-z0-9_-]{43}")) {
            throw new IOException("Code d’appairage Android invalide.");
        }
        URI url;
        try {
            url = new URI(fields[0]);
        } catch (Exception invalid) {
            throw new IOException("Adresse du compagnon Android invalide.");
        }
        if (!"https".equals(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null
                || !url.getRawPath().isEmpty() || url.getRawQuery() != null || url.getRawFragment() != null
                || url.getPort() < 1 || url.getPort() > 65535) {
            throw new IOException("Adresse du compagnon Android invalide.");
        }
        endpoint = url;
        token = fields[2];
        byte[] pin = new byte[32];
        for (int i = 0; i < pin.length; i++) {
            pin[i] = (byte) Integer.parseInt(fields[1].substring(2 * i, 2 * i + 2), 16);
        }
        tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[] {new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                throw new CertificateException("Certificat client inattendu.");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Certificat Android absent.");
                try {
                    chain[0].checkValidity();
                    byte[] hash = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    if (!MessageDigest.isEqual(pin, hash)) throw new CertificateException("Certificat Android différent.");
                } catch (java.security.GeneralSecurityException invalid) {
                    throw new CertificateException("Certificat Android non reconnu.", invalid);
                }
            }
        }}, null);
    }

    private static AtomicFile pairing(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "android-emulator-pairing"));
    }

    static synchronized EmulatorConnection load(Context context) throws Exception {
        AtomicFile file = pairing(context);
        if (!file.getBaseFile().exists()) throw new IOException("Associe d’abord le compagnon PC Android.");
        return new EmulatorConnection(new String(file.readFully(), StandardCharsets.UTF_8));
    }

    static EmulatorConnection pair(Context context, String code) throws Exception {
        EmulatorConnection connection = new EmulatorConnection(code);
        if (!connection.status().optBoolean("ready")) throw new IOException("L’émulateur Android est encore en cours de démarrage.");
        save(context, code);
        return connection;
    }

    private static synchronized void save(Context context, String code) throws Exception {
        AtomicFile file = pairing(context);
        FileOutputStream output = file.startWrite();
        try {
            output.write(code.trim().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException error) {
            file.failWrite(output);
            throw error;
        }
        stopAgent();
    }

    static synchronized void forget(Context context) {
        pairing(context).delete();
        stopAgent();
    }

    static void allowAgent() { agentUntil = SystemClock.elapsedRealtime() + 20 * 60_000L; }
    static void stopAgent() { agentUntil = 0L; }
    static boolean agentAllowed() { return agentUntil > SystemClock.elapsedRealtime(); }

    private HttpsURLConnection request(String route) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) endpoint.resolve(route).toURL().openConnection();
        connection.setSSLSocketFactory(tls.getSocketFactory());
        // The operator pins this exact self-signed certificate when pairing.
        connection.setHostnameVerifier((host, session) -> endpoint.getHost().equalsIgnoreCase(host));
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(3500);
        connection.setReadTimeout(15000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }

    private static byte[] read(InputStream stream, int limit) throws IOException {
        if (stream == null) return new byte[0];
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("Réponse Android trop volumineuse.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static byte[] response(HttpsURLConnection connection, int limit) throws Exception {
        if (connection.getResponseCode() == 200) return read(connection.getInputStream(), limit);
        String message = "Compagnon Android indisponible.";
        try {
            JSONObject result = new JSONObject(new String(read(connection.getErrorStream(), 8192), StandardCharsets.UTF_8));
            message = result.optString("error", message);
        } catch (Exception ignored) { }
        throw new IOException(message);
    }

    private static JSONObject json(HttpsURLConnection connection) throws Exception {
        return new JSONObject(new String(response(connection, 8192), StandardCharsets.UTF_8));
    }

    JSONObject status() throws Exception {
        HttpsURLConnection connection = request("/v1/status");
        try {
            return json(connection);
        } finally {
            connection.disconnect();
        }
    }

    Frame screen() throws Exception {
        HttpsURLConnection connection = request("/v1/frame");
        try {
            byte[] content = response(connection, MAX_FRAME);
            String id = connection.getHeaderField("X-CHK-Frame");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(content, 0, content.length, options);
            if (id == null || !id.matches("[0-9a-f]{24}") || options.outWidth < 1 || options.outHeight < 1
                    || (long) options.outWidth * options.outHeight > 10_000_000L) {
                throw new IOException("Capture Android invalide.");
            }
            options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeByteArray(content, 0, content.length, options);
            if (bitmap == null) throw new IOException("Capture Android illisible.");
            return new Frame(bitmap, id);
        } finally {
            connection.disconnect();
        }
    }

    JSONObject input(JSONObject args) throws Exception {
        byte[] body = args.toString().getBytes(StandardCharsets.UTF_8);
        if (body.length > 8192) throw new IOException("Commande Android trop grande.");
        HttpsURLConnection connection = request("/v1/input");
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            return json(connection);
        } finally {
            connection.disconnect();
        }
    }

    JSONObject sendMedia(InputStream source, String filename, long size) throws Exception {
        String name = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.matches("mp4|mov|m4v|webm|jpg|jpeg|png|webp|mp3|m4a|wav")) {
            throw new IOException("Format vidéo, image ou audio incompatible.");
        }
        if (size <= 0 || size > MAX_MEDIA) throw new IOException("Le média doit faire entre 1 octet et 2 Gio.");
        String base = name.substring(0, dot);
        name = "CHK-" + base.substring(0, Math.min(100, base.length())) + "." + extension;
        HttpsURLConnection connection = request("/v1/media");
        try {
            connection.setReadTimeout(500000);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("X-CHK-Filename", name);
            connection.setFixedLengthStreamingMode(size);
            try (OutputStream output = connection.getOutputStream()) {
                byte[] buffer = new byte[128 * 1024];
                long remaining = size;
                while (remaining > 0) {
                    int count = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new IOException("La taille du média a changé en cours de transfert.");
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
            return json(connection);
        } finally {
            connection.disconnect();
        }
    }
}
