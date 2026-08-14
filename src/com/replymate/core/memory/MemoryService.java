package com.replymate.core.memory;

import com.replymate.core.learning.StyleProfiler;
import com.replymate.core.model.Contact;
import com.replymate.core.model.ContactSummary;
import com.replymate.core.model.FactCategory;
import com.replymate.core.model.MemoryFact;
import com.replymate.core.model.Message;
import com.replymate.core.ports.KvStore;
import com.replymate.core.ports.MemoryStore;
import com.replymate.core.ports.MessageStore;
import com.replymate.core.util.Clock;
import com.replymate.core.util.TimeFmt;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Long-term memory continuity (P-memory-audit). Assembles, refreshes and recalls
 *  the per-contact layers the owner mandated — STRICTLY by contact id:
 *    M1 hot context    — the caller's current thread window (not handled here);
 *    M2 rolling summary — deterministic local ThreadSummarizer over everything
 *                         OLDER than the hot window, persisted as versioned
 *                         contact_summary rows (survives restarts; identical input
 *                         ⇒ identical text ⇒ no duplicate rows);
 *    M3 pinned facts    — stable contact details, owner-authored via the contact
 *                         editor; recalled ranked (pinned first) by MemoryEngine;
 *    M4 learned style   — StyleProfiler lines from the owner's approved replies,
 *                         cached per contact in kv (restart-proof).
 *  Gates (fail-closed, same shape as LearningService): private-mode and
 *  memory-disabled contacts recall NOTHING and write NOTHING. */
public final class MemoryService {

    /** Messages fetched for the hot window by DraftService — the summary boundary. */
    public static final int HOT_WINDOW = 30;
    /** Facts recalled into a single prompt. */
    public static final int FACT_LIMIT = 8;

    private final MemoryStore memory;
    private final MessageStore messages;
    private final KvStore kv;
    private final Clock clock;

    public MemoryService(MemoryStore memory, MessageStore messages, KvStore kv,
                         Clock clock) {
        this.memory = memory;
        this.messages = messages;
        this.kv = kv;
        this.clock = clock;
    }

    /** One contact's recalled memory, rendered as prompt-ready lines + audit data. */
    public static final class Recall {
        public final List<String> lines;       // prompt lines (\"- …\") in layer order
        public final List<String> why;         // audit notes (Prompt Audit rendering)
        public final String summaryText;
        public final String summaryMeta;       // \"covers 41 msgs through Tue 14:02 · v3\"
        public final List<String> facts;       // fact TEXTS recalled (audit)
        public final List<String> learnedStyle;// style line TEXTS recalled (audit)
        public final List<String> retrieved;   // P-intel-13 M5: retrieved older-message TEXTS (audit)

        Recall(List<String> lines, List<String> why, String summaryText,
               String summaryMeta, List<String> facts, List<String> learnedStyle) {
            this(lines, why, summaryText, summaryMeta, facts, learnedStyle,
                Collections.<String>emptyList());
        }

        Recall(List<String> lines, List<String> why, String summaryText,
               String summaryMeta, List<String> facts, List<String> learnedStyle,
               List<String> retrieved) {
            this.lines = lines;
            this.why = why;
            this.summaryText = summaryText;
            this.summaryMeta = summaryMeta;
            this.facts = facts;
            this.learnedStyle = learnedStyle;
            this.retrieved = retrieved == null
                ? Collections.<String>emptyList() : retrieved;
        }
    }

    private static final Recall EMPTY = new Recall(
        Collections.<String>emptyList(), Collections.<String>emptyList(), "", "",
        Collections.<String>emptyList(), Collections.<String>emptyList());

    /** The ONE gate every memory read/write must pass. */
    public boolean openFor(Contact c) {
        return c != null && !c.privateMode && c.memoryEnabled;
    }

    /* ------------------------------------------------------------------ recall */

