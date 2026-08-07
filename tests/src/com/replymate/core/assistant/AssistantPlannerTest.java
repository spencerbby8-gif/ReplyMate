package com.replymate.core.assistant;

import com.replymate.core.assistant.AssistantPlanner.Btn;
import com.replymate.core.assistant.AssistantPlanner.Capability;
import com.replymate.core.listener.RawNotif;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-background: pins the assistant's honest-capability rules, the human-approval
 *  contract in copy, one-alert-per-conversation identity, and the battery/dedupe
 *  trigger. The assistant must NEVER pretend an app without quick-reply supports it. */
public final class AssistantPlannerTest {

    private static RawNotif.ActionRef action(int index, boolean freeForm, String resultKey) {
        RawNotif.ActionRef a = new RawNotif.ActionRef();
        a.index = index;
        a.title = "Reply";
        a.remoteFreeForm = freeForm;
        a.resultKey = resultKey;
        return a;
    }

    /* ------------------------------------------------------------- capability */

    @Test public void directNeedsAFreeFormRemoteInputWithAResultKey() {
        List<RawNotif.ActionRef> actions = new ArrayList<RawNotif.ActionRef>();
        actions.add(action(0, false, null));                 // plain action
        actions.add(action(1, true, "key_text_reply"));      // real quick-reply
        assertEquals(Capability.DIRECT, AssistantPlanner.classify(actions));
        assertEquals(1, AssistantPlanner.directActionIndex(actions));
    }

    @Test public void capabilityDetectionIgnoresOrderAndTitles() {
        List<RawNotif.ActionRef> actions = new ArrayList<RawNotif.ActionRef>();
        actions.add(action(0, true, "voice_reply"));         // ← found even though not first
        RawNotif.ActionRef weird = action(1, true, "key_text_reply");
        weird.title = "Magic unicorn";                       // title carries no meaning
        assertEquals(Capability.DIRECT, AssistantPlanner.classify(actions));
        assertEquals(0, AssistantPlanner.directActionIndex(actions));
    }

    @Test public void noneWhenNothingUsableExists() {
        assertEquals(Capability.NONE, AssistantPlanner.classify(null));
        assertEquals(Capability.NONE,
            AssistantPlanner.classify(new ArrayList<RawNotif.ActionRef>()));

        List<RawNotif.ActionRef> noRemote = new ArrayList<RawNotif.ActionRef>();
        noRemote.add(action(0, false, null));
        assertEquals(Capability.NONE, AssistantPlanner.classify(noRemote));

        List<RawNotif.ActionRef> noKey = new ArrayList<RawNotif.ActionRef>();
        noKey.add(action(0, true, "   "));                   // free-form but no usable key
        assertEquals(Capability.NONE, AssistantPlanner.classify(noKey));
    }

    /* ------------------------------------------------------------- buttons + copy */

    @Test public void directShowsApproveSendFirst() {
        List<Btn> btns = AssistantPlanner.buttonsFor(Capability.DIRECT);
        assertEquals(3, btns.size());
        assertEquals(Btn.APPROVE_SEND, btns.get(0));
        assertEquals(Btn.REGENERATE, btns.get(1));
        assertEquals(Btn.OPEN, btns.get(2));
    }

    @Test public void noneShowsCopyFirstNeverApproveSend() {
        List<Btn> btns = AssistantPlanner.buttonsFor(Capability.NONE);
        assertEquals(3, btns.size());
        assertEquals(Btn.COPY, btns.get(0));
        assertFalse(btns.contains(Btn.APPROVE_SEND));
    }

    @Test public void captionsKeepTheHumanApprovalContract() {
        String direct = AssistantPlanner.caption("WhatsApp", Capability.DIRECT);
        assertTrue(direct.contains("WhatsApp"));
        assertTrue(direct.contains("Approve"));
        assertTrue(direct.contains("nothing sends by itself"));

        String none = AssistantPlanner.caption("Instagram", Capability.NONE);
        assertTrue(none.contains("Instagram"));
        assertTrue(none.contains("won't fake it"));
        assertFalse(none.contains("Approve & send"));
        assertFalse(none.contains("Approve sends"));

        // label fallback never renders a broken sentence
        assertTrue(AssistantPlanner.caption("", Capability.DIRECT).contains("This app"));
        assertTrue(AssistantPlanner.caption(null, Capability.NONE).contains("This app"));
    }

    /* ------------------------------------------------------------- identity */

    @Test public void oneAlertPerConversationStableAcrossRegenerations() {
        assertEquals(AssistantPlanner.notifTag(42), AssistantPlanner.notifTag(42));
        assertFalse(AssistantPlanner.notifTag(42).equals(AssistantPlanner.notifTag(43)));
        assertTrue(AssistantPlanner.notifTag(42).contains("42"));
    }

    @Test public void kvKeysAreContactScoped() {
        assertTrue(AssistantPlanner.targetKvKey(7).endsWith("7"));
        assertTrue(AssistantPlanner.hashKvKey(7).endsWith("7"));
        assertFalse(AssistantPlanner.targetKvKey(7).equals(AssistantPlanner.targetKvKey(8)));
        assertFalse(AssistantPlanner.targetKvKey(7).equals(AssistantPlanner.hashKvKey(7)));
    }

    /* ------------------------------------------------------------- trigger + hash */

    @Test public void dedupeBlocksRepeatsButNeverFreshMessagesOrForcedRegen() {
        assertFalse(AssistantPlanner.shouldGenerate("aaa", "aaa", false));   // repeat burst
        assertTrue(AssistantPlanner.shouldGenerate("bbb", "aaa", false));    // new message
        assertTrue(AssistantPlanner.shouldGenerate("aaa", "", false));       // first time
        assertTrue(AssistantPlanner.shouldGenerate("aaa", "aaa", true));     // Regenerate tap
        assertFalse(AssistantPlanner.shouldGenerate("", "", false));         // nothing to hash
        assertFalse(AssistantPlanner.shouldGenerate(null, "", false));
        assertFalse(AssistantPlanner.shouldGenerate("", "", true));          // force can't
        // conjure a message out of thin air
    }

    @Test public void hashIsStableAcrossRunsAndSensitiveToContent() {
        assertEquals(16, AssistantPlanner.hashOf("hello").length());
        assertEquals(AssistantPlanner.hashOf("same input"), AssistantPlanner.hashOf("same input"));
        assertFalse(AssistantPlanner.hashOf("same input")
            .equals(AssistantPlanner.hashOf("same input!")));
        assertEquals(AssistantPlanner.hashOf(""), AssistantPlanner.hashOf(null));
    }
}
