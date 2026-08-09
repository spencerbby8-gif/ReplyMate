package com.replymate.core.caps;

import com.replymate.core.model.ProviderType;
import java.util.Locale;

/** P-intelligence-6: the docs-verified capability sheet for one (provider × model)
 *  pair — see docs/provider-capability-map.md for the research + citations.
 *  Pure pattern classification on the user-picked/discovered model id (model NAMES
 *  are never configuration; they're classified, and runtime degradation stays the
 *  honest backstop for a pattern that guesses wrong). */
public final class ModelCaps {

    /** How THIS provider pair gets live web facts. */
    public enum SearchTransport {
        /** The provider's own first-party search tool runs inside the generation. */
        NATIVE,
        /** No native tool in our dialect — ReplyMate retrieves from official free
         *  encyclopedia endpoints BEFORE generation and injects the evidence. */
        FALLBACK
    }

    /** Model-level reasoning support. */
    public enum Reasoning {
        /** Nothing documented — never send anything (never invent params). */
        UNSUPPORTED,
        /** An official per-request control exists and is sent when the level asks. */
        ADJUSTABLE,
        /** The model reasons by design (grok-4.x, magistral, kimi-k3) — no knob
         *  documented, so nothing is sent; billed reasoning tokens are metadata. */
        ALWAYS_ON
    }

    public final SearchTransport search;
    public final Reasoning reasoning;
    /** Audit-safe explanation shown on the provider page and in the why-line. */
    public final String note;

    private ModelCaps(SearchTransport search, Reasoning reasoning, String note) {
        this.search = search;
        this.reasoning = reasoning;
        this.note = note;
    }

    public static ModelCaps of(ProviderType type, String modelId) {
        if (type == null) type = ProviderType.OPENAI_COMPAT;
        String m = modelId == null ? "" : modelId.trim().toLowerCase(Locale.US);
        switch (type) {
            case GEMINI:
                if (isGemmaLike(m)) {
                    // gemma/embeddings/image models take NO tools, NO thinking —
                    // declared plainly so the gate never even attaches search.
                    return new ModelCaps(SearchTransport.FALLBACK, Reasoning.UNSUPPORTED,
                        "encyclopedia fallback (this model takes no native tools)");
                }
                return new ModelCaps(SearchTransport.NATIVE,
                    geminiThinks(m) ? Reasoning.ADJUSTABLE : Reasoning.UNSUPPORTED,
                    geminiThinks(m)
                        ? "native Google Search grounding + adjustable thinking"
                        : "native Google Search grounding");
            case OPENAI:
                return new ModelCaps(SearchTransport.NATIVE,
                    openAiReasons(m) ? Reasoning.ADJUSTABLE : Reasoning.UNSUPPORTED,
                    "native web search (Responses API)"
                        + (openAiReasons(m) ? " + reasoning effort" : ""));
            case OPENROUTER:
                return new ModelCaps(SearchTransport.NATIVE, Reasoning.ADJUSTABLE,
                    "OpenRouter web_search tool (native engine when the routed model"
                        + " has one, else Exa) + unified reasoning effort");
            case ANTHROPIC:
                return new ModelCaps(SearchTransport.NATIVE, Reasoning.ADJUSTABLE,
                    "native web_search server tool + thinking (adaptive on 4.6+,"
                        + " budget on older)");
            case GROK:
                return new ModelCaps(SearchTransport.NATIVE, Reasoning.ALWAYS_ON,
                    "native web_search via the xAI Responses API; grok reasons by"
                        + " design (no documented effort knob — none sent)");
            case KIMI:
                return new ModelCaps(SearchTransport.NATIVE,
                    m.startsWith("kimi-k3") ? Reasoning.ALWAYS_ON : Reasoning.ADJUSTABLE,
                    "native $web_search builtin (official echo loop); thinking"
                        + (m.startsWith("kimi-k3") ? " always on"
                            : " toggle (auto-off while searching — official rule)"));
            case DEEPSEEK:
                return new ModelCaps(SearchTransport.FALLBACK, Reasoning.ADJUSTABLE,
                    "encyclopedia fallback (no official search tool exists);"
                        + " thinking toggle + effort");
            case MISTRAL:
                if (m.startsWith("magistral")) {
                    return new ModelCaps(SearchTransport.FALLBACK, Reasoning.ALWAYS_ON,
                        "encyclopedia fallback (web_search isn't offered on chat"
                            + " completions); magistral reasons natively");
                }
                if (m.startsWith("mistral-small") || m.startsWith("ministral")) {
                    return new ModelCaps(SearchTransport.FALLBACK, Reasoning.ADJUSTABLE,
                        "encyclopedia fallback; adjustable reasoning_effort");
                }
                return new ModelCaps(SearchTransport.FALLBACK, Reasoning.UNSUPPORTED,
                    "encyclopedia fallback; no documented reasoning control"
                        + " for this model");
            case OLLAMA:
                return new ModelCaps(SearchTransport.FALLBACK, Reasoning.UNSUPPORTED,
                    "encyclopedia fallback (local models have no native search;"
                        + " hosted Ollama search needs a separate cloud key — not wired)");
            default:
                return new ModelCaps(SearchTransport.FALLBACK, Reasoning.UNSUPPORTED,
                    "encyclopedia fallback (capabilities of this endpoint are"
                        + " unknown — nothing non-standard is ever sent)");
        }
    }

