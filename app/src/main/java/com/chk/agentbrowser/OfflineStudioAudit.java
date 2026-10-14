package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only local diagnostics for the native video studio.
 * No network, no model downloads, no external AI endpoints.
 * Recommendations require an explicit user action before any project mutation.
 */
public final class OfflineStudioAudit {
    private OfflineStudioAudit() {}

    public static JSONObject analyze(Context context, JSONObject project) throws Exception {
        WorkspaceStore store = new WorkspaceStore(context);
        JSONArray clips = project.optJSONArray("clips");
        JSONArray sounds = project.optJSONArray("audio");
        JSONArray videoReports = new JSONArray();
        JSONArray warnings = new JSONArray();
        Map<String, String> fingerprints = new HashMap<>();
        Map<String, String> byChecksum = new HashMap<>();
        long duration = 0;
        int repeatedPath = 0, identicalFiles = 0, checkedFrames = 0;
        if (clips != null) {
            for (int i = 0; i < clips.length(); i++) {
                JSONObject clip = clips.getJSONObject(i);
                String path = clip.optString("path");
                File file = store.file(path);
                long requested = clip.optLong("duration_ms", 10000);
                duration += Math.round(requested / Math.max(0.25, clip.optDouble("speed", 1)));
                JSONObject report = new JSONObject().put("cut", i + 1)
                    .put("path", path).put("duration_ms", requested)
                    .put("source_exists", file.isFile());
                if (!file.isFile()) {
                    report.put("status", "missing");
                    warnings.put("Plan " + (i + 1) + " : fichier source absent");
                    videoReports.put(report);
                    continue;
                }
                if (fingerprints.containsKey(path)) {
                    repeatedPath++;
                    report.put("same_source_as", fingerprints.get(path));
                    // Timeline splits intentionally reuse the SAME source: do not call them duplicates.
                } else {
                    fingerprints.put(path, String.valueOf(i + 1));
                    if (file.length() <= 300L * 1024 * 1024) {
                        String hash = sha256(file);
                        report.put("sha256", hash);
                        String other = byChecksum.get(hash);
                        if (other == null) byChecksum.put(hash, path);
                        if (other != null && !other.equals(path)) {
                            identicalFiles++;
                            report.put("duplicate_of", other);
                            warnings.put("Plan " + (i + 1) + " : contenu strictement identique à " + other);
                        }
                    }
                }
                double sum = 0, darkest = 255, bright = 0, texture = 0;
                int samples = 0;
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(file.getAbsolutePath());
                    long startMs = clip.optLong("start_ms", 0);
                    for (int step = 1; step <= 3; step++) {
                        long timeMs = startMs + (requested * step / 4);
                        Bitmap bitmap = null;
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= 27)
                                bitmap = retriever.getScaledFrameAtTime(
                                    timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 64, 64);
                            else bitmap = retriever.getFrameAtTime(
                                    timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                            if (bitmap == null) continue;
                            double[] metrics = brightnessAndTexture(bitmap);
                            sum += metrics[0];
                            darkest = Math.min(darkest, metrics[0]);
                            bright = Math.max(bright, metrics[0]);
                            texture += metrics[1];
                            samples++;
                        } catch (RuntimeException ignored) {
                            // Unsupported decoder/frame: never label a clip as corrupt.
                        } finally {
                            if (bitmap != null) bitmap.recycle();
                        }
                    }
                } catch (RuntimeException ignored) {
                    report.put("inspection_note", "Décodage d'aperçu indisponible");
                } finally {
                    retriever.release();
                }
                checkedFrames += samples;
                report.put("frames_sampled", samples);
                if (samples > 0) {
                    double avg = sum / samples;
                    report.put("brightness", Math.round(avg * 10) / 10.0);
                    report.put("texture", Math.round(texture / samples * 10) / 10.0);
                    if (avg < 22 && darkest < 14) {
                        report.put("suggestion", "Plan sombre : relever légèrement l'exposition");
                        warnings.put("Plan " + (i + 1) + " : très sombre, à inspecter (peut être artistique)");
                    } else if (avg > 232) {
                        report.put("suggestion", "Plan très clair : vérifier la surexposition");
                    }
                    if (texture / samples < 2.0)
                        report.put("motion_warning", "Image potentiellement uniforme ou floue : contrôle visuel conseillé");
                }
                if (clip.optInt("fade_ms", 0) > requested / 3)
                    warnings.put("Plan " + (i + 1) + " : fondu disproportionné");
                videoReports.put(report);
            }
        }
        JSONObject audio = new JSONObject().put("present", sounds != null && sounds.length() > 0);
        if (sounds != null && sounds.length() > 0) {
            JSONObject track = sounds.optJSONObject(0);
            if (track != null) {
                File source = store.file(track.optString("path"));
                audio.put("path", track.optString("path"));
                audio.put("source_exists", source.isFile());
                if (source.isFile()) {
                    try {
                        JSONObject beat = analyzePcmWave(source, 90);
                        audio.put("beat", beat);
                    } catch (Exception e) {
                        audio.put("beat", new JSONObject().put("available", false)
                            .put("reason", "Analyse BPM PCM WAV uniquement : " + e.getMessage()));
                    }
                }
            }
        }
        return new JSONObject().put("engine", "CHK native offline")
            .put("network_used", false).put("changes_applied", false)
            .put("timeline_ms", duration).put("clip_count", clips == null ? 0 : clips.length())
            .put("source_reuses", repeatedPath).put("identical_separate_files", identicalFiles)
            .put("frames_sampled", checkedFrames).put("audio", audio)
            .put("clips", videoReports).put("warnings", warnings)
            .put("disclaimer", "Analyse indicative. Ne prouve pas la synchronisation labiale ni la qualité intégrale.");
    }

    /** Pixels are transformed to 64x64 to keep memory bounded on Android. */
    private static double[] brightnessAndTexture(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int sx = Math.max(1, w / 48), sy = Math.max(1, h / 48);
        double lum = 0, delta = 0, last = -1;
        int count = 0;
        for (int y = 0; y < h; y += sy) {
            last = -1;
            for (int x = 0; x < w; x += sx) {
                int color = bitmap.getPixel(x, y);
                double l = (((color >> 16) & 255) * .2126)
                    + (((color >> 8) & 255) * .7152) + ((color & 255) * .0722);
                lum += l;
                if (last >= 0) delta += Math.abs(l - last);
                last = l;
                count++;
            }
        }
        return new double[]{count == 0 ? 0 : lum / count, count == 0 ? 0 : delta / count};
    }

    /**
     * Byte-for-byte comparison after a local file-picker import. Compare sizes
     * first; stream in 64-KiB buffers and never delete the user's original file.
     */
    static boolean sameContent(File first, File second) throws Exception {
        if (!first.isFile() || !second.isFile() || first.length() != second.length()) return false;
        if (first.getCanonicalPath().equals(second.getCanonicalPath())) return true;
        byte[] a = new byte[65536], b = new byte[65536];
        try (FileInputStream left = new FileInputStream(first);
             FileInputStream right = new FileInputStream(second)) {
            int n;
            while ((n = left.read(a)) != -1) {
                int at = 0;
                while (at < n) {
                    int read = right.read(b, at, n - at);
                    if (read <= 0) return false;
                    at += read;
                }
                for (int i = 0; i < n; i++) if (a[i] != b[i]) return false;
            }
            return right.read() == -1;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        try (FileInputStream input = new FileInputStream(file)) {
            int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    /**
     * Dependency-free rhythmic envelope estimator. Not speech recognition and
     * not sample-accurate musical beat tracking. Confidence is supplied.
     */
    static JSONObject analyzePcmWave(File file, int maximumSeconds) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.length() < 44 || !fourcc(raf, "RIFF") || raf.skipBytes(4) != 4 || !fourcc(raf, "WAVE"))
                throw new IllegalArgumentException("WAV RIFF attendu");
            int channels = 0, sampleRate = 0, bits = 0, encoding = -1;
            long dataAt = -1, dataSize = 0;
            while (raf.getFilePointer() + 8 <= raf.length()) {
                byte[] id = new byte[4];
                raf.readFully(id);
                long size = readU32(raf);
                long begin = raf.getFilePointer();
                if (new String(id, "US-ASCII").equals("fmt ") && size >= 16) {
                    encoding = readU16(raf);
                    channels = readU16(raf);
                    sampleRate = (int) readU32(raf);
                    raf.skipBytes(6);
                    bits = readU16(raf);
                } else if (new String(id, "US-ASCII").equals("data")) {
                    dataAt = begin;
                    dataSize = Math.min(size, raf.length() - begin);
                    break;
                }
                long next = begin + size + (size & 1);
                if (next > raf.length() || next <= begin) break;
                raf.seek(next);
            }
            if (encoding != 1 || bits != 16 || sampleRate < 8000 || channels < 1 || channels > 8 || dataAt < 0)
                throw new IllegalArgumentException("WAV PCM16 requis");
            int frameBytes = channels * 2;
            int bucketFrames = Math.max(1, sampleRate / 40); // 25-ms blocks
            int bytesPerBucket = bucketFrames * frameBytes;
            int buckets = (int) Math.min(dataSize / bytesPerBucket, maximumSeconds * 40L);
            if (buckets < 120) throw new IllegalArgumentException("Audio trop court pour un BPM fiable");
            double[] envelope = new double[buckets];
            byte[] buf = new byte[bytesPerBucket];
            raf.seek(dataAt);
            for (int k = 0; k < buckets; k++) {
                raf.readFully(buf);
                double energy = 0;
                for (int i = 0; i + 1 < buf.length; i += 2 * channels) {
                    short sample = (short) ((buf[i] & 255) | (buf[i + 1] << 8));
                    energy += Math.abs(sample);
                }
                envelope[k] = energy / bucketFrames;
            }
            // Positive energy onsets reduce sensitivity to sustained instruments.
            double[] onset = new double[buckets];
            for (int i = 1; i < buckets; i++)
                onset[i] = Math.max(0, envelope[i] - envelope[i - 1]);
            double best = -1, second = 0;
            int bestLag = 0;
            for (int lag = 12; lag <= 40; lag++) { // 60–200 BPM
                double score = 0, norm = 0;
                for (int i = lag + 1; i < buckets; i++) {
                    score += onset[i] * onset[i - lag];
                    norm += onset[i] * onset[i];
                }
                score /= Math.max(1, norm);
                if (score > best) { second = best; best = score; bestLag = lag; }
                else if (score > second) second = score;
            }
            double bpm = bestLag == 0 ? 0 : 60.0 * 40 / bestLag;
            double confidence = Math.min(1, Math.max(0, best)); // indicative, not calibrated
            return new JSONObject().put("available", true)
                .put("bpm_estimated", Math.round(bpm))
                .put("confidence_indicator", Math.round(confidence * 100) / 100.0)
                .put("analyzed_seconds", Math.round(buckets / 40.0))
                .put("method", "local PCM onset autocorrelation")
                .put("note", "Tempo indicatif ; vérifier à l'oreille les demi/doubles tempos.");
        }
    }

    private static boolean fourcc(RandomAccessFile f, String s) throws Exception {
        byte[] b = new byte[4];
        f.readFully(b);
        return s.equals(new String(b, "US-ASCII"));
    }
    private static int readU16(RandomAccessFile f) throws Exception {
        int a = f.readUnsignedByte(), b = f.readUnsignedByte();
        return a | (b << 8);
    }
    private static long readU32(RandomAccessFile f) throws Exception {
        return (readU16(f) & 65535L) | ((readU16(f) & 65535L) << 16);
    }
}
