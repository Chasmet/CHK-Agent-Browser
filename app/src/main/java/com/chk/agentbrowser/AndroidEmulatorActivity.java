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
import android.text.InputType;
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
    private final Handler ui = new Handler(Looper.getMainLooper());
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
    private boolean pairing;
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
        connect.setOnClickListener(view -> pair());
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
        try {
            connection = EmulatorConnection.load(this);
        } catch (Exception ignored) { }
        showTab(state == null ? "android" : state.getString("emulator_tab", "android"));
        status.setText(connection == null ? "Associe le compagnon PC pour ouvrir l’émulateur." : "Connexion à l’émulateur…");
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
        if (visible && "android".equals(tab) && connection != null) ui.postDelayed(refresh, delay);
    }

    private void refreshScreen() {
        if (!visible || isDestroyed() || !"android".equals(tab) || connection == null || framePending) return;
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
                                ? "Émulateur connecté · accès Local 3 actif" : "Émulateur connecté");
                    } else {
                        frame = null;
                        screen.setImageDrawable(null);
                        connect.setVisibility(View.VISIBLE);
                        if (!sendingMedia) status.setText(failure);
                    }
                }
                scheduleRefresh(fetched == null ? 2500 : 750);
            });
        });
    }

    private static String message(Exception error) {
        if (error instanceof javax.net.ssl.SSLException) return "Certificat du compagnon Android refusé. Vérifie le code d’appairage.";
        if (error instanceof java.net.SocketException || error instanceof java.net.SocketTimeoutException) {
            return "Compagnon PC inaccessible. Vérifie le réseau et le démarrage de l’émulateur.";
        }
        return error.getMessage() == null ? "Connexion à l’émulateur impossible." : error.getMessage();
    }

    private void pair() {
        if (pairing || sendingMedia) return;
        EditText code = new EditText(this);
        code.setHint("Coller le code d’appairage");
        code.setSingleLine(true);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AgentDialog.Builder(this).setTitle("Associer l’émulateur Android")
                .setMessage("Démarre le compagnon CHK Android sur le PC et colle ici le code qu’il affiche.")
                .setView(code).setNegativeButton("Annuler", null)
                .setPositiveButton("Associer", (dialog, which) -> {
                    pairing = true;
                    status.setText("Vérification du compagnon…");
                    String value = code.getText().toString();
                    commands.execute(() -> {
                        try {
                            EmulatorConnection next = EmulatorConnection.pair(this, value);
                            ui.post(() -> {
                                if (isDestroyed()) return;
                                pairing = false;
                                connection = next;
                                errorDisplayed = false;
                                frame = null;
                                screen.setImageDrawable(null);
                                scheduleRefresh(0);
                            });
                        } catch (Exception error) {
                            ui.post(() -> {
                                if (isDestroyed()) return;
                                pairing = false;
                                showMessage(message(error));
                            });
                        }
                    });
                }).show();
    }

    private void menu() {
        boolean agent = EmulatorConnection.agentAllowed();
        String[] options = {"Actualiser l’écran", "Applications récentes", "Associer le compagnon PC",
                agent ? "Arrêter l’accès Local 3" : "Autoriser Local 3 pendant 20 min", "Oublier le compagnon PC", "Réglages"};
        new AgentDialog.Builder(this).setTitle("Émulateur Android").setItems(options, (dialog, choice) -> {
            switch (choice) {
                case 0: errorDisplayed = false; scheduleRefresh(0); break;
                case 1: key("recent"); break;
                case 2: pair(); break;
                case 3: agentAccess(); break;
                case 4: forget(); break;
                case 5: startActivity(new Intent(this, SettingsActivity.class)); break;
                default: break;
            }
        }).show();
    }

    private void agentAccess() {
        if (EmulatorConnection.agentAllowed()) {
            EmulatorConnection.stopAgent();
            scheduleRefresh(0);
        } else if (frame == null) {
            showMessage("Connecte d’abord l’émulateur Android.");
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

    private void forget() {
        if (pairing || sendingMedia) return;
        EmulatorConnection.forget(this);
        connection = null;
        frame = null;
        screen.setImageDrawable(null);
        connect.setVisibility(View.VISIBLE);
        status.setText("Associe le compagnon PC pour ouvrir l’émulateur.");
        scheduleRefresh(0);
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
        if (connection == null) {
            showMessage("Associe d’abord le compagnon PC Android.");
            return;
        }
        if (sendingInput || pairing) return;
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
        if (connection == null) {
            showMessage("Associe d’abord le compagnon PC Android.");
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
        if (request == PICK_DEVICE_MEDIA && result == RESULT_OK && data != null && data.getData() != null) sendMedia(data.getData());
    }

    private void sendMedia(Uri uri) {
        if (connection == null || sendingMedia) return;
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
                result = "Média reçu et vérifié dans Téléchargements / CHK.";
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
