package com.replymate.core.usecase;

import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.DraftStatus;
import com.replymate.core.model.Message;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression: stale/wrong-contact context + memory leaking across contacts
 *  (P-memory-audit). Two FULLY populated contacts — threads older than the hot
 *  window, pinned facts, rolling summaries, approved replies, learning signals —
 *  then generation for A must carry ONLY A's data, and only A's LATEST text. */
public class ContextIsolationTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.DraftStoreFake drafts;
    private Fakes.UsageStoreFake usage;
    private Fakes.KvStoreFake kv;
    private Fakes.MemoryStoreFake memoryStore;
    private com.replymate.core.memory.MemoryService memoryService;
    private ProfileService profiles;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        drafts = new Fakes.DraftStoreFake();
        usage = new Fakes.UsageStoreFake();
        kv = new Fakes.KvStoreFake();
        memoryStore = new Fakes.MemoryStoreFake();
        profiles = new ProfileService(kv);
        memoryService = new com.replymate.core.memory.MemoryService(
            memoryStore, messages, kv, Fakes.FIXED_CLOCK);

        contacts.put(Fakes.contact(1, "Amara"));
        contacts.put(Fakes.contact(2, "Uche"));

        // A: 45 messages (15 fall out of the hot window), latest = LATEST-A
        for (int i = 1; i <= 44; i++) {
            Message m = Fakes.msg(1, i % 2 == 0 ? Direction.OUTGOING : Direction.INCOMING,
                "amara thread line " + i + " — plan tomorrow " + (2 + i % 6) + "pm?");
            m.sentAt = Fakes.NOW + i * 60_000;
            messages.add(m);
        }
        Message latestA = Fakes.msg(1, Direction.INCOMING, "LATEST-A: so 4pm final?");
        latestA.sentAt = Fakes.NOW + 45 * 60_000;
        messages.add(latestA);

        // B: same size, all marked UCHE-SECRET
        for (int i = 1; i <= 44; i++) {
            Message m = Fakes.msg(2, i % 2 == 0 ? Direction.OUTGOING : Direction.INCOMING,
                "UCHE-SECRET line " + i + " tomorrow " + (3 + i % 4) + "pm?");
            m.sentAt = Fakes.NOW + i * 60_000;
            messages.add(m);
        }
        Message latestB = Fakes.msg(2, Direction.INCOMING, "UCHE-SECRET latest text");
        latestB.sentAt = Fakes.NOW + 46 * 60_000;
        messages.add(latestB);

        // per-contact pinned facts
        memoryService.replacePinnedFacts(1, "Amara pinned: mum's shop in Wuse 2");
        memoryService.replacePinnedFacts(2, "UCHE-SECRET pinned fact");

        // approved replies per contact (M4 learned-style evidence)
        for (int i = 0; i < 4; i++) {
            Draft da = new Draft();
            da.contactId = 1;
            da.replyText = "amara approved " + i + " no full stop";
            da.status = DraftStatus.COPIED;
            da.model = "test-model";
            da.variantGroup = "g" + i;
            da.createdAt = Fakes.NOW;
            drafts.insert(da);
            Draft db = new Draft();
            db.contactId = 2;
            db.replyText = "UCHE-SECRET approved " + i;
            db.status = DraftStatus.COPIED;
            db.model = "test-model";
            db.variantGroup = "g" + i;
            db.createdAt = Fakes.NOW + 1;
            drafts.insert(db);
        }
    }

    private DraftService service(Fakes.GatewayFake gateway) {
        return new DraftService(contacts, messages, new Fakes.StyleStoreFake(), profiles,
            drafts, usage, gateway, Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            null, null, memoryService);
    }

    @Test public void requestCarriesOnlyContactAsWorld() {
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("omo 4pm locked");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(provider)).generateForContact(1);
        assertTrue(String.valueOf(r.error), r.ok);

        String req = provider.lastRequest.system
            + "\n" + provider.lastRequest.task.text + "\n";
        for (com.replymate.core.ai.Turn t : provider.lastRequest.turns) req += t.text + "\n";

        // (a) the REAL latest A text is what gets answered
        assertTrue(req, provider.lastRequest.task.text.contains("LATEST-A: so 4pm final?"));
        // (b) A's older context arrives as hot turns AND rolling summary
        assertTrue(req, req.contains("amara thread line 44"));
        assertTrue(req, provider.lastRequest.system.contains("Earlier in this chat"));
        assertTrue(req, provider.lastRequest.system.contains("amara thread line 15")
            || provider.lastRequest.system.contains("amara thread line 1"));
        // (c) A's pinned fact + learned style arrive
        assertTrue(req, req.contains("mum's shop in Wuse 2"));
        assertTrue(req, req.contains("approved replies"));
        // (d) NOTHING from Uche — thread, latest, fact, summary, style
        assertFalse("B leaked into the request", req.contains("UCHE-SECRET"));
        assertFalse(req, req.contains("Uche"));

        // (e) each draft's snapshot is equally clean
        for (Draft d : r.value.drafts) {
            assertFalse(d.promptSnapshotJson.contains("UCHE-SECRET"));
            assertTrue(d.promptSnapshotJson.contains("LATEST-A"));
        }
    }

    @Test public void generatingForBPicksUpOnlyBsWorld() {
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("text back");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(provider)).generateForContact(2);
        assertTrue(r.ok);
        String req = provider.lastRequest.system + "\n" + provider.lastRequest.task.text;
        for (com.replymate.core.ai.Turn t : provider.lastRequest.turns) req += t.text + "\n";
        assertTrue(req, req.contains("UCHE-SECRET latest text"));
        assertTrue(req, req.contains("UCHE-SECRET pinned fact"));
        assertFalse("A leaked into B's request", req.contains("amara thread line"));
        assertFalse(req, req.contains("Amara"));
        assertFalse(req, req.contains("LATEST-A"));
    }

    @Test public void summaryRowsVersionPerContactOnly() {
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("x");
        service(new Fakes.GatewayFake(provider)).generateForContact(1);
        assertNotNull(memoryStore.latestSummary(1));
        assertNull("generating for A must not create B's summary",
            memoryStore.latestSummary(2));
        assertTrue("summary is contact-1's history",
            memoryStore.latestSummary(1).summaryText.contains("amara thread line"));
    }

    @Test public void staleLatestIsNeverAnswered() {
        // a NEWER incoming arrives for A after setup → THAT one must be answered,
        // not the seeded one (no stale-context replies)
        Message newest = Fakes.msg(1, Direction.INCOMING, "FRESHEST-A: change of plans o");
        newest.sentAt = Fakes.NOW + 99 * 60_000;
        messages.add(newest);
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("wait what changed?");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(provider)).generateForContact(1);
        assertTrue(r.ok);
        assertTrue(provider.lastRequest.task.text.contains("FRESHEST-A: change of plans o"));
        assertFalse(provider.lastRequest.task.text.contains(
            "The message you're replying to — Amara's latest: \"LATEST-A"));
    }
}
