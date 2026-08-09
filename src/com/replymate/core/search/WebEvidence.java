package com.replymate.core.search;

import java.util.ArrayList;
import java.util.List;

/** P-intelligence-6: ONE verified fact from live retrieval — title + a short
 *  snippet + its public source label. This is the ONLY shape external knowledge
 *  may take before it reaches the prompt: bounded, sanitized, attributed. */
public final class WebEvidence {
    public final String title;
    public final String snippet;
    /** Public source label ("Wikipedia", "Wiktionary") — honesty, not decoration. */
    public final String source;

    public WebEvidence(String title, String snippet, String source) {
        this.title = clean(title, 80);
        this.snippet = clean(snippet, 220);
        this.source = clean(source, 40);
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String v = s.replaceAll("\\s+", " ").trim();
        return v.length() > max ? v.substring(0, max - 1).trim() + "…" : v;
    }

    /** The line that rides the prompt's situation channel. `cached` tells the
     *  model (and the audit trail) these facts come from the on-device cache
     *  rather than a lookup performed this second — never lie about freshness. */
    public static String promptLine(List<WebEvidence> facts, String subject,
                                    boolean cached) {
        if (facts == null || facts.isEmpty()) return "";
        StringBuilder b = new StringBuilder(cached
            ? "Live facts (cached on-device from a recent lookup — may be days old)"
            : "Live facts (looked up just now)");
        if (subject != null && !subject.trim().isEmpty()) {
            b.append(" about \"").append(subject.trim()).append("\"");
        }
        b.append(": ");
        boolean first = true;
        for (WebEvidence e : facts) {
            if (e == null || e.snippet.isEmpty()) continue;
            if (!first) b.append(" · ");
            first = false;
            b.append(e.snippet);
            if (!e.source.isEmpty()) b.append(" (").append(e.source).append(')');
        }
        String out = b.toString().trim();
        return out.length() > 560 ? out.substring(0, 559).trim() + "…" : out;
    }

    /** Compact audit why-line for one lookup (title + source, never full text). */
    public static List<String> auditOf(List<WebEvidence> facts) {
        List<String> out = new ArrayList<String>();
        if (facts == null) return out;
        for (WebEvidence e : facts) {
            if (e == null) continue;
            out.add((e.title.isEmpty() ? "(untitled)" : e.title)
                + (e.source.isEmpty() ? "" : " — " + e.source));
        }
        return out;
    }
}