    /** Everything the prompt may know about this contact beyond the hot window.
     *  Also refreshes the rolling summary when the older history changed (local,
     *  deterministic — no AI call, no cost). Returns EMPTY for closed contacts. */
    public Recall recall(Contact c, List<Message> hotWindow) {
        if (!openFor(c)) return EMPTY;

        List<String> lines = new ArrayList<String>();
        List<String> why = new ArrayList<String>();

        // M3 — pinned + remembered facts (ranked; pinned first).
        List<MemoryFact> ranked = MemoryEngine.rankActive(memory.activeFacts(c.id), FACT_LIMIT);
        List<String> factTexts = new ArrayList<String>();
        for (MemoryFact f : ranked) {
            factTexts.add(f.text);
            lines.add("- " + f.text);
        }
        if (!factTexts.isEmpty()) {
            int pinned = 0;
            for (MemoryFact f : ranked) if (f.pinned) pinned++;
            why.add("memory facts applied (" + factTexts.size() + " — "
                + pinned + " pinned by you)");
        }

        // M2 — rolling summary of everything older than the hot window.
        ContactSummary summary = refreshSummary(c, hotWindow);
        if (summary != null && !summary.summaryText.trim().isEmpty()) {
            lines.add("- Earlier in this chat (your own running summary): "
                + summary.summaryText.trim());
        }

        // M5 — P-intelligence-13: RELEVANT retrieval over the deep history. The
        // summary is budgeted, so a months/years-old fact can age out of it; when
        // the NEW incoming text shares its topic, lift only the matching older
        // messages (≤3, ≤460 chars, timestamped, newest marked authoritative) —
        // the deep history is scanned, never flooded into the prompt.
        List<String> retrievedTexts = new ArrayList<String>();
        List<String> query = newestIncomingTexts(hotWindow);
        if (!query.isEmpty()) {
            long boundary = hotWindow == null || hotWindow.isEmpty()
                ? 0 : hotWindow.get(0).id;
            List<Message> pool = boundary <= 0
                ? messages.lastMessages(c.id, HistoryRetriever.POOL_LIMIT)
                : messages.olderThanId(c.id, boundary, HistoryRetriever.POOL_LIMIT);
            List<HistoryRetriever.Hit> hits = HistoryRetriever.retrieve(pool, query);
            int budget = HistoryRetriever.CHAR_BUDGET;
            for (HistoryRetriever.Hit h : hits) {
                String line = "- " + HistoryRetriever.render(h, c.displayName);
                if (budget - line.length() < 0) break;
                budget -= line.length();
                lines.add(line);
                retrievedTexts.add(h.message.body == null ? "" : h.message.body.trim());
            }
            if (!retrievedTexts.isEmpty()) {
                why.add("retrieved " + retrievedTexts.size()
                    + " older message(s) on this topic from beyond the recent window"
                    + " (timestamped — latest is authoritative)");
            }
        }

        return new Recall(lines, why,
            summary == null ? "" : summary.summaryText,
            summary == null ? "" : metaLine(summary),
            factTexts, Collections.<String>emptyList(), retrievedTexts);
    }

    /** The retrieval query: the freshest INCOMING readable texts of the hot window
     *  (at most 2 — the active topic; outgoing owner lines and unusable bodies
     *  never drive a lookup). */
    private static List<String> newestIncomingTexts(List<Message> hotWindow) {
        List<String> out = new ArrayList<String>();
        if (hotWindow == null) return out;
        for (int i = hotWindow.size() - 1; i >= 0 && out.size() < 2; i--) {
            Message m = hotWindow.get(i);
            if (m == null || m.direction != com.replymate.core.model.Direction.INCOMING) {
                continue;
            }
            String body = m.body == null ? "" : m.body.trim();
            if (body.isEmpty()
                    || !com.replymate.core.prompt.PromptBuilder.usableText(body)) continue;
            out.add(body);
        }
        return out;
    }

