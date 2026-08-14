package com.replymate.core.memory;

import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.prompt.PromptBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** P-intelligence-13: M5 — RELEVANT HISTORY RETRIEVAL. The rolling summary is
 *  budgeted (700 chars) and salience-ranked, so a months/years-old fact can age
 *  out of it even while it is still THE fact a new question needs. This layer
 *  scans the older-than-hot-window pool and lifts ONLY the messages that share
 *  the new message's topic — never the whole history, which stays out of the
 *  prompt on every request.
 *
 *  100% local + deterministic (no AI call, no cost): the same pool + the same
 *  query always produce the same hits, so Prompt Audit renderings are
 *  reproducible and tests can pin exact behavior.
 *
 *  Scoring (documented, deliberately simple):
 *    - shared content tokens: len ≥ 6 → 3 · len 4–5 → 2 · digits → 3 · else 1;
 *      everyday stopwords (English + Nigerian Pidgin fillers) never score;
 *    - a 3-token consecutive phrase from the query found verbatim: +5;
 *    - correction markers ("moved", "relocated", "no more", "instead", …): +4
 *      on top of any topical overlap — a later correction MUST outrank the old
 *      fact it amends;
 *    - admission gate: score ≥ MIN_SCORE, so small talk can never ride in.
 *
 *  Currency rule: hits render newest-first; the newest hit carries the
 *  authoritative label and older ones are labeled "(earlier)" — together with
 *  the VoiceCharter's "NEWEST one wins" rule, a newer correction supersedes
 *  old facts by construction. Every line is timestamped so currency is
 *  machine-checkable. Isolation: the caller's pool is strictly one contact's. */
public final class HistoryRetriever {

    /** Older-than-window messages scanned per generation (deep enough for years). */
    public static final int POOL_LIMIT = 800;
    /** Older messages lifted into one prompt, at most. */
    public static final int MAX_HITS = 3;
    /** Character budget for the whole retrieval block inside memory lines. */
    public static final int CHAR_BUDGET = 460;
    /** One retrieved line is flattened + trimmed to this length. */
    public static final int LINE_MAX = 140;
    /** Admission gate: below this score a candidate is noise. */
    public static final int MIN_SCORE = 4;

    /** Words that never carry a topic (everyday English + Nigerian-English filler). */
    private static final Set<String> STOP = new HashSet<String>();
    static {
        String[] w = {
            "the","a","an","and","or","but","if","you","your","yours","youre","you've",
            "i","me","my","mine","we","our","us","they","them","their","he","she","it",
            "its","is","are","was","were","be","been","being","to","of","in","on","at",
            "for","with","as","by","from","that","this","these","those","there","here",
            "what","when","where","which","who","whom","how","why","do","does","did",
            "done","not","no","yes","so","too","very","just","can","cant","could",
            "will","would","wont","should","have","has","had","im","ive","id","lets",
            "let","got","get","gets","go","going","gone","come","coming","came","still",
            "again","now","then","than","about","into","over","under","up","down","out",
            "off","all","any","some","every","each","much","many","more","most","less",
            "few","also","even","really","actually","please","pls","plz","okay","ok",
            "lol","lmao","haha","hehe","wow","yeah","yep","nah","nope","hmm","oh",
            // Nigerian English / Pidgin fillers
            "abeg","sha","sef","abi","dey","na","nah","oo","o","ehn","gan","small",
            "wey","wetin","wahala","omo","nna","biko","kwanu","shey","tori","matter",
            "gist","para","sapa","jollof","omenala","fa","ba","ni","ti","si","wa",
            "kan","ko","fun","nkan","padi","body","day","days","time","thing","things",
            "something","anything","everything","someone","anyone","send","sent","tell",
            "told","say","said","see","saw","know","knew","think","want","need","like",
            "make","made","take","took","give","gave","put","way","back","well","good",
            "bad","fine","nice","great","big","new","old","first","last","next","one",
            "two","dem","una","e","una","shebi","pele","sister","brother","bro","sis",
        };
        for (String s : w) STOP.add(s);
    }

    /** Later-correction language. Any hit carrying one of these AND topical overlap
     *  outranks the older statement it amends (+4). */
    private static final String[] CORRECTIONS = {
        "moved", "relocate", "relocated", "don move", "changed", "change of",
        "no more", "no longer", "instead", "updated", "update:", "new address",
        "now at", "now use", "correction", "switched", "i now ", "we now ",
        "not that", "scratch that", "forget that", "i meant", "meaning ",
        "don change", "don move", "don relocate", "e don", "i don change"
    };

    private HistoryRetriever() { }

    /** One retrieved older message (timestamp + label added at render time). */
    public static final class Hit {
        public Message message;
        public int score;
        public boolean isNewestHit;      // authoritative current value
        public boolean correction;       // carried correction language
    }

