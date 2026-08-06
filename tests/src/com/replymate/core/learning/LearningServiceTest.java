package com.replymate.core.learning;

import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;
import com.replymate.core.model.Contact;
import com.replymate.core.model.StyleSignal;
import com.replymate.fakes.Fakes;
import java.util.HashMap;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P4 learning gate + controls: private/memory-off/disabled/paused contacts can
 *  neither feed nor consume learning; reset wipes; export is parseable LOCAL JSON. */
public class LearningServiceTest {

    private Fakes.LearningStoreFake store;
    private Fakes.KvStoreFake kv;
    private LearningService service;

    @Before public void setUp() {
        store = new Fakes.LearningStoreFake();
        kv = new Fakes.KvStoreFake();
        service = Fakes.learningService(store, kv);
    }

    private static Contact contact(long id, String name) {
        return Fakes.contact(id, name);
    }

    private void approve(Contact c, int n) {
        for (int i = 0; i < n; i++) {
            service.record(c, StyleSignal.Kind.APPROVED, "", null);
        }
    }

    @Test public void recordsWhenOpenAndCountsPerContact() {
        Contact a = contact(1, "Amara");
        Contact b = contact(2, "Bode");
        approve(a, 3);
        service.record(b, StyleSignal.Kind.REJECTED, "", null);
        assertEquals(3, service.counters(1).approved);
        assertEquals(0, service.counters(1).rejected);
        assertEquals(1, service.counters(2).rejected);
        assertEquals(0, service.counters(2).approved);    // isolation at the gate level
    }

    @Test public void privateContactsNeitherRecordNorYield() {
        Contact p = contact(9, "Secret");
        p.privateMode = true;
        p.aiEnabled = false;
        service.record(p, StyleSignal.Kind.APPROVED, "", null);
        assertEquals(0, service.counters(9).total());
        assertTrue(service.hintsFor(p).isEmpty());
        assertFalse(service.openFor(p));
    }

    @Test public void memoryOffContactsAreClosedForLearning() {
        Contact m = contact(3, "NoMemory");
        m.memoryEnabled = false;
        service.record(m, StyleSignal.Kind.APPROVED, "", null);
        assertEquals(0, service.counters(3).total());
        assertTrue(service.hintsFor(m).isEmpty());
    }

    @Test public void disabledStopsBothDirectionsButKeepsData() {
        Contact c = contact(1, "Amara");
        approve(c, 3);
        service.setOff(1, true);
        service.record(c, StyleSignal.Kind.APPROVED, "", null);   // ignored
        assertEquals(3, service.counters(1).total());             // old data kept
        assertTrue(service.hintsFor(c).isEmpty());                // but not applied
        service.setOff(1, false);
        assertTrue(service.openFor(c));                           // re-enabling resumes
    }

    @Test public void pausedFreezesWithoutDeleting() {
        Contact c = contact(1, "Amara");
        approve(c, 3);
        service.setPaused(1, true);
        service.record(c, StyleSignal.Kind.REJECTED, "", null);
        assertEquals(3, service.counters(1).total());             // nothing new recorded
        assertTrue(service.hintsFor(c).isEmpty());                // nothing applied either
        service.setPaused(1, false);
        assertEquals("history survived the pause", 3, service.counters(1).approved);
        service.record(c, StyleSignal.Kind.APPROVED, "", null);   // resumes recording
        assertEquals(4, service.counters(1).approved);
    }

    @Test public void resetWipesOnlyThatContact() {
        approve(contact(1, "Amara"), 3);
        approve(contact(2, "Bode"), 2);
        service.reset(1);
        assertEquals(0, service.counters(1).total());
        assertEquals(2, service.counters(2).total());
    }

    @Test public void hintsFlowOnlyThroughTheGate() {
        Contact c = contact(1, "Amara");
        approve(c, 5);                                            // positive-consistency hint
        assertEquals(1, service.hintsFor(c).size());
        assertTrue(service.hintsFor(c).get(0).line.contains("consistent"));
    }

    @Test public void exportIsLocalParseableJsonWithSignalsAndSettings() {
        Contact c = contact(1, "Amara");
        approve(c, 2);
        service.record(c, StyleSignal.Kind.EDITED, "shorter", 42L);
        String json = service.exportJson(c,
            new HashMap<String, String>() {{ put("emoji", "2"); }},
            new HashMap<String, String>() {{ put("custom.prompt", "keep it playful"); }});
        JsonObj parsed = Json.parseObj(json);
        assertEquals("replymate-learning-export", parsed.str("type"));
        assertEquals("Amara", parsed.str("contact"));
        // Json.parseObj yields PLAIN java.util containers for nested values.
        java.util.Map<?, ?> settings = (java.util.Map<?, ?>) parsed.raw("settings");
        assertEquals("2", settings.get("global.emoji"));
        assertEquals("keep it playful", settings.get("contact.custom.prompt"));
        assertTrue(parsed.raw("signals") instanceof java.util.List);
        java.util.Map<?, ?> counters = (java.util.Map<?, ?>) parsed.raw("counters");
        assertEquals(2L, ((Number) counters.get("approved")).longValue());
        assertEquals(1L, ((Number) counters.get("edited")).longValue());
    }
}
