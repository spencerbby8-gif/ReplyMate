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
            bundle.voiceLine, bundle.voiceExtra, bundle.aboutExtra);
        List<Turn> turns = ThreadMapper.map(bundle.thread, bundle.contact.displayName);
        // P-context-honesty: the task turn quotes the exact message being answered and
        // names the app, so the model can never reply from the contact name alone.
        com.replymate.core.model.Message latest = latestUsableIncoming(bundle.thread);
        String appLabel = latest == null || latest.channel == null
                || latest.channel == com.replymate.core.model.Channel.MANUAL
            ? null : com.replymate.core.listener.WatchedApps.labelFor(latest.channel);
        Turn task = TaskComposer.defaultTask(
            bundle.profile == null ? "the owner of this phone" : bundle.profile.displayName(),
            bundle.contact.displayName,
            latest == null ? null : latest.body, appLabel);
        ChatRequest req = new ChatRequest(system, turns, task,
            GenerationOpts.of(VARIANT_COUNT, 0.8, 220));
        return TokenBudgeter.fit(req, TokenBudgeter.DEFAULT_MAX_INPUT);
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

    public static boolean usableText(String body) {
        if (body == null) return false;
        String t = body.trim();
        return !t.isEmpty()
            && !com.replymate.core.listener.ListenerFilter.MEDIA_PLACEHOLDER.equals(t);
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
                out.put("latestIncoming", JsonObj.create()
                    .put("text", ctx.latestText)
                    .put("channel", ctx.latestChannel)
                    .put("at", ctx.latestAt));
            }
        }
        return out.toJson();
    }
}
