package com.replymate.core.model;

/** AI provider kinds (P-polish: true provider abstraction). Every supported provider +
 *  the generic OpenAI-compatible adapter. Values know their WIRE id (stored in the DB),
 *  display name, API dialect, default base URL (editable at config time) and whether an
 *  API key is required. MODEL NAMES ARE NEVER HARDCODED — the user picks one from live
 *  provider discovery (or types it); defaults here are endpoints, never models. */
public enum ProviderType {
    GEMINI("gemini", "Google Gemini", ApiStyle.GEMINI,
        "https://generativelanguage.googleapis.com", true),
    OPENAI("openai", "OpenAI", ApiStyle.OPENAI,
        "https://api.openai.com/v1", true),
    OPENROUTER("openrouter", "OpenRouter", ApiStyle.OPENAI,
        "https://openrouter.ai/api/v1", true),
    ANTHROPIC("anthropic", "Anthropic (Claude)", ApiStyle.ANTHROPIC,
        "https://api.anthropic.com", true),
    DEEPSEEK("deepseek", "DeepSeek", ApiStyle.OPENAI,
        "https://api.deepseek.com", true),
    GROK("grok", "Grok (xAI)", ApiStyle.OPENAI,
        "https://api.x.ai/v1", true),
    KIMI("kimi", "Kimi (Moonshot)", ApiStyle.OPENAI,
        "https://api.moonshot.ai/v1", true),
    MISTRAL("mistral", "Mistral", ApiStyle.OPENAI,
        "https://api.mistral.ai/v1", true),
    OLLAMA("ollama", "Ollama (local)", ApiStyle.OPENAI,
        "http://localhost:11434/v1", false),
    OPENAI_COMPAT("openai_compat", "Other (OpenAI-compatible)", ApiStyle.OPENAI,
        "", true);

    /** Request/response dialect an adapter must speak. */
    public enum ApiStyle { GEMINI, OPENAI, ANTHROPIC }

    public final String wire;
    public final String label;
    public final ApiStyle apiStyle;
    /** Default base URL (user-editable; empty = must be provided, e.g. custom). */
    public final String defaultBaseUrl;
    /** Whether the provider requires an API key (Ollama does not). */
    public final boolean needsKey;

    ProviderType(String wire, String label, ApiStyle apiStyle, String defaultBaseUrl,
                 boolean needsKey) {
        this.wire = wire;
        this.label = label;
        this.apiStyle = apiStyle;
        this.defaultBaseUrl = defaultBaseUrl;
        this.needsKey = needsKey;
    }

    /** From a stored wire id; never throws on legacy/unknown values (schema v4 widened
     *  the CHECK away): unknown types map to the generic OpenAI-compatible adapter so
     *  an unrecognized row degrades gracefully instead of crashing the app. */
    public static ProviderType fromWire(String w) {
        for (ProviderType t : values()) if (t.wire.equals(w)) return t;
        return OPENAI_COMPAT;
    }
}
