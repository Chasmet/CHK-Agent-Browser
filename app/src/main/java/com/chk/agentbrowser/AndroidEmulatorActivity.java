package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebChromeClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AndroidEmulatorActivity extends Activity {
    private static final int PICK_DEVICE_MEDIA = 814;
    private static final int PICK_SYSTEM_IMAGE = 815;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final ExecutorService ENGINE = Executors.newSingleThreadExecutor();
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private final ExecutorService media = Executors.newSingleThreadExecutor();
    private final Runnable refresh = this::refreshScreen;
    private ImageView screen;
    private TextView status;
    private View connect;
    private WorkspacePanel workspace;
    private WorkspaceMediaChooser picker;
    private EmulatorConnection connection;
    private EmulatorConnection.Frame frame;
    private EmulatorConnection.Frame touchedFrame;
    private RectF touchedBounds;
    private int startX;
    private int startY;
    private boolean visible;
    private boolean framePending;
    private boolean sendingInput;
    private boolean sendingMedia;
    private boolean errorDisplayed;
    private boolean starting;
    private boolean importing;
    private String tab = "android";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_android_emulator);
        screen = findViewById(R.id.emulator_frame);
        status = findViewById(R.id.emulator_status);
        connect = findViewById(R.id.emulator_connect);
        FrameLayout shared = findViewById(R.id.emulator_workspace);
        workspace = new WorkspacePanel(this, shared);
        workspace.restoreState(state);
        findViewById(R.id.emulator_exit).setOnClickListener(view -> finish());
        findViewById(R.id.emulator_menu).setOnClickListener(view -> menu());
        connect.setOnClickListener(view -> prepareAndroid());
        findViewById(R.id.emulator_home).setOnClickListener(view -> key("home"));
        findViewById(R.id.emulator_back).setOnClickListener(view -> key("back"));
        findViewById(R.id.emulator_keyboard).setOnClickListener(view -> keyboard());
        findViewById(R.id.emulator_media).setOnClickListener(view -> chooseMedia());
        findViewById(R.id.emulator_nav_android).setOnClickListener(view -> showTab("android"));
        findViewById(R.id.emulator_nav_files).setOnClickListener(view -> showTab("files"));
        findViewById(R.id.emulator_nav_notes).setOnClickListener(view -> showTab("notes"));
        findViewById(R.id.emulator_nav_studio).setOnClickListener(view ->
                startActivity(new Intent(this, StudioProjectsActivity.class)));
        findViewById(R.id.emulator_nav_cut).setOnClickListener(view ->
                startActivity(new Intent(this, CutStudioHomeActivity.class)));
        screen.setOnTouchListener((view, event) -> touch(event));
        connection = EmulatorConnection.load(this);
        showTab(state == null ? "android" : state.getString("emulator_tab", "android"));
        idleState();
    }

    private void showTab(String selected) {
        tab = selected;
        boolean android = "android".equals(tab);
        findViewById(R.id.emulator_screen).setVisibility(android ? View.VISIBLE : View.GONE);
        findViewById(R.id.emulator_workspace).setVisibility(android ? View.GONE : View.VISIBLE);
        findViewById(R.id.emulator_controls).setVisibility(android ? View.VISIBLE : View.GONE);
        status.setVisibility(android ? View.VISIBLE : View.GONE);
        int[] ids = {R.id.emulator_nav_android, R.id.emulator_nav_files, R.id.emulator_nav_notes};
        String[] tabs = {"android", "files", "notes"};
        for (int i = 0; i < ids.length; i++) {
            TextView button = findViewById(ids[i]);
            boolean active = tab.equals(tabs[i]);
            button.setSelected(active);
            button.setTextColor(active ? 0xffc5bfff : 0xffe6e3ed);
        }
        if (!android) workspace.show(tab);
        scheduleRefresh(0);
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        if (!"android".equals(tab)) workspace.refresh();
        if (!connection.running() && !starting && !importing) idleState();
        scheduleRefresh(0);
    }

    @Override protected void onPause() {
        visible = false;
        touchedFrame = null;
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("emulator_tab", tab);
        workspace.saveState(state);
    }

    @Override protected void onDestroy() {
        if (picker != null) picker.cancel();
        workspace.destroy();
        commands.shutdownNow();
        media.shutdown();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if ("android".equals(tab)) super.onBackPressed();
        else if (!workspace.back()) showTab("android");
    }

    private void scheduleRefresh(long delay) {
        ui.removeCallbacks(refresh);
        if (visible && "android".equals(tab)) ui.postDelayed(refresh, delay);
    }

    private void refreshScreen() {
        if (!visible || isDestroyed() || !"android".equals(tab) || framePending) return;
        if (!connection.running()) {
            frame = null;
            screen.setImageDrawable(null);
            if (!starting && !importing) idleState();
            return;
        }
        EmulatorConnection requested = connection;
        framePending = true;
        commands.execute(() -> {
            EmulatorConnection.Frame next = null;
            String error = null;
            try {
                next = requested.screen();
            } catch (Exception failure) {
                error = message(failure);
            }
            EmulatorConnection.Frame fetched = next;
            String failure = error;
            ui.post(() -> {
                framePending = false;
                if (isDestroyed()) return;
                if (requested == connection) {
                    if (fetched != null) {
                        frame = fetched;
                        screen.setImageBitmap(fetched.bitmap);
                        connect.setVisibility(View.GONE);
                        if (!sendingMedia && !errorDisplayed) status.setText(EmulatorConnection.agentAllowed()
                                ? "Android sur ce téléphone · accès Local 3 actif" : "Android sur ce téléphone");
                    } else {
                        frame = null;
                        screen.setImageDrawable(null);
                        if (connection.running()) {
                            connect.setVisibility(View.GONE);
                            if (!sendingMedia) status.setText("Démarrage de l’écran Android…");
                        } else {
                            idleState();
                            status.setText(failure + " Consulte le journal dans le menu.");
                        }
                    }
                }
                scheduleRefresh(fetched == null ? 2500 : 750);
            });
        });
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? "Machine virtuelle Android indisponible." : error.getMessage();
    }

    private void idleState() {
        TextView button = (TextView) connect;
        boolean compatible = EmulatorConnection.compatible();
        boolean installed = EmulatorConnection.engineInstalled(this);
        boolean hasImage = GuestImage.exists(this);
        button.setText(hasImage ? "Démarrer Android" : "Installer Android");
        button.setVisibility(compatible && installed && !connection.running() ? View.VISIBLE : View.GONE);
        status.setText(connection.running() ? "Démarrage de l’écran Android…"
                : !compatible ? "Le moteur Android nécessite un téléphone ARM64 sous Android 8 ou supérieur."
                : !installed ? "Le moteur Android local manque dans cette version de CHK."
                : hasImage ? "Android est arrêté." : "Importe l’image ARM64 pour créer ton Android dans CHK.");
    }

    private void prepareAndroid() {
        if (starting || importing || connection.running() || !EmulatorConnection.engineInstalled(this)) return;
        if (GuestImage.exists(this)) {
            starting = true;
            errorDisplayed = false;
            connect.setVisibility(View.GONE);
            status.setText("Démarrage d’Android sur le téléphone…");
            ENGINE.execute(() -> {
                String failure = null;
                try { connection.start(getApplicationContext()); }
                catch (Exception error) { failure = message(error); }
                String result = failure;
                ui.post(() -> {
                    if (isDestroyed()) return;
                    starting = false;
                    if (result != null) {
                        idleState();
                        showMessage(result);
                    } else scheduleRefresh(0);
                });
            });
            return;
        }
        new AgentDialog.Builder(this).setTitle("Installer Android dans CHK")
                .setMessage("Télécharge l’image LineageOS ARM64 (environ 1,1 Go), puis choisis le ZIP téléchargé. Le démarrage et l’affichage se font sur le téléphone.")
                .setPositiveButton("Télécharger l’image", (dialog, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GuestImage.URL))); }
                    catch (android.content.ActivityNotFoundException error) { showMessage("Ouvre le lien LineageOS depuis le navigateur classique."); }
                })
                .setNeutralButton("Choisir le ZIP", (dialog, which) -> startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_SYSTEM_IMAGE))
                .setNegativeButton("Annuler", null).show();
    }

    private void importImage(Uri uri) {
        if (importing || GuestImage.exists(this)) return;
        importing = true;
        connect.setVisibility(View.GONE);
        status.setText("Vérification et installation de l’image Android…");
        ENGINE.execute(() -> {
            String failure = null;
            try { GuestImage.install(getApplicationContext(), uri); }
            catch (Exception error) { failure = message(error); }
            String result = failure;
            ui.post(() -> {
                if (isDestroyed()) return;
                importing = false;
                idleState();
                if (result != null) showMessage(result);
                else prepareAndroid();
            });
        });
    }

    private void menu() {
        boolean agent = EmulatorConnection.agentAllowed();
        String[] options = {"Actualiser l’écran", "Démarrer Android", "Arrêter Android",
                agent ? "Arrêter l’accès Local 3" : "Autoriser Local 3 pendant 20 min", "Journal de démarrage", "Réglages"};
        new AgentDialog.Builder(this).setTitle("Android sur le téléphone").setItems(options, (dialog, choice) -> {
            switch (choice) {
                case 0: errorDisplayed = false; scheduleRefresh(0); break;
                case 1: prepareAndroid(); break;
                case 2: stopAndroid(); break;
                case 3: agentAccess(); break;
                case 4: new AgentDialog.Builder(this).setTitle("Journal de démarrage").setMessage(connection.log())
                        .setPositiveButton("Fermer", null).show(); break;
                case 5: startActivity(new Intent(this, SettingsActivity.class)); break;
                default: break;
            }
        }).show();
    }

    private void stopAndroid() {
        if (!connection.running()) return;
        ENGINE.execute(() -> {
            try {
                connection.stop();
                ui.post(() -> { if (!isDestroyed()) showMessage("Arrêt demandé. Attends l’extinction d’Android."); });
            } catch (Exception error) {
                ui.post(() -> { if (!isDestroyed()) showMessage(message(error)); });
            }
        });
    }

    private void agentAccess() {
        if (EmulatorConnection.agentAllowed()) {
            EmulatorConnection.stopAgent();
            scheduleRefresh(0);
        } else if (frame == null) {
            showMessage("Démarre d’abord Android dans CHK.");
        } else {
            new AgentDialog.Builder(this).setTitle("Autoriser Local 3 ?")
                    .setMessage("Pendant 20 minutes, Local 3 pourra voir et piloter cet émulateur et y transférer des médias CHK. Tu peux arrêter cet accès ici.")
                    .setNegativeButton("Annuler", null).setPositiveButton("Autoriser", (dialog, which) -> {
                        EmulatorConnection.allowAgent();
                        errorDisplayed = false;
                        scheduleRefresh(0);
                    }).show();
        }
    }

    private void showMessage(String text) {
        errorDisplayed = true;
        status.setText(text);
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show();
    }

    private boolean touch(MotionEvent event) {
        if (frame == null || connection == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                RectF bounds = new RectF(0, 0, frame.bitmap.getWidth(), frame.bitmap.getHeight());
                screen.getImageMatrix().mapRect(bounds);
                if (!bounds.contains(event.getX(), event.getY())) return false;
                touchedFrame = frame;
                touchedBounds = bounds;
                startX = pixel(event.getX(), bounds.left, bounds.width(), frame.bitmap.getWidth());
                startY = pixel(event.getY(), bounds.top, bounds.height(), frame.bitmap.getHeight());
                return true;
            case MotionEvent.ACTION_UP:
                if (touchedFrame == null) return false;
                int width = touchedFrame.bitmap.getWidth(), height = touchedFrame.bitmap.getHeight();
                int endX = pixel(event.getX(), touchedBounds.left, touchedBounds.width(), width);
                int endY = pixel(event.getY(), touchedBounds.top, touchedBounds.height(), height);
                double movement = Math.hypot(endX - startX, endY - startY);
                boolean swipe = movement > Math.max(width, height) * 0.018;
                try {
                    JSONObject args = new JSONObject().put("operation", swipe ? "swipe" : "tap")
                            .put("frame_id", touchedFrame.id).put("x", startX).put("y", startY);
                    if (swipe) args.put("end_x", endX).put("end_y", endY);
                    else screen.performClick();
                    input(args);
                } catch (org.json.JSONException error) {
                    showMessage(message(error));
                } finally {
                    touchedFrame = null;
                }
                return true;
            case MotionEvent.ACTION_CANCEL: touchedFrame = null; return true;
            default: return touchedFrame != null;
        }
    }

    private static int pixel(float position, float offset, float length, int max) {
        return Math.max(0, Math.min(max - 1, (int) ((position - offset) * max / length)));
    }

    private void key(String key) {
        try {
            input(new JSONObject().put("operation", "key").put("key", key));
        } catch (org.json.JSONException error) {
            showMessage(message(error));
        }
    }

    private void keyboard() {
        EditText text = new EditText(this);
        text.setHint("Texte à saisir");
        text.setSingleLine(true);
        new AgentDialog.Builder(this).setTitle("Clavier de l’émulateur")
                .setMessage("Sélectionne d’abord un champ dans Android. Pour les accents et emojis, utilise le clavier de l’écran Android.")
                .setView(text).setNegativeButton("Annuler", null).setNeutralButton("Entrée", (dialog, which) -> key("enter"))
                .setPositiveButton("Saisir", (dialog, which) -> {
                    if (text.length() == 0) return;
                    try {
                        input(new JSONObject().put("operation", "text").put("text", text.getText().toString()));
                    } catch (org.json.JSONException error) {
                        showMessage(message(error));
                    }
                }).show();
    }

    private void input(JSONObject args) {
        if (!connection.running()) {
            showMessage("Démarre d’abord Android dans CHK.");
            return;
        }
        if (sendingInput || starting) return;
        sendingInput = true;
        EmulatorConnection requested = connection;
        commands.execute(() -> {
            String error = null;
            try {
                requested.input(args);
            } catch (Exception failure) {
                error = message(failure);
            }
            String failure = error;
            ui.post(() -> {
                sendingInput = false;
                if (isDestroyed() || requested != connection) return;
                if (failure != null) showMessage(failure);
                else errorDisplayed = false;
                scheduleRefresh(0);
            });
        });
    }

    private void chooseMedia() {
        if (sendingMedia) return;
        if (!connection.running()) {
            showMessage("Démarre d’abord Android dans CHK.");
            return;
        }
        try {
            picker = WorkspaceMediaChooser.show(this, new WebChromeClient.FileChooserParams() {
                @Override public int getMode() { return MODE_OPEN; }
                @Override public String[] getAcceptTypes() { return new String[] {"video/*", "image/*", "audio/*"}; }
                @Override public boolean isCaptureEnabled() { return false; }
                @Override public CharSequence getTitle() { return "Média pour l’émulateur"; }
                @Override public String getFilenameHint() { return null; }
                @Override public Intent createIntent() {
                    return new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(Intent.EXTRA_MIME_TYPES, getAcceptTypes());
                }
            }, uris -> {
                picker = null;
                if (uris != null && uris.length > 0) sendMedia(uris[0]);
            }, () -> {
                picker = null;
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"video/*", "image/*", "audio/*"}), PICK_DEVICE_MEDIA);
            }, false);
        } catch (Exception error) {
            showMessage(message(error));
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (workspace.result(request, result, data)) return;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == PICK_DEVICE_MEDIA) sendMedia(data.getData());
        if (request == PICK_SYSTEM_IMAGE) importImage(data.getData());
    }

    private void sendMedia(Uri uri) {
        if (!connection.running() || sendingMedia) return;
        EmulatorConnection requested = connection;
        sendingMedia = true;
        status.setText("Envoi du média à l’émulateur…");
        media.execute(() -> {
            String result;
            try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
                 InputStream source = getContentResolver().openInputStream(uri)) {
                if (cursor == null || !cursor.moveToFirst() || source == null) throw new IOException("Média inaccessible.");
                String name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                if (name == null) throw new IOException("Nom du média indisponible.");
                long size = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE));
                if (!requested.sendMedia(source, name, size).optBoolean("verified")) throw new IOException("Transfert Android non vérifié.");
                result = "Copie vérifiée. Redémarre Android pour monter le support d’échange.";
            } catch (Exception error) {
                result = message(error);
            }
            String outcome = result;
            ui.post(() -> {
                sendingMedia = false;
                if (!isDestroyed()) showMessage(outcome);
            });
        });
    }
}
