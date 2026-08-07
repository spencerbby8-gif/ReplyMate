package com.replymate.provider;

import com.replymate.core.model.ProviderDef;
import com.replymate.core.ports.AiProvider;
import com.replymate.core.util.Logger;
import com.replymate.provider.anthropic.AnthropicProvider;
import com.replymate.provider.gemini.GeminiProvider;
import com.replymate.provider.http.HttpClient;
import com.replymate.provider.http.RetryPolicy;
import com.replymate.provider.openai.OpenAiCompatProvider;

/** Builds the right adapter for a stored provider config (P-polish provider
 *  abstraction). The ONLY place dialect choice happens; the app layer never
 *  references a concrete provider class. */
public final class ProviderFactory {

    private ProviderFactory() { }

    public static AiProvider build(ProviderDef def, String apiKey, HttpClient http,
                                   RetryPolicy retry, Logger log) {
        switch (def.type.apiStyle) {
            case GEMINI:
                return new GeminiProvider(def.baseUrl, def.modelName, apiKey, http, retry, log);
            case ANTHROPIC:
                return new AnthropicProvider(def.baseUrl, def.modelName, apiKey, http, retry, log);
            case OPENAI:
            default:
                return new OpenAiCompatProvider(def.type.wire, def.baseUrl, def.modelName,
                    apiKey, http, retry, log);
        }
    }
}