    /** Deterministically pick the older messages a new query actually needs.
     *  @param olderOldestFirst strictly ONE contact's older-than-window messages
     *  @param queryTexts newest incoming text(s) of the active burst
     *  @return hits sorted DISPLAY-oldest-first? No — caller renders; this returns
     *          NEWEST first so position 0 is the authoritative hit. */
    public static List<Hit> retrieve(List<Message> olderOldestFirst,
                                     List<String> queryTexts) {
        List<Hit> out = new ArrayList<Hit>();
        if (olderOldestFirst == null || olderOldestFirst.isEmpty()
                || queryTexts == null || queryTexts.isEmpty()) return out;

        Set<String> qTokens = new HashSet<String>();
        List<String> qShingles = new ArrayList<String>();
        for (String q : queryTexts) {
            List<String> toks = tokens(q);
            for (String t : toks) if (!STOP.contains(t)) qTokens.add(t);
            shingles(toks, qShingles);
        }
        if (qTokens.isEmpty()) return out;   // pure small talk — never retrieve

        List<Message> pool = olderOldestFirst;
        int from = Math.max(0, pool.size() - POOL_LIMIT);
        long newestSent = Long.MIN_VALUE;
        List<Hit> scored = new ArrayList<Hit>();
        for (int i = from; i < pool.size(); i++) {
            Message m = pool.get(i);
            if (m == null) continue;
            String body = flat(m.body);
            if (body.isEmpty() || !PromptBuilder.usableText(body)) continue;
            List<String> toks = tokens(body);
            if (toks.size() < 3) continue;
            Set<String> mSet = new HashSet<String>(toks);
            int score = 0;
            int overlap = 0;
            for (String qt : qTokens) {
                if (mSet.contains(qt)) { score += weight(qt); overlap++; }
            }
            if (overlap == 0) continue;
            String lower = body.toLowerCase(Locale.US);
            for (String sh : qShingles) {
                if (sh.length() >= 8 && lower.contains(sh)) { score += 5; break; }
            }
            boolean correction = false;
            for (String cword : CORRECTIONS) {
                if (lower.contains(cword)) { correction = true; break; }
            }
            if (correction) score += 4;
            if (score < MIN_SCORE) continue;
            Hit h = new Hit();
            h.message = m;
            h.score = score;
            h.correction = correction;
            scored.add(h);
            if (m.sentAt > newestSent) newestSent = m.sentAt;
        }
        // rank: score desc, then NEWER first (stable sorts only)
        java.util.Collections.sort(scored, new java.util.Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                if (a.score != b.score) return b.score - a.score;
                if (a.message.sentAt != b.message.sentAt) {
                    return a.message.sentAt > b.message.sentAt ? -1 : 1;
                }
                return a.message.id < b.message.id ? -1 : 1;
            }
        });
        int n = Math.min(MAX_HITS, scored.size());
        for (int i = 0; i < n; i++) {
            Hit h = scored.get(i);
            h.isNewestHit = h.message.sentAt == newestSent;
            out.add(h);
        }
        return out;
    }

    /** Render ONE hit as a memory line: timestamped, attributed, currency-labeled. */
    public static String render(Hit h, String partnerName) {
        String who = h.message.direction == Direction.OUTGOING ? "you"
            : (h.message.senderName != null && !h.message.senderName.trim().isEmpty()
                ? h.message.senderName.trim()
                : (partnerName == null || partnerName.trim().isEmpty()
                    ? "them" : partnerName.trim()));
        String body = flat(h.message.body);
        if (body.length() > LINE_MAX) body = body.substring(0, LINE_MAX - 1) + "…";
        return com.replymate.core.util.TimeFmt.dayTime(h.message.sentAt)
            + (h.isNewestHit ? " — latest on this, " : " — earlier, ")
            + who + ": \u201C" + body + "\u201D";
    }

    private static int weight(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (Character.isDigit(token.charAt(i))) return 3;
        }
        int len = token.length();
        if (len >= 6) return 3;
        if (len >= 4) return 2;
        return 1;
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        String lower = s == null ? "" : s.toLowerCase(Locale.US);
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                cur.append(ch);
            } else if (cur.length() > 0) {
                push(out, cur);
            }
        }
        if (cur.length() > 0) push(out, cur);
        return out;
    }

    private static void push(List<String> out, StringBuilder cur) {
        String t = cur.toString();
        cur.setLength(0);
        boolean digit = false;
        for (int i = 0; i < t.length(); i++) {
            if (Character.isDigit(t.charAt(i))) { digit = true; break; }
        }
        if (t.length() < 3 && !digit) return;
        out.add(t);
    }

    private static void shingles(List<String> toks, List<String> out) {
        for (int i = 0; i + 2 < toks.size(); i++) {
            String a = toks.get(i), b = toks.get(i + 1), c = toks.get(i + 2);
            if (STOP.contains(a) && STOP.contains(b) && STOP.contains(c)) continue;
            out.add(a + " " + b + " " + c);
        }
    }

    private static String flat(String s) {
        return s == null ? "" : s.replace('\n', ' ').trim();
    }
}
