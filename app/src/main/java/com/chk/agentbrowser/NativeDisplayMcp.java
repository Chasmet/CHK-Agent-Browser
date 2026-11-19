package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.view.KeyEvent;

import com.chk.agentbrowser.nativeview.EngineClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.UUID;

/**
 * Consent-gated Local 3 control of the owner's REAL secondary Android display.
 * Does not create an OS guest, install APKs, use MediaProjection or control
 * the physical phone display.
 */
final class NativeDisplayMcp {
    private static String recentFrameId;
    private static long recentAt;
    private static int width, height;

    private NativeDisplayMcp() { }

    static synchronized JSONObject command(Context context, JSONObject args) throws Exception {
        String operation = args.optString("operation");
        EngineClient client = EngineClient.get(context);
        if ("native_status".equals(operation)) {
            JSONObject report = new JSONObject()
                    .put("source", "native_display")
                    .put("virtual_machine", false)
                    .put("execution", "host_android_secondary_display")
                    .put("requires_shizuku", true)
                    .put("shizuku_running", client.isBinderAlive())
                    .put("shizuku_permission", client.hasPermission())
                    .put("service_connected", client.isServiceConnected())
                    .put("local3_authorized", EmulatorConnection.agentAllowed());
            if (client.isServiceConnected()) {
                try {
                    JSONObject status = new JSONObject(client.status());
                    report.put("display", status);
                } catch (Exception e) { report.put("status_error", e.getClass().getSimpleName()); }
            }
            return report;
        }
        if (!EmulatorConnection.agentAllowed())
            throw new IOException("Autorise Local 3 pendant 20 min depuis Android direct dans CHK.");
        if (!client.isServiceConnected()) throw new IOException("Shizuku non connecté. Ouvre Android direct dans CHK.");
        switch (operation) {
            case "native_create":
                return confirmed(client.createDisplay(720,1280,320));
            case "native_release":
                recentFrameId = null;
                return confirmed(client.releaseDisplay());
            case "native_list_apps":
                return new JSONObject().put("apps", new JSONArray(client.listApps()));
            case "native_launch":
                String name = args.getString("package_name");
                if (!validPackage(name)) throw new IOException("Nom de package Android invalide.");
                return confirmed(client.launchApp(name,false));
            case "native_observe":
                return capture(client);
            case "native_tap":
                checkFrame(args);
                return confirmed(client.tap(coordinate(args,"x",width),coordinate(args,"y",height)));
            case "native_swipe":
                checkFrame(args);
                return confirmed(client.swipe(coordinate(args,"x",width),coordinate(args,"y",height),
                        coordinate(args,"end_x",width),coordinate(args,"end_y",height), 400));
            case "native_key": {
                String key = args.getString("key");
                int code = "home".equals(key) ? KeyEvent.KEYCODE_HOME
                        : "back".equals(key) ? KeyEvent.KEYCODE_BACK
                        : "enter".equals(key) ? KeyEvent.KEYCODE_ENTER
                        : -1;
                if (code == -1) throw new IOException("Touche Android inconnue.");
                return confirmed(client.key(code));
            }
            case "native_text": {
                String text = args.getString("text");
                if (text.isEmpty() || text.length() > 300)
                    throw new IOException("Texte Android invalide (300 caractères maximum).");
                return confirmed(client.text(text));
            }
            default: throw new IOException("Opération Android direct inconnue.");
        }
    }

    static boolean validPackage(String name) {
        return name != null && name.length() <= 230 &&
                name.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    }

    private static JSONObject confirmed(String json) throws Exception {
        JSONObject result = new JSONObject(json);
        if (!result.optBoolean("ok",false))
            throw new IOException(result.optString("error","Commande Android refusée."));
        return result;
    }

    private static JSONObject capture(EngineClient client) throws Exception {
        JSONObject info = new JSONObject(client.status());
        if (!info.optBoolean("display_ready")) throw new IOException("Crée d'abord l'écran Android direct.");
        EngineClient.Frame frame = client.capture(500, 55);
        if (!frame.ok()) throw new IOException("Capture Android indisponible : "+frame.error);
        Bitmap original = BitmapFactory.decodeByteArray(frame.jpeg,0,frame.jpeg.length);
        if (original == null) throw new IOException("Capture Android illisible.");
        try {
            EmulatorMcp.Preview preview = EmulatorMcp.encodePreview(original);
            width = info.optInt("display_width",original.getWidth());
            height = info.optInt("display_height",original.getHeight());
            recentFrameId = UUID.randomUUID().toString().replace("-","");
            recentAt = SystemClock.elapsedRealtime();
            return new JSONObject().put("source", "native_display")
                    .put("frame_id",recentFrameId)
                    .put("width",width).put("height",height)
                    .put("preview_width",preview.width).put("preview_height",preview.height)
                    .put("mime_type","image/jpeg")
                    .put("image_base64",android.util.Base64.encodeToString(
                            preview.jpeg,android.util.Base64.NO_WRAP));
        } finally { original.recycle(); }
    }

    private static void checkFrame(JSONObject args) throws IOException {
        if (recentFrameId == null || !recentFrameId.equals(args.optString("frame_id"))
                || SystemClock.elapsedRealtime() - recentAt > 30000)
            throw new IOException("Actualise la capture Android direct avant d'agir.");
    }

    private static int coordinate(JSONObject args, String name, int dimension) throws Exception {
        int n = args.getInt(name);
        if (n < 0 || n >= dimension) throw new IOException("Coordonnée hors de l'écran virtuel.");
        return n;
    }
}
