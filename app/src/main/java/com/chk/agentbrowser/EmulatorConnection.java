package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class EmulatorConnection {
    private static final EmulatorConnection INSTANCE = new EmulatorConnection();
    private static final long MAX_MEDIA = 480L * 1024 * 1024;
    private static volatile long agentUntil;
    private Process process;
    private File folder;
    private GuestQmp qmp;
    private String frameId;
    private int width, height;
    private long capturedAt;
    private static final String FIRMWARE = "edk2-aarch64-code.fd";
    private static final long FIRMWARE_FLASH_SIZE = 64L * 1024 * 1024;

    static final class Frame {
        final Bitmap bitmap;
        final String id;

        Frame(Bitmap bitmap, String id) {
            this.bitmap = bitmap;
            this.id = id;
        }
    }

    static EmulatorConnection load(Context ignored) { return INSTANCE; }

    static boolean compatible() {
        return Build.VERSION.SDK_INT >= 26 && Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a");
    }

    static boolean engineInstalled(Context context) {
        File libs = new File(context.getApplicationInfo().nativeLibraryDir);
        return new File(libs, "libqemu-system-aarch64.so").canExecute()
                && new File(libs, "libpodroid-launcher.so").canExecute();
    }

    synchronized void start(Context context) throws Exception {
        if (running()) return;
        if (Build.VERSION.SDK_INT < 26 || !compatible()) throw new IOException("Le moteur Android nécessite un téléphone ARM64 sous Android 8 ou plus récent.");
        if (!engineInstalled(context)) throw new IOException("Le moteur Android ARM64 manque dans cette version de CHK.");
        if (!GuestImage.exists(context)) throw new IOException("Importe une image Android avant de démarrer.");
        folder = GuestImage.installed(context);
        File firmware = prepareAsset(context, FIRMWARE);
        if (firmware.length() != FIRMWARE_FLASH_SIZE) throw new IOException("Micrologiciel Android incomplet.");
        File nvram = new File(folder, "efi_vars.fd");
        try (java.io.RandomAccessFile flash = new java.io.RandomAccessFile(nvram, "rw")) {
            long size = flash.length();
            if (size <= 0 || size > FIRMWARE_FLASH_SIZE) throw new IOException("Variables UEFI Android invalides.");
            // QEMU's virt board requires each raw pflash bank to span 64 MiB.
            // Preserve all imported variables and extend only the unused tail.
            if (size < FIRMWARE_FLASH_SIZE) {
                flash.setLength(FIRMWARE_FLASH_SIZE);
                flash.getFD().sync();
            }
        }
        prepareAsset(context, "efi-virtio.rom");
        File sockets = new File(folder, "qmp.sock");
        if (sockets.exists() && !sockets.delete()) throw new IOException("Le canal Android ne peut pas être réinitialisé.");
        String libs = context.getApplicationInfo().nativeLibraryDir;
        List<String> command = new ArrayList<>(Arrays.asList(
                new File(libs, "libpodroid-launcher.so").getPath(),
                new File(libs, "libqemu-system-aarch64.so").getPath(),
                "-L", folder.getPath(),
                "-machine", "virt",
                "-cpu", "max,pauth-impdef=on",
                "-accel", "tcg,thread=multi,tb-size=128",
                "-m", "2048", "-smp", "2",
                "-display", "none", "-monitor", "none", "-serial", "none",
                "-qmp", "unix:qmp.sock,server=on,wait=off",
                "-device", "virtio-gpu-pci",
                "-device", "qemu-xhci,id=usb",
                "-device", "usb-tablet,bus=usb.0",
                "-device", "usb-kbd,bus=usb.0",
                "-device", "virtio-rng-pci",
                "-device", "virtio-serial",
                "-device", "virtio-net-pci,netdev=net0",
                "-netdev", "user,id=net0",
                "-drive", "if=pflash,unit=0,format=raw,readonly=on,file=" + firmware.getName(),
                "-drive", "if=pflash,unit=1,format=raw,file=efi_vars.fd"));
        for (String drive : Arrays.asList("vda", "vdb")) {
            command.addAll(Arrays.asList("-drive", "file=" + drive + ".qcow2,if=none,id=" + drive + ",format=qcow2",
                    "-device", "virtio-blk-pci,drive=" + drive + (drive.equals("vda") ? ",bootindex=0" : ",bootindex=1")));
        }
        File exchange = new File(folder, "media");
        if (!exchange.isDirectory() && !exchange.mkdir()) throw new IOException("Dossier d’échange Android inaccessible.");
        File mountedMedia = new File(folder, "mounted");
        if (!mountedMedia.isDirectory() && !mountedMedia.mkdir()) throw new IOException("Support Android indisponible.");
        File[] queued = exchange.listFiles();
        if (queued == null) throw new IOException("Dossier d’échange Android inaccessible.");
        if (queued.length > 0) {
            File[] old = mountedMedia.listFiles();
            if (old == null) throw new IOException("Support Android indisponible.");
            for (File file : old) if (!file.delete()) throw new IOException("Impossible de remplacer le média échangé.");
            for (File file : queued) {
                if (!file.renameTo(new File(mountedMedia, file.getName()))) throw new IOException("Impossible de monter le média échangé.");
            }
        }
        File[] shared = mountedMedia.listFiles();
        if (shared != null && shared.length > 0) {
            command.addAll(Arrays.asList("-drive", "file=fat:mounted,format=raw,if=none,id=exchange,readonly=on",
                    "-device", "usb-storage,bus=usb.0,drive=exchange"));
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(folder);
        builder.environment().put("LD_LIBRARY_PATH", libs);
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File(folder, "boot.log"));
        process = builder.start();
        // Keep the spawning worker alive for the native launcher's parent-death signal.
        SystemClock.sleep(250);
        if (!running()) throw new IOException("Le moteur Android s’est arrêté au démarrage. Consulte le journal dans le menu.");
    }

    private File prepareAsset(Context context, String name) throws IOException {
        File target = new File(folder, name);
        if (target.length() > 0) return target;
        File pending = new File(folder, name + ".part");
        try {
            try (InputStream input = context.getAssets().open("android-guest/" + name);
                 FileOutputStream output = new FileOutputStream(pending)) {
                GuestImage.copy(input, output, 64L * 1024 * 1024, null);
            }
            if (!pending.renameTo(target)) throw new IOException("Impossible de préparer le micrologiciel Android.");
        } finally {
            pending.delete();
        }
        return target;
    }

    synchronized boolean running() {
        if (process == null) return false;
        try {
            process.exitValue();
        } catch (IllegalThreadStateException running) {
            return true;
        }
        process = null;
        resetChannel();
        stopAgent();
        return false;
    }

    synchronized String log() {
        if (folder == null) return "Aucun démarrage Android enregistré.";
        File path = new File(folder, "boot.log");
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(path, "r")) {
            byte[] message = new byte[(int) Math.min(file.length(), 8192)];
            file.seek(file.length() - message.length);
            file.readFully(message);
            return message.length == 0 ? "Aucun message du moteur Android." : new String(message, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "Journal Android indisponible.";
        }
    }

    private void resetChannel() {
        frameId = null;
        if (qmp != null) {
            try { qmp.close(); } catch (IOException ignored) { }
            qmp = null;
        }
    }

    private Object command(String name, JSONObject arguments) throws Exception {
        if (!running()) throw new IOException("Démarre Android dans CHK.");
        try {
            if (qmp == null) qmp = new GuestQmp(new File(folder, "qmp.sock").getPath());
            return qmp.command(name, arguments);
        } catch (Exception error) {
            resetChannel();
            throw error;
        }
    }

    synchronized void stop() throws Exception {
        if (!running()) return;
        command("system_powerdown", null);
        stopAgent();
    }

    static void allowAgent() { agentUntil = SystemClock.elapsedRealtime() + 20 * 60_000L; }
    static void stopAgent() { agentUntil = 0L; }
    static boolean agentAllowed() { return agentUntil > SystemClock.elapsedRealtime(); }

    synchronized JSONObject status() throws Exception {
        return new JSONObject().put("ready", running() && frameId != null)
                .put("running", running()).put("execution", "on_device");
    }

    synchronized Frame screen() throws Exception {
        if (!running()) throw new IOException("Android est arrêté.");
        File file = new File(folder, "current.png");
        try {
            command("screendump", new JSONObject().put("filename", file.getName()).put("format", "png"));
            if (file.length() > 12 * 1024 * 1024) throw new IOException("Capture Android trop grande.");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            if (options.outWidth < 1 || options.outHeight < 1 || (long) options.outWidth * options.outHeight > 10_000_000) {
                throw new IOException("Android prépare son écran.");
            }
            options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            if (bitmap == null) throw new IOException("Capture Android illisible.");
            if (frameId == null || width != bitmap.getWidth() || height != bitmap.getHeight()
                    || SystemClock.elapsedRealtime() - capturedAt >= 30000) {
                frameId = UUID.randomUUID().toString().replace("-", "");
                capturedAt = SystemClock.elapsedRealtime();
                width = bitmap.getWidth();
                height = bitmap.getHeight();
            }
            return new Frame(bitmap, frameId);
        } finally {
            file.delete();
        }
    }

    synchronized JSONObject input(JSONObject args) throws Exception {
        String operation = args.getString("operation");
        switch (operation) {
            case "tap": case "swipe":
                if (frameId == null || !frameId.equals(args.optString("frame_id"))
                        || SystemClock.elapsedRealtime() - capturedAt >= 30000) throw new IOException("Actualise l’écran Android avant d’agir.");
                int x = coordinate(args, "x", width), y = coordinate(args, "y", height);
                boolean swipe = "swipe".equals(operation);
                int endX = swipe ? coordinate(args, "end_x", width) : x;
                int endY = swipe ? coordinate(args, "end_y", height) : y;
                send(pointer(x, y).put(button(true)));
                try {
                    if (swipe) {
                        for (int part = 1; part <= 6; part++) {
                            SystemClock.sleep(45);
                            send(pointer(x + (endX - x) * part / 6, y + (endY - y) * part / 6));
                        }
                    }
                } finally {
                    send(new JSONArray().put(button(false)));
                }
                break;
            case "key":
                sendKey(args.getString("key"));
                break;
            case "text":
                typeText(args.getString("text"));
                break;
            default:
                throw new IOException("Action Android inconnue.");
        }
        frameId = null;
        return new JSONObject().put("input_sent", true);
    }

    private static int coordinate(JSONObject args, String name, int max) throws Exception {
        Object value = args.get(name);
        if (!(value instanceof Integer) || (Integer) value < 0 || (Integer) value >= max) {
            throw new IOException("Coordonnée hors de l’écran Android.");
        }
        return (Integer) value;
    }

    private JSONArray pointer(int x, int y) throws Exception {
        return new JSONArray().put(event("abs", new JSONObject().put("axis", "x").put("value", x * 32767 / Math.max(width - 1, 1))))
                .put(event("abs", new JSONObject().put("axis", "y").put("value", y * 32767 / Math.max(height - 1, 1))));
    }

    private static JSONObject event(String type, JSONObject data) throws Exception {
        return new JSONObject().put("type", type).put("data", data);
    }

    private static JSONObject button(boolean down) throws Exception {
        return event("btn", new JSONObject().put("button", "left").put("down", down));
    }

    private static JSONObject key(String name, boolean down) throws Exception {
        return event("key", new JSONObject().put("down", down)
                .put("key", new JSONObject().put("type", "qcode").put("data", name)));
    }

    private void send(JSONArray events) throws Exception {
        command("input-send-event", new JSONObject().put("events", events));
    }

    private void sendKey(String requested) throws Exception {
        String name;
        switch (requested) {
            case "home": name = "home"; break;
            case "back": name = "esc"; break;
            case "enter": name = "ret"; break;
            case "delete": name = "backspace"; break;
            default: throw new IOException("Touche Android inconnue.");
        }
        send(new JSONArray().put(key(name, true)).put(key(name, false)));
    }

    private void typeText(String text) throws Exception {
        if (!text.matches("[a-zA-Z0-9 ._-]{1,300}")) {
            throw new IOException("Utilise le clavier d’Android pour les accents et caractères spéciaux.");
        }
        for (char ch : text.toCharArray()) {
            boolean shift = Character.isUpperCase(ch) || ch == '_';
            String name = ch == ' ' ? "spc" : ch == '.' ? "dot" : ch == '-' || ch == '_' ? "minus"
                    : Character.toString(Character.toLowerCase(ch));
            JSONArray events = new JSONArray();
            if (shift) events.put(key("shift", true));
            events.put(key(name, true)).put(key(name, false));
            if (shift) events.put(key("shift", false));
            send(events);
        }
    }

    JSONObject sendMedia(InputStream source, String filename, long size) throws Exception {
        File targetFolder;
        synchronized (this) {
            if (folder == null) throw new IOException("Démarre une première fois la machine virtuelle Android.");
            targetFolder = new File(folder, "media");
        }
        if (size <= 0 || size > MAX_MEDIA) throw new IOException("Choisis un média de 480 Mio maximum pour le support d’échange.");
        String name = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!name.toLowerCase(Locale.ROOT).matches("[a-z0-9._-]{1,125}\\.(mp4|mov|m4v|webm|jpg|jpeg|png|webp|mp3|m4a|wav)")) {
            throw new IOException("Choisis un fichier vidéo, image ou audio compatible.");
        }
        File staged = File.createTempFile("media-", ".part", folder);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try {
            try (FileOutputStream output = new FileOutputStream(staged)) {
                if (GuestImage.copy(source, output, size, digest) != size) throw new IOException("Taille du média modifiée pendant la copie.");
            }
            String finalName = UUID.randomUUID().toString().substring(0, 8) + "-" + name;
            synchronized (this) {
                File[] queued = targetFolder.listFiles();
                if (queued == null) throw new IOException("Dossier d’échange Android inaccessible.");
                for (File file : queued) if (!file.delete()) throw new IOException("Impossible de remplacer le média en attente.");
                File target = new File(targetFolder, finalName);
                if (!staged.renameTo(target)) throw new IOException("Copie Android inaccessible.");
            }
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            return new JSONObject().put("verified", true).put("available_in_guest", false).put("restart_required", true)
                    .put("path", finalName).put("size", size).put("sha256", hash.toString());
        } finally {
            staged.delete();
        }
    }
}
