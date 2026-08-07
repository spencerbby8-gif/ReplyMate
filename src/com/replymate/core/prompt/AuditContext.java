package com.replymate.core.prompt;

import com.replymate.core.model.ProviderRef;

/** Extra provenance written into every prompt-audit snapshot (P-context-honesty,
 *  extended P-audit-deep): WHICH provider/endpoint/model the request went to,
 *  WHICH message was being answered (its text, channel, app, content kind, media
 *  availability), WHO/WhatsApp-style-free source identity was behind it and WHY
 *  the reply was generated. Carried beside the ChatRequest because the request
 *  itself must stay lean. */
public final class AuditContext {

    public final String providerWire;
    public final String providerLabel;
    public final String baseUrl;
    public final String providerModel;
    public final String endpoint;       // display form, e.g. "POST https://…/chat/completions"
    public final String latestText;     // latest usable incoming message being answered (may be "")
    public final String latestChannel;  // e.g. "WhatsApp" ("" when unknown)
    public final long latestAt;         // epoch ms (0 unknown)
    public final String latestKind;     // ContentKind.wire of the answered item ("" unknown)
    public final String latestPackage;  // source app's package ("" when unknown/manual)
    public final boolean mediaRefAvailable; // a media reference was captured (local only)
    public final String sourceIdentity; // resolved remote key, e.g. "cid:234…@s.whatsapp.net"
    public final String sourceConfidence; // high | medium | low
    public final String reason;         // why this reply was generated (may be "")

    public AuditContext(String providerWire, String providerLabel, String baseUrl,
                        String providerModel, String endpoint, String latestText,
                        String latestChannel, long latestAt) {
        this(providerWire, providerLabel, baseUrl, providerModel, endpoint, latestText,
            latestChannel, latestAt, "", "", false, "", "", "");
    }

    public AuditContext(String providerWire, String providerLabel, String baseUrl,
                        String providerModel, String endpoint, String latestText,
                        String latestChannel, long latestAt, String latestKind,
                        String latestPackage, boolean mediaRefAvailable,
                        String sourceIdentity, String sourceConfidence, String reason) {
        this.providerWire = empty(providerWire);
        this.providerLabel = empty(providerLabel);
        this.baseUrl = empty(baseUrl);
        this.providerModel = empty(providerModel);
        this.endpoint = empty(endpoint);
        this.latestText = empty(latestText);
        this.latestChannel = empty(latestChannel);
        this.latestAt = latestAt;
        this.latestKind = empty(latestKind);
        this.latestPackage = empty(latestPackage);
        this.mediaRefAvailable = mediaRefAvailable;
        this.sourceIdentity = empty(sourceIdentity);
        this.sourceConfidence = empty(sourceConfidence);
        this.reason = empty(reason);
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

    private static String empty(String s) { return s == null ? "" : s; }
}
