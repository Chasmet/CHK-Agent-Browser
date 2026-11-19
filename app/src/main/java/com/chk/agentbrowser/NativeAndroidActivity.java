package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.chk.agentbrowser.nativeview.EngineClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One extra screen inside the existing CHK APK, not a second browser or virtual OS.
 * Displays a genuine, isolated virtual DISPLAY using the host Android runtime.
 * Apps installed on the owner's phone are launched on it through a consented Shizuku
 * shell service. Existing packages, signatures and application data are untouched.
 */
public final class NativeAndroidActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EngineClient engine;
    private ImageView screen;
    private TextView status, placeholder;
    private Bitmap rendered;
    private boolean visible, capturePending;
    private int screenWidth = 720, screenHeight = 1280;
    private float gestureX, gestureY;
    private long gestureTime;
    private final EngineClient.Listener listener = () -> ui.post(this::refreshState);
    private final Runnable poll = this::refreshFrame;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_native_android);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        engine = EngineClient.get(this);
        screen = findViewById(R.id.native_screen);
        status = findViewById(R.id.native_status);
        placeholder = findViewById(R.id.native_placeholder);
        findViewById(R.id.native_exit).setOnClickListener(v -> finish());
        findViewById(R.id.native_help).setOnClickListener(v -> showHelp());
        findViewById(R.id.native_shizuku).setOnClickListener(v -> setupShizuku());
        findViewById(R.id.native_start).setOnClickListener(v -> action(() -> engine.createDisplay(720, 1280, 320)));
        findViewById(R.id.native_apps).setOnClickListener(v -> chooseApp());
        findViewById(R.id.native_release).setOnClickListener(v -> new AgentDialog.Builder(this)
                .setTitle("Arrêter l'écran Android direct ?")
                .setMessage("Ferme uniquement l'écran virtuel. Ne désinstalle pas les applications et ne supprime aucune donnée.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Arrêter l'écran", (dialog, which) -> action(engine::releaseDisplay)).show());
        findViewById(R.id.native_back).setOnClickListener(v -> action(() -> engine.key(KeyEvent.KEYCODE_BACK)));
        findViewById(R.id.native_home).setOnClickListener(v -> action(() -> engine.key(KeyEvent.KEYCODE_HOME)));
        findViewById(R.id.native_keyboard).setOnClickListener(v -> keyboard());
        findViewById(R.id.native_agent).setOnClickListener(v -> agentAccess());
        screen.setOnTouchListener((v, event) -> touch(event));
        engine.addListener(listener);
        refreshState();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        if (engine.isBinderAlive() && engine.hasPermission() && !engine.isServiceConnected())
            engine.startService();
        refreshState();
        ui.post(poll);
    }

    @Override protected void onPause() {
        visible = false;
        ui.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        engine.removeListener(listener);
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        screen.setImageDrawable(null);
        if (rendered != null) rendered.recycle();
        super.onDestroy();
    }

    private void refreshState() {
        if (isDestroyed()) return;
        String prefix = engine.isBinderAlive()
                ? engine.hasPermission()
                    ? engine.isServiceConnected() ? "Shizuku relié" : "Shizuku autorisé · connecter l'espace"
                    : "Shizuku actif · autorisation à accorder"
                : "Shizuku absent ou arrêté · voir Aide";
        ((TextView)findViewById(R.id.native_shizuku)).setText(
                engine.isBinderAlive() && engine.hasPermission() ? "Reconnecter" : "Autoriser Shizuku");
        status.setText(prefix + (EmulatorConnection.agentAllowed() ? " · Local 3 autorisé" : ""));
        if (engine.isServiceConnected()) {
            io.execute(() -> {
                try {
                    JSONObject info = new JSONObject(engine.status());
                    if (info.optBoolean("display_ready")) {
                        screenWidth = info.optInt("display_width", 720);
                        screenHeight = info.optInt("display_height", 1280);
                    }
                    String detail = info.optString("display_state", "");
                    ui.post(() -> {
                        if (!isDestroyed()) status.setText(prefix + (detail.isEmpty() ? "" : " · écran " + detail)
                                + (EmulatorConnection.agentAllowed() ? " · Local 3 autorisé" : ""));
                    });
                } catch (Exception ignored) { }
            });
        }
    }

    private void setupShizuku() {
        if (!engine.isBinderAlive()) {
            showHelp();
            return;
        }
        String result = engine.hasPermission() ? engine.startService() : engine.requestPermission();
        if (result != null) message(result);
        else refreshState();
    }

    private void showHelp() {
        new AgentDialog.Builder(this).setTitle("Android direct dans CHK")
                .setMessage("Ce mode ne démarre PAS une deuxième copie d'Android. Il crée un écran virtuel où les applications réellement installées sur ce téléphone peuvent apparaître.\n\n"
                        + "Pour l'autoriser, installe et démarre Shizuku sur le téléphone avec le débogage sans fil, puis accorde l'accès à CHK. Aucune connexion PC ni nouveau serveur CHK n'est nécessaire.\n\n"
                        + "Le résultat dépend des limitations Honor/Android. Certains programmes refusent les écrans secondaires ou cachent leurs vidéos protégées. Les applications partagent leurs données et comptes existants ; elles ne sont pas clonées.")
                .setNegativeButton("Fermer", null)
                .setPositiveButton("Site officiel Shizuku", (d,w) -> startActivity(
                        new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/"))))
                .show();
    }

    private interface Job { String run() throws Exception; }

    private void action(Job job) {
        if (!engine.isServiceConnected()) { message("Autorise Shizuku puis reconnecte le moteur."); return; }
        io.execute(() -> {
            String result;
            try { result = job.run(); }
            catch (Exception e) { result = "{\"ok\":false,\"error\":\"" + e.getClass().getSimpleName() + "\"}"; }
            String message;
            try {
                JSONObject response = new JSONObject(result);
                message = response.optBoolean("ok") ? response.optString("detail", "Commande acceptée")
                        : response.optString("error", "Commande refusée");
            } catch (Exception e) { message = result; }
            String feedback = message;
            ui.post(() -> {
                if (isDestroyed()) return;
                refreshState();
                if (!feedback.isEmpty()) status.setText(feedback);
                if (visible) ui.post(poll);
            });
        });
    }

    private void chooseApp() {
        if (!engine.isServiceConnected()) { message("Démarre Shizuku et l'écran Android direct."); return; }
        io.execute(() -> {
            try {
                JSONArray apps = new JSONArray(engine.listApps());
                List<String[]> list = new ArrayList<>();
                for (int i = 0; i < apps.length(); i++) {
                    JSONObject item = apps.optJSONObject(i);
                    if (item == null) continue;
                    String pkg = item.optString("package", ""), label = item.optString("label", "");
                    if (pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") && !pkg.equals(getPackageName()))
                        list.add(new String[]{label.isEmpty() ? pkg : label, pkg});
                }
                Collections.sort(list, (a,b) -> a[0].compareToIgnoreCase(b[0]));
                ui.post(() -> {
                    if (isDestroyed()) return;
                    if (list.isEmpty()) { message("Aucune application de téléphone détectée."); return; }
                    String[] titles = new String[list.size()];
                    for (int i = 0; i < list.size(); i++) titles[i] = list.get(i)[0];
                    new AgentDialog.Builder(this).setTitle("Ouvrir sur l'écran CHK (sans QEMU)")
                            .setItems(titles, (d,which) -> action(() -> engine.launchApp(list.get(which)[1], false)))
                            .setNegativeButton("Annuler", null).show();
                });
            } catch (Exception e) {
                ui.post(() -> message("Liste inaccessible : " + e.getClass().getSimpleName()));
            }
        });
    }

    private void refreshFrame() {
        ui.removeCallbacks(poll);
        if (!visible || isDestroyed() || !engine.isServiceConnected() || capturePending) {
            if (visible) ui.postDelayed(poll, 1800);
            return;
        }
        capturePending = true;
        io.execute(() -> {
            EngineClient.Frame next = engine.capture(250, 58);
            Bitmap bitmap = next.ok() ? BitmapFactory.decodeByteArray(next.jpeg,0,next.jpeg.length) : null;
            ui.post(() -> {
                capturePending = false;
                if (isDestroyed()) { if (bitmap != null) bitmap.recycle(); return; }
                if (bitmap != null) {
                    Bitmap old = rendered;
                    rendered = bitmap;
                    screen.setImageBitmap(bitmap);
                    placeholder.setVisibility(View.GONE);
                    if (old != null) old.recycle();
                }
                if (visible) ui.postDelayed(poll, bitmap == null ? 2500 : 850);
            });
        });
    }

    private boolean touch(MotionEvent event) {
        if (rendered == null || !engine.isServiceConnected()) return false;
        RectF bounds = new RectF(0, 0, rendered.getWidth(), rendered.getHeight());
        screen.getImageMatrix().mapRect(bounds);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (!bounds.contains(event.getX(),event.getY())) return false;
            gestureX = (event.getX() - bounds.left) / bounds.width();
            gestureY = (event.getY() - bounds.top) / bounds.height();
            gestureTime = android.os.SystemClock.elapsedRealtime();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (!bounds.contains(event.getX(),event.getY())) return true;
            int x = Math.max(0,Math.min(screenWidth-1,Math.round(gestureX*screenWidth)));
            int y = Math.max(0,Math.min(screenHeight-1,Math.round(gestureY*screenHeight)));
            int endX = Math.max(0,Math.min(screenWidth-1,
                    Math.round((event.getX()-bounds.left)/bounds.width()*screenWidth)));
            int endY = Math.max(0,Math.min(screenHeight-1,
                    Math.round((event.getY()-bounds.top)/bounds.height()*screenHeight)));
            int duration = (int)Math.min(2000,android.os.SystemClock.elapsedRealtime()-gestureTime);
            if (Math.abs(x-endX)+Math.abs(y-endY) > 25)
                action(() -> engine.swipe(x,y,endX,endY,Math.max(160,duration)));
            else action(() -> engine.tap(endX,endY));
            return true;
        }
        return true;
    }

    private void keyboard() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("Texte à saisir dans l'application");
        new AgentDialog.Builder(this).setTitle("Clavier Android direct")
                .setMessage("Sélectionne d'abord un champ sur l'écran virtuel.")
                .setView(field).setNegativeButton("Annuler",null)
                .setPositiveButton("Saisir",(dialog,which) -> {
                    String text = field.getText().toString();
                    if (!text.isEmpty()) action(() -> engine.text(text));
                }).show();
    }

    private void agentAccess() {
        if (EmulatorConnection.agentAllowed()) {
            EmulatorConnection.stopAgent();
            message("Accès Local 3 arrêté.");
        } else {
            new AgentDialog.Builder(this).setTitle("Autoriser Local 3 ?")
                    .setMessage("Pendant 20 minutes, ChatGPT peut voir et contrôler les applications affichées sur l'écran virtuel CHK. Les actions dans tes comptes restent réelles.")
                    .setNegativeButton("Annuler", null)
                    .setPositiveButton("Autoriser",(dialog,which) -> {
                        EmulatorConnection.allowAgent();
                        refreshState();
                    }).show();
        }
    }

    private void message(String text) {
        if (!isDestroyed()) {
            status.setText(text);
            android.widget.Toast.makeText(this,text,android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
