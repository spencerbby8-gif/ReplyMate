package com.replymate.core.prompt;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.core.budget.TokenBudgeter;
import com.replymate.core.json.JsonArr;
import com.replymate.core.json.JsonObj;
import java.util.List;

/** Assembles the full provider request (BLUEPRINT §5.3, P1 scope: L0 + L3 + L4). */
public final class PromptBuilder {

    public static final int VARIANT_COUNT = 3;

    private PromptBuilder() { }

    public static ChatRequest build(PromptBundle bundle) {
        String system = SystemComposer.compose(bundle.profile, bundle.contact, bundle.styleRules,
            bundle.voiceLine, bundle.voiceExtra, bundle.aboutExtra, bundle.memoryLines);
        List<Turn> turns = ThreadMapper.map(bundle.thread, bundle.contact.displayName);
        // P-context-honesty: the task turn quotes the exact message being answered and
        // names the app, so the model can never reply from the contact name alone.
        // P-memory-audit: the answered message is attributed to its ACTUAL sender
        // (group chats — schema v6 sender_name).
        com.replymate.core.model.Message latest = latestUsableIncoming(bundle.thread);
        String appLabel = latest == null || latest.channel == null
                || latest.channel == com.replymate.core.model.Channel.MANUAL
            ? null : com.replymate.core.listener.WatchedApps.labelFor(latest.channel);
        com.replymate.core.model.ContentKind kind = latest == null
            ? null : latest.effectiveKind();
        // P-background-6: a rapid-fire unread tail is answered ONCE as a burst —
        // summarizing it into its single point — not message by message.
        java.util.List<String> burst = burstTailUsableIncoming(bundle.thread, 6);
        Turn task = burst.size() >= 2
            ? TaskComposer.burstTask(
                bundle.profile == null ? "the owner of this phone" : bundle.profile.displayName(),
                bundle.contact.displayName, burst, appLabel)
            : TaskComposer.defaultTask(
                bundle.profile == null ? "the owner of this phone" : bundle.profile.displayName(),
                bundle.contact.displayName,
                latest == null ? null : latest.body, appLabel, kind,
                latest == null ? null : latest.senderName);
        ChatRequest req = new ChatRequest(system, turns, task,
            GenerationOpts.of(VARIANT_COUNT, 0.8, 220));
        return TokenBudgeter.fit(req, TokenBudgeter.DEFAULT_MAX_INPUT);
    }

    /** The unread burst tail: consecutive INCOMING usable-text messages counting
     *  back from the thread end, stopping at the owner's last OUTGOING (that ends
     *  the burst logically) or at {@code max} items. Oldest-first in the result. */
    public static java.util.List<String> burstTailUsableIncoming(
            java.util.List<com.replymate.core.model.Message> thread, int max) {
        java.util.LinkedList<String> out = new java.util.LinkedList<String>();
        if (thread == null) return out;
        for (int i = thread.size() - 1; i >= 0 && out.size() < max; i--) {
            com.replymate.core.model.Message m = thread.get(i);
            if (m == null) continue;
            if (m.direction == com.replymate.core.model.Direction.OUTGOING) break;
            if (m.direction == com.replymate.core.model.Direction.INCOMING
                    && usableText(m.body)) {
                out.addFirst(m.body.trim());
            }
        }
        return out;
    }

    /** The message a reply would answer: the LATEST incoming message whose body carries
     *  usable text (non-empty, not the media placeholder). Single source of truth shared
     *  by the generation gate in DraftService and the task composer here. */
    public static com.replymate.core.model.Message latestUsableIncoming(
            java.util.List<com.replymate.core.model.Message> thread) {
        if (thread == null) return null;
        com.replymate.core.model.Message found = null;
        for (com.replymate.core.model.Message m : thread) {   // oldest-first → ends at latest
            if (m != null && m.direction == com.replymate.core.model.Direction.INCOMING
                    && usableText(m.body)) {
                found = m;
            }
        }
        return found;
    }

    /** Real readable text test (the generation gate): non-empty and NOT a stored
     *  placeholder of ANY content kind (legacy or per-kind). A captioned media
     *  message passes — its caption IS real text (the task discloses the media). */
    public static boolean usableText(String body) {
        if (body == null) return false;
        String t = body.trim();
        return !t.isEmpty()
            && !com.replymate.core.listener.ListenerFilter.isPlaceholder(t);
    }

