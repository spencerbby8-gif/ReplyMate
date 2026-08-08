package com.replymate.core.assistant;

import com.replymate.core.listener.RawNotif;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** P-background (1.4.0): the Background Reply Assistant's pure decision logic —
 *  capability classification, honest caption copy, action sets and dedupe/trigger
 *  rules. Zero Android imports so every rule is pinned by JVM tests.
 *
 *  Official-capability mapping (docs-audited 2026-08-07):
 *    DIRECT — the source notification carries a reply action whose RemoteInput
 *             allows free-form text. ReplyMate may deliver the APPROVED draft via
 *             RemoteInput.addResultsToIntent + the action's PendingIntent — the
 *             same contract Android Auto uses. There is NO official API to
 *             pre-type text into another app's reply box; "Approve" sends.
 *    NONE   — no such action exists on the current notification. ReplyMate must
 *             never pretend quick-reply works: buttons become Copy / Regenerate /
 *             Open and the caption says exactly why. */
public final class AssistantPlanner {

    private AssistantPlanner() {
    }

    public enum Capability { DIRECT, NONE }

    /** Banner buttons. P-background-9: EDIT replaces OPEN in the action row — the
     *  framework caps a notification at THREE visible actions, and the owner
     *  requires Approve/Edit/Regenerate directly visible; OPEN stays ONE tap away
     *  as the card body tap (and on settled cards), which is not an expansion
     *  step. Keep EDIT LAST: request codes derive from ordinals. */
    public enum Btn { APPROVE_SEND, COPY, REGENERATE, OPEN, EDIT }

    /** P-intelligence-3: RemoteInput result key for the inline EDIT action on the
     *  draft alert — the typed correction arrives under this key in the broadcast
     *  (framework RemoteInput contract; constant lives here so both the notifier
     *  and the receiver pin the SAME key). */
    public static final String EDIT_INPUT_KEY = "rm_edit_text";

    /* ------------------------------------------------------------------ capability */

    /** The best usable direct-reply action across BOTH documented surfaces
     *  (standard + wearable), else null. Preference: standard surface first,
     *  then wearable — both carry identical RemoteInput semantics per the docs. */
    public static RawNotif.ActionRef directAction(List<RawNotif.ActionRef> actions) {
        if (actions == null) return null;
        RawNotif.ActionRef wearableHit = null;
        for (RawNotif.ActionRef a : actions) {
            if (a == null) continue;
            if (a.remoteFreeForm && a.resultKey != null && !a.resultKey.trim().isEmpty()) {
                if (a.source == RawNotif.ActionRef.SRC_STANDARD) return a;
                if (wearableHit == null) wearableHit = a;
            }
        }
        return wearableHit;
    }

    /** First action index whose RemoteInput accepts free-form text, else -1.
     *  (Compat shim over directAction for older callers/tests.) */
    public static int directActionIndex(List<RawNotif.ActionRef> actions) {
        RawNotif.ActionRef a = directAction(actions);
        return a == null ? -1 : a.index;
    }

    public static Capability classify(List<RawNotif.ActionRef> actions) {
        return directAction(actions) != null ? Capability.DIRECT : Capability.NONE;
    }

    /* ------------------------------------------------------------------ actions+copy */

    /** Buttons for the ReplyMate notification, in display order (max 3 — framework
     *  cap; the card body tap opens the conversation). */
    public static List<Btn> buttonsFor(Capability cap) {
        List<Btn> out = new ArrayList<Btn>();
        out.add(cap == Capability.DIRECT ? Btn.APPROVE_SEND : Btn.COPY);
        out.add(Btn.EDIT);
        out.add(Btn.REGENERATE);
        return Collections.unmodifiableList(out);
    }

    /** The honest caption under the draft. appLabel falls back to "This app".
     *  P-background-9: both flavors end with the body-tap hint, since OPEN moved
     *  from the (3-slot) action row to the card tap. */
    public static String caption(String appLabel, Capability cap) {
        String app = appLabel == null || appLabel.trim().isEmpty() ? "This app" : appLabel;
        if (cap == Capability.DIRECT) {
            return "Approve sends it through " + app + "'s own quick-reply —"
                + " nothing sends by itself. Tap the card to open this chat.";
        }
        return app + "'s notification offers no quick-reply box in this version —"
            + " ReplyMate won't fake it: copy, or open " + app + " to send."
            + " Tap the card to open this chat.";
    }

    /* ------------------------------------------------------------------ identity */

    /** One ReplyMate notification PER CONVERSATION: Regenerate (or a fresher draft)
     *  reuses this tag so the alert updates in place instead of stacking. */
    public static String notifTag(long contactId) {
        return "assistant:c" + contactId;
    }

    /** kv key for the captured reply target (package + sbn key + action geometry). */
    public static String targetKvKey(long contactId) {
        return "assistant.target." + contactId;
    }

    /** kv key for the last incoming hash we already background-generated for. */
    public static String hashKvKey(long contactId) {
        return "assistant.hash." + contactId;
    }

    /** kv key for the per-conversation "already heads-up alerted" flag
     *  (P-background-8): one audible pop per fresh draft cycle; burst updates and
     *  regenerations refresh the SAME alert silently; approving/copying/dismissing
     *  clears the flag so the NEXT genuinely new burst pops again. */
    public static String alertedKvKey(long contactId) {
        return "assistant.alerted." + contactId;
    }

    /* ------------------------------------------------------------------ trigger */

    /** Battery/dedupe rule: background-generate only for an incoming message we
     *  have NOT generated for yet; force (the Regenerate button) always runs. */
    public static boolean shouldGenerate(String incomingHash, String lastDoneHash,
                                         boolean force) {
        if (incomingHash == null || incomingHash.isEmpty()) return false;
        if (force) return true;
        return !incomingHash.equals(lastDoneHash);
    }

    /** Stable, dependency-free content hash (FNV-1a 64). Same input ⇒ same output
     *  across process restarts — required for the dedupe rule above. */
    public static String hashOf(String s) {
        String v = s == null ? "" : s;
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < v.length(); i++) {
            h ^= v.charAt(i);
            h *= 0x100000001b3L;
        }
        String hex = Long.toHexString(h);
        StringBuilder sb = new StringBuilder(16);
        for (int i = hex.length(); i < 16; i++) sb.append('0');
        return sb.append(hex).toString();
    }
}
