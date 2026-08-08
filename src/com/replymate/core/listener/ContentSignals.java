package com.replymate.core.listener;

import com.replymate.core.model.ContentKind;
import java.util.LinkedHashMap;
import java.util.Map;

/** Evidence-based CONTENT-KIND detection for posted notifications (P-audit-deep),
 *  deliberately decoupled from source identity: this class never looks at package,
 *  sender or conversation to decide WHAT an item is.
 *
 *  Evidence order (strongest first):
 *    1. MIME type from the MessagingStyle attachment marker ("type" / dataMimeType)
 *    2. the posting app's canonical fallback shape ("📷 Photo", "Voice message (0:07)",
 *       "sent a photo", …) — matched EXACTLY after normalization, never by fuzzy
 *       contains, so a real one-word text like "photo" is never demoted to media.
 *  Bare single words ("photo", "video", "audio", "sticker", "gif", "document"…)
 *  are ONLY trusted when the notification carried attachment evidence too — humans
 *  actually type those words; multi-word/emoji canonical shapes are app-generated
 *  and trusted on their own. */
public final class ContentSignals {

    private ContentSignals() { }

    /** Canonical fallback shapes → kind. Filled at class load; keys are normalized
     *  (lowercase, variation-selectors stripped, trailing "(0:07)"-style durations
     *  removed, whitespace collapsed). */
    private static final Map<String, ContentKind> SHAPES = new LinkedHashMap<String, ContentKind>();
    /** Bare single-word shapes that REQUIRE attachment evidence (humans type these). */
    private static final java.util.Set<String> BARE_WORDS =
        new java.util.HashSet<String>(java.util.Arrays.asList(
            "photo", "video", "audio", "sticker", "gif", "image", "document", "file",
            "location", "poll", "contact card"));

    static {
        // WhatsApp-style (emoji + word); Telegram/Signal variants; Slack/Discord
        // "sent a …" English shapes. Various emoji presentations incl. FE0F stripped.
        put(IMAGE_SHAPES(), ContentKind.IMAGE);
        put(VIDEO_SHAPES(), ContentKind.VIDEO);
        put(AUDIO_SHAPES(), ContentKind.AUDIO);
        put(VOICE_SHAPES(), ContentKind.VOICE);
        put(STICKER_SHAPES(), ContentKind.STICKER);
        put(UNKNOWN_SHAPES(), ContentKind.UNKNOWN);
    }

    private static String[] IMAGE_SHAPES() {
        return new String[] {"📷 photo", "📸 photo", "🖼 image", "🖼️ image", "image",
            "photo", "sent a photo", "sent an image", "sent an photo", "📷", "📸", "🖼"};
    }
    private static String[] VIDEO_SHAPES() {
        return new String[] {"🎥 video", "📹 video", "video", "sent a video",
            "🎥", "📹", "🎞 gif", "gif", "sent a gif", "🎞"};
    }
    private static String[] AUDIO_SHAPES() {
        return new String[] {"🎵 audio", "🎶 audio", "audio", "sent an audio",
            "sent an audio file", "sent a song", "🎵", "🎶"};
    }
    private static String[] VOICE_SHAPES() {
        return new String[] {"voice message", "🎤 voice message", "🎙 voice message",
            "🎙️ voice message", "voice note", "🎤", "🎙", "ptt",
            "sent a voice message", "sent a voice note"};
    }
    private static String[] STICKER_SHAPES() {
        return new String[] {"sticker", "sent a sticker"};
    }
    private static String[] UNKNOWN_SHAPES() {
        return new String[] {"document", "📄 document", "file", "📁 file",
            "contact card", "👤 contact card", "location", "📍 location",
            "poll", "📊 poll", "📄", "📍", "📊", "sent a document", "sent a file"};
    }

    private static void put(String[] keys, ContentKind kind) {
        for (String k : keys) SHAPES.put(normalize(k), kind);
    }

    /** Normalize a candidate fallback text: trim, lowercase, collapse whitespace,
     *  strip emoji variation selectors, drop a trailing duration/count parenthetical
     *  — \"🎤 Voice message (0:07)\" → \"🎤 voice message\". */
    public static String normalize(String text) {
        if (text == null) return "";
        String t = text.replace("️", "").trim().toLowerCase(java.util.Locale.US);
        t = t.replaceAll("\\s+", " ");
        int paren = t.lastIndexOf('(');
        if (paren > 0 && t.endsWith(")")) t = t.substring(0, paren).trim();
        return t;
    }

