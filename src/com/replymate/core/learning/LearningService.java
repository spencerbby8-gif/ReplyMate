package com.replymate.core.learning;

import com.replymate.core.json.JsonArr;
import com.replymate.core.json.JsonObj;
import com.replymate.core.model.Contact;
import com.replymate.core.model.StyleSignal;
import com.replymate.core.ports.KvStore;
import com.replymate.core.ports.LearningStore;
import com.replymate.core.util.Clock;
import java.util.List;

/** Learning gate + use-cases (P4 owner rules):
 *    - learns from APPROVED / EDITED / REGENERATED / REJECTED replies only;
 *    - NEVER learns for private-mode contacts, contacts with memory off, or when
 *      the per-contact "learning off" switch is set — record() and hintsFor()
 *      share the same gate so an off contact can neither feed nor consume learning;
 *    - PAUSED freezes both recording and hint application but keeps the data;
 *    - reset wipes history; export dumps settings+signals as LOCAL JSON (clipboard /
 *      share sheet — no cloud, ever).
 *  Per-contact switches live in kv: learn.<cid>.off / learn.<cid>.paused ("1"/"0"). */
public final class LearningService {

    /** Bounded derivation window: recent signals dominate, table stays small. */
    public static final int SIGNAL_WINDOW = 500;

    private final LearningStore store;
    private final KvStore kv;
    private final Clock clock;

    public LearningService(LearningStore store, KvStore kv, Clock clock) {
        this.store = store;
        this.kv = kv;
        this.clock = clock;
    }

    /* ------------------------------------------- writing samples (P22 §2)
     *  Owner directive: the personal-writing profile must also learn from
     *  USER-PROVIDED SAMPLES — messages the owner wrote themselves, pasted in
     *  deliberately. They are explicit gold evidence: stored locally in kv
     *  (JSON array per contact, bounded + deduped), read by the style profiler
     *  alongside approved-reply texts. The SAME gate as every other learning
     *  write applies (private mode / memory off / learning off / paused ⇒
     *  refused with a human-readable reason). clearSamples + reset both wipe. */

    public static final int MAX_SAMPLES = 8;
    public static final int MAX_SAMPLE_CHARS = 500;

    private static String samplesKey(long contactId) {
        return "learn." + contactId + ".samples";
    }

