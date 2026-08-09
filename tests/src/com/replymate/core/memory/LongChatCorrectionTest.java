package com.replymate.core.memory;

import com.replymate.core.ai.Turn;
import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.usecase.DraftService;
import com.replymate.core.usecase.DraftOutcome;
import com.replymate.core.usecase.ProfileService;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-4/5 pins (directive 5 — long-chat memory): prove BOTH halves of
 *  the months-long contract in one request —
 *    (a) an important fact from the chat's BEGINNING still reaches the prompt via
 *        the rolling summary/pinned layer (it can still shape the reply), and
 *    (b) a NEWER correction rides the hot window verbatim, with the prompt's
 *        newest-statement-wins rule there to resolve the conflict.
 *  Nothing is sent twice, and nobody else's memory ever rides along. */
public final class LongChatCorrectionTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private MemoryService memoryService;
    private DraftService service;
    private Fakes.FakeProvider provider;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        Fakes.StyleSettingStoreFake settings = new Fakes.StyleSettingStoreFake();
        Fakes.LearningStoreFake signals = new Fakes.LearningStoreFake();
        LearningService learning = Fakes.learningService(signals, new Fakes.KvStoreFake());
        memoryService = new MemoryService(new Fakes.MemoryStoreFake(), messages, kv,
            Fakes.FIXED_CLOCK);
        provider = Fakes.FakeProvider.returning("no wahala, GRA it is");
        service = new DraftService(contacts, messages, new Fakes.StyleStoreFake(),
            new ProfileService(kv), new Fakes.DraftStoreFake(),
            new Fakes.UsageStoreFake(), new Fakes.GatewayFake(provider),
            Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(settings, learning), learning, memoryService);
        contacts.put(Fakes.contact(1, "Amara"));
    }

    private void seedMonthsWithEarlyFactAndLateCorrection(long contactId, int count) {
        for (int i = 1; i <= count; i++) {
            Direction dir = i % 2 == 0 ? Direction.OUTGOING : Direction.INCOMING;
            String body;
            if (i == 5) {
                body = "remember my shop address na adeniyi jones street";  // months-old
                dir = Direction.INCOMING;
            } else if (i == 89) {
                body = "my shop don move go GRA phase two o — adeniyi jones no more";
                dir = Direction.INCOMING;                                    // the correction
            } else {
                body = dir == Direction.INCOMING
                    ? (i <= 60 ? "long ago ok" : "lol ok")
                    : (i <= 60 ? "way back yeah" : "yeah sure");
            }
            com.replymate.core.model.Message m = Fakes.msg(contactId, dir, body);
            m.id = i;
            m.sentAt = Fakes.NOW + i * 3_600_000L;
            messages.add(m);
        }
    }

    private static String rawTurnsText(Fakes.FakeProvider p) {
        StringBuilder sb = new StringBuilder();
        for (Turn t : p.lastRequest.turns) sb.append(t.text).append('\n');
        if (p.lastRequest.task != null) sb.append(p.lastRequest.task.text);
        return sb.toString();
    }

    @Test public void monthsOldFactStillInfluencesWhileTheNewerCorrectionWins() {
        seedMonthsWithEarlyFactAndLateCorrection(1, 90);
        Result<DraftOutcome> r = service.generateForContact(1L);
        assertTrue(r.ok);

        String system = provider.lastRequest.system;
        String turns = rawTurnsText(provider);
        // (a) the OLD fact is still reachable — months later — via the summary
        assertTrue("months-old fact must still reach the prompt: " + system,
            system.contains("adeniyi jones"));
        assertTrue(system.contains("Earlier in this chat (your own running summary)"));
        // (b) the NEWER correction is in the hot window, verbatim
        assertTrue("the correction rides the recent window verbatim: " + turns,
            turns.contains("GRA phase two"));
        // (c) and the prompt literally instructs how to resolve the conflict
        assertTrue(system.contains("NEWEST one wins"));
        assertTrue(system.contains("never quoted back"));
        // (d) audit proves the old fact was applied (snapshot carries the summary)
        Draft d = r.value.drafts.get(0);
        assertTrue("Prompt Audit carries the months-old fact via the summary",
            d.promptSnapshotJson.contains("adeniyi jones"));
    }

    @Test public void pinnedOldFactAlsoSurvivesAlongsideTheCorrection() {
        seedMonthsWithEarlyFactAndLateCorrection(1, 90);
        memoryService.replacePinnedFacts(1, "shop landmark: the yellow gate");
        assertTrue(service.generateForContact(1L).ok);
        String system = provider.lastRequest.system;
        assertTrue("owner-pinned fact travels even without summary coverage",
            system.contains("the yellow gate"));
        assertTrue(rawTurnsText(provider).contains("GRA phase two"));
        assertTrue(system.contains("NEWEST one wins"));
    }
}
