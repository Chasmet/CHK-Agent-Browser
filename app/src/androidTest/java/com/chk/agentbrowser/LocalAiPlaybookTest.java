package com.chk.agentbrowser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** No network or model download required: fast deterministic tests. */
@RunWith(AndroidJUnit4.class)
public final class LocalAiPlaybookTest {
    @Test public void frenchIntentRoutesToTheRightPlaybook() {
        assertEquals("youtube",LocalAiPlaybook.category("","Je veux programmer une vidéo sur YouTube"));
        assertEquals("suno",LocalAiPlaybook.category("","Fais 3 musiques sur Suno"));
        assertEquals("studio",LocalAiPlaybook.category("","Montage avec sous-titres dans CHK Studio"));
    }
    @Test public void planDoesNotClaimActionsOrEvidence() throws Exception {
        JSONObject p=LocalAiPlaybook.plan("youtube","programme une vidéo");
        assertEquals("youtube",p.getString("category"));
        assertEquals(3,p.getJSONArray("steps").length());
        assertFalse(p.getBoolean("verified"));
        assertFalse(p.getBoolean("actions_executed"));
        assertTrue(p.getBoolean("requires_chatgpt_mcp_verification"));
        assertEquals("local_deterministic_rules",p.getString("source"));
        for(int i=0;i<3;i++) {
            JSONObject item=p.getJSONArray("steps").getJSONObject(i);
            assertFalse(item.getBoolean("verified"));
            assertFalse(item.getBoolean("executed"));
            assertFalse(item.getString("evidence_required").isEmpty());
        }
    }
    @Test public void qualityRejectsEchoAndContradiction() throws Exception {
        assertFalse(LocalAiPlaybook.quality("Comment publier une vidéo dans YouTube?",
            "Comment publier une vidéo dans YouTube?").getBoolean("passed"));
        assertFalse(LocalAiPlaybook.quality("Checklist YouTube",
            "Video is scheduled. Video is not scheduled.").getBoolean("passed"));
        assertTrue(LocalAiPlaybook.quality("2 plus 3?", "5").getBoolean("passed"));
    }
    @Test public void textualComparisonNeverCertifiesPublishing() throws Exception {
        JSONObject o=LocalAiPlaybook.verifyReport(
            new JSONObject().put("expected","programmée").put("observed","La vidéo est programmée"));
        assertTrue(o.getBoolean("text_match"));
        assertFalse(o.getBoolean("actual_action_confirmed"));
        JSONObject no=LocalAiPlaybook.verifyReport(
            new JSONObject().put("expected","programmée").put("observed","en attente"));
        assertFalse(no.getBoolean("text_match"));
        assertFalse(no.getBoolean("actual_action_confirmed"));
    }
}
