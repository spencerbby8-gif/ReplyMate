package com.replymate.core.memory;

import com.replymate.core.model.ContentKind;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Rolling conversation summary — the M2 memory layer (P-memory-audit).
 *  100% LOCAL, deterministic, zero AI calls: the same older message list always
 *  produces byte-identical text, so MemoryService can compare-and-persist without
 *  ever duplicating rows, and every Prompt Audit rendering is reproducible.
 *
 *  Honesty rules (guarded by ThreadSummarizerTest):
 *    - media/empty/placeholder bodies are NEVER paraphrased as content — they are
 *      only COUNTED ("3 media item(s) shared that ReplyMate can't read");
 *    - questions, plans, dates/times and amounts are kept over small talk;
 *    - attribution is explicit: "you: …" for the owner, "<partner>: …" otherwise;
 *    - output stays within CHAR_BUDGET and in chronological order;
 *    - input is the caller's older-than-hot-window list (strictly one contact's). */
public final class ThreadSummarizer {

    /** At most this many older messages are considered per rebuild. */
    public static final int MAX_INPUT = 400;
    /** Total character budget for the summary text (without the media note). */
    public static final int CHAR_BUDGET = 700;
    /** One kept line is flattened + trimmed to this length. */
    public static final int LINE_MAX = 120;

    private static final String[] PLAN_WORDS = {
        "meet", "coming", "come over", "call me", "call you", "send", "bring", "buy",
        "pay ", "paid", "money", "price", "amount", "let's", "lets ", "i'll", "i will",
        "we'll", "we will", "don't forget", "dont forget", "remember", "deadline",
        "tomorrow", "tonight", "today", "this morning", "this afternoon",
        "this evening", "weekend", "monday", "tuesday", "wednesday", "thursday",
        "friday", "saturday", "sunday", "next week", "this week", "address",
        "location", "pickup", "pick up", "drop off", "deliver", "birthday", "party",
        "wedding", "travel", "flight", "ticket", "urgent", "church", "office",
        "school", "market", "hospital", "interview"
    };

    /** Deterministic result of one summarization pass. */
    public static final class Summary {
        public String text = "";
        public int msgCount;          // older messages summarized
        public int mediaEvents;       // unreadable media items inside them
        public long coveredUntilTs;   // sentAt of the last covered message
    }

    private ThreadSummarizer() { }

    /** Summarize the older-than-hot-window history of ONE contact.
     *  @param older oldest-first; strictly that contact's rows (isolation upstream). */
    public static Summary summarize(List<Message> older, String partnerName) {
        Summary out = new Summary();
        if (older == null || older.isEmpty()) return out;
        String partner = partnerName == null || partnerName.trim().isEmpty()
            ? "them" : partnerName.trim();

        int from = Math.max(0, older.size() - MAX_INPUT);
        List<Message> window = new ArrayList<Message>(older.subList(from, older.size()));
        out.msgCount = window.size();
        out.coveredUntilTs = window.get(window.size() - 1).sentAt;

        final List<int[]> meta = new ArrayList<int[]>();   // {score, index}
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < window.size(); i++) {
            Message m = window.get(i);
            if (m == null || m.body == null) continue;
            String body = flat(m.body);
            if (body.isEmpty()
                    || !com.replymate.core.prompt.PromptBuilder.usableText(body)) {
                ContentKind kind = m.effectiveKind();
                if (kind != null && kind.isMedia()) out.mediaEvents++;
                continue;   // never paraphrase media/empty content
            }
            int score = score(body);
            if (score <= 0) continue;   // small talk doesn't earn summary space
            if (body.length() > LINE_MAX) body = body.substring(0, LINE_MAX - 1) + "…";
            String who = m.direction == Direction.OUTGOING ? "you" : partner;
            lines.add(who + ": " + body);
            meta.add(new int[] {score, lines.size() - 1});
        }

        // rank: score desc, then NEWER first; keep until the budget is spent …
        List<int[]> ranked = new ArrayList<int[]>(meta);
        Collections.sort(ranked, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) {
                if (a[0] != b[0]) return b[0] - a[0];
                return b[1] - a[1];
            }
        });
        List<Integer> keep = new ArrayList<Integer>();
        int budget = CHAR_BUDGET;
        for (int[] r : ranked) {
            String line = lines.get(r[1]);
            if (budget - line.length() - 2 < 0) continue;
            budget -= line.length() + 2;
            keep.add(r[1]);
        }
        // … then restore chronological order (deterministic — stable sorts only).
        Collections.sort(keep);

        StringBuilder sb = new StringBuilder();
        for (int idx : keep) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(lines.get(idx));
        }
        if (sb.length() == 0 && out.mediaEvents > 0) {
            sb.append("(no readable older text — only media)");
        }
        if (out.mediaEvents > 0) {
            if (sb.length() > 0) sb.append(". ");
            sb.append(out.mediaEvents)
              .append(" media item(s) were shared that ReplyMate can't read");
        }
        out.text = sb.toString();
        return out;
    }

    /** Salience: questions (+2), digits/dates/amounts (+2), plan words (+1 each, ≤3). */
    static int score(String body) {
        int score = 0;
        if (body.indexOf('?') >= 0) score += 2;
        if (hasDigit(body)) score += 2;
        String lower = body.toLowerCase(Locale.US);
        int hits = 0;
        for (String w : PLAN_WORDS) {
            if (lower.contains(w)) { hits++; if (hits == 3) break; }
        }
        return score + hits;
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }

    private static String flat(String s) {
        return s == null ? "" : s.replace('\n', ' ').trim();
    }
}
