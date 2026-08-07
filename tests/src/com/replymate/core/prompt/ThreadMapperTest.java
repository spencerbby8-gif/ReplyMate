package com.replymate.core.prompt;

import com.replymate.core.ai.Turn;
import com.replymate.core.model.ContentKind;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.fakes.Fakes;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Thread → provider turns: sender attribution + structured media (P-memory-audit). */
public class ThreadMapperTest {

    @Test public void defaultPrefixIsContactName() {
        List<Turn> turns = ThreadMapper.map(Arrays.asList(
            Fakes.msg(1, Direction.INCOMING, "you dey around?")), "Amara");
        assertEquals(1, turns.size());
        assertEquals("Amara: you dey around?", turns.get(0).text);
        assertEquals(Turn.Role.USER, turns.get(0).role);
    }

    @Test public void perMessageSenderWinsForGroupRows() {
        Message m = Fakes.msg(1, Direction.INCOMING, "who dey come?");
        m.senderName = "Kunle";
        List<Turn> turns = ThreadMapper.map(Arrays.asList(m), "The Crew");
        assertEquals("Kunle: who dey come?", turns.get(0).text);
    }

    @Test public void outgoingMapsToModelTurnsPlain() {
        List<Turn> turns = ThreadMapper.map(Arrays.asList(
            Fakes.msg(1, Direction.OUTGOING, "omo i dey o")), "Amara");
        assertEquals(Turn.Role.MODEL, turns.get(0).role);
        assertEquals("omo i dey o", turns.get(0).text);
    }

    @Test public void mediaRowsBecomeStructuredContextNeverFakeText() {
        Message photo = Fakes.msg(1, Direction.INCOMING, ContentKind.IMAGE.placeholder());
        photo.contentKind = ContentKind.IMAGE.wire;
        Message voice = Fakes.msg(1, Direction.INCOMING, ContentKind.VOICE.placeholder());
        voice.contentKind = ContentKind.VOICE.wire;
        List<Turn> turns = ThreadMapper.map(Arrays.asList(photo, voice), "Amara");
        assertEquals(2, turns.size());
        for (Turn t : turns) {
            assertTrue(t.text, t.text.startsWith("Amara [sent "));
            assertTrue(t.text, t.text.contains("not readable"));
            assertTrue(t.text, t.text.contains("do not describe or guess"));
            assertFalse(t.text, t.text.contains("open in chat app"));
        }
        assertTrue(turns.get(0).text.contains("photo"));
        assertTrue(turns.get(1).text.contains("voice note"));
    }

    @Test public void captionedMediaKeepsItsRealCaption() {
        Message m = Fakes.msg(1, Direction.INCOMING, "this dress fine die 😂");
        m.contentKind = ContentKind.IMAGE.wire;   // caption + image
        m.mediaMime = "image/jpeg";
        List<Turn> turns = ThreadMapper.map(Arrays.asList(m), "Amara");
        assertEquals("Amara: this dress fine die 😂", turns.get(0).text);
    }

    @Test public void emptyBodiesAreSkipped() {
        List<Turn> turns = ThreadMapper.map(Arrays.asList(
            Fakes.msg(1, Direction.INCOMING, "   "),
            Fakes.msg(1, Direction.INCOMING, "real text")), "Amara");
        assertEquals(1, turns.size());
        assertEquals("Amara: real text", turns.get(0).text);
    }
}
