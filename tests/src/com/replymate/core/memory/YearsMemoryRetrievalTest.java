package com.replymate.core.memory;

import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.usecase.DraftOutcome;
import com.replymate.core.usecase.DraftService;
import com.replymate.core.usecase.ProfileService;
import com.replymate.core.learning.LearningService;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-13: the owner's mandated years-long proof. A thread spanning
 *  years: an OLD fact, a LATER correction, then months of unrelated chatter
 *  (including salience-flooding digit noise that pushes the topic OUT of the
 *  rolling summary), then a new question that needs that old context.
 *
 *  Proven here, end-to-end through real generation:
 *    - the deep history is NEVER flooded into the prompt (unrelated older text
 *      stays out; ≤3 retrieved lines; ≤460-char block);
 *    - the CORRECTION is retrieved and labeled authoritative ("latest on
 *      this"); the old fact is labeled "(earlier)";
 *    - the summary alone does NOT carry the topic (retrieval is what saves it);
 *    - a topic-free control message retrieves NOTHING;
 *    - retrieval is strictly per-contact (another contact's pool never leaks). */
public final class YearsMemoryRetrievalTest {

    private static final long HOUR = 3_600_000L;

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.DraftStoreFake drafts;
    private MemoryService memoryService;
    private Fakes.FakeProvider provider;
    private DraftService service;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        drafts = new Fakes.DraftStoreFake();
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        Fakes.StyleSettingStoreFake settings = new Fakes.StyleSettingStoreFake();
        Fakes.LearningStoreFake signals = new Fakes.LearningStoreFake();
        LearningService learning = Fakes.learningService(signals, new Fakes.KvStoreFake());
        memoryService = new MemoryService(new Fakes.MemoryStoreFake(), messages, kv,
            Fakes.FIXED_CLOCK);
        provider = Fakes.FakeProvider.returning("no wahala, GRA phase two it is");
        service = new DraftService(contacts, messages, new Fakes.StyleStoreFake(),
            new ProfileService(kv), drafts, new Fakes.UsageStoreFake(),
            new Fakes.GatewayFake(provider), Fakes.IDS, Fakes.FIXED_CLOCK,
            Fakes.NOOP_LOG, Fakes.styleService(settings, learning), learning,
            memoryService);
        contacts.put(Fakes.contact(1, "Amara"));
        contacts.put(Fakes.contact(2, "Bode"));
        contacts.put(Fakes.contact(3, "Chika"));
    }

    /** Years of chatter: 620 messages spread over ~2 years, oldest first.
     *  Positions 7 / 412 are the fact + correction; 200..590 carry a salience
     *  flood ("invoice NNN113 settled at 4") that evicts the topic from the
     *  budgeted summary; 620 is the new question needing the old fact. */
    private void seedYearsForAmara() {
        for (int i = 1; i <= 620; i++) {
            Direction dir = i % 2 == 0 ? Direction.OUTGOING : Direction.INCOMING;
            String body;
            if (i == 7) {
                dir = Direction.INCOMING;
                body = "remember my shop address na adeniyi jones street, ikeja";
            } else if (i == 412) {
                dir = Direction.INCOMING;
                body = "my shop don relocate go GRA phase two o — adeniyi jones no more";
            } else if (i >= 200 && i <= 590 && i % 3 == 0) {
                body = "invoice " + (10_000 + i) + " settled at 4";   // salience flood
            } else if (i == 620) {
                dir = Direction.INCOMING;
                body = "where is your shop again? send address abeg";  // the NEED
            } else {
                body = dir == Direction.INCOMING ? "lol ok heard" : "yeah sure cool";
            }
            com.replymate.core.model.Message m = Fakes.msg(1, dir, body);
            m.sentAt = Fakes.NOW - (620 - i) * 30L * HOUR;   // ~2.1 years deep
            messages.add(m);
        }
    }

    private void seedBodeOwnFact() {
        for (int i = 1; i <= 700; i++) {
            Direction dir = i % 2 == 0 ? Direction.OUTGOING : Direction.INCOMING;
            String body;
            if (i == 200) {
                dir = Direction.INCOMING;
                body = "my shop address na surulere lagos, no forget am";
            } else if (i == 700) {
                dir = Direction.INCOMING;
                body = "where is your shop again? send address abeg";
            } else {
                body = dir == Direction.INCOMING ? "lol ok" : "yeah sure";
            }
            com.replymate.core.model.Message m = Fakes.msg(2, dir, body);
            m.sentAt = Fakes.NOW - (700 - i) * 26L * HOUR;
            messages.add(m);
        }
    }

    private void seedTopicFreeControl() {
        for (int i = 1; i <= 500; i++) {
            Direction dir = i % 2 == 0 ? Direction.OUTGOING : Direction.INCOMING;
            String body;
            if (i == 150) {
                dir = Direction.INCOMING;
                body = "remember my shop address na adeniyi jones street, ikeja";
            } else if (i == 500) {
                dir = Direction.INCOMING;
                body = "how far, you dey around this weekend?";   // zero topic overlap
            } else {
                body = dir == Direction.INCOMING ? "lol ok" : "yeah sure";
            }
            com.replymate.core.model.Message m = Fakes.msg(3, dir, body);
            m.sentAt = Fakes.NOW - (500 - i) * 26L * HOUR;
            messages.add(m);
        }
    }

    private static String lineContaining(String haystack, String needle) {
        for (String line : haystack.split("\n")) {
            if (line.contains(needle)) return line;
        }
        return "";
    }

    @Test public void yearsOldNeedRetrievesTheCorrectionNotTheHistory() {
        seedYearsForAmara();
        // the mandated gap first: the budgeted summary does NOT carry the topic
        MemoryService.Recall probe = memoryService.recall(contacts.get(1),
            messages.lastMessages(1, MemoryService.HOT_WINDOW));
        assertFalse("the summary provably aged the topic out (char budget flood): "
                + probe.summaryText,
            probe.summaryText.contains("GRA phase two"));
        assertFalse(probe.summaryText.contains("adeniyi jones"));
        assertTrue("retrieval must carry it instead", probe.retrieved.toString()
            .contains("GRA phase two"));

        Result<DraftOutcome> r = service.generateForContact(1L);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        String system = provider.lastRequest.system;

        // the correction is IN, labeled authoritative
        String latestLine = lineContaining(system, "latest on this");
        assertTrue("the newest hit is labeled as the latest word: " + system,
            latestLine.contains("GRA phase two"));
        // the old fact is IN, labeled earlier (timestamps make currency checkable)
        String earlier = lineContaining(system, "— earlier,");
        assertTrue("old fact present, explicitly marked earlier: " + system,
            earlier.contains("adeniyi jones"));
        // the charter's resolution rule rides the same prompt
        assertTrue(system.contains("NEWEST one wins"));
        // the audit carries the retrieved material
        Draft d = r.value.drafts.get(0);
        assertTrue(d.promptSnapshotJson.contains("GRA phase two"));
        // … invariant: the flood is NOT in the prompt (no whole-history dump)
        assertFalse("salience-flood noise must never reach the provider",
            system.contains("invoice 10383"));
        assertFalse(system.contains("invoice 10119"));
    }

    @Test public void retrievalIsBoundedToThreeLinesAndOneBudget() {
        seedYearsForAmara();
        assertTrue(service.generateForContact(1L).ok);
        String system = provider.lastRequest.system;
        int retrievedLines = 0;
        int chars = 0;
        for (String line : system.split("\n")) {
            if (line.contains("latest on this") || line.contains("— earlier,")) {
                retrievedLines++;
                chars += line.length();
            }
        }
        assertTrue("never more than MAX_HITS retrieved lines: " + retrievedLines,
            retrievedLines <= HistoryRetriever.MAX_HITS);
        assertTrue("retrieval block obeys its char budget: " + chars,
            chars <= HistoryRetriever.CHAR_BUDGET + 3 * 6);  // "- " prefixes
        assertTrue("at least the correction AND the old fact both surfaced",
            retrievedLines >= 2);
    }

    @Test public void topicFreeMessageRetrievesNothing() {
        seedTopicFreeControl();
        assertTrue(service.generateForContact(3L).ok);
        String system = provider.lastRequest.system;
        assertFalse("no topical overlap ⇒ no retrieval block (no noise, no spend)",
            system.contains("latest on this"));
        assertFalse(system.contains("— earlier,"));
        MemoryService.Recall probe = memoryService.recall(contacts.get(3),
            messages.lastMessages(3, MemoryService.HOT_WINDOW));
        assertTrue(probe.retrieved.isEmpty());
    }

    @Test public void retrievalNeverCrossesContacts() {
        seedYearsForAmara();
        seedBodeOwnFact();
        assertTrue(service.generateForContact(2L).ok);
        String system = provider.lastRequest.system;
        assertTrue("Bode's own fact retrieved", system.contains("surulere"));
        assertFalse("Amara's history must never surface in Bode's prompt",
            system.contains("GRA phase two"));
        assertFalse(system.contains("adeniyi jones"));
        assertFalse(system.contains("invoice"));
    }

    @Test public void twentyYearScaleHistoryStillRetrievesOnlyTheNeedle() {
        // scale hammer: 3,000 messages ≈ many years of daily chat, one fact,
        // one correction, one question — still ≤3 lines and the right one.
        for (int i = 1; i <= 3000; i++) {
            Direction dir = i % 2 == 0 ? Direction.OUTGOING : Direction.INCOMING;
            String body;
            if (i == 40) {
                dir = Direction.INCOMING;
                body = "my accountant na Mr Eze from Onitsha main market";
            } else if (i == 2600) {
                dir = Direction.INCOMING;
                body = "scratch that — my accountant don change, na Mrs Okafor now";
            } else if (i == 3000) {
                dir = Direction.INCOMING;
                body = "abeg who be your accountant again?";
            } else {
                body = dir == Direction.INCOMING ? "lol ok" : "yeah sure";
            }
            com.replymate.core.model.Message m = Fakes.msg(1, dir, body);
            m.sentAt = Fakes.NOW - (3000 - i) * 6L * HOUR;
            messages.add(m);
        }
        Result<DraftOutcome> r = service.generateForContact(1L);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        String system = provider.lastRequest.system;
        String latest = lineContaining(system, "latest on this");
        assertTrue("the newer correction wins at year scale: " + system,
            latest.contains("Mrs Okafor"));
        int retrievedLines = 0;
        for (String line : system.split("\n")) {
            if (line.contains("latest on this") || line.contains("— earlier,")) {
                retrievedLines++;
            }
        }
        assertTrue("bounded even at 3,000 messages: " + retrievedLines,
            retrievedLines <= HistoryRetriever.MAX_HITS);
    }

    @Test public void memoryDisabledContactRetrievesAbsolutelyNothing() {
        seedYearsForAmara();
        com.replymate.core.model.Contact amara = contacts.get(1);
        amara.memoryEnabled = false;
        contacts.put(amara);
        assertTrue(service.generateForContact(1L).ok);
        String system = provider.lastRequest.system;
        assertFalse(system.contains("GRA phase two"));
        assertFalse(system.contains("adeniyi jones"));
        assertFalse(system.contains("latest on this"));
        List<String> retrieved = memoryService.recall(contacts.get(1),
            messages.lastMessages(1, MemoryService.HOT_WINDOW)).retrieved;
        assertTrue(retrieved.isEmpty());
    }
}
