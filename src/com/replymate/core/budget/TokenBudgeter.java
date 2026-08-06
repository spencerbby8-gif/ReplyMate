package com.replymate.core.budget;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.Turn;
import java.util.ArrayList;
import java.util.List;

/** Input budget enforcement (BLUEPRINT §5.4). Estimate ceil(chars/4).
 *  Truncation order: drop OLDEST thread turns first; never drop system or task;
 *  keep at least one thread turn. L1/L2 trimming arrives with memory layers (P4). */
public final class TokenBudgeter {

    public static final int DEFAULT_MAX_INPUT = 6000;

    private TokenBudgeter() { }

    public static int estimate(String s) {
        return s == null || s.isEmpty() ? 0 : (s.length() + 3) / 4;
    }

    public static int estimate(ChatRequest req) {
        long total = estimate(req.system) + estimate(req.task == null ? "" : req.task.text);
        for (Turn t : req.turns) total += estimate(t.text) + 2;   // small per-turn overhead
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    /** Returns a request whose estimated input is <= maxTokens (best effort). */
    public static ChatRequest fit(ChatRequest req, int maxTokens) {
        if (estimate(req) <= maxTokens) return req;
        List<Turn> trimmed = new ArrayList<Turn>(req.turns);
        while (trimmed.size() > 1) {
            trimmed.remove(0);
            ChatRequest candidate = new ChatRequest(req.system, trimmed, req.task, req.opts);
            if (estimate(candidate) <= maxTokens) return candidate;
        }
        return new ChatRequest(req.system, trimmed, req.task, req.opts);
    }
}
