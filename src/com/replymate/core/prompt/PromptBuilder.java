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
        Turn task = TaskComposer.defaultTask(
            bundle.profile == null ? "the owner of this phone" : bundle.profile.displayName(),
            bundle.contact.displayName);
        ChatRequest req = new ChatRequest(system, turns, task,
            GenerationOpts.of(VARIANT_COUNT, 0.8, 220));
        return TokenBudgeter.fit(req, TokenBudgeter.DEFAULT_MAX_INPUT);
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
        return JsonObj.create()
            .put("kind", kind)
            .put("model", model)
            .put("why", whyArr)
            .put("system", req.system)
            .put("turns", turns)
            .put("task", req.task == null ? "" : req.task.text)
            .put("temperature", req.opts.temperature)
            .put("candidateCount", req.opts.candidates)
            .put("maxOutputTokens", req.opts.maxOutputTokens)
            .toJson();
    }
}