    /** Append the M4 learned-style layer (DraftService supplies the approved reply
     *  texts — it owns the draft store; the kv cache here makes it restart-proof). */
    public Recall withLearnedStyle(Recall base, Contact c, List<String> approvedTexts) {
        return withLearnedStyle(base, c, approvedTexts,
            java.util.Collections.<String>emptySet());
    }

    /** P-intelligence-2 precedence: as above, but a derived line touching a voice
     *  control the user has EXPLICITLY set for this contact (explicitControls:
     *  "length"/"emoji") is suppressed — explicit settings beat learned guesses,
     *  and the audit says so. Suppression happens AFTER the cache read so toggling
     *  the explicit setting takes effect on the very next generation. */
    public Recall withLearnedStyle(Recall base, Contact c, List<String> approvedTexts,
                                   java.util.Set<String> explicitControls) {
        if (base == null) base = EMPTY;
        if (!openFor(c)) return base;
        java.util.Set<String> suppressed = explicitControls == null
            ? java.util.Collections.<String>emptySet() : explicitControls;
        List<String[]> tagged = learnedStyleTagged(c, approvedTexts);
        if (tagged.isEmpty()) return base;
        List<String> lines = new ArrayList<String>();
        int blocked = 0;
        for (String[] tl : tagged) {
            if (!tl[0].isEmpty() && suppressed.contains(tl[0])) { blocked++; continue; }
            lines.add(tl[1]);
        }
        List<String> newWhy = new ArrayList<String>(base.why);
        if (blocked > 0) {
            newWhy.add("learned style suppressed (" + blocked
                + " rule(s)) — your explicit setting for this contact wins");
        }
        if (lines.isEmpty()) return new Recall(new ArrayList<String>(base.lines), newWhy,
            base.summaryText, base.summaryMeta, base.facts, base.learnedStyle,
            base.retrieved);
        List<String> newLines = new ArrayList<String>(base.lines);
        for (String l : lines) newLines.add("- " + l);
        newWhy.add("learned style applied (" + lines.size()
            + " rule(s) from your approved replies)");
        return new Recall(newLines, newWhy, base.summaryText, base.summaryMeta,
            base.facts, lines, base.retrieved);
    }

    /** Unit-separator between control tag and line inside the v2 cache (never
     *  appears in natural text). */
    private static final String TAG_SEP = "";

    /** Cached tagged derivation: [control, line] pairs per contact. */
    private List<String[]> learnedStyleTagged(Contact c, List<String> approvedTexts) {
        if (!openFor(c)) return Collections.emptyList();
        int approved = approvedTexts == null ? 0
            : Math.min(approvedTexts.size(), StyleProfiler.MAX_TEXTS);
        String key = styleKey(c.id);
        String cached = kv.get(key, "");
        if (!cached.isEmpty()) {
            int nl = cached.indexOf('\n');
            String head = nl < 0 ? cached : cached.substring(0, nl);
            if (String.valueOf(approved).equals(head)) {
                List<String[]> out = new ArrayList<String[]>();
                if (nl > 0) {
                    for (String l : cached.substring(nl + 1).split("\n")) {
                        String t = l.trim();
                        if (t.isEmpty()) continue;
                        int sep = t.indexOf(TAG_SEP);
                        out.add(sep < 0
                            ? new String[] {"", t}
                            : new String[] {t.substring(0, sep), t.substring(sep + 1)});
                    }
                }
                return out;
            }
        }
        List<StyleProfiler.Derived> derived = StyleProfiler.derive(approvedTexts);
        List<String[]> out = new ArrayList<String[]>();
        StringBuilder cache = new StringBuilder(String.valueOf(approved));
        for (StyleProfiler.Derived d : derived) {
            out.add(new String[] {d.control, d.line});
            cache.append('\n').append(d.control).append(TAG_SEP)
                 .append(d.line.replace('\n', ' '));
        }
        kv.put(key, cache.toString());
        return out;
    }

