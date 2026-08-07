package com.replymate.core.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

/** P-background-2: pins the owner-mandated diagnostics record — every failure must
 *  carry conversation, provider, model, notification id, stage, exact reason,
 *  action taken and suggested fix, stably serialized for the Diagnostics screen. */
public final class AssistantEventTest {

    private static AssistantEvent full() {
        AssistantEvent e = new AssistantEvent();
        e.ts = 1722448800000L;
        e.contactId = 42L;
        e.contactName = "Ada";
        e.provider = "Gemini";
        e.model = "gemini-3.5-flash-lite";
        e.alertTag = AssistantPlanner.notifTag(42);
        e.sbnKey = "…sbn-key-tail";
        e.stage = AssistantEvent.Stage.REMOTE_SEND;
        e.reason = "the original WhatsApp notification was dismissed";
        e.action = "fell back to clipboard copy (nothing was sent)";
        e.fix = "open WhatsApp and paste it";
        return e;
    }

    @Test public void jsonRoundTripKeepsEveryMandatedField() {
        AssistantEvent in = full();
        String json = in.toJson();
        AssistantEvent out = AssistantEvent.fromJson(json);
        assertEquals(in.ts, out.ts);
        assertEquals(in.contactId, out.contactId);
        assertEquals(in.contactName, out.contactName);
        assertEquals(in.provider, out.provider);
        assertEquals(in.model, out.model);
        assertEquals(in.alertTag, out.alertTag);
        assertEquals(in.sbnKey, out.sbnKey);
        assertEquals(in.stage, out.stage);
        assertEquals(in.reason, out.reason);
        assertEquals(in.action, out.action);
        assertEquals(in.fix, out.fix);
    }

    @Test public void allPipelineStagesHaveStableWireNames() {
        assertEquals("schedule", AssistantEvent.Stage.SCHEDULE.wire);
        assertEquals("gates", AssistantEvent.Stage.GATES.wire);
        assertEquals("generate", AssistantEvent.Stage.GENERATE.wire);
        assertEquals("notify", AssistantEvent.Stage.NOTIFY.wire);
        assertEquals("approve_resolve", AssistantEvent.Stage.APPROVE_RESOLVE.wire);
        assertEquals("remote_send", AssistantEvent.Stage.REMOTE_SEND.wire);
        assertEquals("regen", AssistantEvent.Stage.REGEN.wire);
        assertEquals("copy_fallback", AssistantEvent.Stage.COPY_FALLBACK.wire);
        assertEquals(AssistantEvent.Stage.REMOTE_SEND,
            AssistantEvent.Stage.fromWire("remote_send"));
        assertEquals(AssistantEvent.Stage.GENERATE, AssistantEvent.Stage.fromWire("bogus"));
    }

    @Test public void specialCharactersSurviveTheRoundTrip() {
        AssistantEvent e = full();
        e.reason = "provider said: {\"error\":\"AUTH\"} — 'key' <bad> \\path\nnewline";
        AssistantEvent out = AssistantEvent.fromJson(e.toJson());
        assertEquals(e.reason, out.reason);
    }

    @Test public void lineRendersWhoProviderStageAndReason() {
        String l = full().line();
        assertTrue(l.contains("remote_send"));
        assertTrue(l.contains("Ada"));
        assertTrue(l.contains("Gemini/gemini-3.5-flash-lite"));
        assertTrue(l.contains("dismissed"));
        assertTrue(l.contains("fell back"));
        assertTrue(l.contains("fix:"));
    }

    @Test public void corruptJsonNeverThrows() {
        AssistantEvent e = AssistantEvent.fromJson("{not json");
        assertEquals("(unreadable record)", e.reason);
    }
}
