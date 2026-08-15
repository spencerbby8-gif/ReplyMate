package com.replymate.core.usecase;

import com.replymate.core.convo.ConversationState;
import com.replymate.core.convo.Engagement;
import com.replymate.core.convo.EngagementClassifier;
import com.replymate.core.convo.ParticipantRegistry;
import com.replymate.core.convo.TopicTracker;
import com.replymate.core.json.JsonObj;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.ports.KvStore;
import com.replymate.core.util.Clock;
import java.util.ArrayList;
import java.util.List;

/** P-intelligence-16b: orchestrates the ConversationState engine over the STORED
 *  thread (per-contact isolation holds — every read is contact-scoped). Builds the
 *  state (participants, burst, topic), persists the learnables in kv, and runs the
 *  engagement classifier. Pure JVM aside from the kv/clock ports — everything here
 *  is unit-tested through Fakes. */
public final class ConversationStateService {

    public static final String KV_REG = "convo.registry.";   // + contactId → participants JSON
    public static final String KV_TOPIC = "convo.topic.";    // + contactId → topic JSON
    public static final String KV_WAIT = "convo.wait.";      // + contactId → content hash deferred once
    public static final String KV_SKIP = "convo.skip.";      // + contactId → "hash|VERDICT:reason"
    public static final String KV_LAST = "convo.last.";      // + contactId → "VERDICT|reason|targetLabel"

    private static final int BURST_CAP = 6;
    private static final int SPEAKER_CAP = 6;

    private final KvStore kv;
    private final Clock clock;

    public ConversationStateService(KvStore kv, Clock clock) {
        this.kv = kv;
        this.clock = clock;
    }

    /** State + verdict in one handoff. */
    public static final class Evaluation {
        public final ConversationState state;
        public final Engagement engagement;
        public Evaluation(ConversationState s, Engagement e) { state = s; engagement = e; }
    }