    /** Cached deterministic StyleProfiler derivation for one contact.
     *  kv value (v2): 1st line = approved-count; rest = "control␟line" (control may
     *  be empty). Public contract stays untagged lines. */
    public List<String> learnedStyleLines(Contact c, List<String> approvedTexts) {
        List<String> out = new ArrayList<String>();
        for (String[] tl : learnedStyleTagged(c, approvedTexts)) out.add(tl[1]);
        return out;
    }

    /** kv cache key for a contact's learned-style layer (isolation by key).
     *  v2 adds control tags for the explicit-vs-learned precedence rule. */
    public static String styleKey(long contactId) {
        return "style." + contactId + ".approved.v2";
    }

    /* ---------------------------------------------------------------- summaries */

    /** Rebuild the rolling summary when the older-than-hot-window history changed.
     *  Persistence rule: a new contact_summary VERSION row is inserted only when
     *  the deterministic text actually changed — restarts and re-generations with
     *  unchanged data reproduce the stored text and insert nothing. */
    public ContactSummary refreshSummary(Contact c, List<Message> hotWindow) {
        if (!openFor(c)) return null;
        long boundary = hotWindow == null || hotWindow.isEmpty()
            ? Long.MAX_VALUE : hotWindow.get(0).id;
        List<Message> older = boundary == Long.MAX_VALUE
            ? messages.lastMessages(c.id, ThreadSummarizer.MAX_INPUT)
            : messages.olderThanId(c.id, boundary, ThreadSummarizer.MAX_INPUT);
        if (older.isEmpty()) return memory.latestSummary(c.id);

        ThreadSummarizer.Summary s = ThreadSummarizer.summarize(older, c.displayName);
        ContactSummary latest = memory.latestSummary(c.id);
        if (latest != null && latest.summaryText.equals(s.text)
                && latest.coversUntilTs == s.coveredUntilTs) {
            return latest;   // unchanged — reuse the persisted row (restart-stable)
        }
        ContactSummary row = new ContactSummary();
        row.contactId = c.id;
        row.summaryText = s.text;
        row.coversUntilTs = s.coveredUntilTs;
        row.version = latest == null ? 1 : latest.version + 1;
        row.createdAt = clock.now();
        row.id = memory.insertSummary(row);
        return row;
    }

    public static String metaLine(ContactSummary s) {
        return "summary v" + s.version + " · covers history through "
            + TimeFmt.dayTime(s.coversUntilTs);
    }

    /* ------------------------------------------------------------------- facts */

    /** Owner-authored pinned facts (contact editor, one per line): replace the
     *  contact's pinned set with exactly these lines. Only the owner's own pinned
     *  rows are replaced — merge-learned rows (future extractors) stay untouched.
     *  Everything runs through MemoryEngine.merge: normalization, dedupe, clamps. */
    public void replacePinnedFacts(long contactId, String multilineText) {
        List<MemoryFact> existing = memory.allFacts(contactId);
        for (MemoryFact f : existing) {
            if (f.pinned) memory.deleteFact(f.id);
        }
        List<MemoryFact> candidates = new ArrayList<MemoryFact>();
        if (multilineText != null) {
            for (String raw : multilineText.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (line.length() > 200) line = line.substring(0, 200).trim();
                MemoryFact f = new MemoryFact();
                f.contactId = contactId;
                f.category = FactCategory.PERSON;
                f.text = line;
                f.importance = 5;
                f.confidence = 1.0;
                f.pinned = true;
                candidates.add(f);
            }
        }
        MemoryEngine.merge(contactId, memory.allFacts(contactId), candidates,
            memory, clock.now());
    }

    /** The owner's pinned facts for the editor box, one per line (contact-scoped). */
    public String pinnedFactsText(long contactId) {
        StringBuilder sb = new StringBuilder();
        for (MemoryFact f : MemoryEngine.rankActive(memory.allFacts(contactId), -1)) {
            if (!f.pinned) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(f.text);
        }
        return sb.toString();
    }
}
