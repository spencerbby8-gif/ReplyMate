package com.replymate.core.usecase;

import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.ProviderDef;
import com.replymate.core.model.ProviderType;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P21-production §7 — owner defect, Nov 4 device evidence: the configured model
 *  failed at the endpoint (502 wrapping the backend's "Requested entity was not
 *  found"), generation surfaced a masked generic error and NO fallback ever ran.
 *  These pins cover the whole contract: fallback ATTEMPTED in configured order,
 *  bounded (at most 2 hops beyond the primary), every hop recorded in the audit
 *  trail, the SAVED draft/usage credited to the model that actually answered —
 *  and when nothing else is configured, the real error stands unedited (no
 *  simulated recovery, ever). */
public class ProviderFallbackTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.StyleStoreFake styles;
    private Fakes.DraftStoreFake drafts;
    private Fakes.UsageStoreFake usage;
    private Fakes.KvStoreFake kv;
    private ProfileService profiles;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        styles = new Fakes.StyleStoreFake();
        drafts = new Fakes.DraftStoreFake();
        usage = new Fakes.UsageStoreFake();
        kv = new Fakes.KvStoreFake();
        profiles = new ProfileService(kv);

        Contact a = Fakes.contact(1, "Amara");
        a.relationshipType = "close friend";
        contacts.put(a);
        messages.add(Fakes.msg(1, Direction.OUTGOING, "did you land safe?"));
        messages.add(Fakes.msg(1, Direction.INCOMING, "yes o! just dey settle"));
    }

    private DraftService service(Fakes.GatewayFake gateway) {
        return new DraftService(contacts, messages, styles, profiles,
            drafts, usage, gateway, Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(new Fakes.StyleSettingStoreFake(),
                Fakes.learningService(new Fakes.LearningStoreFake(), new Fakes.KvStoreFake())),
            Fakes.learningService(new Fakes.LearningStoreFake(), new Fakes.KvStoreFake()),
            new com.replymate.core.memory.MemoryService(
                new Fakes.MemoryStoreFake(), messages, kv, Fakes.FIXED_CLOCK));
    }

    private static ProviderDef def(long id, ProviderType type, String model) {
        ProviderDef d = new ProviderDef();
        d.id = id;
        d.type = type;
        d.label = type.label;
        d.modelName = model;
        d.keyRef = "provider." + type.wire + ".key";
        return d;
    }

    @Test public void primaryFailureWithNoOtherProviderConfiguredLeavesTheRealErrorStanding() {
        Fakes.FakeProvider primary = Fakes.FakeProvider.failing("HTTP 502 backend not-found");
        Fakes.GatewayFake gw = new Fakes.GatewayFake(primary);

        Result<DraftOutcome> r = service(gw).generateForContact(1);

        assertFalse(r.ok);
        assertTrue("the error is the provider's own, unedited",
            r.error.contains("HTTP 502 backend not-found"));
        assertFalse("no fallback was possible — the error must not claim otherwise",
            r.error.contains("fallback"));
        assertEquals(0, drafts.saved.size());
        assertEquals(0, usage.events.size());
        assertEquals(1, primary.calls);
    }

    @Test public void fallbackAnswersAndTheSavedDraftCreditsTheFallbackModelNameably() {
        Fakes.FakeProvider primary = Fakes.FakeProvider.failing("HTTP 502 backend not-found");
        Fakes.FakeProvider fb = Fakes.FakeProvider.returning("safe landing, love it!");
        Fakes.GatewayFake gw = new Fakes.GatewayFake(primary);
        ProviderDef deepseek = def(11, ProviderType.DEEPSEEK, "deepseek-chat");
        gw.fallbacks.add(deepseek);
        gw.byId.put(11L, fb);

        Result<DraftOutcome> r = service(gw).generateForContact(1);

        assertTrue("fallback recovered the draft honestly", r.ok);
        assertEquals(1, drafts.saved.size());
        assertEquals("safe landing, love it!", drafts.saved.get(0).replyText);
        assertEquals("the SAVED draft must name the model that actually answered —"
                + " never the failed primary",
            "deepseek-chat", drafts.saved.get(0).model);
        assertEquals(1, usage.events.size());
        assertEquals("deepseek-chat", usage.events.get(0).model);
        assertEquals(1, primary.calls);
        assertEquals(1, fb.calls);

        String snap = drafts.saved.get(0).promptSnapshotJson;
        assertTrue("the audit names the fallback provider and model",
            snap.contains("DeepSeek / deepseek-chat"));
        assertTrue("the audit records WHY the primary was abandoned",
            snap.contains("primary failed"));
    }

    @Test public void fallbackStopsAtTheFirstSuccessAndSkipsUnbuildableRows() {
        Fakes.FakeProvider primary = Fakes.FakeProvider.failing("boom");
        Fakes.FakeProvider fb1 = Fakes.FakeProvider.failing("also boom");
        Fakes.FakeProvider fb2 = Fakes.FakeProvider.returning("recovered by hop two");
        Fakes.FakeProvider fb3 = Fakes.FakeProvider.returning("should never run");
        Fakes.GatewayFake gw = new Fakes.GatewayFake(primary);
        gw.fallbacks.add(def(21, ProviderType.GROK, "grok-3"));
        gw.fallbacks.add(def(22, ProviderType.KIMI, "kimi-latest"));
        gw.fallbacks.add(def(23, ProviderType.MISTRAL, "mistral-large"));
        gw.byId.put(21L, fb1);
        gw.byId.put(22L, fb2);
        gw.byId.put(23L, fb3);

        Result<DraftOutcome> r = service(gw).generateForContact(1);

        assertTrue(r.ok);
        assertEquals("chain walked in configured order, first success wins",
            "kimi-latest", drafts.saved.get(0).model);
        assertEquals(1, fb1.calls);
        assertEquals(1, fb2.calls);
        assertEquals("never burn calls past the first success", 0, fb3.calls);
    }

    @Test public void everyHopBeyondTwoIsNeverAttempted() {
        Fakes.FakeProvider primary = Fakes.FakeProvider.failing("boom");
        Fakes.FakeProvider fb1 = Fakes.FakeProvider.failing("one");
        Fakes.FakeProvider fb2 = Fakes.FakeProvider.failing("two");
        Fakes.FakeProvider fb3 = Fakes.FakeProvider.failing("three");
        Fakes.GatewayFake gw = new Fakes.GatewayFake(primary);
        gw.fallbacks.add(def(31, ProviderType.GROK, "m1"));
        gw.fallbacks.add(def(32, ProviderType.KIMI, "m2"));
        gw.fallbacks.add(def(33, ProviderType.MISTRAL, "m3"));
        gw.byId.put(31L, fb1);
        gw.byId.put(32L, fb2);
        gw.byId.put(33L, fb3);

        Result<DraftOutcome> r = service(gw).generateForContact(1);

        assertFalse(r.ok);
        assertTrue(r.error.contains("fallback tried 2 other provider(s) — all failed"));
        assertEquals(1, fb1.calls);
        assertEquals(1, fb2.calls);
        assertEquals("bounded: the third configured fallback is never called", 0, fb3.calls);
        assertEquals(0, drafts.saved.size());
        assertEquals(0, usage.events.size());
    }

    @Test public void allFallbacksFailingSurfacesTheFullChainInTheError() {
        Fakes.FakeProvider primary = Fakes.FakeProvider.failing("HTTP 502 backend not-found");
        Fakes.FakeProvider fb1 = Fakes.FakeProvider.failing("HTTP 401 bad key");
        Fakes.GatewayFake gw = new Fakes.GatewayFake(primary);
        gw.fallbacks.add(def(41, ProviderType.GROK, "grok-3"));
        gw.byId.put(41L, fb1);

        Result<DraftOutcome> r = service(gw).generateForContact(1);

        assertFalse(r.ok);
        assertTrue("the owner's original error survives",
            r.error.contains("HTTP 502 backend not-found"));
        assertTrue("the fallback attempt and its own error are on record",
            r.error.contains("fallback tried 1 other provider(s)")
                || r.error.contains("fallback 1"));
    }

    @Test public void ownerOffSwitchMeansNoProviderBeyondThePrimaryIsEverContacted() {
        // P21-review §7-audit consent: the Settings toggle is the contract — OFF
        // must mean the backup provider is never even called, and the failure
        // surfaces the ORIGINAL error unedited (and nothing is persisted).
        Fakes.FakeProvider primary = Fakes.FakeProvider.failing("HTTP 502 backend not-found");
        Fakes.FakeProvider fb = Fakes.FakeProvider.returning("must never be sent for");
        Fakes.GatewayFake gw = new Fakes.GatewayFake(primary);
        gw.fallbacks.add(def(61, ProviderType.DEEPSEEK, "deepseek-chat"));
        gw.byId.put(61L, fb);

        DraftService svc = service(gw);
        Fakes.KvStoreFake liveKv = new Fakes.KvStoreFake();
        liveKv.put(com.replymate.core.privacy.FallbackPolicy.KV_ENABLED, "0");
        svc.setLiveKv(liveKv);
        Result<DraftOutcome> r = svc.generateForContact(1);

        assertFalse(r.ok);
        assertEquals("the backup was: never", 0, fb.calls);
        assertTrue(r.error.contains("HTTP 502 backend not-found"));
        assertFalse(r.error.contains("fallback"));
        assertEquals(0, drafts.saved.size());
        assertEquals(0, usage.events.size());
    }

    @Test public void ownerOnSwitchKeepsTheChainAlive() {
        // control for the consent gate: default/"1" state leaves P21 §7 behavior
        // exactly as designed.
        Fakes.FakeProvider primary = Fakes.FakeProvider.failing("boom");
        Fakes.FakeProvider fb = Fakes.FakeProvider.returning("backup answer");
        Fakes.GatewayFake gw = new Fakes.GatewayFake(primary);
        gw.fallbacks.add(def(62, ProviderType.KIMI, "kimi-latest"));
        gw.byId.put(62L, fb);

        DraftService svc = service(gw);
        Fakes.KvStoreFake liveKv = new Fakes.KvStoreFake();
        liveKv.put(com.replymate.core.privacy.FallbackPolicy.KV_ENABLED, "1");
        svc.setLiveKv(liveKv);
        Result<DraftOutcome> r = svc.generateForContact(1);

        assertTrue(r.ok);
        assertEquals(1, fb.calls);
        assertEquals("kimi-latest", drafts.saved.get(0).model);
    }

    @Test public void primarySuccessNeverTouchesFallbacksAndCreditsThePrimary() {
        Fakes.FakeProvider primary = Fakes.FakeProvider.returning("straight from the top");
        Fakes.FakeProvider fb = Fakes.FakeProvider.returning("never used");
        Fakes.GatewayFake gw = new Fakes.GatewayFake(primary);
        gw.fallbacks.add(def(51, ProviderType.DEEPSEEK, "deepseek-chat"));
        gw.byId.put(51L, fb);

        Result<DraftOutcome> r = service(gw).generateForContact(1);

        assertTrue(r.ok);
        assertEquals("test-model", drafts.saved.get(0).model);
        assertEquals(0, fb.calls);
        assertFalse(drafts.saved.get(0).promptSnapshotJson.contains("fallback provider"));
    }
}