    static boolean geminiThinks(String m) {
        return m.contains("2.5") || m.startsWith("gemini-3") || m.contains("-3.");
    }

    /** Tool-less Gemini-family models (no grounding, no thinking budgets). */
    static boolean isGemmaLike(String m) {
        return m.startsWith("gemma") || m.contains("embedding") || m.contains("imagen")
            || m.contains("tts") || m.contains("aqa") || m.contains("image");
    }

    static boolean openAiReasons(String m) {
        return m.startsWith("gpt-5") || m.startsWith("o1") || m.startsWith("o3")
            || m.startsWith("o4") || m.startsWith("gpt-o");
    }

    /** Provider/model family mismatch detection (P6 directive 4): the model id
     *  should belong to the selected provider's family. OpenRouter, Ollama and
     *  the generic compatible type accept anything (they legitimately host/rout
     *  many families). */
    public static boolean familyMatches(ProviderType type, String modelId) {
        if (type == null) return true;
        String m = modelId == null ? "" : modelId.trim().toLowerCase(Locale.US);
        if (m.isEmpty()) return true;   // nothing typed yet — nothing to flag
        switch (type) {
            case GEMINI:
                return m.startsWith("gemini") || m.startsWith("gemma")
                    || m.startsWith("learnlm") || m.startsWith("nano-banana")
                    || m.startsWith("imagen");
            case OPENAI:
                return m.startsWith("gpt") || m.startsWith("o1") || m.startsWith("o3")
                    || m.startsWith("o4") || m.startsWith("chatgpt")
                    || m.startsWith("dall") || m.startsWith("whisper")
                    || m.startsWith("text-embedding") || m.startsWith("tts");
            case ANTHROPIC:
                return m.startsWith("claude");
            case GROK:
                return m.startsWith("grok");
            case DEEPSEEK:
                return m.startsWith("deepseek");
            case KIMI:
                return m.startsWith("kimi") || m.contains("moonshot");
            case MISTRAL:
                return m.startsWith("mistral") || m.startsWith("magistral")
                    || m.startsWith("codestral") || m.startsWith("devstral")
                    || m.startsWith("mixtral") || m.startsWith("ministral")
                    || m.startsWith("pixtral") || m.startsWith("voxtral")
                    || m.startsWith("open-");
            default:
                return true;   // OPENROUTER / OLLAMA / OPENAI_COMPAT host anything
        }
    }

    /** One honest capability line for the provider page (never a promise we can't
     *  keep — graceful degradation and the audit trail cover the edge cases). */
    public String summary() {
        String s = search == SearchTransport.NATIVE
            ? "live web search: native (provider's official tool)"
            : "live web search: encyclopedia fallback (official, free)";
        String r = reasoning == Reasoning.ADJUSTABLE ? "reasoning: adjustable"
            : reasoning == Reasoning.ALWAYS_ON ? "reasoning: always on"
            : "reasoning: not documented";
        return s + " · " + r;
    }
}
