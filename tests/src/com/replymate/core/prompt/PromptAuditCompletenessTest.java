package com.replymate.core.prompt;

import com.replymate.core.json.Json;
import com.replymate.core.json.JsonArr;
import com.replymate.core.json.JsonObj;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.DraftStatus;
import com.replymate.core.model.Message;
import com.replymate.core.usecase.DraftOutcome;
import com.replymate.core.usecase.DraftService;
import com.replymate.core.usecase.ProfileService;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression (P-memory-audit): the Prompt Audit snapshot must carry EVERY field the
 *  owner listed — exact latest message, recent thread history used, long-term memory
 *  used, source app, content type, source identity, provider, endpoint, model,
 *  request settings, and why the reply was generated — and the request settings must
 *  be the ACTUAL generation options used for the call. */
public class PromptAuditCompletenessTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.DraftStoreFake drafts;
    private Fakes.KvStoreFake kv;
    private DraftService service;
    private Fakes.FakeProvider provider;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        drafts = new Fakes.DraftStoreFake();
        kv = new Fakes.KvStoreFake();
        Fakes.MemoryStoreFake memoryStore = new Fakes.MemoryStoreFake();
        com.replymate.core.memory.MemoryService memoryService =
            new com.replymate.core.memory.MemoryService(memoryStore, messages, kv,
                Fakes.FIXED_CLOCK);
        provider = Fakes.FakeProvider.returning("omo nice");

        Contact amara = Fakes.contact(1, "Amara");
        amara.relationshipType = "close friend";
        contacts.put(amara);
        com.replymate.core.model.ContactChannel ch = new com.replymate.core.model.ContactChannel();
        ch.contactId = 1;
        ch.channel = com.replymate.core.model.Channel.WHATSAPP;
        ch.remoteKey = "cid:2348012345678@s.whatsapp.net";
        contacts.channels.add(ch);

        for (int i = 1; i <= 40; i++) {
            Message m = Fakes.msg(1,
                i % 2 == 0 ? Direction.OUTGOING : Direction.INCOMING,
                "history " + i + " meeting tomorrow " + (2 + i % 5) + "pm?");
            m.channel = com.replymate.core.model.Channel.WHATSAPP;
            m.sentAt = Fakes.NOW + i * 60_000;
            messages.add(m);
        }
        Message latest = Fakes.msg(1, Direction.INCOMING, "so is 4pm still on?");
        latest.channel = com.replymate.core.model.Channel.WHATSAPP;
        latest.sentAt = Fakes.NOW + 41 * 60_000;
        latest.senderName = "Amara";
        messages.add(latest);

        memoryService.replacePinnedFacts(1, "Amara runs a logistics business in PH");
        for (int i = 0; i < 4; i++) {
            Draft d = new Draft();
            d.contactId = 1;
            d.replyText = "omw now " + i;
            d.status = DraftStatus.COPIED;
            d.model = "test-model";
            d.variantGroup = "g" + i;
            d.createdAt = Fakes.NOW;
            drafts.insert(d);
        }

        service = new DraftService(contacts, messages, new Fakes.StyleStoreFake(),
            new ProfileService(kv), drafts, new Fakes.UsageStoreFake(),
            new Fakes.GatewayFake(provider), Fakes.IDS, Fakes.FIXED_CLOCK,
            Fakes.NOOP_LOG, null, null, memoryService);
    }

    @Test public void snapshotCarriesEveryMandatedField() throws Exception {
        Result<DraftOutcome> r = service.generateForContact(1);
        assertTrue(String.valueOf(r.error), r.ok);
        String json = r.value.drafts.get(0).promptSnapshotJson;   // THIS call's draft
        JsonObj root = Json.parseObj(json);

        // exact latest message + source app + content type + source identity + confidence
        JsonObj latest = root.obj("latestIncoming");
        assertNotNull(latest);
        assertEquals("so is 4pm still on?", latest.str("text"));
        assertEquals("WhatsApp", latest.str("channel"));
        assertEquals("com.whatsapp", latest.str("app"));
        assertEquals("text", latest.str("contentType"));
        assertEquals("Amara", latest.str("sender"));
        JsonObj source = root.obj("source");
        assertEquals("cid:2348012345678@s.whatsapp.net", source.str("identity"));
        assertEquals("high", source.str("confidence"));

        // provider + endpoint + model + the ACTUAL request settings used
        JsonObj prov = root.obj("provider");
        assertEquals("Google Gemini", prov.str("label"));
        assertEquals("https://generativelanguage.googleapis.com", prov.str("baseUrl"));
        assertEquals("test-model", prov.str("model"));
        assertTrue(prov.str("endpoint"), prov.str("endpoint").startsWith(
            "POST https://generativelanguage.googleapis.com/v1beta/models/test-model"));
        assertEquals("0.8", String.valueOf(root.raw("temperature")));
        assertEquals("3", String.valueOf(root.raw("candidateCount")));
        assertEquals("220", String.valueOf(root.raw("maxOutputTokens")));
        assertEquals("reply", root.str("kind"));

        // recent thread history used
        assertTrue(root.lng("contextTurns", 0) >= 30);
        JsonArr turns = root.arr("turns");
        assertNotNull(turns);
        boolean sawLatestInTurns = false;
        String allTurns = turns.toJson();
        sawLatestInTurns = allTurns.contains("so is 4pm still on?");
        assertTrue("hot history must include the latest message", sawLatestInTurns);
        assertTrue(allTurns.contains("history 40"));

        // long-term memory used (rolling summary + pinned facts + learned style)
        JsonObj mem = root.obj("memory");
        assertNotNull("memory block required", mem);
        assertTrue(mem.str("summary"), mem.str("summary").contains("history"));
        assertTrue(mem.str("summaryMeta"), mem.str("summaryMeta").contains("summary v1"));
        JsonArr facts = mem.arr("facts");
        assertTrue(facts.toJson().contains("logistics business in PH"));
        JsonArr style = mem.arr("learnedStyle");
        assertTrue(style.toJson().contains("keep it short"));

        // why the reply was generated
        String reason = root.str("reason");
        assertTrue(reason, reason.contains("Amara"));
        assertTrue(reason, reason.contains("WhatsApp"));
        assertTrue(reason, reason.toLowerCase().contains("manual reply request"));
        JsonArr why = root.arr("why");
        assertTrue(why.toJson(), why.toJson().contains("memory facts applied"));
        assertTrue(why.toJson(), why.toJson().contains("learned style applied"));
    }

    @Test public void snapshotMatchesExactlyWhatCrossedTheWire() throws Exception {
        Result<DraftOutcome> r = service.generateForContact(1);
        assertTrue(String.valueOf(r.error), r.ok);
        String json = r.value.drafts.get(0).promptSnapshotJson;   // THIS call's draft
        JsonObj root = Json.parseObj(json);
        // system + task in the snapshot are byte-identical to the provider call
        assertEquals(provider.lastRequest.system, root.str("system"));
        assertEquals(provider.lastRequest.task.text, root.str("task"));
        // and the exact latest message crossed the wire in BOTH the task and a turn
        assertTrue(provider.lastRequest.task.text.contains("so is 4pm still on?"));
    }
}