    /** The owner's saved writing samples for one contact (oldest-first). */
    public java.util.List<String> samplesFor(long contactId) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        String raw = kv.get(samplesKey(contactId), "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JsonArr arr = com.replymate.core.json.Json.parseArr(raw);
            for (int i = 0; i < arr.size(); i++) {
                String s = arr.str(i);
                if (s != null && !s.trim().isEmpty()) out.add(s);
            }
        } catch (RuntimeException corrupted) {
            kv.delete(samplesKey(contactId));      // never choke generation on bad kv
        }
        return out;
    }

    /** @return null on success, otherwise the human-readable refusal reason. */
    public String addSample(com.replymate.core.model.Contact contact, String text) {
        if (!openFor(contact)) {
            return "Learning is off for this contact (private mode, memory off,"
                + " or the learning switch) — samples would never be used.";
        }
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return "Type or paste a message you wrote first.";
        if (t.length() > MAX_SAMPLE_CHARS) {
            return "Keep each sample under " + MAX_SAMPLE_CHARS + " characters.";
        }
        java.util.List<String> cur = samplesFor(contact.id);
        if (cur.contains(t)) return "That sample is already saved.";
        if (cur.size() >= MAX_SAMPLES) {
            return "Samples are full (" + MAX_SAMPLES + ") — clear samples to add new ones.";
        }
        JsonArr arr = JsonArr.create();
        for (String s : cur) arr.add(s);
        arr.add(t);
        kv.put(samplesKey(contact.id), arr.toJson());
        return null;
    }

    public void clearSamples(long contactId) {
        kv.delete(samplesKey(contactId));
    }

    /* --------------------------------------------------------------- gating */

    public boolean isOff(long contactId) {
        return "1".equals(kv.get(offKey(contactId), "0"));
    }

    public boolean isPaused(long contactId) {
        return "1".equals(kv.get(pauseKey(contactId), "0"));
    }

    public void setOff(long contactId, boolean off) {
        kv.put(offKey(contactId), off ? "1" : "0");
    }

    public void setPaused(long contactId, boolean paused) {
        kv.put(pauseKey(contactId), paused ? "1" : "0");
    }

    /** The ONE gate every learning read/write must pass. */
    public boolean openFor(Contact contact) {
        if (contact == null) return false;
        if (contact.privateMode) return false;          // hard privacy gate
        if (!contact.memoryEnabled) return false;       // memory (and thus learning) off
        if (isOff(contact.id)) return false;            // user disabled learning here
        if (isPaused(contact.id)) return false;         // frozen by user
        return true;
    }

    /* ------------------------------------------------------------- recording */

    /** Record a user decision. No-op when the gate is closed — by design the UI can
     *  call this unconditionally and never leak a signal for a protected contact. */
    public void record(Contact contact, StyleSignal.Kind kind, String detail, Long draftId) {
        if (kind == null || !openFor(contact)) return;
        StyleSignal s = new StyleSignal();
        s.contactId = contact.id;
        s.kind = kind;
        s.detail = detail == null ? "" : detail;
        s.draftId = draftId;
        s.createdAt = clock.now();
        store.insert(s);
    }

    /* ---------------------------------------------------------------- hints */

    public LearningEngine.Counters counters(long contactId) {
        return LearningEngine.count(store.byContact(contactId, SIGNAL_WINDOW));
    }

    /** Derived hints for prompt composition; empty when the gate is closed. */
    public List<LearningEngine.Hint> hintsFor(Contact contact) {
        if (!openFor(contact)) return java.util.Collections.emptyList();
        return LearningEngine.deriveHints(counters(contact.id));
    }

    /* ------------------------------------------------------------ controls */

    /** Learning "reset": history AND provided samples gone; switches stay as
     *  the user left them. (P22: samples are learned data — reset clears them
     *  too, exactly what "reset what has been learned" promises.) */
    public void reset(long contactId) {
        store.deleteForContact(contactId);
        clearSamples(contactId);
    }

    /** LOCAL export (clipboard/share): signals + style settings. Never leaves device
     *  unless the user explicitly pastes/shares it somewhere. */
    public String exportJson(Contact contact, java.util.Map<String, String> globalSettings,
                             java.util.Map<String, String> contactSettings) {
        JsonArr sigs = JsonArr.create();
        for (StyleSignal s : store.byContact(contact.id, SIGNAL_WINDOW)) {
            sigs.add(JsonObj.create()
                .put("ts", s.createdAt)
                .put("kind", s.kind.wire)
                .put("detail", s.detail)
                .put("draft_id", s.draftId));
        }
        JsonObj settings = JsonObj.create();
        if (globalSettings != null) {
            for (java.util.Map.Entry<String, String> e : globalSettings.entrySet()) {
                settings.put("global." + e.getKey(), e.getValue());
            }
        }
        if (contactSettings != null) {
            for (java.util.Map.Entry<String, String> e : contactSettings.entrySet()) {
                settings.put("contact." + e.getKey(), e.getValue());
            }
        }
        LearningEngine.Counters c = counters(contact.id);
        return JsonObj.create()
            .put("type", "replymate-learning-export")
            .put("contact", contact.displayName)
            .put("exported_at", clock.now())
            .put("counters", JsonObj.create()
                .put("approved", c.approved).put("edited", c.edited)
                .put("regenerated", c.regenerated).put("rejected", c.rejected))
            .put("settings", settings)
            .put("signals", sigs)
            .toJson();
    }

    private static String offKey(long contactId) { return "learn." + contactId + ".off"; }
    private static String pauseKey(long contactId) { return "learn." + contactId + ".paused"; }
}
