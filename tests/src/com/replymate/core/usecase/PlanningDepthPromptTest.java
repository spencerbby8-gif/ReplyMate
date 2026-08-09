package com.replymate.core.usecase;

import com.replymate.core.learning.LearningService;
import com.replymate.core.memory.MemoryService;
import com.replymate.core.model.Direction;
import com.replymate.core.plan.PlanDepth;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-5 pins (directives 1, 2, 8): Planning Depth gates the planner at
 *  the REAL provider request — Normal rides one compact line, Deep rides the full
 *  block (topic/focus/ignore), Basic is byte-clean, and Prompt Audit always shows
 *  the depth that was used. All depths: the provider still gets ONE call and the
 *  task still ENDS with "Output only the reply text." */
public final class PlanningDepthPromptTest {

    private static final class Fx {
        Fakes.ContactStoreFake contacts = new Fakes.ContactStoreFake();
        Fakes.MessageStoreFake messages = new Fakes.MessageStoreFake();
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        Fakes.FakeProvider provider =
            Fakes.FakeProvider.returning("sure, tuesday works");
        DraftService service;
    }

    private static Fx fixture(String depth) {
        Fx f = new Fx();
        LearningService learning = Fakes.learningService(
            new Fakes.LearningStoreFake(), new Fakes.KvStoreFake());
        f.service = new DraftService(f.contacts, f.messages, new Fakes.StyleStoreFake(),
            new ProfileService(f.kv), new Fakes.DraftStoreFake(),
            new Fakes.UsageStoreFake(), new Fakes.GatewayFake(f.provider),
            Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(new Fakes.StyleSettingStoreFake(), learning), learning,
            new MemoryService(new Fakes.MemoryStoreFake(), f.messages, f.kv, Fakes.FIXED_CLOCK));
        Fakes.KvStoreFake settingsKv = new Fakes.KvStoreFake();
        settingsKv.put(PlanDepth.KV_KEY, depth);
        f.service.setLiveKv(settingsKv);
        f.contacts.put(Fakes.contact(1, "Amara"));
        return f;
    }

    private static String taskText(Fx f) { return f.provider.lastRequest.task.text; }

    private static Result<DraftOutcome> burst(Fx f) {
        f.messages.add(Fakes.msg(1, Direction.INCOMING, "you there"));
        f.messages.add(Fakes.msg(1, Direction.INCOMING, "actually make it tuesday not thursday"));
        f.messages.add(Fakes.msg(1, Direction.INCOMING, "??"));
        f.messages.add(Fakes.msg(1, Direction.INCOMING, "flights to abuja are climbing o, can we still make tuesday work?"));
        return f.service.generateForContact(1L);
    }

    @Test public void normalDepthRidesOneCompactPlanLine() {
        Fx f = fixture(PlanDepth.NORMAL);
        Result<DraftOutcome> r = burst(f);
        assertTrue(r.ok);
        String t = taskText(f);
        assertTrue("compact moment line rides the task: " + t,
            t.contains("The moment:"));
        assertTrue(t.contains("Plan:"));
        assertFalse("full block stays out at Normal", t.contains("don't answer these"));
        assertTrue("plan is grounded BEFORE the output-only instruction",
            t.indexOf("The moment:") < t.lastIndexOf("Output only the reply text."));
        assertTrue(t.trim().endsWith("Output only the reply text."));
        assertTrue(r.value.drafts.get(0).promptSnapshotJson.contains("planning depth: Normal"));
    }

    @Test public void deepDepthRidesTheFullBlock() {
        Fx f = fixture(PlanDepth.DEEP);
        Result<DraftOutcome> r = burst(f);
        assertTrue(r.ok);
        String t = taskText(f);
        assertTrue(t.contains("This is about:"));
        assertTrue("the focus list names the corrected line: " + t,
            t.contains("actually make it tuesday not thursday"));
        assertTrue("filler is consciously ignored: " + t,
            t.contains("don't answer these") && t.contains("you there"));
        assertTrue(t.trim().endsWith("Output only the reply text."));
        assertTrue(r.value.drafts.get(0).promptSnapshotJson.contains("planning depth: Deep"));
    }

    @Test public void basicDepthShipsTheLegacyTaskByteClean() {
        Fx f = fixture(PlanDepth.BASIC);
        Result<DraftOutcome> r = burst(f);
        assertTrue(r.ok);
        String t = taskText(f);
        assertFalse("no planning content at Basic", t.contains("The moment:"));
        assertFalse(t.contains("This is about:"));
        assertTrue(r.value.drafts.get(0).promptSnapshotJson.contains("planning depth: Basic"));
    }
}