    /** Audit snapshot of exactly what is sent (stored on each draft row). */
    public static String snapshot(ChatRequest req, String model) {
        return snapshot(req, model, "reply");
    }

    /** As above, with an explicit kind tag ("reply", "tone:friendlier", …) so the
     *  audit viewer can tell full generations from tone transforms. */
    public static String snapshot(ChatRequest req, String model, String kind) {
        return snapshot(req, model, kind, null);
    }

    /** Full audit snapshot incl. the "why" notes: human-readable lines explaining
     *  exactly which style inputs shaped this request (P4 prompt audit). */
    public static String snapshot(ChatRequest req, String model, String kind,
                                  List<String> why) {
        return snapshot(req, model, kind, why, null);
    }

    /** Full audit snapshot with provider provenance + the message being answered
     *  (P-context-honesty). ctx may be null (e.g. tone transforms of old drafts). */
    public static String snapshot(ChatRequest req, String model, String kind,
                                  List<String> why, AuditContext ctx) {
        JsonArr turns = JsonArr.create();
        for (Turn t : req.turns) {
            turns.add(JsonObj.create()
                .put("role", t.role == Turn.Role.USER ? "user" : "model")
                .put("text", t.text));
        }
        JsonArr whyArr = JsonArr.create();
        if (why != null) {
            for (String w : why) {
                if (w != null && !w.trim().isEmpty()) whyArr.add(w.trim());
            }
        }
        JsonObj out = JsonObj.create()
            .put("kind", kind)
            .put("model", model)
            .put("why", whyArr)
            .put("system", req.system)
            .put("turns", turns)
            .put("contextTurns", req.turns.size())
            .put("task", req.task == null ? "" : req.task.text)
            .put("temperature", req.opts.temperature)
            .put("candidateCount", req.opts.candidates)
            .put("maxOutputTokens", req.opts.maxOutputTokens)
            .put("maxInputTokens", TokenBudgeter.DEFAULT_MAX_INPUT);
        if (ctx != null) {
            out.put("provider", JsonObj.create()
                .put("wire", ctx.providerWire)
                .put("label", ctx.providerLabel)
                .put("baseUrl", ctx.baseUrl)
                .put("model", ctx.providerModel)
                .put("endpoint", ctx.endpoint));
            if (!ctx.latestText.isEmpty()) {
                JsonObj li = JsonObj.create()
                    .put("text", ctx.latestText)
                    .put("channel", ctx.latestChannel)
                    .put("app", ctx.latestPackage)
                    .put("at", ctx.latestAt)
                    .put("contentType", ctx.latestKind)
                    .put("mediaRef", ctx.mediaRefAvailable
                        ? "captured locally — never opened, never uploaded" : "none");
                if (!ctx.latestSender.isEmpty()) li.put("sender", ctx.latestSender);
                if (!ctx.latestMediaMime.isEmpty()) li.put("mediaMime", ctx.latestMediaMime);
                out.put("latestIncoming", li);
            }
            if (!ctx.sourceIdentity.isEmpty()) {
                out.put("source", JsonObj.create()
                    .put("identity", ctx.sourceIdentity)
                    .put("confidence", ctx.sourceConfidence));
            }
            if (!ctx.reason.isEmpty()) {
                out.put("reason", ctx.reason);
            }
            // P-memory-audit: the long-term memory layers this request leaned on.
            if (ctx.memory != null && !ctx.memory.isEmpty()) {
                JsonObj mem = JsonObj.create();
                if (!ctx.memory.summaryText.isEmpty()) {
                    mem.put("summary", ctx.memory.summaryText)
                       .put("summaryMeta", ctx.memory.summaryMeta);
                }
                if (!ctx.memory.facts.isEmpty()) {
                    JsonArr fa = JsonArr.create();
                    for (String f : ctx.memory.facts) fa.add(f);
                    mem.put("facts", fa);
                }
                if (!ctx.memory.learnedStyle.isEmpty()) {
                    JsonArr ls = JsonArr.create();
                    for (String l : ctx.memory.learnedStyle) ls.add(l);
                    mem.put("learnedStyle", ls);
                }
                out.put("memory", mem);
            }
        }
        return out.toJson();
    }
}
