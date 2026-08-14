package com.replymate.core.memory;

import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Direction;
import com.replymate.core.usecase.DraftOutcome;
import com.replymate.core.usecase.DraftService;
import com.replymate.core.usecase.ProfileService;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-14, topic 2: RESTART PERSISTENCE. Months/years of memory must
 *  be DURABLE: kill every service instance (a process death), rebuild the whole
 *  stack over the same persisted stores, and the memory the model sees must be
 *  IDENTICAL — the rolling summary row is reused (never re-versioned), recalled
 *  facts and older-message retrieval come back byte-for-byte. The proof is the
 *  exact ChatRequest the provider receives before vs. after the restart. */
public final class MemoryRestartTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.DraftStoreFake drafts;
    private Fakes.UsageStoreFake usage;
    private Fakes.KvStoreFake kv;
    private Fakes.MemoryStoreFake memoryStore;
    private Fakes.StyleSettingStoreFake settings;
    private Fakes.LearningStoreFake learningStore;
    private LearningService learning;
    private ProfileService profiles;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        drafts = new Fakes.DraftStoreFake();
        usage = new Fakes.UsageStoreFake();
        kv = new Fakes.KvStoreFake();
        memoryStore = new Fakes.MemoryStoreFake();
        settings = new Fakes.StyleSettingStoreFake();
        learningStore = new Fakes.LearningStoreFake();
        learning = Fakes.learningService(learningStore, new Fakes.KvStoreFake());
        profiles = new ProfileService(kv);
    }

    /** A brand-new service stack over the SAME persisted stores — the simulated
     *  restart: every in-memory object dies, only the "SQLite" fakes survive. */
    private DraftService freshServiceStack(Fakes.GatewayFake gateway) {
        return new DraftService(contacts, messages, new Fakes.StyleStoreFake(), profiles,
            drafts, usage, gateway, Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(settings, learning), learning,
            new MemoryService(memoryStore, messages, kv, Fakes.FIXED_CLOCK));
    }

    @Test public void memorySurvivesARestartByteForByte() {
        contacts.put(Fakes.contact(1, "Ada"));
        // 44 messages — comfortably older than the 30-message hot window, so the
        // rolling summary and the older-message retrieval layers both have real
        // material to lose if persistence were broken.
        for (int i = 1; i <= 44; i++) {
            messages.add(Fakes.msg(1,
                i % 2 == 0 ? Direction.INCOMING : Direction.OUTGOING,
                "update " + i + " about the landlord and the rent plan for the shop"));
        }
        memorySetUpFact();
        messages.add(Fakes.msg(1, Direction.INCOMING,
            "did the landlord call about the rent plan?"));

        Fakes.FakeProvider before = Fakes.FakeProvider.returning("first reply");
        Result<DraftOutcome> r1 =
            freshServiceStack(new Fakes.GatewayFake(before)).generateForContact(1);
        assertTrue(String.valueOf(r1.ok ? "" : r1.error), r1.ok);
        String sysBefore = before.lastRequest.system;
        assertTrue("control: the rolling summary IS part of the prompt",
            sysBefore.contains("Earlier in this chat"));
        assertTrue("control: the pinned fact IS part of the prompt",
            sysBefore.contains("ADA-SHOP-FACT: mum's shop is in Wuse 2"));
        assertTrue("control: older-message retrieval IS part of the prompt",
            sysBefore.contains("latest on this"));
        assertEquals("control: exactly one summary version persisted",
            1, memoryStore.summariesByContact.get(1L).size());

        // ---- simulated restart: drop every service instance, keep the stores ----
        Fakes.FakeProvider after = Fakes.FakeProvider.returning("second reply");
        Result<DraftOutcome> r2 =
            freshServiceStack(new Fakes.GatewayFake(after)).generateForContact(1);
        assertTrue(String.valueOf(r2.ok ? "" : r2.error), r2.ok);

        assertEquals("identical older history reuses the persisted summary row — "
                + "no new version is created after a restart",
            1, memoryStore.summariesByContact.get(1L).size());
        assertEquals("the prompt after a restart is byte-identical: summary, pinned "
                + "facts and older-message retrieval all survived",
            sysBefore, after.lastRequest.system);
    }

    @Test public void memoryOnlyAdvancesWhenHistoryActuallyChanges() {
        contacts.put(Fakes.contact(1, "Ada"));
        for (int i = 1; i <= 40; i++) {
            messages.add(Fakes.msg(1,
                i % 2 == 0 ? Direction.INCOMING : Direction.OUTGOING,
                "checkpoint " + i + " on the travel plan"));
        }
        messages.add(Fakes.msg(1, Direction.INCOMING, "are we still travelling Friday?"));

        Fakes.FakeProvider p1 = Fakes.FakeProvider.returning("r1");
        assertTrue(freshServiceStack(new Fakes.GatewayFake(p1)).generateForContact(1).ok);
        int versionsAfterFirst = memoryStore.summariesByContact.get(1L).size();

        // restart — nothing changed: same summary row, no churn
        Fakes.FakeProvider p2 = Fakes.FakeProvider.returning("r2");
        assertTrue(freshServiceStack(new Fakes.GatewayFake(p2)).generateForContact(1).ok);
        assertEquals(versionsAfterFirst, memoryStore.summariesByContact.get(1L).size());

        // restart — then real new history arrives: the summary MUST advance (v+1)
        for (int i = 41; i <= 46; i++) {
            messages.add(Fakes.msg(1,
                i % 2 == 0 ? Direction.INCOMING : Direction.OUTGOING,
                "brand new development " + i + " on the travel plan"));
        }
        Fakes.FakeProvider p3 = Fakes.FakeProvider.returning("r3");
        assertTrue(freshServiceStack(new Fakes.GatewayFake(p3)).generateForContact(1).ok);
        assertEquals("new older-than-window history advances the version exactly once",
            versionsAfterFirst + 1, memoryStore.summariesByContact.get(1L).size());
    }

    private void memorySetUpFact() {
        MemoryService mem =
            new MemoryService(memoryStore, messages, kv, Fakes.FIXED_CLOCK);
        mem.replacePinnedFacts(1, "ADA-SHOP-FACT: mum's shop is in Wuse 2");
    }
}
