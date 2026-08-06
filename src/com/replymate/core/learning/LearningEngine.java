package com.replymate.core.learning;

import com.replymate.core.model.StyleSignal;
import java.util.ArrayList;
import java.util.List;

/** Pure derivation over learning signals (P4 owner-approved learning rules).
 *  Everything is thresholded and deterministic — the same signal set always yields
 *  the same hints, so the "why" view can show exactly which counters produced them. */
public final class LearningEngine {

    /** Minimum totals before ANY hint is derived (avoid hair-trigger adaptation). */
    public static final int MIN_SIGNALS = 3;
    /** Edit-delta hints need this many style edits. */
    public static final int EDIT_HINT_MIN = 3;

    private LearningEngine() { }

    /* --------------------------------------------------------- edit classification */

    /** Classify how the user changed a draft before copying: compact "+"-joined tokens. */
    public static String classifyEdit(String from, String to) {
        if (from == null) from = "";
        if (to == null) to = "";
        if (from.trim().equals(to.trim())) return "none";

        List<String> tokens = new ArrayList<String>();
        int toLen = to.trim().length();
        int fromLen = from.trim().length();
        if (fromLen > 0) {
            if (toLen <= (int) (fromLen * 0.70)) tokens.add("shorter");
            else if (toLen >= (int) (fromLen * 1.40)) tokens.add("longer");
        }
        int emojiFrom = emojiCount(from);
        int emojiTo = emojiCount(to);
        if (emojiTo < emojiFrom) tokens.add("emoji-down");
        else if (emojiTo > emojiFrom) tokens.add("emoji-up");

        return tokens.isEmpty() ? "tweaked" : join(tokens);
    }

    /** Rough emoji counter: surrogate pairs + common emoji blocks. Pure + testable. */
    public static int emojiCount(String s) {
        if (s == null) return 0;
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isHighSurrogate(ch)) { count++; i++; continue; }
            if (ch == 0x2764 || ch == 0x263A || ch == 0x2600 || ch == 0x2705 || ch == 0x2B50
                    || (ch >= 0x2190 && ch <= 0x21FF)) count++;
        }
        return count;
    }

    /* --------------------------------------------------------------- hint derivation */

    /** Counters rollup of a contact's signals, shared by hints + UI + export + audit. */
    public static final class Counters {
        public int approved;
        public int edited;
        public int regenerated;
        public int rejected;
        public int shorter;
        public int longer;
        public int emojiDown;
        public int emojiUp;
        public int tweaked;

        public int total() { return approved + edited + regenerated + rejected; }
    }

    public static Counters count(List<StyleSignal> signals) {
        Counters c = new Counters();
        if (signals == null) return c;
        for (StyleSignal s : signals) {
            if (s == null || s.kind == null) continue;
            switch (s.kind) {
                case APPROVED: c.approved++; break;
                case REGENERATED: c.regenerated++; break;
                case REJECTED: c.rejected++; break;
                case EDITED:
                    c.edited++;
                    if (s.detail != null) {
                        if (s.detail.contains("shorter")) c.shorter++;
                        if (s.detail.contains("longer")) c.longer++;
                        if (s.detail.contains("emoji-down")) c.emojiDown++;
                        if (s.detail.contains("emoji-up")) c.emojiUp++;
                        if ("tweaked".equals(s.detail)) c.tweaked++;
                    }
                    break;
                default: break;
            }
        }
        return c;
    }

    /** Hint with its evidence (for the audit view's "why"). */
    public static final class Hint {
        public final String line;       // prompt line
        public final String why;        // human evidence, e.g. "5 of 7 edits made text shorter"
        public Hint(String line, String why) { this.line = line; this.why = why; }
    }

    /** Deterministic hint derivation — every rule above its threshold, else silent. */
    public static List<Hint> deriveHints(Counters c) {
        List<Hint> out = new ArrayList<Hint>();
        if (c == null || c.total() < MIN_SIGNALS) return out;

        if (c.edited >= EDIT_HINT_MIN) {
            if (c.shorter >= EDIT_HINT_MIN && c.shorter >= 2 * c.longer) {
                out.add(new Hint("keep replies noticeably shorter",
                    c.shorter + " of " + c.edited + " edits made the text shorter"));
            } else if (c.longer >= EDIT_HINT_MIN && c.longer >= 2 * c.shorter) {
                out.add(new Hint("a bit more room is welcome — don't over-compress",
                    c.longer + " of " + c.edited + " edits made the text longer"));
            }
            if (c.emojiDown >= EDIT_HINT_MIN && c.emojiDown >= 2 * c.emojiUp) {
                out.add(new Hint("skip emoji here (the owner keeps removing them)",
                    c.emojiDown + " edits removed emoji"));
            } else if (c.emojiUp >= EDIT_HINT_MIN && c.emojiUp >= 2 * c.emojiDown) {
                out.add(new Hint("emoji are welcome in this chat",
                    c.emojiUp + " edits added emoji"));
            }
        }
        if (c.regenerated >= 4 && c.regenerated > c.approved) {
            out.add(new Hint("vary the wording between options more",
                c.regenerated + " regenerations vs " + c.approved + " approvals"));
        }
        if (c.rejected >= 3 && c.rejected >= 2 * c.approved) {
            out.add(new Hint("be more conservative: plain, natural, low-risk replies",
                c.rejected + " drafts deleted vs " + c.approved + " approved"));
        }
        if (out.isEmpty() && c.approved >= 5 && c.edited == 0) {
            out.add(new Hint("the current style is landing well — keep it consistent",
                c.approved + " approvals with no edits"));
        }
        return out;
    }

    private static String join(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) sb.append('+');
            sb.append(tokens.get(i));
        }
        return sb.toString();
    }
}
