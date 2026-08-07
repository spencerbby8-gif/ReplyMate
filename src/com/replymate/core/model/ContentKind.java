package com.replymate.core.model;

/** What a notification item actually IS — detected SEPARATELY from WHERE it came
 *  from (P-audit-deep): the source identity (app, sender, conversation) never
 *  decides the content kind and vice versa. Detection is evidence-based only:
 *  the notification's MIME type first, then the posting app's canonical fallback
 *  text (\"📷 Photo\" style) — never a guess from a free-form sentence.
 *
 *  TEXT        real readable message text (the only kind a reply can answer from text)
 *  IMAGE       photo / picture / gif
 *  VIDEO       video clip
 *  AUDIO       audio file (music, recordings)
 *  VOICE       voice note / PTT message
 *  STICKER     sticker
 *  CALL        missed/declined call event (stored for timeline truth, never answered)
 *  UNKNOWN     media/unreadable item whose exact kind could not be determined */
public enum ContentKind {
    TEXT("text"),
    IMAGE("image"),
    VIDEO("video"),
    AUDIO("audio"),
    VOICE("voice"),
    STICKER("sticker"),
    CALL("call"),
    UNKNOWN("unknown");

    public final String wire;

    ContentKind(String wire) { this.wire = wire; }

    /** Legacy rows (pre-0.9.0) stored unreadable media with this exact body. Kept
     *  byte-identical; ListenerFilter.MEDIA_PLACEHOLDER mirrors it for old callers. */
    public static final String LEGACY_PLACEHOLDER = "[media/voice — open in chat app]";

    public static ContentKind fromWire(String w) {
        if (w == null || w.isEmpty()) return null;   // empty = legacy row, not recorded
        for (ContentKind k : values()) if (k.wire.equals(w)) return k;
        return UNKNOWN;
    }

    /** True for anything that carries NO reliably readable words (all media kinds,
     *  calls, unknown). TEXT is the only kind that carries its own text. */
    public boolean isUnreadable() { return this != TEXT; }

    /** True for media kinds (not calls, not text). */
    public boolean isMedia() {
        switch (this) {
            case IMAGE: case VIDEO: case AUDIO: case VOICE: case STICKER: case UNKNOWN:
                return true;
            default:
                return false;
        }
    }

    /** Human phrase used in gate/audit copy: \"a photo\", \"a voice note\", … */
    public String label() {
        switch (this) {
            case IMAGE:   return "a photo";
            case VIDEO:   return "a video";
            case AUDIO:   return "an audio file";
            case VOICE:   return "a voice note";
            case STICKER: return "a sticker";
            case CALL:    return "a call";
            case UNKNOWN: return "media";
            default:      return "a text message";
        }
    }

    /** Honest stored body for an item with NO caption — visible in the thread and in
     *  the provider context, so the timeline stays truthful without hallucinating. */
    public String placeholder() {
        switch (this) {
            case IMAGE:   return "[photo — open in the chat app to view]";
            case VIDEO:   return "[video — open in the chat app to play]";
            case AUDIO:   return "[audio — open in the chat app to hear]";
            case VOICE:   return "[voice note — open in the chat app to hear]";
            case STICKER: return "[sticker — open in the chat app to view]";
            case CALL:    return "[call — no message text]";
            case UNKNOWN: return LEGACY_PLACEHOLDER;
            default:      return "";
        }
    }

    /** Body-placeholder inference for LEGACY rows that have no content_type column
     *  value (schema v5 added it): match any known placeholder shape. Returns null
     *  for real text bodies. */
    public static ContentKind fromBodyShape(String body) {
        if (body == null) return null;
        String t = body.trim();
        if (t.isEmpty()) return null;
        if (LEGACY_PLACEHOLDER.equals(t)) return UNKNOWN;
        for (ContentKind k : values()) {
            if (k != TEXT && k.placeholder().equals(t)) return k;
        }
        return null;
    }
}
