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

    /** P-intelligence-6 (directive 4): the Base URL field is ALWAYS editable for
     *  every provider — selecting a provider AUTO-SUGGESTS its official endpoint
     *  but a hand-typed value is sacred and never overwritten. */
    public boolean baseUrlEditable() {
        return true;
    }

    /** True when the text is still one of the built-in official endpoints
     *  (any provider's), i.e. NOT a value the user typed by hand. */
    public static boolean isAnyProviderDefault(String text) {
        String cur = text == null ? "" : text.trim();
        if (cur.isEmpty()) return false;
        for (ProviderType t : values()) {
            if (!t.defaultBaseUrl.isEmpty() && t.defaultBaseUrl.equals(cur)) return true;
        }
        return false;
    }

    /** The base URL the editor shows for a selected provider type: empty or
     *  another provider's official default ⇒ suggest THIS provider's official
     *  endpoint; a hand-typed value (or this provider's own default) ⇒ preserved.
     *  Providers with NO official endpoint (the open-compatible type) never
     *  touch the field at all — there is nothing to suggest, and the user's
     *  endpoint is the whole point of that type. */
    public static String resolveBaseUrlForUi(ProviderType t, String currentText) {
        if (t == null) return "";
        String current = currentText == null ? "" : currentText.trim();
        if (current.isEmpty()) return t.defaultBaseUrl;
        if (!t.defaultBaseUrl.isEmpty() && isAnyProviderDefault(current)
                && !t.defaultBaseUrl.equals(current)) {
            return t.defaultBaseUrl;   // switching providers re-suggests the official one
        }
        return current;                // hand-typed (or already correct) — keep it
    }
}