    /** Build the conversation's current state from the stored thread; learn and
     *  persist participants + topic as a side effect (kv, contact-scoped). */
    public ConversationState build(Contact c, List<Message> thread, String ownerName) {
        long now = clock.now();
        ParticipantRegistry reg =
            ParticipantRegistry.fromJson(kv.get(KV_REG + c.id, ""));
        boolean regDirty = false;

        Message lastOut = null;
        List<Message> incoming = new ArrayList<Message>();
        if (thread != null) {
            for (Message m : thread) {
                if (m == null) continue;
                if (m.direction == Direction.OUTGOING && usable(m.body)) {
                    lastOut = m;                       // loop ends at newest
                } else if (m.direction == Direction.INCOMING && usable(m.body)) {
                    incoming.add(m);
                    int before = reg.size();
                    reg.observe(m.senderKey, null, m.senderName, m.sentAt);
                    if (reg.size() != before) regDirty = true;
                }
            }
        }
        String regJson = reg.toJson();
        if (regDirty || !regJson.equals(kv.get(KV_REG + c.id, ""))) {
            kv.put(KV_REG + c.id, regJson);            // name refinements persist too
        }

        long outAt = lastOut == null ? 0L : lastOut.sentAt;
        List<ConversationState.Line> burst = new ArrayList<ConversationState.Line>();
        List<String> burstTexts = new ArrayList<String>();
        int overflow = 0;
        for (Message m : incoming) {
            if (outAt > 0 && m.sentAt < outAt) continue; // before owner's last reply
            String sid = ParticipantRegistry.stableIdFor(m.senderKey, null, m.senderName);
            String label = sid.isEmpty() ? "" : reg.labelFor(sid);
            if (label.isEmpty()) label = m.senderName == null ? "" : m.senderName.trim();
            if (burst.size() >= BURST_CAP) { overflow++; continue; }
            burst.add(new ConversationState.Line(sid, label, m.body.trim(), m.sentAt,
                m.notifKey == null ? "" : m.notifKey));
            burstTexts.add(m.body.trim());
        }

        List<String> speakers = new ArrayList<String>();
        for (int i = incoming.size() - 1; i >= 0 && speakers.size() < SPEAKER_CAP; i--) {
            Message m = incoming.get(i);
            String sid = ParticipantRegistry.stableIdFor(m.senderKey, null, m.senderName);
            String label = sid.isEmpty() ? "" : reg.labelFor(sid);
            if (label.isEmpty()) label = m.senderName == null ? "" : m.senderName.trim();
            if (!label.isEmpty() && !speakers.contains(label)) speakers.add(label);
        }

        // topic (+ change) — persisted per conversation so a chat can return to an
        // earlier topic without it being mistaken for the current one.
        List<String> terms = TopicTracker.topTerms(burstTexts, 2);
        String topic = TopicTracker.label(terms);
        String prevTopic = "";
        JsonObj saved = null;
        try { saved = JsonObj.create(); } catch (RuntimeException ignored) { }
        String oldLabel = "", oldTerms = "";
        String savedRaw = kv.get(KV_TOPIC + c.id, "");
        if (!savedRaw.isEmpty()) {
            try {
                JsonObj o = com.replymate.core.json.Json.parseObj(savedRaw);
                oldLabel = o.str("label", "");
                oldTerms = o.str("terms", "");
            } catch (RuntimeException malformed) { /* fresh start, honestly */ }
        }
        if (!topic.isEmpty()) {
            List<String> oldTermList = new ArrayList<String>();
            for (String t : oldTerms.split("\\|")) if (!t.isEmpty()) oldTermList.add(t);
            if (!oldLabel.isEmpty() && !TopicTracker.sameTopic(oldTermList, terms)) {
                prevTopic = oldLabel;                  // topic CHANGED just now
            }
            JsonObj o = JsonObj.create();
            o.put("label", topic);
            StringBuilder jt = new StringBuilder();
            for (String t : terms) { if (jt.length() > 0) jt.append('|'); jt.append(t); }
            o.put("terms", jt.toString());
            String ser = o.toJson();
            if (!ser.equals(savedRaw)) kv.put(KV_TOPIC + c.id, ser);
        }
        // people are not topics: owner/participant/group-title tokens are excluded
        // from subtopic narrowing ("Spencer, the ticketing site…" narrows to
        // "ticketing", never to the owner's own name).
        java.util.Set<String> nameTokens = new java.util.HashSet<String>();
        for (String t : com.replymate.core.convo.EngagementClassifier.ownerTokens(ownerName)) {
            nameTokens.add(t);
        }
        TopicTracker.addNameTokens(nameTokens, c.displayName);
        for (com.replymate.core.convo.Participant p : reg.all()) {
            TopicTracker.addNameTokens(nameTokens, p.displayName);
        }
        String subtopic = burstTexts.isEmpty() ? ""
            : TopicTracker.subtopic(burstTexts.get(burstTexts.size() - 1), terms, nameTokens);

        return new ConversationState(c.id, c.isGroup, c.displayName,
            ownerName == null ? "" : ownerName, reg, speakers, burst, overflow,
            topic, subtopic, prevTopic, terms,
            lastOut == null ? "" : lastOut.body.trim(), outAt, now);
    }

    /** Full evaluation. waitExhausted is derived from this conversation's WAIT
     *  marker: TRUE when THIS identical content hash already waited once. */
    public Evaluation evaluate(Contact c, List<Message> thread, String ownerName,
                             String incomingHash) {
        ConversationState st = build(c, thread, ownerName);
        boolean exhausted = incomingHash != null
            && incomingHash.equals(kv.get(KV_WAIT + c.id, ""));
        Engagement en = EngagementClassifier.evaluate(st, exhausted);
        return new Evaluation(st, en);
    }

    // ---------------- orchestration markers (AssistantRunner + catch-up) ----------------

    public void markWaited(long cid, String hash) { kv.put(KV_WAIT + cid, hash == null ? "" : hash); }
    public String waitedFor(long cid) { return kv.get(KV_WAIT + cid, ""); }
    public void markSkip(long cid, String hash, String verdictReason) {
        kv.put(KV_SKIP + cid, (hash == null ? "" : hash) + "|" + verdictReason);
    }
    /** non-null when this exact content was already consciously skipped. */
    public String skippedFor(long cid, String hash) {
        String v = kv.get(KV_SKIP + cid, "");
        int bar = v.indexOf('|');
        return (hash != null && bar > 0 && v.substring(0, bar).equals(hash))
            ? v.substring(bar + 1) : null;
    }
    public void markLast(long cid, Engagement en) {
        if (en == null) return;
        kv.put(KV_LAST + cid, en.verdict.name() + "|" + en.reason + "|"
            + (en.target == null ? "" : en.target.senderLabel));
    }
    /** "VERDICT|reason|targetLabel" of the last evaluation (notifier salience), "" if none. */
    public String lastFor(long cid) { return kv.get(KV_LAST + cid, ""); }

    private static boolean usable(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
