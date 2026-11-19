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
    private int screenWidth = 540, screenHeight = 960;
    private float gestureX, gestureY;
    private long gestureTime;
    private final EngineClient.Listener listener = () -> ui.post(this::refreshState);
    private final Runnable poll = this::refreshFrame;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_native_android);
        engine = EngineClient.get(this);
        screen = findViewById(R.id.native_screen);
        status = findViewById(R.id.native_status);
        placeholder = findViewById(R.id.native_placeholder);
        findViewById(R.id.native_exit).setOnClickListener(v -> { io.execute(engine::releaseDisplay); finish(); });
        findViewById(R.id.native_help).setOnClickListener(v -> showHelp());
        findViewById(R.id.native_shizuku).setOnClickListener(v -> setupShizuku());
        findViewById(R.id.native_start).setOnClickListener(v -> action(() -> engine.createDisplay(540, 960, 240)));
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
        // No display polling when CHK is no longer visible.
        super.onPause();
    }
    @Override protected void onStop() {
        super.onStop();
        if (!NativeDisplayAccess.agentAllowed() && !io.isShutdown())
            io.execute(engine::releaseDisplay);
    }

    @Override protected void onDestroy() {
        engine.removeListener(listener);
        ui.removeCallbacksAndMessages(null);
        io.shutdown();
        screen.setImageDrawable(null);
        if (rendered != null) rendered.recycle();
        if (!NativeDisplayAccess.agentAllowed()) WorkspaceCommands.IO.execute(engine::stopService);
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
        status.setText(prefix + (NativeDisplayAccess.agentAllowed() ? " · Local 3 autorisé" : ""));
        if (engine.isServiceConnected()) {
            io.execute(() -> {
                try {
                    JSONObject info = new JSONObject(engine.status());
                    if (info.optBoolean("display_ready")) {
                        screenWidth = info.optInt("display_width", 540);
                        screenHeight = info.optInt("display_height", 960);
                    }
                    String detail = info.optString("display_state", "");
                    ui.post(() -> {
                        if (!isDestroyed()) status.setText(prefix + (detail.isEmpty() ? "" : " · écran " + detail)
                                + (NativeDisplayAccess.agentAllowed() ? " · Local 3 autorisé" : ""));
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
        new AgentDialog.Builder(this).setTitle("Android intégré dans CHK")
                .setMessage("Ce mode ne démarre PAS une deuxième copie d'Android. Il crée un écran virtuel où les applications réellement installées sur ce téléphone peuvent apparaître.\n\n"
                        + "Pour l'autoriser, installe et démarre Shizuku sur le téléphone avec le débogage sans fil, puis accorde l'accès à CHK. Aucune connexion PC ni nouveau serveur CHK n'est nécessaire.\n\n"
                        + "Play Store et TikTok apparaissent dans CHK uniquement si Honor/Android autorise leur affichage sur un écran secondaire. Aucun succès ne peut être promis sans essai réel. Les données et comptes sont ceux du téléphone, pas un profil séparé.")
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
                    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                    android.widget.GridLayout grid = new android.widget.GridLayout(this);
                    grid.setColumnCount(4);
                    scroll.addView(grid);
                    float density = getResources().getDisplayMetrics().density;
                    int cellWidth = Math.max((int)(70*density),
                            (getResources().getDisplayMetrics().widthPixels-(int)(48*density))/4);
                    android.app.AlertDialog chooser = new AgentDialog.Builder(this)
                            .setTitle("Applications Android · CHK")
                            .setView(scroll)
                            .setNegativeButton("Fermer",null)
                            .create();
                    for (String[] item : list) {
                        android.widget.LinearLayout tile = new android.widget.LinearLayout(this);
                        tile.setOrientation(android.widget.LinearLayout.VERTICAL);
                        tile.setGravity(android.view.Gravity.CENTER);
                        tile.setPadding(4,8,4,8);
                        android.widget.ImageView icon = new android.widget.ImageView(this);
                        try { icon.setImageDrawable(getPackageManager().getApplicationIcon(item[1])); }
                        catch (Exception ignored) { icon.setImageResource(R.drawable.ic_browser); }
                        tile.addView(icon,new android.widget.LinearLayout.LayoutParams((int)(46*density),(int)(46*density)));
                        android.widget.TextView name = new android.widget.TextView(this);
                        name.setText(item[0]);
                        name.setTextColor(0xffEDEAF5);
                        name.setTextSize(11);
                        name.setGravity(android.view.Gravity.CENTER);
                        name.setMaxLines(2);
                        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        tile.addView(name,new android.widget.LinearLayout.LayoutParams(-1,(int)(38*density)));
                        android.widget.GridLayout.LayoutParams pos = new android.widget.GridLayout.LayoutParams();
                        pos.width = cellWidth;
                        pos.height = (int)(112*density);
                        grid.addView(tile,pos);
                        tile.setContentDescription("Ouvrir "+item[0]+" dans CHK");
                        tile.setOnClickListener(v -> {
                            chooser.dismiss();
                            action(() -> engine.launchApp(item[1], false));
                        });
                    }
                    chooser.show();
                    if (chooser.getWindow()!=null) chooser.getWindow().setLayout(-1,(int)(
                            getResources().getDisplayMetrics().heightPixels*0.82f));
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
            EngineClient.Frame next = engine.capture(250, 52);
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
                if (visible) ui.postDelayed(poll, bitmap == null ? 4000 :
                        (android.os.SystemClock.elapsedRealtime()-gestureTime < 12000L ? 1300L : 3200L));
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
        if (NativeDisplayAccess.agentAllowed()) {
            NativeDisplayAccess.stopAgent(this);
            message("Accès Local 3 arrêté.");
        } else {
            new AgentDialog.Builder(this).setTitle("Autoriser Local 3 ?")
                    .setMessage("Pendant 20 minutes, ChatGPT peut voir et contrôler les applications affichées sur l'écran virtuel CHK. Les actions dans tes comptes restent réelles.")
                    .setNegativeButton("Annuler", null)
                    .setPositiveButton("Autoriser",(dialog,which) -> {
                        NativeDisplayAccess.allowAgent(this);
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
