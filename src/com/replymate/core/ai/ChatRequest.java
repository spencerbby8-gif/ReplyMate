package com.replymate.core.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Provider-agnostic chat generation request (assembled by core.prompt.PromptBuilder). */
public final class ChatRequest {
    public final String system;                 // L0 (+L1/L2 when memory layers activate)
    public final List<Turn> turns;              // L3 thread
    public final Turn task;                     // L4 task turn
    public final GenerationOpts opts;

    public ChatRequest(String system, List<Turn> turns, Turn task, GenerationOpts opts) {
        this.system = system == null ? "" : system;
        List<Turn> copy = new ArrayList<Turn>();
        if (turns != null) copy.addAll(turns);
        this.turns = Collections.unmodifiableList(copy);
        this.task = task;
        this.opts = opts == null ? GenerationOpts.defaults() : opts;
    }
}
