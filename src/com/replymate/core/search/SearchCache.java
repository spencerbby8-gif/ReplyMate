package com.replymate.core.search;

import com.replymate.core.json.Json;
import com.replymate.core.json.JsonArr;
import com.replymate.core.json.JsonObj;
import com.replymate.core.ports.KvStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** P-intelligence-6: on-device cache for live lookups — one normalised subject →
 *  bounded evidence + timestamp, 7-day TTL (same honest envelope the P5 term
 *  cache used, now shared by native and fallback search). A repeat look for the
 *  same subject inside a week costs ZERO and says "cached" in the audit. */
public final class SearchCache {

    public static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private SearchCache() { }

    public static String normalize(String subject) {
        return subject == null ? "" : subject.toLowerCase(Locale.US).trim()
            .replaceAll("\\s+", " ").replaceAll("[^\\p{L}0-9'\\- ]+", "");
    }

    static String key(String normalizedSubject) {
        return "search.v1." + normalizedSubject;
    }

    /** Fresh cached evidence, or null (malformed rows read as a miss, never a crash). */
    public static List<WebEvidence> get(KvStore kv, String subject, long nowMs) {
        if (kv == null || subject == null || subject.trim().isEmpty()) return null;
        String raw = kv.get(key(normalize(subject)), "");
        if (raw.isEmpty()) return null;
        try {
            JsonObj o = Json.parseObj(raw);
            long at = o.lng("at", 0);
            if (nowMs - at > TTL_MS) return null;
            List<WebEvidence> out = new ArrayList<WebEvidence>();
            JsonArr arr = o.arr("f");
            if (arr == null || arr.size() == 0) return null;
            for (int i = 0; i < arr.size() && i < 2; i++) {
                JsonObj e = arr.obj(i);
                if (e == null) continue;
                out.add(new WebEvidence(e.str("t"), e.str("s"), e.str("src")));
            }
            return out.isEmpty() ? null : out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void put(KvStore kv, String subject, List<WebEvidence> facts,
                           long nowMs) {
        if (kv == null || subject == null || subject.trim().isEmpty()
                || facts == null || facts.isEmpty()) return;
        JsonArr arr = JsonArr.create();
        for (WebEvidence e : facts) {
            if (e == null) continue;
            arr.add(JsonObj.create()
                .put("t", e.title).put("s", e.snippet).put("src", e.source));
        }
        kv.put(key(normalize(subject)),
            JsonObj.create().put("q", subject.trim()).put("at", nowMs)
                .put("f", arr).toJson());
    }
}
