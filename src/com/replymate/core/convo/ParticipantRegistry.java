package com.replymate.core.convo;

import com.replymate.core.json.JsonArr;
import com.replymate.core.json.JsonObj;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** P-intelligence-16b: the per-conversation PARTICIPANT REGISTRY. Learns who is in
 *  a group from the messages it sees (feed with stored thread rows), keeps stable
 *  ids across sessions (kv JSON), refines display names, and guarantees that two
 *  people who share a name can never be confused in a prompt. Pure JVM. */
public final class ParticipantRegistry {

    private final Map<String, Participant> byId = new LinkedHashMap<String, Participant>();

    /** Stable id for one sender: native Person key &gt; Person uri &gt; name fallback.
     *  Returns "" when the platform exposed NOTHING (never invent an identity). */
    public static String stableIdFor(String key, String uri, String name) {
        if (key != null && !key.trim().isEmpty()) return "k:" + key.trim();
        if (uri != null && !uri.trim().isEmpty()) return "u:" + uri.trim();
        if (name != null && !name.trim().isEmpty()) return "n:" + name.trim().toLowerCase();
        return "";
    }

    /** Record one utterance; returns the participant (null when no identity at all —
     *  sender-less entries are system chrome and must not create phantom members). */
    public Participant observe(String key, String uri, String name, long tsMs) {
        String id = stableIdFor(key, uri, name);
        if (id.isEmpty()) return null;
        Participant p = byId.get(id);
        // name-fallback ids are weak: if the same person LATER arrives with a native
        // key while a "n:&lt;samename&gt;" id exists, keep both — the keyed id is the
        // stronger truth; the weak one simply stops growing. Never merge silently.
        if (p == null) {
            p = new Participant(id, name, tsMs);
            byId.put(id, p);
        } else {
            p.saw(name, tsMs);
        }
        return p;
    }

    public Participant get(String stableId) { return byId.get(stableId); }
    public int size() { return byId.size(); }
    public List<Participant> all() { return new ArrayList<Participant>(byId.values()); }

    /** Deterministic display label: plain name for unique-name members; "Name 2/3…"
     *  for same-name members in first-seen order. The FIRST same-name member keeps
     *  the plain name. */
    public String labelFor(String stableId) {
        Participant self = byId.get(stableId);
        if (self == null) return "";
        List<Participant> same = new ArrayList<Participant>();
        for (Participant p : byId.values()) {
            if (!p.stableId.equals(stableId)
                    && p.displayName.equalsIgnoreCase(self.displayName)) {
                same.add(p);
            }
        }
        if (same.isEmpty()) return self.displayName;
        // collision: rank everyone (incl. self) by firstSeen, stableId tiebreak
        List<Participant> group = new ArrayList<Participant>(same);
        group.add(self);
        java.util.Collections.sort(group, new java.util.Comparator<Participant>() {
            @Override public int compare(Participant a, Participant b) {
                if (a.firstSeenMs != b.firstSeenMs) {
                    return a.firstSeenMs < b.firstSeenMs ? -1 : 1;
                }
                return a.stableId.compareTo(b.stableId);
            }
        });
        int rank = group.indexOf(self);
        return rank <= 0 ? self.displayName : self.displayName + " " + (rank + 1);
    }

    /** True when at least one same-name collision exists (prompt must disambiguate). */
    public boolean hasCollision() {
        List<Participant> all = all();
        for (int i = 0; i < all.size(); i++) {
            Participant a = all.get(i);
            String lbl = labelFor(a.stableId);
            if (!lbl.equalsIgnoreCase(a.displayName)) return true;
        }
        return false;
    }

    // ---------------- persistence (kv JSON) ----------------

    public String toJson() {
        JsonArr arr = new JsonArr();
        for (Participant p : byId.values()) {
            Map<String, Object> o = new LinkedHashMap<String, Object>();
            o.put("id", p.stableId);
            o.put("name", p.displayName);
            o.put("first", p.firstSeenMs);
            o.put("last", p.lastSpokeMs);
            o.put("count", (long) p.msgCount);
            arr.add(o);
        }
        return arr.toJson();
    }

    public static ParticipantRegistry fromJson(String json) {
        ParticipantRegistry r = new ParticipantRegistry();
        if (json == null || json.trim().isEmpty()) return r;
        try {
            JsonArr arr = com.replymate.core.json.Json.parseArr(json);
            for (int i = 0; i < arr.size(); i++) {
                JsonObj o = arr.obj(i);
                if (o == null || !o.has("id")) continue;
                Participant p = new Participant(o.str("id", ""), o.str("name", ""),
                    o.lng("first", 0L));
                p.lastSpokeMs = o.lng("last", p.firstSeenMs);
                p.msgCount = (int) o.lng("count", 1L);
                if (!p.stableId.isEmpty()) r.byId.put(p.stableId, p);
            }
        } catch (RuntimeException malformed) {
            // a corrupt blob must never kill the pipeline — start fresh, honestly empty
            return new ParticipantRegistry();
        }
        return r;
    }
}
