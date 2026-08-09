package com.replymate.core.live;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/** LIVE CONTEXT — device temporal truth, wired once per GENERATION (never per
 *  message): the actual date, time and timezone of THIS phone, so "later",
 *  "tomorrow", "next Friday" are judged against a real moment, not a
 *  training-data calendar. Sources: the injected Clock + java.util.TimeZone.
 *
 *  P-intelligence-6 (directives 2/7): the STATIC CURATED GLOSSARY IS REMOVED —
 *  anything word/meme/slang/meaning-related is now automatic live search
 *  (SearchGate + native provider search / encyclopedia fallback), which is
 *  genuinely current instead of stamped-and-stale. The device clock stays: it is
 *  free, exact, offline, and the right live capability for the device's own
 *  time-of-day. Everything degrades gracefully; generation never depends on
 *  this module. */
public final class LiveContext {

    private LiveContext() { }

    /** Settings toggle (default ON) — SettingsActivity writes, DraftService reads. */
    public static final String KV_ENABLED = "livectx.enabled";

    /** What the prompt receives + what Prompt Audit credits. */
    public static final class Snapshot {
        public final String promptLine;   // "" when disabled
        public final String whyLine;      // always present (honest in both states)
        Snapshot(String promptLine, String whyLine) {
            this.promptLine = promptLine;
            this.whyLine = whyLine;
        }
    }

    /** Build the snapshot for ONE generation pass.
     *  @param incomingTexts unused since 1.5.6 (kept for signature compatibility) */
    public static Snapshot build(long nowMs, TimeZone tz, boolean enabled,
                                 java.util.List<String> incomingTexts) {
        if (!enabled) {
            return new Snapshot("",
                "live context: switched off in Settings — no device-clock line"
                    + " reached the prompt");
        }
        TimeZone zone = tz == null ? TimeZone.getDefault() : tz;
        SimpleDateFormat fmt = new SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.ENGLISH);
        fmt.setTimeZone(zone);
        String when = fmt.format(new java.util.Date(nowMs));
        String zoneShort = zone.getDisplayName(true, TimeZone.SHORT, Locale.ENGLISH);
        if (zoneShort == null || zoneShort.trim().isEmpty()) zoneShort = zone.getID();

        String line = "Now (device clock): " + when + ' ' + zoneShort + " (" + zone.getID()
            + "). Judge words like \"now\", \"later\", \"tomorrow\", \"next week\""
            + " against this exact moment.";
        String why = "live context: device clock " + when + ' ' + zoneShort;
        return new Snapshot(line, why);
    }
}
