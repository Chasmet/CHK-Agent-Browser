package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
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
    private long startedElapsed;
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
        String nvramFormat = prepareNvram(new File(folder, "efi_vars.fd"));
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
                "-accel", "tcg,thread=multi,tb-size=256",
                "-m", "2048", "-smp", "2",
                "-display", "none", "-monitor", "none", "-serial", "file:serial.log",
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
                "-drive", "if=pflash,unit=1,format=" + nvramFormat + ",file=efi_vars.fd"));
        for (String drive : Arrays.asList("vda", "vdb")) {
            command.addAll(Arrays.asList("-drive", "file=" + drive + ".qcow2,if=none,id=" + drive + ",format=qcow2,discard=unmap,detect-zeroes=unmap",
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
        startedElapsed = SystemClock.elapsedRealtime();
        // Keep the spawning worker alive for the native launcher's parent-death signal.
        SystemClock.sleep(250);
        if (!running()) throw new IOException("Le moteur Android s’est arrêté au démarrage. Consulte le journal dans le menu.");
    }

    static String prepareNvram(File nvram) throws IOException {
        try (java.io.RandomAccessFile flash = new java.io.RandomAccessFile(nvram, "rw")) {
            long size = flash.length();
            if (size < 32) throw new IOException("Variables UEFI Android invalides.");
            if (flash.readInt() == 0x514649fb) {
                // UTM can store the flash bank in a sparse QCOW2 container.
                // Its virtual size is independent of the size of the file.
                flash.seek(24);
                if (flash.readLong() != FIRMWARE_FLASH_SIZE) throw new IOException("Taille de la banque UEFI Android invalide.");
                return "qcow2";
            }
            if (size > FIRMWARE_FLASH_SIZE) throw new IOException("Variables UEFI Android invalides.");
            if (size < FIRMWARE_FLASH_SIZE) {
                flash.setLength(FIRMWARE_FLASH_SIZE);
                flash.getFD().sync();
            }
            return "raw";
        }
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
    synchronized long bootElapsedSeconds() {
        return running() && startedElapsed > 0 ? (SystemClock.elapsedRealtime() - startedElapsed) / 1000L : 0L;
    }
    static void stopAgent() { agentUntil = 0L; }
    static boolean agentAllowed() { return agentUntil > SystemClock.elapsedRealtime(); }

    synchronized JSONObject status() throws Exception {
        boolean active = running();
        // Backward-compatible ready means QEMU's display is accessible, NOT Android boot_completed.
        JSONObject result = new JSONObject().put("ready", active && frameId != null)
                .put("display_ready", active && frameId != null)
                .put("guest_boot_verified", false)
                .put("running", active).put("execution", "on_device")
                .put("boot_elapsed_seconds", bootElapsedSeconds());
        if (active) {
            try {
                Object response = command("query-status", null);
                if (response instanceof JSONObject) {
                    result.put("qemu_runstate", ((JSONObject) response).optString("status", "unknown"));
                }
            } catch (Exception error) {
                result.put("qemu_status_error", safeMessage(error));
            }
        }
        return result;
    }

    synchronized JSONObject diagnostics(Context context) throws Exception {
        JSONObject report = status()
                .put("abi_compatible", compatible())
                .put("engine_present", engineInstalled(context))
                .put("image_present", GuestImage.exists(context))
                .put("display_does_not_prove_boot", true)
                .put("guest_ram_mib", 2048)
                .put("guest_cpus", 2);
        if (folder != null) {
            report.put("engine_log_tail", tail(new File(folder, "boot.log"), 4096))
                    .put("guest_serial_tail", tail(new File(folder, "serial.log"), 4096));
        }
        return report;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message.substring(0, Math.min(300, message.length()));
    }

    private static String tail(File path, int limit) {
        if (!path.isFile()) return "Journal indisponible.";
        try (java.io.RandomAccessFile log = new java.io.RandomAccessFile(path, "r")) {
            long size = log.length();
            byte[] content = new byte[(int) Math.min(size, limit)];
            log.seek(size - content.length);
            log.readFully(content);
            return content.length == 0 ? "Journal vide." : new String(content, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Journal inaccessible.";
        }
    }

    synchronized void forceStop() throws InterruptedException {
        // The Android guest is only supported on API 26+; do not invoke newer Process APIs below it.
        if (Build.VERSION.SDK_INT < 26) return;
        Process active = process;
        if (active != null) {
            active.destroy();
            if (!active.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                active.destroyForcibly();
                active.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        process = null;
        resetChannel();
        stopAgent();
    }

    synchronized Frame screen() throws Exception {
        if (!running()) throw new IOException("Android est arrêté.");
        File file = new File(folder, "current.ppm");
        try {
            command("screendump", new JSONObject().put("filename", file.getName()).put("format", "ppm"));
            Bitmap bitmap = GuestFrame.read(file);
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

    static boolean acceptedGuestFile(String name) {
        return name != null && name.toLowerCase(Locale.ROOT)
                .matches("[a-z0-9._-]{1,125}\\.(mp4|mov|m4v|webm|jpg|jpeg|png|webp|mp3|m4a|wav|apk)");
    }

    JSONObject sendMedia(InputStream source, String filename, long size) throws Exception {
        File targetFolder;
        synchronized (this) {
            if (folder == null) throw new IOException("Démarre une première fois la machine virtuelle Android.");
            targetFolder = new File(folder, "media");
        }
        if (size <= 0 || size > MAX_MEDIA) throw new IOException("Choisis un média de 480 Mio maximum pour le support d’échange.");
        String name = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!acceptedGuestFile(name)) {
            throw new IOException("Choisis un APK, une vidéo, une image ou un audio compatible.");
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
