package com.replymate.core.usecase;

import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.DraftStatus;
import com.replymate.core.model.ToneTransform;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P3 tone transforms on existing drafts — with the SAME isolation and
 *  fail-closed guarantees as full generation. */
public class DraftServiceToneTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.StyleStoreFake styles;
    private Fakes.DraftStoreFake drafts;
    private Fakes.UsageStoreFake usage;
    private Fakes.KvStoreFake kv;
    private ProfileService profiles;
    private long originId;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        styles = new Fakes.StyleStoreFake();
        drafts = new Fakes.DraftStoreFake();
        usage = new Fakes.UsageStoreFake();
        kv = new Fakes.KvStoreFake();
        profiles = new ProfileService(kv);

        contacts.put(Fakes.contact(1, "Amara"));
        contacts.put(Fakes.contact(2, "Bank Client"));
        messages.add(Fakes.msg(1, Direction.INCOMING, "yes o! just dey settle"));

        Draft origin = new Draft();
        origin.contactId = 1;
        origin.inReplyToId = 55L;
        origin.replyText = "nice one, glad you landed safe";
        origin.model = "test-model";
        origin.status = DraftStatus.GENERATED;
        originId = drafts.insert(origin);

        Draft other = new Draft();
        other.contactId = 2;
        other.replyText = "B-SECRET draft you must never touch";
        drafts.insert(other);
    }

    private DraftService service(Fakes.GatewayFake gateway) {
        Fakes.StyleSettingStoreFake styleSettings = new Fakes.StyleSettingStoreFake();
        Fakes.LearningStoreFake learningStore = new Fakes.LearningStoreFake();
        com.replymate.core.learning.LearningService learning =
            Fakes.learningService(learningStore, new Fakes.KvStoreFake());
        return new DraftService(contacts, messages, styles, profiles,
            drafts, usage, gateway, Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(styleSettings, learning), learning);
    }

    @Test public void happyPathSavesNewAuditedVariantAndMetersUsage() {
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("glad you landed safe o!");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(provider))
            .transformDraftForContact(1, originId, ToneTransform.FRIENDLIER);

        assertTrue(r.ok);
        assertEquals(1, provider.calls);
        Draft saved = null;
        for (Draft d : drafts.byContact(1, 50)) {
            if (d.id != originId) saved = d;
        }
        assertNotNull("transform must save a NEW draft", saved);
        assertEquals("glad you landed safe o!", saved.replyText);
        assertEquals(Long.valueOf(55L), saved.inReplyToId);   // reply linkage preserved
        assertEquals(3, drafts.saved.size());                 // origin + B's + transformed

        // rewrite prompt is honest: original text + tone instruction, nothing else.
        assertTrue(provider.lastRequest.task.text.contains("nice one, glad you landed safe"));
        assertTrue(provider.lastRequest.task.text.contains(ToneTransform.FRIENDLIER.instruction));
        assertFalse("other contacts never cross the wire",
            provider.lastRequest.task.text.contains("B-SECRET"));
        assertTrue(provider.lastRequest.turns.isEmpty());     // no thread history needed

        // audit: snapshot explicitly tagged as a tone transform
        assertTrue(saved.promptSnapshotJson.contains("\"kind\":\"tone:friendlier\""));
        assertTrue(saved.promptSnapshotJson.contains("test-model"));

        assertEquals("transform is metered as one AI call", 1, usage.events.size());
    }

    @Test public void generationSnapshotsAreTaggedReply() {
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("ok");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(provider)).generateForContact(1);
        assertTrue(r.ok);
        assertTrue(r.value.drafts.get(0).promptSnapshotJson.contains("\"kind\":\"reply\""));
    }

    @Test public void failsClosedForPrivateContact() {
        Contact p = Fakes.contact(9, "Secret");
        p.privateMode = true;
        p.aiEnabled = false;
        contacts.put(p);
        Draft dp = new Draft();
        dp.contactId = 9;
        dp.replyText = "never transform this";
        drafts.insert(dp);

        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("x");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(provider))
            .transformDraftForContact(9, dp.id, ToneTransform.SHORTER);
        assertFalse(r.ok);
        assertTrue(r.error.toLowerCase().contains("private"));
        assertEquals("private draft must never hit the wire", 0, provider.calls);
    }

    @Test public void aiDisabledContactRejected() {
        Contact c = Fakes.contact(7, "NoAI");
        c.aiEnabled = false;
        contacts.put(c);
        Draft d = new Draft();
        d.contactId = 7;
        d.replyText = "text";
        drafts.insert(d);
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(Fakes.FakeProvider.returning("x")))
            .transformDraftForContact(7, d.id, ToneTransform.SHORTER);
        assertFalse(r.ok);
        assertTrue(r.error.toLowerCase().contains("disabled"));
    }

    @Test public void draftsOfOtherContactsAreUnreachable() {
        long bDraftId = -1;
        for (Draft d : drafts.saved) if (d.contactId == 2) bDraftId = d.id;
        assertTrue(bDraftId > 0);
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("x");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(provider))
            .transformDraftForContact(1, bDraftId, ToneTransform.SHORTER);  // contact 1 asking for B's draft
        assertFalse(r.ok);
        assertEquals("That draft is gone.", r.error);
        assertEquals(0, provider.calls);
    }

    @Test public void missingProviderToneAndDraftAllFailSafely() {
        Result<DraftOutcome> noProvider = service(new Fakes.GatewayFake(null))
            .transformDraftForContact(1, originId, ToneTransform.SHORTER);
        assertFalse(noProvider.ok);
        assertTrue(noProvider.error.toLowerCase().contains("api key"));

        Result<DraftOutcome> noTone = service(new Fakes.GatewayFake(Fakes.FakeProvider.returning("x")))
            .transformDraftForContact(1, originId, null);
        assertFalse(noTone.ok);

        Result<DraftOutcome> noDraft = service(new Fakes.GatewayFake(Fakes.FakeProvider.returning("x")))
            .transformDraftForContact(1, 99999L, ToneTransform.SHORTER);
        assertFalse(noDraft.ok);
        assertEquals("That draft is gone.", noDraft.error);
    }

    @Test public void providerErrorPropagates() {
        Result<DraftOutcome> r = service(
            new Fakes.GatewayFake(Fakes.FakeProvider.failing("network unreachable")))
            .transformDraftForContact(1, originId, ToneTransform.CONFIDENT);
        assertFalse(r.ok);
        assertEquals("network unreachable", r.error);
    }
}
