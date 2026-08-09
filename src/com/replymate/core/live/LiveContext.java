package com.replymate.core.live;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** P-intelligence-4 (directive 6 — LIVE CONTEXT, offline-honest): two things Android
 *  can give generation WITHOUT the web, wired once per GENERATION (never per message):
 *
 *   1. DEVICE TEMPORAL TRUTH — the actual date, time and timezone of THIS phone, so
 *      "later", "tomorrow", "next Friday" are judged against a real moment, not a
 *      training-data calendar. Sources: the injected Clock + java.util.TimeZone.
 *   2. DATED WORD HELP — slang/abbreviations shift; a small CURATED glossary ships
 *      inside the app with an honest freshness stamp ({@link #GLOSSARY_STAMP}). It is
 *      injected ONLY when the incoming text actually uses a listed term, and the
 *      audit line always says it is curated (refreshed with app updates), NEVER
 *      marketed as live research. There is deliberately NO web call: ReplyMate has
 *      no research infra, and pretending otherwise would be a fake feature.
 *
 *  Everything degrades gracefully: disabled → an empty prompt line and an honest
 *  audit breadcrumb; generation itself never depends on this module. */
public final class LiveContext {

    private LiveContext() { }

    /** Settings toggle (default ON) — SettingsActivity writes, DraftService reads. */
    public static final String KV_ENABLED = "livectx.enabled";
    /** Freshness stamp of the bundled glossary — bumped whenever the words are reviewed. */
    public static final String GLOSSARY_STAMP = "2026-08";

    /** What the prompt receives + what Prompt Audit credits. */
    public static final class Snapshot {
        public final String promptLine;   // "" when disabled
        public final String whyLine;      // always present (honest in both states)
        Snapshot(String promptLine, String whyLine) {
            this.promptLine = promptLine;
            this.whyLine = whyLine;
        }
    }

    /* ------------------------------------------------------------ the glossary */

    /** Single-token terms, matched on word boundaries. Keep compact + high-signal. */
    private static final Map<String, String> TOKENS = new LinkedHashMap<String, String>();
    /** Multi-word phrases, matched on padded lowercase contains. */
    private static final Map<String, String> PHRASES = new LinkedHashMap<String, String>();

    static {
        TOKENS.put("ate", "did amazingly");
        TOKENS.put("bet", "okay, agreed");
        TOKENS.put("mid", "average, not impressive");
        TOKENS.put("rizz", "charm, flirting game");
        TOKENS.put("delulu", "playfully unrealistic");
        TOKENS.put("sus", "suspicious");
        TOKENS.put("aura", "cool points, presence");
        TOKENS.put("cooked", "done for, finished");
        TOKENS.put("stan", "be a devoted fan");
        TOKENS.put("ratio", "getting out-replied, losing the room");
        TOKENS.put("goated", "the best at it");
        TOKENS.put("bussin", "really good (usually food)");
        TOKENS.put("sapa", "being broke (Naija slang)");
        TOKENS.put("japa", "relocating abroad (Naija slang)");
        TOKENS.put("owo", "money (Yoruba/Pidgin)");
        TOKENS.put("fr", "for real");
        TOKENS.put("ong", "on God — I swear");
        TOKENS.put("lowkey", "kind of, secretly");
        TOKENS.put("highkey", "openly, obviously");
        TOKENS.put("npc", "acting robotic, background-character");
        TOKENS.put("glazing", "praising way too much");
        TOKENS.put("tbh", "to be honest");
        TOKENS.put("ngl", "not gonna lie");
        TOKENS.put("idk", "I don't know");
        TOKENS.put("w", "a win");
        TOKENS.put("l", "a loss");
        PHRASES.put("no cap", "no lie, for real");
        PHRASES.put("iykyk", "if you know you know");
        PHRASES.put("on god", "I swear");
        PHRASES.put("rent free", "can't stop thinking about it");
    }

    /* ------------------------------------------------------------ construction */

    /** Build the snapshot for ONE generation pass.
     *  @param incomingTexts the incoming side of the current conversation window
     *                       (used to match glossary terms; may be null/empty). */
    public static Snapshot build(long nowMs, TimeZone tz, boolean enabled,
                                 List<String> incomingTexts) {
        if (!enabled) {
            return new Snapshot("",
                "live context: switched off in Settings — no clock line, no word help"
                    + " reached the prompt");
        }
        TimeZone zone = tz == null ? TimeZone.getDefault() : tz;
        SimpleDateFormat fmt = new SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.ENGLISH);
        fmt.setTimeZone(zone);
        String when = fmt.format(new java.util.Date(nowMs));
        String zoneShort = zone.getDisplayName(true, TimeZone.SHORT, Locale.ENGLISH);
        if (zoneShort == null || zoneShort.trim().isEmpty()) zoneShort = zone.getID();

        List<String> hits = glossaryHits(incomingTexts, 6);
        StringBuilder line = new StringBuilder("Now (device clock): ")
            .append(when).append(' ').append(zoneShort).append(" (").append(zone.getID())
            .append("). Judge words like \"now\", \"later\", \"tomorrow\", \"next week\"")
            .append(" against this exact moment.");
        StringBuilder why = new StringBuilder("live context: device clock ")
            .append(when).append(' ').append(zoneShort);
        if (!hits.isEmpty()) {
            line.append(" Word help (curated ").append(GLOSSARY_STAMP)
                .append(", not a live lookup): ");
            why.append(" · word help for ").append(join(hits))
               .append(" (curated ").append(GLOSSARY_STAMP).append(", not live)");
            boolean first = true;
            for (String term : hits) {
                if (!first) line.append("; ");
                line.append(term).append(" = ").append(meaning(term));
                first = false;
            }
            line.append('.');
        }
        return new Snapshot(line.toString(), why.toString());
    }

    /** Terms from the bundled glossary that actually appear in the incoming text. */
    static List<String> glossaryHits(List<String> incomingTexts, int cap) {
        List<String> out = new ArrayList<String>();
        if (incomingTexts == null || incomingTexts.isEmpty()) return out;
        StringBuilder all = new StringBuilder();
        for (String t : incomingTexts) {
            if (t != null) all.append(' ').append(t.toLowerCase(Locale.ENGLISH)).append(' ');
        }
        String hay = " " + all.toString().replaceAll("[^\\p{L}']+", " ") + " ";
        Set<String> words = new LinkedHashSet<String>();
        for (String w : hay.trim().split("\\s+")) words.add(w);
        for (Map.Entry<String, String> e : TOKENS.entrySet()) {
            if (out.size() >= cap) break;
            if (words.contains(e.getKey())) out.add(e.getKey());
        }
        for (Map.Entry<String, String> e : PHRASES.entrySet()) {
            if (out.size() >= cap) break;
            if (hay.contains(" " + e.getKey() + " ")) out.add(e.getKey());
        }
        return out;
    }

    private static String meaning(String term) {
        String m = TOKENS.get(term);
        return m != null ? m : PHRASES.get(term);
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(xs.get(i));
        }
        return sb.toString();
    }
}
