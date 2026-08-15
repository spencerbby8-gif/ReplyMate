package com.replymate.core.convo;

/** P-intelligence-16b: ONE participant of ONE conversation, keyed by a STABLE id.
 *  Stability order (native metadata first, honest fallback last):
 *    "k:&lt;Person key&gt;"  — the posting app's own per-user id (MessagingStyle Person.getKey)
 *    "u:&lt;Person uri&gt;"  — tel:/username identity when the app exposes it
 *    "n:&lt;lower name&gt;"  — display-name fallback ONLY when the platform gives nothing
 *  Two different stable ids sharing one display name are DIFFERENT people — the
 *  registry assigns deterministic aliases ("Chidi", "Chidi 2") so prompts, drafts
 *  and the owner can never confuse them. Aliases are recomputed from first-seen
 *  order on every load: stable, stateless, no eviction surprises. */
public final class Participant {

    public final String stableId;
    public String displayName;      // latest name the platform gave this id (may refine)
    public long firstSeenMs;
    public long lastSpokeMs;
    public int msgCount;

    public Participant(String stableId, String displayName, long nowMs) {
        this.stableId = stableId;
        this.displayName = displayName == null ? "" : displayName;
        this.firstSeenMs = nowMs;
        this.lastSpokeMs = nowMs;
        this.msgCount = 1;
    }

    /** copy/alias is applied by the registry view, not stored here. */
    public String label(String alias) {
        return alias == null || alias.isEmpty() ? displayName : alias;
    }

    public void saw(String name, long nowMs) {
        if (name != null && !name.trim().isEmpty()) {
            // latest non-empty name wins — apps refine "234801…" → "Amara" over time
            displayName = name.trim();
        }
        lastSpokeMs = Math.max(lastSpokeMs, nowMs);
        msgCount++;
    }
}
