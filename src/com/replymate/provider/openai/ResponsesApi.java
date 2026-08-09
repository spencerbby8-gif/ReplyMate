package com.replymate.provider.openai;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.Turn;
import com.replymate.core.json.Json;
import com.replymate.core.json.JsonArr;
import com.replymate.core.json.JsonObj;
import com.replymate.core.util.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** P-intelligence-6: the OpenAI Responses dialect —
 *    POST {base}/responses
 *  spoken by OpenAI (api.openai.com/v1) and xAI (api.x.ai/v1), wire-verified
 *  against both providers' official docs (platform.openai.com Responses reference,
 *  docs.x.ai/developers/tools/web-search). Used ONLY where the capability needs it
 *  (native web search, or reasoning on OpenAI's reasoning models); plain replies
 *  keep the chat-completions path.
 *
 *  Privacy choices baked in: "store": false (the provider must not retain the
 *  request) and reasoning items are requested WITHOUT any disclosure — whatever
 *  the model thinks privately stays at the provider; we read ONLY output_text /
 *  usage counters, and reasoning items are skipped on purpose. */
public final class ResponsesApi {

    private ResponsesApi() { }

    public static String endpoint(String baseUrl) {
        return OpenAiPayloads.trimBase(baseUrl) + "/responses";
    }

    /** Build the request. search attaches {"type":"web_search"} with
     *  tool_choice "auto" (the model searches only when useful — never forced);
     *  reasoning LOW/HIGH maps to reasoning.effort "low"/"high" (documented levels;
     *  DEFAULT sends nothing). max_output_tokens carries headroom for billed
     *  reasoning tokens (OpenAI counts them inside that cap — documented). */
    public static String body(ChatRequest req, String model, boolean search,
                              String reasoning) {
        JsonArr input = JsonArr.create();
        for (Turn t : req.turns) {
            input.add(JsonObj.create()
                .put("role", t.role == Turn.Role.USER ? "user" : "assistant")
                .put("content", t.text));
        }
        if (req.task != null) {
            input.add(JsonObj.create().put("role", "user").put("content", req.task.text));
        }
        JsonObj body = JsonObj.create()
            .put("model", model)
            .put("store", false)
            .put("input", input);
        if (req.system != null && !req.system.trim().isEmpty()) {
            body.put("instructions", req.system);
        }
        if (search) {
            body.put("tools", JsonArr.create()
                .add(JsonObj.create().put("type", "web_search")));
            body.put("tool_choice", "auto");
        }
        boolean reasons = "low".equals(reasoning) || "high".equals(reasoning);
        if (reasons) {
            body.put("reasoning", JsonObj.create().put("effort", reasoning));
        }
        int budget = req.opts.maxOutputTokens + (reasons ? 1500 : 0);
        body.put("max_output_tokens", Math.max(budget, 256));
        // temperature is documented as unsupported on reasoning models; it is only
        // meaningful on the plain path, which never reaches this dialect.
        return body.toJson();
    }

    /** Parse a 2xx Responses body. Reply text comes exclusively from
     *  output[].type=="message" content[].type=="output_text" entries (fallback:
     *  the convenience `output_text` string). `reasoning` items are ignored.
     *  Counters: web_search_call items → searchQueries; url_citation annotation
     *  titles → searchSources; usage.output_tokens_details.reasoning_tokens →
     *  reasoningTokens. */
    public static Result<ChatReply> parseReply(String body) {
        try {
            JsonObj root = Json.parseObj(body);
            int searchCalls = 0, reasoningTokens = 0;
            List<String> sources = new ArrayList<String>();
            List<String> texts = new ArrayList<String>();
            Object statusRaw = root.raw("status");
            Object outputRaw = root.raw("output");
            if (outputRaw instanceof List) {
                for (Object iRaw : (List<?>) outputRaw) {
                    if (!(iRaw instanceof Map)) continue;
                    Map<?, ?> item = (Map<?, ?>) iRaw;
                    Object type = item.get("type");
                    if ("web_search_call".equals(type)) { searchCalls++; continue; }
                    if (!"message".equals(type)) continue;   // reasoning etc: ignored
                    Object content = item.get("content");
                    if (!(content instanceof List)) continue;
                    for (Object cRaw : (List<?>) content) {
                        if (!(cRaw instanceof Map)) continue;
                        Map<?, ?> c = (Map<?, ?>) cRaw;
                        if (!"output_text".equals(c.get("type"))) continue;
                        Object text = c.get("text");
                        if (text instanceof String && !((String) text).trim().isEmpty()) {
                            texts.add(((String) text).trim());
                        }
                        Object ann = c.get("annotations");
                        if (ann instanceof List) {
                            for (Object aRaw : (List<?>) ann) {
                                if (!(aRaw instanceof Map)) continue;
                                if (!"url_citation".equals(((Map<?, ?>) aRaw).get("type"))) continue;
                                Object title = ((Map<?, ?>) aRaw).get("title");
                                if (title instanceof String && !((String) title).trim().isEmpty()
                                        && !sources.contains(title)) {
                                    sources.add(((String) title).trim());
                                }
                            }
                        }
                    }
                }
            }
            if (texts.isEmpty()) {
                String ot = root.str("output_text");   // convenience field, if present
                if (ot != null && !ot.trim().isEmpty()) texts.add(ot.trim());
            }
            if (texts.isEmpty()) {
                return Result.err("PARSE — provider reply had no message text"
                    + ("incomplete".equals(statusRaw) ? " (status: incomplete)" : ""));
            }
            int tin = 0, tout = 0;
            Object usageRaw = root.raw("usage");
            if (usageRaw instanceof Map) {
                Map<?, ?> u = (Map<?, ?>) usageRaw;
                tin = intOf(u.get("input_tokens"));
                tout = intOf(u.get("output_tokens"));
                Object det = u.get("output_tokens_details");
                if (det instanceof Map) {
                    reasoningTokens = intOf(((Map<?, ?>) det).get("reasoning_tokens"));
                }
            }
            return Result.ok(new ChatReply(texts, tin, tout, null,
                searchCalls, sources, reasoningTokens, ""));
        } catch (RuntimeException boom) {
            return Result.err("PARSE — " + boom.getMessage());
        }
    }

    /** xAI reports per-tool counts here (usage.server_side_tool_usage /
     *  usage.server_side_tool_usage_details) — read opportunistically; absence
     *  means "not reported", never an error. */
    @SuppressWarnings("unchecked")
    public static int xaiToolCalls(String body) {
        try {
            JsonObj root = Json.parseObj(body);
            Object usageRaw = root.raw("usage");
            if (!(usageRaw instanceof Map)) return 0;
            Object sstu = ((Map<?, ?>) usageRaw).get("server_side_tool_usage");
            if (sstu instanceof Map) {
                int n = 0;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) sstu).entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Number) n += ((Number) v).intValue();
                }
                return n;
            }
        } catch (RuntimeException ignore) { }
        return 0;
    }

    private static int intOf(Object n) {
        return n instanceof Number ? ((Number) n).intValue() : 0;
    }
}