    /** True when the whole text IS one of the known app fallback shapes (exact match
     *  after normalization). Bare words still count as shapes here — callers decide
     *  whether bare words need attachment evidence via classify(). */
    public static boolean isFallbackShape(String text) {
        return SHAPES.containsKey(normalize(text));
    }

    /** Decide the content kind of one notification item.
     *  @param mime          MessagingStyle attachment MIME (may be null)
     *  @param hasAttachment the posting app marked an attachment ("uri"/"type" present)
     *  @param text          the message/fallback text (may be null/empty) */
    public static ContentKind classify(String mime, boolean hasAttachment, String text) {
        String m = mime == null ? "" : mime.trim().toLowerCase(java.util.Locale.US);
        ContentKind shapeKind = shapeKind(text, hasAttachment || !m.isEmpty());

        if (!m.isEmpty()) {
            // MIME is the strongest signal; text shapes refine inside a family.
            if (m.startsWith("image/")) {
                // image/webp alone is NOT proof of a sticker (photos can be webp) —
                // the app's own fallback text must say so.
                return shapeKind == ContentKind.STICKER ? ContentKind.STICKER
                    : ContentKind.IMAGE;
            }
            if (m.startsWith("video/")) return ContentKind.VIDEO;
            if (m.startsWith("audio/")) {
                // voice notes ride on audio/*; the app text decides voice vs music
                return shapeKind == ContentKind.VOICE ? ContentKind.VOICE : ContentKind.AUDIO;
            }
            // other MIME (documents, vcf, …): trust the shape if one matched
            return shapeKind != null ? shapeKind : ContentKind.UNKNOWN;
        }

        if (hasAttachment) {
            return shapeKind == null ? ContentKind.UNKNOWN : shapeKind;
        }

        // No attachment metadata at all: only multi-word/emoji canonical shapes are
        // trusted as media evidence; a bare human-typed word stays TEXT.
        if (shapeKind != null && !BARE_WORDS.contains(normalize(text))) return shapeKind;
        return ContentKind.TEXT;
    }

    /** Shape lookup honoring the bare-word evidence rule. */
    private static ContentKind shapeKind(String text, boolean evidence) {
        String norm = normalize(text);
        if (norm.isEmpty()) return null;
        ContentKind k = SHAPES.get(norm);
        if (k == null) return null;
        if (evidence) return k;
        return BARE_WORDS.contains(norm) ? null : k;
    }

    /** Missed/declined-call event text (WhatsApp: \"Missed voice call\", …). Kept
     *  contains-based on \"missed\"/\"declined\" + \"call\" because apps append details;
     *  only consulted together with the notification's call category. */
    public static boolean isCallEvent(String text) {
        String t = normalize(text);
        if (t.isEmpty()) return false;
        if (!t.contains("call")) return false;
        return t.startsWith("missed") || t.contains("missed") || t.contains("declined");
    }

    /** True when the value is one of our stored placeholder bodies (any kind, incl.
     *  the pre-0.9.0 legacy one) — the generation gate's unreadable-text test. */
    public static boolean isPlaceholder(String body) {
        return ContentKind.fromBodyShape(body) != null;
    }

    /** P-background-9: reaction notices are NOT messages — WhatsApp/Signal style
     *  "Reacted 👍 to “…”" and Instagram/Messenger style "liked your message".
     *  Kept deliberately narrow (official shapes only):
     *   - must START with the reaction verb (a person quoting the pattern mid-sentence
     *     is a real message), AND
     *   - "reacted … to …" requires a QUOTED segment after "to" (real apps quote the
     *     target message) — "reacted to the news quickly" stays a real message.
     *  Used by the ingest pipeline to drop these before they can touch a
     *  conversation, memory, bursts or drafting. */
    public static boolean isReactionNotice(String text) {
        String t = normalize(text);
        if (t.isEmpty()) return false;
        if (t.startsWith("liked your message") || t.startsWith("loved your message")
                || t.startsWith("liked a message") || t.startsWith("loved a message")) {
            return true;
        }
        // prefix test on normalized (lowercase) text …
        if (!t.startsWith("reacted ")) return false;
        // … but the quote test runs on the RAW text after the first " to "
        String raw = text.trim();
        int rawTo = raw.indexOf(" to ");
        if (rawTo < 0) return false;
        char q = raw.length() > rawTo + 4 ? raw.charAt(rawTo + 4) : ' ';
        return q == '“' || q == '"' || q == '‘';
    }
}
