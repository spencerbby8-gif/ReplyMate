package com.replymate.core.usecase;

import com.replymate.core.learning.LearningService;
import com.replymate.core.memory.MemoryService;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-4 prompt-reachability pins (directives 6 & 9): the device clock
 *  line actually reaches the PROVIDER request and the audit snapshot credits it;
 *  the Settings toggle removes it cleanly with an honest breadcrumb; a slangy
 *  incoming message pulls the dated glossary clause along. */
public final class LiveContextPromptTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.KvStoreFake liveKv;
    private DraftService service;
    private Fakes.FakeProvider provider;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        Fakes.StyleStoreFake styles = new Fakes.StyleStoreFake();
        Fakes.DraftStoreFake drafts = new Fakes.DraftStoreFake();
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ProfileService profiles = new ProfileService(kv);
        Fakes.StyleSettingStoreFake settings = new Fakes.StyleSettingStoreFake();
        Fakes.LearningStoreFake signals = new Fakes.LearningStoreFake();
        LearningService learning = Fakes.learningService(signals, new Fakes.KvStoreFake());
        provider = Fakes.FakeProvider.returning("yup, Saturday works");
        service = new DraftService(contacts, messages, styles, profiles, drafts,
            new Fakes.UsageStoreFake(), new Fakes.GatewayFake(provider),
            Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(settings, learning), learning,
            new MemoryService(new Fakes.MemoryStoreFake(), messages, kv, Fakes.FIXED_CLOCK));
        liveKv = new Fakes.KvStoreFake();
        service.setLiveKv(liveKv);
        contacts.put(Fakes.contact(1, "Amara"));
    }

    private String system() { return provider.lastRequest.system; }

    private String snapshot(Result<DraftOutcome> r) {
        Draft d = r.value.drafts.get(0);
        return d.promptSnapshotJson;
    }

    @Test public void deviceClockReachesTheProviderRequestWithAuditCredit() {
        messages.add(Fakes.msg(1, Direction.INCOMING, "we still on for Saturday?"));
        Result<DraftOutcome> r = service.generateForContact(1L);
        assertTrue(r.ok);
        assertTrue("the real device moment must reach the provider prompt",
            system().contains("Now (device clock):"));
        assertTrue("audit credits the injection", snapshot(r).contains("live context: device clock"));
        assertFalse("no slang in the message ⇒ no glossary clause",
            system().contains("Word help"));
    }

    @Test public void slangyMessagePullsTheDatedGlossaryClauseAlong() {
        messages.add(Fakes.msg(1, Direction.INCOMING, "ur reply ate fr"));
        assertTrue(service.generateForContact(1L).ok);
        assertTrue(system().contains("Word help (curated 2026-08, not a live lookup): ate = did amazingly; fr = for real")
                || system().contains("ate = did amazingly"));
        assertTrue("audit keeps the honesty stamp",
            snapshot(service.generateForContact(1L)).contains("not live"));
    }

    @Test public void settingsToggleRemovesTheLineWithAnHonestBreadcrumb() {
        liveKv.put(com.replymate.core.live.LiveContext.KV_ENABLED, "0");
        messages.add(Fakes.msg(1, Direction.INCOMING, "that japa plan still dey?"));
        Result<DraftOutcome> r = service.generateForContact(1L);
        assertTrue(r.ok);
        assertFalse("off means the provider never sees a clock line",
            system().contains("Now (device clock):"));
        assertFalse("off means no glossary either (even for 'japa')",
            system().contains("Word help"));
        assertTrue("the audit still says WHY nothing arrived",
            snapshot(r).contains("live context: switched off in Settings"));
    }

    @Test public void generationStillWorksWithNoKvWiredAtAll() {
        // app-layer-less legacy construction: the setter is optional (default ON).
        Fakes.ContactStoreFake c2 = new Fakes.ContactStoreFake();
        Fakes.MessageStoreFake m2 = new Fakes.MessageStoreFake();
        Fakes.FakeProvider p2 = Fakes.FakeProvider.returning("ok");
        DraftService bare = new DraftService(c2, m2, new Fakes.StyleStoreFake(),
            new ProfileService(new Fakes.KvStoreFake()),
            new Fakes.DraftStoreFake(), new Fakes.UsageStoreFake(),
            new Fakes.GatewayFake(p2), Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            null, null, null);
        c2.put(Fakes.contact(9, "Tobi"));
        m2.add(Fakes.msg(9, Direction.INCOMING, "hello there"));
        Result<DraftOutcome> r = bare.generateForContact(9L);
        assertTrue(r.ok);
        assertTrue(p2.lastRequest.system.contains("Now (device clock):"));
    }
}
