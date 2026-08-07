import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.core.util.Logger;
import com.replymate.core.util.Result;
import com.replymate.provider.anthropic.AnthropicApi;
import com.replymate.provider.anthropic.AnthropicProvider;
import com.replymate.provider.gemini.GeminiPayloads;
import com.replymate.provider.gemini.GeminiProvider;
import com.replymate.provider.http.HttpClient;
import com.replymate.provider.http.HttpResponse;
import com.replymate.provider.http.RetryPolicy;
import com.replymate.provider.openai.OpenAiCompatProvider;
import com.replymate.provider.openai.OpenAiPayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Live provider-audit probe (P-provider-audit). Runs ReplyMate's REAL provider classes
 *  against the REAL provider endpoint and prints the complete mandated transcript:
 *  request URL, method, headers (keys redacted to last 4), request body, HTTP status,
 *  raw response body, and how ReplyMate maps the reply (variants or full diagnostics).
 *
 *  Usage: java LiveProbe <wireType> <baseUrl> <model> <apiKey|-> ["badModelProbe"]
 *  Never prints more than the last 4 chars of the API key. */
public final class LiveProbe {

    static final Logger LOG = new Logger() {
        public void d(String t, String m) { System.out.println("  [log.d " + t + "] " + m); }
        public void i(String t, String m) { System.out.println("  [log.i " + t + "] " + m); }
        public void w(String t, String m) { System.out.println("  [log.w " + t + "] " + m); }
        public void e(String t, String m) { System.out.println("  [log.e " + t + "] " + m); }
        public void e(String t, String m, Throwable x) { System.out.println("  [log.e " + t + "] " + m); }
    };

    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("usage: LiveProbe <wireType> <baseUrl> <model> <apiKey|-> [badModelProbe]");
            System.exit(2);
        }
        String wire = args[0], base = args[1], model = args[2], key = args[3];
        String badModel = args.length > 4 ? "replymate-audit-no-such-model" : null;
        HttpClient http = new HttpClient();

        System.out.println("################ REPLYMATE LIVE PROVIDER PROBE ################");
        System.out.println("provider wire : " + wire);
        System.out.println("base URL      : " + base);
        System.out.println("model         : " + model);
        System.out.println("api key       : " + redact(key));

        // ---- 1. model discovery: raw wire + provider-level mapping ----
        String modelsUrl;
        Map<String, String> headers;
        if ("gemini".equals(wire)) {
            modelsUrl = GeminiPayloads.modelsEndpoint(base);
            headers = GeminiPayloads.headers(key);
        } else if ("anthropic".equals(wire)) {
            modelsUrl = AnthropicApi.modelsEndpoint(base);
            headers = AnthropicApi.headers(key);
        } else {
            modelsUrl = OpenAiPayloads.modelsEndpoint(base);
            headers = OpenAiPayloads.headers(key);
        }
        transcriptRequest("GET", modelsUrl, headers, null);
        HttpResponse modelsRaw = http.get(modelsUrl, headers);
        transcriptResponse(modelsRaw);

        // ---- 2. provider-level discovery (what the app would show) ----
        com.replymate.core.ports.AiProvider provider = build(wire, base, model, key, http);
        Result<List<String>> listed = provider.listModels();
        if (listed.ok) {
            System.out.println("APP MAPPING (listModels): OK — " + listed.value.size() + " models");
            int n = 0;
            for (String m : listed.value) { if (++n <= 60) System.out.println("   - " + m); }
            if (listed.value.size() > 60) System.out.println("   … (" + (listed.value.size() - 60) + " more)");
        } else {
            System.out.println("APP MAPPING (listModels): ERROR ↓\n" + indent(listed.error));
        }

        // ---- 3. generation: raw wire + provider-level mapping ----
        ChatRequest req = sampleRequest();
        String genUrl, genBody;
        if ("gemini".equals(wire)) {
            genUrl = GeminiPayloads.endpoint(base, model);
            genBody = GeminiPayloads.generateBody(req);
        } else if ("anthropic".equals(wire)) {
            genUrl = AnthropicApi.messagesEndpoint(base);
            genBody = AnthropicApi.messagesBody(req, model);
        } else {
            genUrl = OpenAiPayloads.chatEndpoint(base);
            genBody = OpenAiPayloads.chatBody(req, model, true);
        }
        transcriptRequest("POST", genUrl, headers, genBody);
        HttpResponse genRaw = http.post(genUrl, headers, genBody);
        transcriptResponse(genRaw);

        Result<ChatReply> generated = provider.generate(req);
        if (generated.ok) {
            System.out.println("APP MAPPING (generate): OK — " + generated.value.variants.size()
                + " variant(s), tokens in=" + generated.value.tokensIn + " out=" + generated.value.tokensOut);
            int n = 0;
            for (String v : generated.value.variants) {
                System.out.println("   variant " + (++n) + ": \"" + v + "\"");
            }
        } else {
            System.out.println("APP MAPPING (generate): ERROR ↓\n" + indent(generated.error));
        }

        // ---- 4. wrong-model error shape (mapping must stay honest) ----
        if (badModel != null) {
            System.out.println("\n---- wrong-model probe: \"" + badModel + "\" ----");
            com.replymate.core.ports.AiProvider bad = build(wire, base, badModel, key, http);
            Result<ChatReply> r = bad.generate(req);
            System.out.println(r.ok
                ? "APP MAPPING (wrong model): unexpectedly OK — " + r.value.variants.get(0)
                : "APP MAPPING (wrong model): ERROR ↓\n" + indent(r.error));
        }
        System.out.println("################ END PROBE ################");
    }

    static ChatRequest sampleRequest() {
        List<Turn> turns = new ArrayList<Turn>();
        turns.add(Turn.user("Amara: hey, you still coming through on Saturday?"));
        turns.add(Turn.user("Reply as the owner, one short natural text."));
        return new ChatRequest("You write chat replies as the owner.", turns,
            Turn.user("Write the reply now."), GenerationOpts.of(2, 0.8, 220));
    }

    static com.replymate.core.ports.AiProvider build(String wire, String base, String model,
                                                     String key, HttpClient http) {
        RetryPolicy retry = new RetryPolicy(new java.util.Random(42));
        if ("gemini".equals(wire)) return new GeminiProvider(base, model, key, http, retry, LOG);
        if ("anthropic".equals(wire)) return new AnthropicProvider(base, model, key, http, retry, LOG);
        return new OpenAiCompatProvider(wire, base, model, key, http, retry, LOG);
    }

    static void transcriptRequest(String method, String url, Map<String, String> headers, String body) {
        System.out.println("\n==> REQUEST");
        System.out.println(method + " " + url);
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) {
                System.out.println("  header: " + h.getKey() + ": " + redactValue(h.getKey(), h.getValue()));
            }
        }
        if (body != null) System.out.println("  body: " + body);
    }

    static void transcriptResponse(HttpResponse r) {
        System.out.println("<== RESPONSE");
        System.out.println("  HTTP status: " + (r.code < 0 ? "NO RESPONSE (transport: "
            + r.header("x-error") + ")" : String.valueOf(r.code)));
        String b = r.body == null ? "" : r.body;
        System.out.println("  raw body: " + (b.length() > 1200 ? b.substring(0, 1200) + "…[truncated]" : b));
        System.out.println();
    }

    static String redact(String key) {
        if (key == null || key.isEmpty() || "-".equals(key)) return "(none)";
        return "(set, …" + key.substring(Math.max(0, key.length() - 4)) + ", " + key.length() + " chars)";
    }

    static String redactValue(String name, String value) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        if (n.contains("key") || n.contains("authorization") || n.contains("token")) {
            return redact(value);
        }
        return value;
    }

    static String indent(String block) {
        return "  " + (block == null ? "(null)" : block.replace("\n", "\n  "));
    }
}
