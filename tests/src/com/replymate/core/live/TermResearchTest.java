package com.replymate.core.live;

import com.replymate.fakes.Fakes;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-5 pins (directive 1 + 8): live research fires ONLY on an explicit
 *  information need (never on ordinary text), the dated glossary always wins first,
 *  the 7-day cache makes repeats free, and every outcome is audit-honest. */
public final class TermResearchTest {

    private static final long NOW = 1786189530000L;

    @Test public void explicitAskTriggersTheLookup() {
        assertEquals("odogwu", TermResearch.detectTerm(
            Collections.singletonList("what does odogwu even mean bro"),
            Collections.<String>emptyList(), Collections.<String>emptyList()));
        assertEquals("odogwu", TermResearch.detectTerm(
            Collections.singletonList("bro what is odogwu when dem dey call you odogwu"),
            null, null));
        assertEquals("odogwu", TermResearch.detectTerm(
            Collections.singletonList("wym odogwu, i no sabi am"),
            null, null));
        assertEquals("skibidi", TermResearch.detectTerm(
            Collections.singletonList("what does 'skibidi' even mean?"),
            null, null));
    }

    @Test public void glossaryTermsNeverTriggerAPaidLookup() {
        // these are all in the bundled glossary — paying to re-define them is waste
        assertNull(TermResearch.detectTerm(
            Collections.singletonList("what does 'ate' mean?"), null, null));
        assertNull(TermResearch.detectTerm(
            Collections.singletonList("wym 'no cap'"), null, null));
        assertNull(TermResearch.detectTerm(
            Collections.singletonList("bro what does rizz even mean"),
            null, null));
    }

    @Test public void genericAsksAndOrdinaryTextNeverTrigger() {
        assertNull(TermResearch.detectTerm(
            Collections.singletonList("what's up"), null, null));
        assertNull(TermResearch.detectTerm(
            Collections.singletonList("what's wrong with you today"), null, null));
        assertNull(TermResearch.detectTerm(
            Arrays.asList("any luck with the interview?", "did the delivery come?"),
            null, null));   // ordinary English is NOT proof of need
        assertNull(TermResearch.detectTerm(null, null, null));
    }

    @Test public void newestAskWinsDeterministically() {
        List<String> in = Arrays.asList(
            "what does odogwu mean?", "and what does skibidi mean now?");
        assertEquals("skibidi", TermResearch.detectTerm(in, null, null));
    }

    @Test public void cacheIsFreshFor7DaysThenMisses() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        TermResearch.store(kv, "rizz", "charm, flirting game", NOW);
        assertEquals("charm, flirting game", TermResearch.cached(kv, "rizz", NOW + 1000));
        assertEquals("charm, flirting game",
            TermResearch.cached(kv, "rizz", NOW + TermResearch.TTL_MS));
        assertNull("one ms past TTL is a miss",
            TermResearch.cached(kv, "rizz", NOW + TermResearch.TTL_MS + 1));
        kv.put("research.v1.broken", "not json");
        assertNull(TermResearch.cached(kv, "broken", NOW));
        assertNull(TermResearch.cached(null, "rizz", NOW));
    }

    @Test public void lookupRequestIsTinyAndDisciplineIsWritten() {
        com.replymate.core.ai.ChatRequest req =
            TermResearch.lookupRequest("rizz", "his rizz is different fr");
        assertTrue(req.system.contains("12 plain words or fewer"));
        assertTrue(req.system.contains("unsure"));
        assertTrue(req.system.contains("Output only the definition"));
        assertTrue(req.task.text.contains("\"rizz\""));
        assertTrue("context line rides along for disambiguation",
            req.task.text.contains("his rizz"));
        assertEquals(1, req.opts.candidates);   // ONE candidate — cost discipline
        assertTrue(req.opts.maxOutputTokens <= 60);
    }

    @Test public void auditLinesAreHonestInEveryOutcome() {
        assertTrue(TermResearch.whyOff("rizz").contains("off in Settings"));
        assertTrue(TermResearch.whyCached("rizz").contains("7-day cache"));
        assertTrue(TermResearch.whyLookedUp("rizz").contains("cached for 7 days"));
        assertTrue(TermResearch.whyFailed("rizz", "timeout").contains("timeout"));
        assertTrue(TermResearch.whyUnsure("rizz").contains("without it"));
        assertTrue(TermResearch.promptLine("rizz", "charm")
            .contains("researched on-device, cached"));
    }
}
