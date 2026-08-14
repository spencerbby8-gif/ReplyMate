package com.replymate.core.listener;

/** P-background-9: in-chat SYSTEM/SERVICE lines — the machine text messaging apps
 *  inject INTO a conversation's notification history, where every older gate
 *  (StatusFilter's self-title rule, the category gate, summary geometry) is blind
 *  because they arrive under the CONTACT's name with real MessagingStyle messages:
 *
 *    - "🔒 Messages and calls are end-to-end encrypted. … Tap to learn more."
 *    - "Your security code with Amara changed. …"
 *    - "Waiting for this message. This may take a while." (decryption placeholder)
 *    - bare missed-call cards with NO call category ("Missed voice call")
 *
 *  These are NOT messages: they must never be stored, never anchor a burst or a
 *  memory fact, never ping, and never trigger a paid generation.
 *
 *  Absolute safety rule (same posture as StatusFilter): a real human 1:1 message
 *  must ALWAYS survive. Shapes are matched as canonical wholes after
 *  normalization (leading decoration emoji stripped), never by loose contains —
 *  "sorry i missed your call 🙏" is a person talking and passes; the app-stamped
 *  whole-card "Missed voice call" is chrome and drops.
 *
 *  Pure + Android-free so every rule is pinned by JVM tests. */
public final class SystemLines {

    private SystemLines() { }

    /** True ⇒ this text is an app-generated in-chat system/service line. */
    public static boolean isSystemLine(String text) {
        String t = stripLead(ContentSignals.normalize(text));
        if (t.isEmpty()) return false;

        // WhatsApp canonical encryption / security notices (whole-card openers).
        if (t.startsWith("messages and calls are end-to-end encrypted")) return true;
        if (t.contains("your security code with") && t.contains("changed")) return true;
        // WhatsApp decryption placeholder ("Waiting for this message…").
        if (t.startsWith("waiting for this message")) return true;

        // Missed/declined-call cards that arrived WITHOUT the call category:
        // the app's whole-card stamp only. Conversation about a call passes.
        if (isCallCard(t)) return true;

        return false;
    }

    /** Canonical whole-card call notices, e.g. "Missed voice call", "📞 Missed
     *  group video call", "Declined call". Exact-set membership after
     *  normalization + decoration strip — a person's sentence that merely
     *  mentions a missed call never matches. */
    static boolean isCallCard(String normalizedStripped) {
        String t = normalizedStripped;
        // tolerate a trailing duration/status parenthetical the app may append
        // ("Missed voice call (tap to call back)" variants): canonical core only.
        int paren = t.lastIndexOf('(');
        if (paren > 0 && t.endsWith(")")) t = t.substring(0, paren).trim();
        // P-bg-10: some skins append the time to the card itself — "Missed
        // voice call at 2:14 pm". Strip a trailing " at <digit…>" tail so the
        // canonical core still matches; a person's sentence like "sorry,
        // missed your call at 2 though 🙏" is not in CALL_CARDS anyway.
        int at = t.indexOf(" at ");
        if (at > 0 && t.substring(at).matches(" at \\d.*")) {
            t = t.substring(0, at).trim();
        }
        return CALL_CARDS.contains(t);
    }

    /** P-bg-10: WhatsApp Channels / broadcast lists / status updates publish a
     *  native conversation id ending "@newsletter" or "@broadcast" while the
     *  rest of the notification looks like an ordinary 1:1 chat. Detect the id
     *  shape itself so the parser can drop the event before any contact, row,
     *  burst, or provider call exists. */
    public static boolean isAnnouncementId(String conversationId) {
        String id = conversationId == null ? "" : conversationId.trim().toLowerCase(java.util.Locale.US);
        return id.endsWith("@newsletter") || id.endsWith("@broadcast");
    }

    private static final java.util.Set<String> CALL_CARDS =
        new java.util.HashSet<String>(java.util.Arrays.asList(
            "missed call",
            "missed voice call",
            "missed video call",
            "missed group call",
            "missed group voice call",
            "missed group video call",
            "declined call",
            "declined voice call",
            "declined video call",
            "call declined",
            "call not answered",
            "no answer"
        ));

    /** A MessagingStyle entry with NO sender identity at all (no display name, no
     *  Person key, no Person uri). Human senders on every watched app publish at
     *  least one; sender-less entries inside an otherwise sendered history are
     *  the app's own inserts (system lines / promoted cards). */
    public static boolean hasSenderIdentity(String senderName, String senderKey,
                                            String senderUri) {
        return nonBlank(senderName) || nonBlank(senderKey) || nonBlank(senderUri);
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** Strip a leading run of decoration (emoji, arrows, locks, bullets, spaces)
     *  so "🔒 Messages and calls…" and "📞 Missed voice call" normalize to their
     *  canonical openers. Stops at the first letter or digit — never removes text. */
    static String stripLead(String t) {
        if (t == null) return "";
        int i = 0;
        while (i < t.length()) {
            char ch = t.charAt(i);
            if (Character.isLetterOrDigit(ch)) break;
            i++;
        }
        return t.substring(i);
    }
}
