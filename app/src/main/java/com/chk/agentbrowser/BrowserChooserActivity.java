package com.chk.agentbrowser;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public final class BrowserChooserActivity extends Activity {
    static final String RETURN_TO_CLASSIC = "return_to_classic";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_browser_chooser);
        findViewById(R.id.open_classic).setOnClickListener(view -> {
            if (getIntent().getBooleanExtra(RETURN_TO_CLASSIC, false)) finish();
            else startActivity(new Intent(this, MainActivity.class));
        });
        findViewById(R.id.open_emulator).setOnClickListener(view ->
                startActivity(new Intent(this, AndroidEmulatorActivity.class)));
        findViewById(R.id.browser_chooser_settings).setOnClickListener(view ->
                startActivity(new Intent(this, SettingsActivity.class)));
        WorkspaceHealth.ensure(this);
        MainActivity.checkForUpdate(this);
    }
}
