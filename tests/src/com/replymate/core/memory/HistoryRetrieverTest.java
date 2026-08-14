package com.replymate.core.memory;

import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-13: unit pins for the deterministic retriever — admission
 *  gate, correction boost, caps, stopword discipline, render labels. */
public final class HistoryRetrieverTest {

    private static Message msg(long id, long ts, Direction dir, String body) {
        Message m = Fakes.msg(1, dir, body);
        m.sentAt = ts;
        return m;
    }

    @Test public void stopwordOnlyQueryRetrievesNothingEvenWithMatches() {
        List<Message> older = new ArrayList<Message>();
        older.add(msg(1, 1000L, Direction.INCOMING, "yeah ok lol abeg no wahala"));
        older.add(msg(2, 2000L, Direction.INCOMING, "how are you doing today dear"));
        List<HistoryRetriever.Hit> hits = HistoryRetriever.retrieve(older,
            Collections.singletonList("lol you dey around?"));
        assertTrue("filler must never pull old messages into a paid prompt",
            hits.isEmpty());
    }

    @Test public void oneSharedWordIsBelowTheGateTwoSharedWordsPass() {
        List<Message> older = new ArrayList<Message>();
        older.add(msg(1, 1000L, Direction.INCOMING,
            "the landlord came about the money yesterday"));
        List<HistoryRetriever.Hit> one = HistoryRetriever.retrieve(older,
            Collections.singletonList("did the money land?"));
        assertTrue("a single mid-weight shared token is not enough",
            one.isEmpty());
        List<HistoryRetriever.Hit> two = HistoryRetriever.retrieve(older,
            Collections.singletonList("did the landlord money wahala settle?"));
        // landlord(8→3) + money(5→2) = 5 ≥ MIN_SCORE
        assertEquals(1, two.size());
    }

    @Test public void correctionBoostOutranksTheOldStatement() {
        List<Message> older = new ArrayList<Message>();
        older.add(msg(1, 1000L, Direction.INCOMING,
            "my warehouse dey apapa — collect the container there"));
        older.add(msg(2, 5000L, Direction.INCOMING,
            "warehouse don moved go festac o, apapa no more"));
        List<HistoryRetriever.Hit> hits = HistoryRetriever.retrieve(older,
            Collections.singletonList("the warehouse container — send am where?"));
        assertEquals(2, hits.size());
        assertTrue("ranked first: the correction (boost + newer)",
            hits.get(0).message.body.contains("festac"));
        assertTrue(hits.get(0).correction);
        assertTrue("the newest hit is the authoritative one",
            hits.get(0).isNewestHit);
        assertFalse(hits.get(1).isNewestHit);
    }

    @Test public void neverMoreThanMaxHitsNoMatterHowManyQualify() {
        List<Message> older = new ArrayList<Message>();
        for (int i = 0; i < 9; i++) {
            older.add(msg(i, 1000L + i * 100, Direction.INCOMING,
                "the workshop generator repaired again today number " + (5000 + i)));
        }
        List<HistoryRetriever.Hit> hits = HistoryRetriever.retrieve(older,
            Collections.singletonList("is the workshop generator working?"));
        assertEquals(HistoryRetriever.MAX_HITS, hits.size());
    }

    @Test public void renderCarriesTimeAttributionAndCurrencyLabels() {
        List<Message> older = new ArrayList<Message>();
        Message own = msg(1, 1000L, Direction.OUTGOING,
            "the pickup point na ojuelegba bridge");
        older.add(own);
        Message them = msg(2, 9000L, Direction.INCOMING,
            "pickup point don change, na yaba tech gate now");
        them.senderName = "Nkem";
        older.add(them);
        List<HistoryRetriever.Hit> hits = HistoryRetriever.retrieve(older,
            Collections.singletonList("where exactly is the pickup point again?"));
        assertEquals(2, hits.size());
        // rank is by relevance score; CURRENCY rides the label — the newest hit
        // (the correction) is flagged authoritative wherever it lands.
        String latest = "";
        String earlier = "";
        for (HistoryRetriever.Hit h : hits) {
            String rendered = HistoryRetriever.render(h, "Ada");
            if (h.isNewestHit) latest = rendered; else earlier = rendered;
        }
        assertTrue(latest.contains("yaba"));
        assertTrue(latest.contains("latest on this"));
        assertTrue("group sender attribution beats the contact name",
            latest.contains("Nkem"));
        assertTrue(earlier.contains("— earlier, "));
        assertTrue("outgoing rows attribute to the owner", earlier.contains("you"));
    }

    @Test public void emptyPoolAndEmptyQueryAreBothSilent() {
        assertTrue(HistoryRetriever.retrieve(new ArrayList<Message>(),
            Collections.singletonList("anything at all?")).isEmpty());
        List<Message> older = new ArrayList<Message>();
        older.add(msg(1, 1000L, Direction.INCOMING, "invoice 5001 settled at 4"));
        assertTrue(HistoryRetriever.retrieve(older,
            new ArrayList<String>()).isEmpty());
    }
}
