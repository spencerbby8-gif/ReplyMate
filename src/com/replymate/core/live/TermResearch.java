package com.replymate.core.live;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.core.ports.KvStore;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** P-intelligence-5 (directive 1 — LIVE RESEARCH, researched boundary): an OPTIONAL,
 *  need-triggered provider lookup for the meaning of a term (slang, abbreviation,
 *  current-day usage). It is NOT web search and never pretends to be one:
 *
 *  Research notes (official docs, 2026-08-08): true web grounding is a
 *  provider-specific billed tool (Gemini's "Grounding with Google Search" bills
 *  per executed query / per prompt beyond a small allowance and exists only on
 *  the Gemini wire; OpenAI/Claude have their own tool APIs), and free-tier data
 *  may train the provider's models. ReplyMate's BYOK chat layer deliberately does
 *  NOT wire provider tools: a per-message search burst would burn free-tier RPM
 *  (5–15) on mobile, sink privacy, and fake "live" for every other provider.
 *  THIS lookup uses the model's own reasonably-current knowledge instead:
 *    · runs ONLY on an EXPLICIT information need ("what does X mean"-style asks
 *      — see {@link #detectTerm}'s doc for why implicit slang-scanning is a
 *      documented NOT-NOW, never faked);
 *    · caches meanings 7 days in kv (repeat terms cost nothing, instant offline);
 *    · ONE tiny extra provider call per NEW term (metered like every AI call);
 *    · failure NEVER blocks the reply — the audit line says it continued without;
 *    · the bundled LiveContext glossary always wins first (a lookup for a term we
 *      already know is money burned). */
public final class TermResearch {

    private TermResearch() { }

    /** Settings toggle (default OFF — optional means off until asked). */
    public static final String KV_ENABLED = "research.enabled";
    public static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;

    /* ------------------------------------------------------------ trigger ---- */

    /** Words that look like asks but carry no definable term — consulted ONLY
     *  after an ask-lead was found, so generous is safe. */
    private static final Set<String> ASK_GENERIC = new LinkedHashSet<String>();
    static {
        Collections.addAll(ASK_GENERIC, new String[] {
            "up","this","that","it","its","wrong","happening","the","matter","going",
            "on","you","happened","mean","means","meant","to","be","being","been",
            "do","does","did","doing","done","so","if","in","at","by","my","mine",
            "your","yours","their","theirs","our","ours","his","her","hers","him",
            "them","us","we","i","me","he","she","they","is","are","was","were",
            "am","a","an","and","or","not","no","yes","dey","una","am mean"
        });
    }

    /** The ONE term worth a lookup in this conversation slice, or null.
     *
     *  TRIGGER = EXPLICIT INFORMATION NEED ONLY ("what does rizz mean",
     *  "wym 'no cap'", "what is 'UI'?"). An unknown-LOOKING word is deliberately
     *  NOT a trigger: without a full offline dictionary, ordinary English
     *  ("an interview", "a delivery") would false-fire and bill the owner for
     *  nothing — the directive says ordinary replies must never trigger a lookup,
     *  and the bundled LiveContext glossary already covers ambient slang at zero
     *  cost. (Researched boundary: a real implicit-slang detector needs a full
     *  lexicon — documented, not faked.) */
    public static String detectTerm(List<String> incomingTexts,
                                    List<String> outgoingTexts,
                                    List<String> knownNamesLower) {
        if (incomingTexts == null || incomingTexts.isEmpty()) return null;
        // Any candidate covered by the bundled glossary means "this ask is already
        // answered locally at zero cost" — remember that and keep scanning for a
        // genuinely unknown one; helper words ("bro", "even", "now") are never
        // promoted to lookup terms while a real ask exists.
        boolean glossaryHandled = false;
        for (int i = incomingTexts.size() - 1; i >= 0; i--) {
            String t = incomingTexts.get(i);
            if (t == null || !ASK_LEAD.matcher(t).find()) continue;
            java.util.regex.Matcher q = ASK_QUOTED.matcher(t);
            while (q.find()) {
                String cand = clean(q.group(1));
                if (cand == null || ASK_GENERIC.contains(cand)) continue;
                if (LiveContext.glossaryMeaning(cand) != null) { glossaryHandled = true; continue; }
                return cand;
            }
            java.util.regex.Matcher m = ASK_WORD.matcher(t);
            while (m.find()) {
                String cand = clean(m.group(1));
                if (cand == null || ASK_GENERIC.contains(cand)) continue;
                if (LiveContext.glossaryMeaning(cand) != null) { glossaryHandled = true; continue; }
                if (glossaryHandled && ASK_FOLLOWUP_JUNK.contains(cand)) continue;
                return cand;   // a person asked what it means — always a real need
            }
        }
        return null;
    }

    /** Tail-of-ask filler that only looks like a term after a REAL candidate was
     *  skipped ("what does rizz mean bro", "…aura mean now?"). */
    private static final Set<String> ASK_FOLLOWUP_JUNK = new LinkedHashSet<String>();
    static {
        Collections.addAll(ASK_FOLLOWUP_JUNK, new String[] {
            "bro","sis","even","really","actually","mean","means","meant","now",
            "today","abeg","sha","sef","na","ehn","exactly","please","pls","then",
            "again","first","self","own","thing","one","like","tho","though","tbh"
        });
    }

    private static final java.util.regex.Pattern ASK_LEAD = java.util.regex.Pattern.compile(
        "(?i)\\b(?:what does|what's|whats|what is|what are|wym|wdym|meaning of|means?)\\b");
    private static final java.util.regex.Pattern ASK_QUOTED = java.util.regex.Pattern.compile(
        "(?i)[\"'“]([\\p{L}][\\p{L}'\\- ]{1,30}?)[\"'”]");
    private static final java.util.regex.Pattern ASK_WORD = java.util.regex.Pattern.compile(
        "(?i)\\b(?:what does|what's|whats|what is|what are|wym|wdym|meaning of|means?)"
            + "\\s+(?:the word\\s+|the term\\s+)?([\\p{L}][\\p{L}'\\-]{1,20})\\b");

    private static String clean(String raw) {
        if (raw == null) return null;
        String v = raw.toLowerCase(Locale.US).trim()
            .replaceAll("\\s+", " ").replaceAll("[^\\p{L}'\\- ]+", "");
        return v.length() >= 2 ? v : null;
    }

    /* ------------------------------------------------------------ cache ------ */

    /** Cached meaning when fresh (&lt; TTL), else null. Malformed rows read as miss. */
    public static String cached(KvStore kv, String term, long nowMs) {
        if (kv == null || term == null) return null;
        try {
            com.replymate.core.json.JsonObj o = com.replymate.core.json.Json.parseObj(
                kv.get(cacheKey(term), ""));
            String meaning = o.str("m", "");
            long at = o.lng("at", 0L);
            if (meaning.isEmpty() || at <= 0L) return null;
            return nowMs - at <= TTL_MS ? meaning : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void store(KvStore kv, String term, String meaning, long nowMs) {
        if (kv == null || term == null || meaning == null || meaning.trim().isEmpty()) {
            return;
        }
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("m", meaning.trim());
        m.put("at", Long.valueOf(nowMs));
        kv.put(cacheKey(term), com.replymate.core.json.Json.write(m));
    }

    static String cacheKey(String term) {
        return "research.v1." + term.toLowerCase(Locale.US).trim();
    }

    /* ------------------------------------------------------------ lookup ----- */

    /** The tiny, cheap lookup request (1 candidate, low temperature, capped output —
     *  researched cost discipline: this is the ONLY extra call live research can
     *  ever make, and only on a cache miss for a genuinely needed term). */
    public static ChatRequest lookupRequest(String term, String contextLine) {
        String ctx = contextLine == null ? "" : contextLine.trim();
        if (ctx.length() > 160) ctx = ctx.substring(0, 160);
        String system = "You are a one-line dictionary inside a private chat app."
            + " Define the slang, abbreviation or word below the way people use it"
            + " right now, in 12 plain words or fewer. If you genuinely don't know"
            + " it, answer exactly: unsure. Output only the definition — no quotes,"
            + " no preamble, no disclaimers.";
        String task = "Term: \"" + term + "\""
            + (ctx.isEmpty() ? "" : "\nSeen in a chat: \"" + ctx + "\"");
        return new ChatRequest(system, Collections.<Turn>emptyList(),
            Turn.user(task), GenerationOpts.of(1, 0.2, 60));
    }

    /** The prompt line injected into the real generation once a meaning exists. */
    public static String promptLine(String term, String meaning) {
        return "Word help (researched on-device, cached): \"" + term + "\" means: "
            + meaning + ".";
    }

    /** Audit lines — every research outcome is credited honestly. */
    public static String whyOff(String term) {
        return "live research: '" + term + "' looked like it needed a lookup, but"
            + " Live Research is off in Settings — reply made without it";
    }
    public static String whyCached(String term) {
        return "live research: '" + term + "' meaning from the 7-day cache"
            + " (no provider call)";
    }
    public static String whyLookedUp(String term) {
        return "live research: '" + term + "' looked up once with your AI provider"
            + " — cached for 7 days";
    }
    public static String whyFailed(String term, String reason) {
        String r = reason == null ? "no reason given" : reason;
        if (r.length() > 80) r = r.substring(0, 80);
        return "live research: lookup for '" + term + "' failed (" + r
            + ") — reply made without it";
    }
    public static String whyUnsure(String term) {
        return "live research: the provider couldn't define '" + term
            + "' (answered unsure) — reply made without it";
    }
}
