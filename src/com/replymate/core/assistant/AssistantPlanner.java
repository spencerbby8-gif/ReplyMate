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

    public enum Btn { APPROVE_SEND, COPY, REGENERATE, OPEN }

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

    /** Buttons for the ReplyMate notification, in display order. */
    public static List<Btn> buttonsFor(Capability cap) {
        List<Btn> out = new ArrayList<Btn>();
        out.add(cap == Capability.DIRECT ? Btn.APPROVE_SEND : Btn.COPY);
        out.add(Btn.REGENERATE);
        out.add(Btn.OPEN);
        return Collections.unmodifiableList(out);
    }

    /** The honest caption under the draft. appLabel falls back to "This app". */
    public static String caption(String appLabel, Capability cap) {
        String app = appLabel == null || appLabel.trim().isEmpty() ? "This app" : appLabel;
        if (cap == Capability.DIRECT) {
            return "Approve sends it through " + app + "'s own quick-reply —"
                + " nothing sends by itself.";
        }
        return app + "'s notification offers no quick-reply box in this version —"
            + " ReplyMate won't fake it: copy, or open " + app + " to send.";
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
