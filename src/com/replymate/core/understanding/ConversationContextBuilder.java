package com.replymate.core.understanding;

import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.prompt.PromptBuilder;
import com.replymate.core.style.StyleService;
import java.util.List;
import java.util.Map;

/** P-intelligence-1: assembles the {@link ConversationContext} the model actually
 *  consumes. Reads ONLY this one contact's thread, voice rows and signal counters —
 *  per-contact isolation is structural, same as every other store read.
 *  Cold start reuses {@link StyleService#isColdStart} verbatim so the understanding
 *  layer can never disagree with the style audit credit about what "new" means. */
public final class ConversationContextBuilder {

    private ConversationContextBuilder() { }

    /** @param thread this contact's hot window (OLDEST-first)
     *  @param burst the burst tail actually being answered (from
     *               {@link PromptBuilder#burstTailUsableIncoming}), OLDEST-first
     *  @param extraLines the composed voice extras (custom prompts + learned hints —
     *               their presence ends cold start, matching the audit credit)
     *  @param memoryLines recalled memory bullets (any memory line ends cold start)
     *  @param signalsTotal recorded learning signals for THIS contact
     *  @param global/globalRows + contactRows style rows for the cold-start test */
    public static ConversationContext build(
            Contact contact, List<Message> thread, List<String> burst,
            Map<String, String> globalRows, Map<String, String> contactRows,
            java.util.List<String> extraLines, java.util.List<String> memoryLines,
            int signalsTotal) {

        Message newestIncoming = null;
        Message lastOutgoing = null;
        if (thread != null) {
            for (Message m : thread) {           // oldest-first → loops end at newest
                if (m == null) continue;
                if (m.direction == Direction.INCOMING && PromptBuilder.usableText(m.body)) {
                    newestIncoming = m;
                } else if (m.direction == Direction.OUTGOING
                        && PromptBuilder.usableText(m.body)) {
                    lastOutgoing = m;
                }
            }
        }

        String appLabel = "";
        if (newestIncoming != null && newestIncoming.channel != null
                && newestIncoming.channel != com.replymate.core.model.Channel.MANUAL) {
            appLabel = com.replymate.core.listener.WatchedApps.labelFor(newestIncoming.channel);
        }
        String kindWire = newestIncoming == null ? "text" : (
            newestIncoming.effectiveKind() == null
                ? "text" : newestIncoming.effectiveKind().wire);

        boolean cold = contact != null
            && StyleService.isColdStart(contact, globalRows, contactRows, extraLines)
            && (memoryLines == null || memoryLines.isEmpty())
            && signalsTotal == 0;

        return new ConversationContext(
            contact == null ? 0 : contact.id,
            contact == null ? "" : contact.displayName,
            appLabel,
            thread == null ? 0 : thread.size(),
            burst == null ? 0 : burst.size(),
            newestIncoming == null ? "" : newestIncoming.body.trim(),
            kindWire,
            newestIncoming == null || newestIncoming.senderName == null
                ? "" : newestIncoming.senderName.trim(),
            newestIncoming == null ? 0 : newestIncoming.sentAt,
            BurstSignals.detect(burst),
            lastOutgoing == null ? "" : lastOutgoing.body.trim(),
            lastOutgoing == null ? 0 : lastOutgoing.sentAt,
            cold,
            Math.max(0, signalsTotal));
    }

    /** The standing prompt line for a cold-start contact (system level). Empty
     *  otherwise — known chats need no situational preamble. */
    public static String coldStartPromptLine(ConversationContext ctx) {
        if (ctx == null || !ctx.coldStart) return "";
        return "New chat — you barely know " + ctx.displayName + " yet and have no learned"
            + " style for them: stay warm, plain and lightly reserved; prefer a short honest"
            + " answer (or a small clarifying question) over assuming context; never fake"
            + " shared history or inside jokes.";
    }

    /** Burst annotation lines for the task turn (grounded mechanics only). Empty when
     *  nothing fired — tasks then stay byte-identical to the pre-understanding text. */
    public static java.util.List<String> burstAnnotations(ConversationContext ctx) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (ctx == null || ctx.signals == null || !ctx.burstDetected) return out;
        if (ctx.signals.hasCorrection()) {
            out.add("Mechanical read: line "
                + ints(ctx.signals.correctionLines)
                + " of the burst is a self-correction — answer the corrected version,"
                + " not what it corrected.");
        }
        if (ctx.signals.multiQuestion) {
            out.add("Mechanical read: " + ctx.signals.questions
                + " of the " + ctx.signals.size
                + " burst lines are questions — your single reply answers the NEWEST"
                + " question; older ones may be folded in only if still open.");
        }
        if (ctx.signals.fillerHeavy && ctx.signals.size > 1) {
            out.add("Mechanical read: " + ctx.signals.fillers + " of " + ctx.signals.size
                + " lines are pure filler pings — do not greet them back; answer the one"
                + " real line.");
        }
        return out;
    }

    private static String ints(List<Integer> xs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append("+");
            sb.append('#').append(xs.get(i).intValue());
        }
        return sb.toString();
    }
}
