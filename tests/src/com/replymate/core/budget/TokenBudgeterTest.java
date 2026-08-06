package com.replymate.core.budget;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TokenBudgeterTest {

    @Test public void estimateIsCharsOverFourCeil() {
        assertEquals(0, TokenBudgeter.estimate((String) null));
        assertEquals(1, TokenBudgeter.estimate("abcdx".substring(0, 1)));
        assertEquals(2, TokenBudgeter.estimate("abcde"));
        assertEquals(250, TokenBudgeter.estimate(n(1000)));
    }

    @Test public void fitIsIdentityWhenUnderBudget() {
        ChatRequest req = req("sys", 5, 10);
        assertSame(req, TokenBudgeter.fit(req, 6000));
    }

    @Test public void fitDropsOldestTurnsFirstAndKeepsTask() {
        ChatRequest req = req("sys", 100, 400);   // way over
        ChatRequest fit = TokenBudgeter.fit(req, 1000);
        assertTrue(TokenBudgeter.estimate(fit) <= 1000);
        assertTrue(fit.turns.size() < req.turns.size());
        assertSame(req.task, fit.task);
        assertEquals("sys", fit.system);
        // newest turn preserved
        assertEquals(fit.turns.get(fit.turns.size() - 1).text,
            req.turns.get(req.turns.size() - 1).text);
    }

    @Test public void fitNeverEmptiesThreadBelowOneTurn() {
        ChatRequest req = req("sys", 10, 100000); // single turns huge
        List<Turn> one = new ArrayList<Turn>();
        one.add(Turn.user(n(100000)));
        ChatRequest single = new ChatRequest("s", one, Turn.user("t"), GenerationOpts.defaults());
        ChatRequest fit = TokenBudgeter.fit(single, 10);
        assertEquals(1, fit.turns.size());   // kept even though over budget
    }

    private static ChatRequest req(String system, int turns, int charsPerTurn) {
        List<Turn> list = new ArrayList<Turn>();
        for (int i = 0; i < turns; i++) list.add(Turn.user(n(charsPerTurn)));
        return new ChatRequest(system, list, Turn.user("task"), GenerationOpts.defaults());
    }

    private static String n(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append('m');
        return sb.toString();
    }
}
