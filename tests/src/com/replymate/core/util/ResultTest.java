package com.replymate.core.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ResultTest {

    @Test public void okCarriesValue() {
        Result<Integer> r = Result.ok(7);
        assertTrue(r.ok);
        assertEquals(Integer.valueOf(7), r.value);
        assertNull(r.error);
    }

    @Test public void errCarriesMessage() {
        Result<Integer> r = Result.err("boom");
        assertFalse(r.ok);
        assertEquals("boom", r.error);
        assertNull(r.value);
    }

    @Test public void nullErrorBecomesSafeDefault() {
        assertEquals("unknown error", Result.err(null).error);
    }

    @Test public void mapTransformsOnSuccess() {
        Result<String> r = Result.ok(3).map(new Result.Mapper<Integer, String>() {
            @Override public String apply(Integer a) { return "v" + a; }
        });
        assertTrue(r.ok);
        assertEquals("v3", r.value);
    }

    @Test public void mapPropagatesError() {
        Result<String> r = Result.<Integer>err("nope").map(new Result.Mapper<Integer, String>() {
            @Override public String apply(Integer a) { return "x"; }
        });
        assertFalse(r.ok);
        assertEquals("nope", r.error);
    }

    @Test public void mapConvertsExceptionToErr() {
        Result<String> r = Result.ok(1).map(new Result.Mapper<Integer, String>() {
            @Override public String apply(Integer a) { throw new RuntimeException("kaboom"); }
        });
        assertFalse(r.ok);
        assertEquals("kaboom", r.error);
    }
}
