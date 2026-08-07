package com.replymate.core.prompt;

import com.replymate.core.model.ProviderRef;

/** Extra provenance written into every prompt-audit snapshot (P-context-honesty):
 *  WHICH provider/endpoint/model the request went to, and WHICH message was being
 *  answered. Carried beside the ChatRequest because the request itself must stay lean. */
public final class AuditContext {

    public final String providerWire;
    public final String providerLabel;
    public final String baseUrl;
    public final String providerModel;
    public final String endpoint;       // display form, e.g. "POST https://…/chat/completions"
    public final String latestText;     // latest usable incoming message being answered (may be "")
    public final String latestChannel;  // e.g. "WhatsApp" ("" when unknown)
    public final long latestAt;         // epoch ms (0 unknown)

    public AuditContext(String providerWire, String providerLabel, String baseUrl,
                        String providerModel, String endpoint, String latestText,
                        String latestChannel, long latestAt) {
        this.providerWire = providerWire == null ? "" : providerWire;
        this.providerLabel = providerLabel == null ? "" : providerLabel;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.providerModel = providerModel == null ? "" : providerModel;
        this.endpoint = endpoint == null ? "" : endpoint;
        this.latestText = latestText == null ? "" : latestText;
        this.latestChannel = latestChannel == null ? "" : latestChannel;
        this.latestAt = latestAt;
    }

    public static AuditContext of(ProviderRef ref, String latestText,
                                  String latestChannel, long latestAt) {
        if (ref == null) return null;
        return new AuditContext(ref.wire, ref.label, ref.baseUrl, ref.modelName,
            endpointFor(ref.wire, ref.baseUrl, ref.modelName), latestText, latestChannel, latestAt);
    }

    /** Display form of the generation endpoint for a configured provider.
     *  MUST mirror the real request builders — pinned by ProviderPathTest:
     *  GeminiPayloads.endpoint · OpenAiPayloads.chatEndpoint · AnthropicApi.messagesEndpoint. */
    public static String endpointFor(String wire, String baseUrl, String model) {
        String base = trimBase(baseUrl);
        if ("gemini".equals(wire)) {
            return "POST " + base + "/v1beta/models/" + (model == null ? "" : model) + ":generateContent";
        }
        if ("anthropic".equals(wire)) {
            return "POST " + base + "/v1/messages";
        }
        return "POST " + base + "/chat/completions";   // every OpenAI-compatible dialect
    }

    private static String trimBase(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
}
