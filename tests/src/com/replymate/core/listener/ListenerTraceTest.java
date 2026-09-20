package com.replymate.core.listener;

import com.replymate.fakes.Fakes;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-listener-foundation §3: the capture-boundary trace is the device evidence
 *  lens. Pinned here: bounded ring, newest-first order, hashed (non-reversible)
 *  notification-key tags, shape signatures built from presence/counts ONLY
 *  (identical structure + different bodies ⇒ identical signature), shape-flip
 *  dedupe (late Reply action / MessagingStyle history on re-post produces
 *  exactly one new line), and the hard privacy rule — no body ever lands in a
 *  trace line. */
public final class ListenerTraceTest {

    private static final String SECRET_BODY = "the-oven-code-is-4121";

    private static RawNotif baseShape() {
        RawNotif raw = new RawNotif();
        raw.packageName = "com.whatsapp";
        raw.category = "msg";
        raw.title = "Ada";
        raw.text = SECRET_BODY;
        raw.group = Boolean.FALSE;
        raw.conversationId = "2348012345678";
        raw.sbnKey = "0|com.whatsapp|98421|null|10455";
        raw.postTimeMs = 1500;
        RawNotif.Entry e = new RawNotif.Entry();
        e.senderName = "Ada";
        e.senderKey = "wa-uid-7";
        e.text = SECRET_BODY;
        e.timestampMs = 1500;
        raw.messages.add(e);
        RawNotif.ActionRef reply = new RawNotif.ActionRef();
        reply.title = "Reply";
        reply.remoteFreeForm = true;
        reply.resultKey = "key_reply";
        raw.actions.add(reply);
        return raw;
    }

    private static String joined(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    @Test public void linesAreNewestFirstAndBounded() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        com.replymate.core.util.Clock fixed = Fakes.FIXED_CLOCK;
        for (int i = 0; i < 20; i++) {
            ListenerTrace.line(kv, fixed, "stage-" + i);
        }
        List<String> lines = ListenerTrace.lines(kv);
        assertEquals(DiagnosticsRing.CAP, lines.size());     // capped
        assertTrue(lines.get(0).endsWith("stage-19"));       // newest first
        assertTrue(lines.get(lines.size() - 1).endsWith("stage-" + (19 - DiagnosticsRing.CAP + 1)));
    }

    @Test public void keyTagIsStableShortAndDoesNotRevealTheKey() {
        String key = "0|com.whatsapp|98421|null|10455";
        String tag = ListenerTrace.keyTag(key);
        assertEquals(tag, ListenerTrace.keyTag(key));            // deterministic
        assertNotEquals(tag, ListenerTrace.keyTag("0|com.discord|7|null|10455"));
        assertTrue(tag.length() <= 8);
        assertFalse(tag.contains("98421"));
        assertEquals("-", ListenerTrace.keyTag(null));
        assertEquals("-", ListenerTrace.keyTag(""));
    }

    @Test public void shapeSignatureDependsOnStructureNeverOnBodies() {
        RawNotif a = baseShape();
        RawNotif b = baseShape();
        b.title = "Bim";
        b.text = "totally different body";
        b.messages.get(0).text = "and another one";
        b.messages.get(0).senderName = "Bim";
        // different content, identical structure ⇒ identical signature.
        assertEquals(ListenerTrace.shapeSignature(a), ListenerTrace.shapeSignature(b));
        // …but the signature itself must never carry the content.
        assertFalse(ListenerTrace.shapeSignature(a).contains(SECRET_BODY));
        assertFalse(ListenerTrace.shapeSignature(b).contains("totally different"));
    }

    @Test public void structuralChangesAreVisible() {
        String base = ListenerTrace.shapeSignature(baseShape());

        RawNotif lateAction = baseShape();
        RawNotif.ActionRef wearReply = new RawNotif.ActionRef();
        wearReply.source = RawNotif.ActionRef.SRC_WEARABLE;
        wearReply.remoteFreeForm = true;
        lateAction.actions.add(wearReply);
        assertNotEquals(base, ListenerTrace.shapeSignature(lateAction));   // owner row "late Reply action"

        RawNotif historyAppears = baseShape();
        RawNotif.Entry hist = new RawNotif.Entry();
        hist.senderName = "Ada";
        hist.text = "earlier";
        historyAppears.historic.add(hist);
        assertNotEquals(base, ListenerTrace.shapeSignature(historyAppears));  // MessagingStyle on re-post

        RawNotif burstGrows = baseShape();
        RawNotif.Entry more = new RawNotif.Entry();
        more.senderName = "Ada";
        more.text = "second";
        burstGrows.messages.add(more);
        assertNotEquals(base, ListenerTrace.shapeSignature(burstGrows));   // burst growth
    }

    @Test public void shapeProbeDedupesUntilShapeActuallyChanges() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        com.replymate.core.util.Clock fixed = Fakes.FIXED_CLOCK;

        assertTrue(ListenerTrace.maybeRecordShape(kv, fixed, baseShape()));     // first sight → 1 line
        assertFalse(ListenerTrace.maybeRecordShape(kv, fixed, baseShape()));    // same shape → silent
        assertEquals(1, ListenerTrace.lines(kv).size());

        RawNotif repost = baseShape();               // re-post, content changed only
        repost.text = "new body entirely";
        repost.messages.get(0).text = "new body entirely";
        assertFalse(ListenerTrace.maybeRecordShape(kv, fixed, repost));
        assertEquals(1, ListenerTrace.lines(kv).size());

        RawNotif lateReply = baseShape();            // shape flip: reply action arrives late
        RawNotif.ActionRef wearReply = new RawNotif.ActionRef();
        wearReply.source = RawNotif.ActionRef.SRC_WEARABLE;
        wearReply.remoteFreeForm = true;
        lateReply.actions.add(wearReply);
        assertTrue(ListenerTrace.maybeRecordShape(kv, fixed, lateReply));
        assertEquals(2, ListenerTrace.lines(kv).size());
    }

    @Test public void noBodyEverEntersTraceLines() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        com.replymate.core.util.Clock fixed = Fakes.FIXED_CLOCK;
        ListenerTrace.maybeRecordShape(kv, fixed, baseShape());
        ListenerTrace.line(kv, fixed, "route · com.whatsapp · PARSED · events=1");
        String all = joined(ListenerTrace.lines(kv));
        assertFalse(all.contains(SECRET_BODY));
        assertFalse(all.contains("Ada"));
    }
}
