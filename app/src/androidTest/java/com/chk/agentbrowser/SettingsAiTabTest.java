package com.chk.agentbrowser;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Switch;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Two stable settings tabs: UI state must not change Local 3 or user data. */
@RunWith(AndroidJUnit4.class)
public final class SettingsAiTabTest {
    @Test public void aiControlsBelongToSeparateTabAndMcpStateIsPreserved() {
        Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
        Context app=instrument.getTargetContext();
        app.getSharedPreferences("update_monitor",Context.MODE_PRIVATE)
            .edit().putBoolean("enabled",false).commit();
        boolean local3Before=Local3Client.get(app).enabled();
        boolean autonomousBefore=AgentClient.get(app).isAutonomous();
        boolean aiBefore=LocalAiController.get(app).enabled();
        Intent intent=new Intent(app,SettingsActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        SettingsActivity activity=(SettingsActivity)instrument.startActivitySync(intent);
        try {
            instrument.runOnMainSync(()->{
                View general=activity.findViewById(R.id.settings_general_scroll);
                View ai=activity.findViewById(R.id.settings_ai_scroll);
                Switch toggle=activity.findViewById(R.id.local_ai_toggle);
                assertNotNull(toggle);
                assertEquals(View.VISIBLE,general.getVisibility());
                assertEquals(View.GONE,ai.getVisibility());
                activity.findViewById(R.id.settings_tab_ai).performClick();
                assertEquals(View.GONE,general.getVisibility());
                assertEquals(View.VISIBLE,ai.getVisibility());
                assertEquals(aiBefore,toggle.isChecked());
                toggle.setChecked(!aiBefore);
                assertEquals(!aiBefore,LocalAiController.get(app).enabled());
                activity.findViewById(R.id.settings_tab_general).performClick();
                assertEquals(View.VISIBLE,general.getVisibility());
                assertEquals(View.GONE,ai.getVisibility());
                assertEquals(local3Before,Local3Client.get(app).enabled());
                assertEquals(autonomousBefore,AgentClient.get(app).isAutonomous());
                toggle.setChecked(aiBefore);
                activity.finish();
            });
        } finally {
            LocalAiController.get(app).setEnabled(aiBefore);
            instrument.runOnMainSync(()->{if(!activity.isFinishing())activity.finish();});
        }
    }
}
