package com.replymate.core.convo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** P-intelligence-16b: mechanical TOPIC reading over a burst (pure; test-pinned).
 *  No semantic guessing: topic = the burst's dominant content terms (frequency,
 *  stopword-filtered, ≥4-char tokens). A topic CHANGE is declared only when the
 *  new dominant-term set shares less than 1/3 overlap with the previous one —
 *  anything softer stays "same topic". The ACTIVE SUBTOPIC is the newest line's
 *  own dominant term when it differs from the burst topic. Labels are honest
 *  keyword summaries rendered for the model, never invented concepts. */
public final class TopicTracker {

    private static final Set<String> STOP = new LinkedHashSet<String>();
    static {
        String[] w = {
            "this","that","with","have","will","would","should","could","about","there",
            "here","what","when","where","which","from","your","yours","you","the","and",
            "for","are","was","were","been","being","they","them","their","then","than",
            "just","also","still","really","very","some","any","all","one","two","dey",
            "don","like","know","think","want","need","going","come","came","back","well",
            "okay","ok","yes","yeah","lol","haha","abeg","howfar","omo","sha","shey",
            "please","thanks","thank","sorry","hello","what's","lets","let","sure","good",
        };
        for (String s : w) STOP.add(s);
    }

    private TopicTracker() { }

    /** Top content terms of the given lines, most-frequent first (stable order). */
    public static List<String> topTerms(List<String> lines, int max) {
        Map<String, Integer> freq = new HashMap<String, Integer>();
        List<String> order = new ArrayList<String>();
        if (lines != null) {
            for (String line : lines) {
                if (line == null) continue;
                Set<String> seenInLine = new LinkedHashSet<String>();
                for (String tok : line.toLowerCase().split("[^a-z0-9']+")) {
                    if (tok.length() < 4 || STOP.contains(tok) || seenInLine.contains(tok)) {
                        continue;
                    }
                    seenInLine.add(tok);
                    Integer n = freq.get(tok);
                    if (n == null) { freq.put(tok, 1); order.add(tok); }
                    else freq.put(tok, n + 1);
                }
            }
        }
        List<String> terms = new ArrayList<String>(order);
        final Map<String, Integer> f = freq;
        final Map<String, Integer> firstIdx = new HashMap<String, Integer>();
        for (int i = 0; i < order.size(); i++) {
            if (!firstIdx.containsKey(order.get(i))) firstIdx.put(order.get(i), i);
        }
        java.util.Collections.sort(terms, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                int fa = f.get(a), fb = f.get(b);
                if (fa != fb) return fb - fa;                       // frequency desc
                return firstIdx.get(a) - firstIdx.get(b);           // first-seen asc
            }
        });
        return terms.size() <= max ? terms : terms.subList(0, max);
    }

    /** Human label for a topic: the top term, or "a, b" when the second is tied. */
    public static String label(List<String> terms) {
        if (terms == null || terms.isEmpty()) return "";
        return terms.size() == 1 ? terms.get(0) : terms.get(0) + ", " + terms.get(1);
    }

    /** ≥1/3 term-set overlap ⇒ SAME topic (true). Either side empty ⇒ not "same". */
    public static boolean sameTopic(List<String> prevTerms, List<String> newTerms) {
        if (prevTerms == null || prevTerms.isEmpty()
                || newTerms == null || newTerms.isEmpty()) return false;
        int overlap = 0;
        for (String t : newTerms) if (prevTerms.contains(t)) overlap++;
        int denom = Math.min(prevTerms.size(), newTerms.size());
        return denom > 0 && overlap * 3 >= denom;
    }

    /** The newest line's own top term when it is NOT one of the burst topic terms —
     *  the active subtopic ("tickets" inside a "match" topic). "" when none. */
    public static String subtopic(String newestLine, List<String> burstTerms) {
        return subtopic(newestLine, burstTerms, null);
    }

    /** Exclusion-aware form: PEOPLE are not topics — owner/participant/group-name
     *  tokens must never surface as a subtopic (names enter the newest line all
     *  the time: "Spencer, the ticketing site…"). */
    public static String subtopic(String newestLine, List<String> burstTerms,
                                  Set<String> excluded) {
        List<String> own = new ArrayList<String>();
        own.add(newestLine == null ? "" : newestLine);
        // walk ALL own terms, first-seen order, skipping excluded names
        List<String> terms = topTerms(own, 8);
        for (String t : terms) {
            if (excluded != null && excluded.contains(t)) continue;
            if (burstTerms != null && burstTerms.contains(t)) continue;
            return t;
        }
        return "";
    }

    /** Raw content tokens of a person/group label ("Chidi 2" → chidi — the
     *  numbering is ours, not conversation content). */
    public static void addNameTokens(Set<String> into, String label) {
        if (into == null || label == null) return;
        for (String tok : label.toLowerCase().split("[^a-z0-9']+")) {
            if (tok.length() >= 3 && !STOP.contains(tok)) into.add(tok);
        }
    }
}
