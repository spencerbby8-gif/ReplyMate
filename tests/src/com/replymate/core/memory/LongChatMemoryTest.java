package com.replymate.core.memory;

import com.replymate.core.ai.Turn;
import com.replymate.core.learning.LearningService;
import com.replymate.core.model.ContactSummary;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.Message;
import com.replymate.core.usecase.DraftOutcome;
import com.replymate.core.usecase.DraftService;
import com.replymate.core.usecase.ProfileService;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-3 (owner directive 2 & 9): long-chat memory must ACTUALLY reach
 *  generation —
 *    M1 recent-turn window: only the latest 30 turns travel as raw thread;
 *    M2 rolling summary: everything OLDER than the window is compressed into the
 *      deterministic running summary, so the beginning of a months-long chat is
 *      still known to the prompt without blowing the token budget;
 *    M3 pinned facts ride along;
 *    summaries advance as the window slides and never leak across contacts;
 *    and a brand-new contact is honestly cold (no memory, explicit cold-start line).
 *  Everything is asserted against the REAL ChatRequest handed to the provider fake. */
public final class LongChatMemoryTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.MemoryStoreFake memory;
    private Fakes.DraftStoreFake drafts;
    private MemoryService memoryService;
    private DraftService service;
    private Fakes.FakeProvider provider;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        memory = new Fakes.MemoryStoreFake();
        drafts = new Fakes.DraftStoreFake();
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        Fakes.StyleSettingStoreFake settings = new Fakes.StyleSettingStoreFake();
        Fakes.LearningStoreFake signals = new Fakes.LearningStoreFake();
        LearningService learning = Fakes.learningService(signals, new Fakes.KvStoreFake());
        memoryService = new MemoryService(memory, messages, kv, Fakes.FIXED_CLOCK);
        provider = Fakes.FakeProvider.returning("on my way now!");
        service = new DraftService(contacts, messages, new Fakes.StyleStoreFake(),
            new ProfileService(kv), drafts,
            new Fakes.UsageStoreFake(), new Fakes.GatewayFake(provider),
            Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(settings, learning), learning, memoryService);
        contacts.put(Fakes.contact(1, "Amara"));
    }

    private void seedMonthsOfChat(long contactId, int count) {
        for (int i = 1; i <= count; i++) {
            Direction dir = i % 2 == 0 ? Direction.OUTGOING : Direction.INCOMING;
            String body;
            // three anchored long-term facts from the chat's BEGINNING (they carry
            // the deterministic summarizer's keep-words). Everything else is TRUE
            // digit-less small talk so it scores zero and can't crowd the facts out
            // of the summary budget (digits would earn +2 fake salience).
            if (i == 3) {
                body = "don't forget february travel for the wedding";
                dir = Direction.INCOMING;
            } else if (i == 7) {
                body = "my interview is on monday";
                dir = Direction.INCOMING;
            } else if (i == 10) {
                body = "remember we planned to meet at the beach bar";
                dir = Direction.OUTGOING;
            } else if (i == 89) {
                body = "you still coming tonight?";   // the LATEST incoming (hot window)
                dir = Direction.INCOMING;
            } else {
                // era-marked small talk: the HOT window (i>60) uses a different
                // filler phrase than the OLD block, so raw-window leaks are testable
                body = dir == Direction.INCOMING
                    ? (i <= 60 ? "long ago ok" : "lol ok")
                    : (i <= 60 ? "way back yeah" : "yeah sure");
            }
            Message m = Fakes.msg(contactId, dir, body);
            m.id = i;
            m.sentAt = Fakes.NOW + i * 3_600_000L;     // hourly — months-scale spacing
            messages.add(m);
        }
    }

    /** Texts of ONLY the raw conversation turns the model sees (never the system). */
    private static String rawTurnsText(Fakes.FakeProvider p) {
        StringBuilder sb = new StringBuilder();
        for (Turn t : p.lastRequest.turns) sb.append(t.text).append('\n');
        if (p.lastRequest.task != null) sb.append(p.lastRequest.task.text);
        return sb.toString();
    }

    @Test public void beginningOfALongChatSurvivesViaTheRollingSummary() {
        // 90 messages = 60 older-than-window + 30 hot — the "months ago" part.
        seedMonthsOfChat(1, 90);
        Result<DraftOutcome> r = service.generateForContact(1L);
        assertTrue(r.ok);

        String system = provider.lastRequest.system;
        String turns = rawTurnsText(provider);
        // M2: the running summary of the FIRST 60 turns rides the system block…
        assertTrue("the rolling summary must travel with the prompt",
            system.contains("Earlier in this chat (your own running summary)"));
        // …and it must actually carry the chat's BEGINNING (the directive's point):
        assertTrue("the oldest planned-fact must survive into the prompt",
            system.contains("wedding"));
        assertTrue("the oldest interview fact must survive into the prompt",
            system.contains("interview"));
        assertTrue("the oldest owner plan must survive into the prompt",
            system.contains("beach bar"));
        // M2 honesty: those facts ride the summary, NOT the raw turns — the raw
        // window stays the bounded M1 layer (no unbounded token history).
        assertFalse("old-era small talk must stay out of the raw window",
            turns.contains("long ago ok"));
        assertFalse("old-era outgoing filler must stay out of the raw window",
            turns.contains("way back yeah"));
        assertTrue("hot-window filler is fine as a raw turn",
            turns.contains("lol ok"));
        assertFalse("the summarized fact must NOT be duplicated as a raw turn",
            turns.contains("wedding"));
        assertTrue("a recent raw turn stays in the hot window",
            turns.contains("you still coming tonight?"));
        // the answer must be to the LATEST incoming line, per the honesty gate
        assertTrue(provider.lastRequest.task.text.contains("you still coming tonight?"));
    }

    @Test public void summaryAdvancesAsTheWindowSlidesAndNeverLeaks() {
        seedMonthsOfChat(1, 40);
        assertTrue(service.generateForContact(1L).ok);
        List<ContactSummary> rows = memory.summariesByContact.get(1L);
        assertEquals(1, rows.size());
        long v1covers = rows.get(0).coversUntilTs;

        Message newer = Fakes.msg(1, Direction.INCOMING, "and one more thing — still on?");
        newer.id = 41;
        newer.sentAt = Fakes.NOW + 41L * 3_600_000;
        messages.add(newer);
        assertTrue(service.generateForContact(1L).ok);
        rows = memory.summariesByContact.get(1L);
        assertEquals("a new version — the summary advanced with the window",
            2, rows.size());
        assertTrue(rows.get(1).coversUntilTs > v1covers);

        // strict isolation: another contact's generation must not see Amara's memory.
        contacts.put(Fakes.contact(2, "Uche"));
        messages.add(Fakes.msg(2, Direction.INCOMING, "uche says hi"));
        assertTrue(service.generateForContact(2L).ok);
        String ucheSystem = provider.lastRequest.system;
        assertFalse(ucheSystem.contains("running summary"));
        assertFalse(ucheSystem.contains("lol ok"));
        assertFalse(ucheSystem.contains("wedding"));
    }

    @Test public void pinnedFactsAndMemoryFramingRideTheSameRequest() {
        seedMonthsOfChat(1, 35);
        memoryService.replacePinnedFacts(1, "she's allergic to peanuts");
        Result<DraftOutcome> r = service.generateForContact(1L);
        assertTrue(r.ok);
        String system = provider.lastRequest.system;
        assertTrue("pinned fact must travel", system.contains("allergic to peanuts"));
        assertTrue("memory framing protects against mention-of-notes",
            system.contains("your own memory"));
        Draft d = r.value.drafts.get(0);
        assertTrue("Prompt Audit credits the memory facts used",
            d.promptSnapshotJson.contains("memory facts applied"));
    }

    @Test public void brandNewContactIsHonestColdStart() {
        contacts.put(Fakes.contact(3, "New Person"));
        messages.add(Fakes.msg(3, Direction.INCOMING, "hey, is this Tobi?"));
        assertTrue(service.generateForContact(3L).ok);
        String system = provider.lastRequest.system;
        assertFalse("no memory claims for a cold contact",
            system.contains("running summary"));
        assertFalse(system.contains("What you remember about"));
        assertTrue("the explicit cold-start line must be there instead",
            system.toLowerCase(java.util.Locale.US).contains("new chat"));
        List<Draft> theirs = new ArrayList<Draft>(drafts.byContact(3L, 5));
        assertEquals(1, theirs.size());
        assertTrue("Prompt Audit says cold start for them",
            theirs.get(0).promptSnapshotJson.contains("cold start"));
    }
}
