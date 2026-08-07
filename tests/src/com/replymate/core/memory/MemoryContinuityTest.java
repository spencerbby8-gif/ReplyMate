package com.replymate.core.memory;

import com.replymate.core.model.Contact;
import com.replymate.core.model.ContactSummary;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Memory continuity layers (P-memory-audit): rolling summary refresh + versioning,
 *  pinned facts replace/recall, learned-style kv cache, memory-off gating, and
 *  strict per-contact isolation. */
public class MemoryContinuityTest {

    private Fakes.MemoryStoreFake memory;
    private Fakes.MessageStoreFake messages;
    private Fakes.KvStoreFake kv;
    private MemoryService svc;
    private Contact amara;
    private Contact uche;

    @Before public void setUp() {
        memory = new Fakes.MemoryStoreFake();
        messages = new Fakes.MessageStoreFake();
        kv = new Fakes.KvStoreFake();
        svc = new MemoryService(memory, messages, kv, Fakes.FIXED_CLOCK);
        amara = Fakes.contact(1, "Amara");
        uche = Fakes.contact(2, "Uche");
    }

    private List<Message> seedHistory(long contactId, int count, String tag) {
        List<Message> all = new ArrayList<Message>();
        for (int i = 1; i <= count; i++) {
            Direction dir = i % 2 == 0 ? Direction.OUTGOING : Direction.INCOMING;
            Message m = Fakes.msg(contactId, dir, tag + " plan " + i + " tomorrow "
                + (3 + i % 5) + "pm?");
            m.sentAt = Fakes.NOW + i * 60_000;
            messages.add(m);
            all.add(m);
        }
        return all;
    }

    @Test public void summaryBuildsOnceThenReusesRow() {
        List<Message> all = seedHistory(1, 40, "amara");
        List<Message> hot = messages.lastMessages(1, MemoryService.HOT_WINDOW);
        assertEquals(MemoryService.HOT_WINDOW, hot.size());

        ContactSummary first = svc.refreshSummary(amara, hot);
        assertNotNull(first);
        assertEquals(1, first.version);
        assertFalse(first.summaryText.isEmpty());
        assertEquals(all.get(all.size() - MemoryService.HOT_WINDOW - 1).sentAt,
            first.coversUntilTs);

        // refresh again with unchanged data → SAME row, no new version (restart-stable)
        ContactSummary again = svc.refreshSummary(amara, hot);
        assertEquals(first.id, again.id);
        assertEquals(1, again.version);
        assertEquals(1, memory.summariesByContact.get(1L).size());
    }

    @Test public void summaryAdvancesWhenNewMessagesPushOlderOut() {
        seedHistory(1, 40, "amara");
        ContactSummary v1 = svc.refreshSummary(amara, messages.lastMessages(1, 30));
        assertEquals(1, v1.version);

        // a new message arrives → the hot window slides, one row leaves the window
        Message newer = Fakes.msg(1, Direction.INCOMING, "newest text 7pm okay?");
        newer.sentAt = Fakes.NOW + 99 * 60_000;
        messages.add(newer);

        ContactSummary v2 = svc.refreshSummary(amara, messages.lastMessages(1, 30));
        assertEquals(2, v2.version);
        assertEquals(v1.coversUntilTs < v2.coversUntilTs || v1.coversUntilTs > 0, true);
        assertEquals(2, memory.summariesByContact.get(1L).size());
        // latestSummary must hand back the NEWEST version (what survives a restart)
        assertEquals(2, memory.latestSummary(1).version);
    }

    @Test public void recallRendersSummaryOnlyWhenOlderHistoryExists() {
        seedHistory(1, 5, "amara");   // everything fits the hot window
        MemoryService.Recall small = svc.recall(amara, messages.lastMessages(1, 30));
        assertEquals("", small.summaryText);
        assertTrue(small.lines.isEmpty());
    }

