package com.replymate.core.understanding;

import java.util.ArrayList;
import java.util.List;

/** P-intelligence-1 (message understanding): the CLEAN conversation object every
 *  generation consumes — assembled from the stored thread + contact + voice +
 *  memory before the prompt is composed. The model never receives raw notification
 *  text; it receives the thread plus THIS structured reading: who sent what, on
 *  which app, message type, burst state, burst mechanics, the owner's last reply,
 *  and whether the chat is new (cold start) or known. */
public final class ConversationContext {

    public final long contactId;
    public final String displayName;
    public final String appLabel;          // e.g. "WhatsApp" ("" for manual chats)
    public final int threadSize;           // messages in the hot window

    public final int burstSize;            // incoming lines since the owner's last reply (>=1)
    public final boolean burstDetected;    // burstSize >= 2
    public final String newestText;        // the point being answered (trimmed)
    public final String newestContentKind; // ContentKind.wire ("text", "image", …)
    public final String newestSender;      // actual sender in groups ("" = the contact)
    public final long newestAt;
    public final BurstSignals.Result signals;

    public final String lastOutgoingText;  // owner's last reply in this chat ("" = none)
    public final long lastOutgoingAt;

    public final boolean coldStart;        // new contact: no voice/profile/rules/memory/signals
    public final int learningSignals;      // total recorded signals for this contact

    public ConversationContext(long contactId, String displayName, String appLabel,
                               int threadSize, int burstSize, String newestText,
                               String newestContentKind, String newestSender, long newestAt,
                               BurstSignals.Result signals, String lastOutgoingText,
                               long lastOutgoingAt, boolean coldStart, int learningSignals) {
        this.contactId = contactId;
        this.displayName = displayName == null ? "" : displayName;
        this.appLabel = appLabel == null ? "" : appLabel;
        this.threadSize = threadSize;
        this.burstSize = burstSize;
        this.burstDetected = burstSize >= 2;
        this.newestText = newestText == null ? "" : newestText;
        this.newestContentKind = newestContentKind == null ? "" : newestContentKind;
        this.newestSender = newestSender == null ? "" : newestSender;
        this.newestAt = newestAt;
        this.signals = signals;
        this.lastOutgoingText = lastOutgoingText == null ? "" : lastOutgoingText;
        this.lastOutgoingAt = lastOutgoingAt;
        this.coldStart = coldStart;
        this.learningSignals = learningSignals;
    }

    /** Audit-render lines — Prompt Audit's "why it sounded this way" block. */
    public List<String> whyLines() {
        List<String> out = new ArrayList<String>();
        StringBuilder head = new StringBuilder("understanding: ");
        head.append(burstDetected
                ? burstSize + "-message burst" : "single message")
            .append(newestSender.isEmpty() || newestSender.equals(displayName)
                ? (" from " + displayName) : (" from " + newestSender + " in " + displayName));
        if (!appLabel.isEmpty()) head.append(" on ").append(appLabel);
        if (!"text".equals(newestContentKind) && !newestContentKind.isEmpty()) {
            head.append(" (type: ").append(newestContentKind).append(')');
        }
        out.add(head.toString());
        // P-intelligence-2 (audit-vs-prompt parity): signal lines are credited ONLY
        // when the same signals actually reach the provider task — which is the
        // burst path (burstDetected). A single message never carries a "burst
        // signal" annotation, so the audit must never claim one here either.
        if (burstDetected && signals != null && signals.hasCorrection()) {
            out.add("burst signal: self-correction in line "
                + joinInts(signals.correctionLines) + " — answer the corrected version");
        }
        if (burstDetected && signals != null && signals.multiQuestion) {
            out.add("burst signal: " + signals.questions + " questions in "
                + signals.size + " messages — the newest leads");
        }
        if (burstDetected && signals != null && signals.fillerHeavy) {
            out.add("burst signal: mostly filler pings (" + signals.fillers + " of "
                + signals.size + ") — answer the real line, not the noise");
        }
        if (!lastOutgoingText.isEmpty()) {
            out.add("your own last reply in this chat is in context above — the draft"
                + " follows up on it, never repeats it");
        }
        if (coldStart) {
            out.add("new contact — cold start: neutral assumptions, no learned style yet"
                + (learningSignals == 0 ? " (0 learning signals)" : ""));
        }
        return out;
    }

    private static String joinInts(List<Integer> xs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('#').append(xs.get(i).intValue());
        }
        return sb.toString();
    }
}
