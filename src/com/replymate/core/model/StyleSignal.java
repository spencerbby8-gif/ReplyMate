package com.replymate.core.model;

/** One recorded learning signal about how the owner treated a draft (P4).
 *  kind wire values match the style_signal CHECK constraint.
 *  detail carries compact tokens for edited-signals, e.g. "shorter" or
 *  "shorter+emoji-down" (joined by '+', matched by containment — deliberately
 *  dumb so the derivation stays auditable). */
public class StyleSignal {

    public enum Kind {
        APPROVED("approved"),       // copied as-is
        EDITED("edited"),           // edited before copying
        REGENERATED("regenerated"), // asked for fresh variants over existing drafts
        REJECTED("rejected");       // deleted the draft

        public final String wire;
        Kind(String wire) { this.wire = wire; }

        public static Kind fromWire(String w) {
            for (Kind k : values()) if (k.wire.equals(w)) return k;
            throw new IllegalArgumentException("unknown signal kind: " + w);
        }
    }

    public long id;
    public long contactId;
    public Kind kind = Kind.APPROVED;
    public String detail = "";      // tokens for EDITED; "" otherwise
    public Long draftId;
    public long createdAt;

    public StyleSignal() { }
}