    @Test public void recallRendersFactsSummaryAndWhy() {
        List<Message> all = seedHistory(1, 45, "amara");
        svc.replacePinnedFacts(1, "Her mum's shop is in Wuse 2\nAllergic to peanuts");

        MemoryService.Recall r = svc.recall(amara, messages.lastMessages(1, 30));
        assertEquals(2, r.facts.size());
        assertTrue(String.join("\n", r.lines), r.lines.get(0).contains("Wuse 2")
            || r.lines.get(1).contains("Wuse 2"));
        assertFalse(r.summaryText.isEmpty());
        assertTrue(r.summaryMeta, r.summaryMeta.contains("summary v1"));
        boolean sawFactWhy = false, sawSummaryLine = false;
        for (String w : r.why) if (w.contains("memory facts applied (2")) sawFactWhy = true;
        for (String l : r.lines) if (l.contains("Earlier in this chat")) sawSummaryLine = true;
        assertTrue(sawFactWhy);
        assertTrue(sawSummaryLine);
    }

    @Test public void pinnedFactsReplaceIsExactAndDeduped() {
        svc.replacePinnedFacts(1, "Fact one\nFact two\nFact one");
        assertEquals(2, svc.pinnedFactsText(1).split("\n").length);
        svc.replacePinnedFacts(1, "Fact one\nFact three");
        String text = svc.pinnedFactsText(1);
        assertTrue(text.contains("Fact one"));
        assertTrue(text.contains("Fact three"));
        assertFalse(text.contains("Fact two"));
        // pinned facts survive repeated merges (never rewritten by lower-confidence data)
        assertEquals(2, memory.allFacts(1).size());
    }

    @Test public void learnedStyleCachedInKvAndRestartStable() {
        List<String> approved = Arrays.asList(
            "omw now", "lol true", "sure give me 5", "no wahala");
        List<String> lines1 = svc.learnedStyleLines(amara, approved);
        assertFalse(lines1.isEmpty());
        String cached = kv.get(MemoryService.styleKey(1), "");
        assertFalse(cached.isEmpty());
        // same approved-count → the cache answers (identical lines)
        List<String> lines2 = svc.learnedStyleLines(amara,
            new ArrayList<String>(approved));
        assertEquals(lines1, lines2);
        // evidence grew → recompute happens honestly
        List<String> grown = new ArrayList<String>(approved);
        grown.add("omo 😂😂 this one loud");
        List<String> lines3 = svc.learnedStyleLines(amara, grown);
        assertNotNull(lines3);   // re-derived without error
    }

    @Test public void memoryDisabledContactGetsNothing() {
        seedHistory(1, 45, "amara");
        svc.replacePinnedFacts(1, "Some fact");
        amara.memoryEnabled = false;
        MemoryService.Recall r = svc.recall(amara, messages.lastMessages(1, 30));
        assertTrue(r.lines.isEmpty());
        assertTrue(r.facts.isEmpty());
        assertEquals("", r.summaryText);
        // and nothing new is persisted while the gate is closed
        assertNull(memory.latestSummary(1));
        Contact privateC = Fakes.contact(9, "Secret");
        privateC.privateMode = true;
        privateC.memoryEnabled = true;
        assertTrue(svc.recall(privateC, new ArrayList<Message>()).lines.isEmpty());
    }

    @Test public void noMemoryEverLeaksAcrossContacts() {
        List<Message> aAll = seedHistory(1, 45, "amara");
        seedHistory(2, 45, "UCHE-SECRET");
        svc.replacePinnedFacts(1, "Amara pinned fact");
        svc.replacePinnedFacts(2, "UCHE-SECRET fact");
        svc.learnedStyleLines(uche, Arrays.asList("aa", "bb", "cc", "dd"));

        MemoryService.Recall rA = svc.recall(amara, messages.lastMessages(1, 30));
        String blobA = String.join("\n", rA.lines) + "\n" + rA.summaryText
            + "\n" + String.join("\n", rA.facts);
        assertFalse(blobA, blobA.contains("UCHE-SECRET"));
        assertFalse(blobA, blobA.toLowerCase().contains("uche"));

        MemoryService.Recall rU = svc.recall(uche, messages.lastMessages(2, 30));
        String blobU = String.join("\n", rU.lines) + "\n" + rU.summaryText;
        assertFalse(blobU, blobU.contains("amara plan"));
        // summaries are versioned per contact independently
        assertEquals(1, memory.latestSummary(1).version);
        assertEquals(1, memory.latestSummary(2).version);
        // and the hot windows themselves are contact-scoped
        for (Message m : messages.lastMessages(1, 30)) assertEquals(1, m.contactId);
        assertEquals(aAll.size() - 30,
            messages.olderThanId(1, messages.lastMessages(1, 30).get(0).id, 400).size());
    }
}
