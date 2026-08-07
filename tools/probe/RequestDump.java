import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.Turn;
import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.usecase.DraftOutcome;
import com.replymate.core.usecase.DraftService;
import com.replymate.core.usecase.ProfileService;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import com.replymate.provider.gemini.GeminiPayloads;
import com.replymate.provider.http.HttpClient;
import com.replymate.provider.http.HttpResponse;

/** Exit-gate evidence (P-context-honesty): composes the REAL reply request through the
 *  REAL DraftService + PromptBuilder (fakes only for storage), prints the FULL final
 *  provider request body, then sends those exact bytes to Gemini live (dummy key) so the
 *  transcript proves both the request we build and the provider's response to it.
 *  Exits non-zero if the latest incoming message does not reach the wire body. */
public final class RequestDump {

    public static void main(String[] args) {
        String latest = "omg yes, Saturday 4pm — don't be late o!";

        Fakes.ContactStoreFake contacts = new Fakes.ContactStoreFake();
        Fakes.MessageStoreFake messages = new Fakes.MessageStoreFake();
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ProfileService profiles = new ProfileService(kv);
        Fakes.DraftStoreFake drafts = new Fakes.DraftStoreFake();
        contacts.put(Fakes.contact(1, "Amara"));
        messages.add(Fakes.msg(1, Direction.INCOMING, "you around this weekend?"));
        messages.add(Fakes.msg(1, Direction.OUTGOING, "yeah, free on Saturday"));
        messages.add(Fakes.msg(1, Direction.INCOMING, latest));

        Fakes.LearningStoreFake ls = new Fakes.LearningStoreFake();
        LearningService learning = Fakes.learningService(ls, new Fakes.KvStoreFake());
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("omo, 4pm it is!");
        DraftService svc = new DraftService(contacts, messages, new Fakes.StyleStoreFake(),
            profiles, drafts, new Fakes.UsageStoreFake(),
            new Fakes.GatewayFake(provider), Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(new Fakes.StyleSettingStoreFake(), learning), learning);

        Result<DraftOutcome> r = svc.generateForContact(1);
        if (!r.ok) { System.out.println("GENERATION FAILED: " + r.error); System.exit(2); }

        ChatRequest req = provider.lastRequest;
        System.out.println("############ REQUEST COMPOSITION (real app code path) ############");
        System.out.println("--- system prompt (" + req.system.length() + " chars) ---");
        System.out.println(req.system.length() > 400 ? req.system.substring(0, 400) + "…" : req.system);
        System.out.println("--- conversation turns (" + req.turns.size() + ") ---");
        for (Turn t : req.turns) System.out.println("  [" + t.role + "] " + t.text);
        System.out.println("--- task turn ---");
        System.out.println("  " + req.task.text);

        String body = GeminiPayloads.generateBody(req);
        System.out.println("\n############ FINAL WIRE BODY (exact bytes sent to provider) ############");
        System.out.println(body);

        String url = GeminiPayloads.endpoint("https://generativelanguage.googleapis.com",
            "gemini-3.5-flash-lite");
        System.out.println("\n############ LIVE SEND (dummy key — proves the wire + error honesty) ############");
        System.out.println("POST " + url);
        System.out.println("  header: x-goog-api-key: (set, …UMMY, 5 chars)");
        HttpResponse resp = new HttpClient().post(url, GeminiPayloads.headers("DUMMY"), body);
        System.out.println("  HTTP status: " + resp.code);
        String rb = resp.body == null ? "" : resp.body;
        System.out.println("  raw body: " + (rb.length() > 600 ? rb.substring(0, 600) + "…" : rb));

        System.out.println("\n############ GATE CHECK ############");
        boolean inTurns = false;
        for (Turn t : req.turns) if (t.text.contains(latest)) inTurns = true;
        boolean inTask = req.task.text.contains(latest);
        boolean inWire = body.contains(latest);
        boolean inSnapshot = drafts.saved.get(0).promptSnapshotJson.contains(latest);
        System.out.println("latest message in conversation turns : " + inTurns);
        System.out.println("latest message quoted in task turn   : " + inTask);
        System.out.println("latest message in final wire body    : " + inWire);
        System.out.println("latest message in audit snapshot     : " + inSnapshot);
        if (!(inTurns && inWire && inSnapshot)) {
            System.out.println("GATE FAILED — the model would have answered without the latest message");
            System.exit(1);
        }
        System.out.println("GATE PASSED — the latest incoming message reaches the provider verbatim.");
    }
}
