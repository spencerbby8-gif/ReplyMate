package com.replymate.core.assistant;

import com.replymate.fakes.Fakes;
import org.junit.Test;
import static org.junit.Assert.*;

/** P21-production §1: the draft-banner alert cycle.
 *
 *  Owner-reported on OPPO A16K (1.7.2 vc919 + ANR fix): listener bound, DB
 *  healthy, parse/store/generate all green, one pipeline completed in 9 s —
 *  but completed drafts stopped appearing as heads-up banners. Root cause: the
 *  per-conversation "already alerted this cycle" flag was cleared ONLY by card
 *  interaction (approve / copy / dismiss / edit). An owner who replies inside
 *  the source app (never touching the ReplyMate card) left it armed forever,
 *  so every later draft posted as a SILENT refresh — the banner vanished while
 *  everything else worked.
 *
 *  Contract pinned here:
 *   - a fresh cycle pops (banner + sound);
 *   - the pop claims the cycle (burst updates + Regenerate stay silent);
 *   - a NEW incoming message re-arms the cycle (next draft pops again);
 *   - force (Regenerate) NEVER pops;
 *   - cycles are strictly per-contact;
 *   - a null kv (no storage) fails OPEN to alerting (never silently dark). */
public final class AlertCycleTest {

    @Test public void freshCyclePopsOnceThenStaysSilent() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        assertTrue("never-alerted conversation must pop",
            AssistantPlanner.isFreshCycle(kv, 42, false));
        AssistantPlanner.markAlerted(kv, 42);
        assertFalse("same cycle: burst updates refresh silently",
            AssistantPlanner.isFreshCycle(kv, 42, false));
        assertFalse("same cycle: still silent on later checks",
            AssistantPlanner.isFreshCycle(kv, 42, false));
    }

    @Test public void newIncomingMessageRearmsTheCycle() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        AssistantPlanner.markAlerted(kv, 42);
        assertFalse(AssistantPlanner.isFreshCycle(kv, 42, false));
        // the live-capture schedule path re-arms on every genuinely new ping
        // (dedupe filters replays BEFORE this; debounce keeps one pop per burst)
        AssistantPlanner.rearmAlert(kv, 42);
        assertTrue("a new burst must pop again — this was the missing behavior",
            AssistantPlanner.isFreshCycle(kv, 42, false));
    }

    @Test public void forceNeverPops() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        assertFalse("Regenerate on a fresh cycle still must not pop",
            AssistantPlanner.isFreshCycle(kv, 42, true));
        AssistantPlanner.rearmAlert(kv, 42);
        assertFalse("force stays silent even right after a re-arm",
            AssistantPlanner.isFreshCycle(kv, 42, true));
    }

    @Test public void cyclesArePerContact() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        AssistantPlanner.markAlerted(kv, 42);
        assertTrue(AssistantPlanner.isFreshCycle(kv, 43, false));
        assertFalse(AssistantPlanner.isFreshCycle(kv, 42, false));
        AssistantPlanner.rearmAlert(kv, 42);
        assertTrue(AssistantPlanner.isFreshCycle(kv, 42, false));
        assertTrue("re-arming 42 must never disturb 43's first cycle",
            AssistantPlanner.isFreshCycle(kv, 43, false));
    }

    @Test public void nullStorageFailsOpenToAlerting() {
        assertTrue(AssistantPlanner.isFreshCycle(null, 99, false));
        // mark/rearm on null storage must never throw
        AssistantPlanner.markAlerted(null, 99);
        AssistantPlanner.rearmAlert(null, 99);
    }

    @Test public void rearmIsWhatRecoveryMustNotDo() {
        // The recovery repost contract: an EXISTING draft re-posts SILENTLY.
        // That path never calls rearmAlert; pin that a still-armed flag keeps
        // recovery silent until a NEW message arrives.
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        AssistantPlanner.markAlerted(kv, 7);
        assertFalse("recovery repost before any new message: silent",
            AssistantPlanner.isFreshCycle(kv, 7, false));
    }
}
