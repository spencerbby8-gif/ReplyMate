package com.replymate.core.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-6 directive 2/8: THE GATE pinned both ways — real information
 *  needs (slang/Pidgin/abbreviations/memes/current events/scores/prices) DO fire
 *  with the right subject, ordinary messages NEVER fire, and every fire carries
 *  its audit reason. */
public final class SearchGateTest {

    private static SearchGate.Need assess(String... active) {
        List<String> in = new ArrayList<String>(Arrays.asList(active));
        return SearchGate.assess(in,
            Arrays.asList("earlier small talk about the weather"),
            Arrays.asList("tobi", "spencer"));
    }

    /* ------------------------------------------------------------ positives */

    @Test public void explicitEnglishMeaningAskFiresWithTheTerm() {
        SearchGate.Need n = assess("what does odogwu even mean bro");
        assertEquals(SearchGate.Kind.MEANING, n.kind);
        assertEquals("odogwu", n.subject);
        assertTrue(n.reason.contains("odogwu"));
    }

    @Test public void pidginMeaningAskFiresWithTheTerm() {
        SearchGate.Need n = assess("wetin be kaku abeg");
        assertEquals(SearchGate.Kind.MEANING, n.kind);
        assertEquals("kaku", n.subject);
    }

    @Test public void quotedSlangAskWins() {
        SearchGate.Need n = assess("wym 'no cap fr fr'");
        assertEquals(SearchGate.Kind.MEANING, n.kind);
        assertEquals("no cap fr fr", n.subject);
    }

    @Test public void sportsResultAskFiresAsCurrent() {
        SearchGate.Need n = assess("who won the Arsenal game last night");
        assertEquals(SearchGate.Kind.CURRENT, n.kind);
        assertTrue(n.subject.contains("Arsenal"));
        assertTrue(n.reason.contains("changes"));
    }

    @Test public void priceAskFiresAsCurrent() {
        SearchGate.Need n = assess("wetin be fuel price for Lagos this week");
        assertEquals(SearchGate.Kind.CURRENT, n.kind);
    }

    @Test public void trendingNewsAskFiresAsCurrent() {
        SearchGate.Need n = assess("any latest on the elections tribunal");
        assertEquals(SearchGate.Kind.CURRENT, n.kind);
    }

    @Test public void aWordCarryingAShortMessageFiresAsUnknown() {
        SearchGate.Need n = assess("bro you dey zuzu");
        assertEquals(SearchGate.Kind.UNKNOWN, n.kind);
        assertEquals("zuzu", n.subject);
    }

    @Test public void aQuotedUnknownWordInsideALongerMessageFires() {
        SearchGate.Need n = assess("everyone keeps calling my fit 'skibidi' and I am tired");
        assertEquals(SearchGate.Kind.UNKNOWN, n.kind);
        assertEquals("skibidi", n.subject);
    }

    /* ------------------------------------------------------------ negatives */

    @Test public void ordinaryMessagesNeverFire() {
        String[][] ordinaries = {
            {"are we still on for dinner at 7"},
            {"lol that's amazing, congratulations!"},
            {"my mum is in the hospital"},
            {"did you watch the Arsenal match last night?"} ,      // no current marker
            {"actually make it tuesday not thursday"},
            {"send me the address when you can"},
            {"happy birthday bro, many more years"},
        };
        for (String[] msgs : ordinaries) {
            SearchGate.Need n = assess(msgs);
            assertEquals("ordinary message must never trigger a lookup: " + msgs[0],
                SearchGate.Kind.NONE, n.kind);
        }
    }

    @Test public void pidginEverydayVocabularyNeverFires() {
        assertEquals(SearchGate.Kind.NONE, assess("wetin dey sup na").kind);
        assertEquals(SearchGate.Kind.NONE, assess("I dey come now, wahala for traffic").kind);
        assertEquals(SearchGate.Kind.NONE, assess("oya no vex, sapa don hold me").kind);
    }

    @Test public void namesAndContactVocabularyNeverFire() {
        assertEquals(SearchGate.Kind.NONE, assess("tobi are you coming").kind);
        assertEquals(SearchGate.Kind.NONE,
            SearchGate.assess(Arrays.asList("aura check later"),
                Arrays.asList("we already discussed aura yesterday"),
                Arrays.asList("tobi", "spencer")).kind);   // word already in thread history
    }

    @Test public void properNounsNeverFireAsUnknown() {
        // capitalised entities are treated as things the model already knows;
        // only a CURRENT marker may fire for them.
        assertEquals(SearchGate.Kind.NONE, assess("Arsenal!!!").kind);
        assertEquals(SearchGate.Kind.NONE, assess("Tesla").kind);
    }

    @Test public void wellKnownSlangNeverFires() {
        assertEquals(SearchGate.Kind.NONE, assess("that goal was lit fr").kind);
        assertEquals(SearchGate.Kind.NONE, assess("lol you're lowkey goated").kind);
    }
}
