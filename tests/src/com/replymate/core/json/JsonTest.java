package com.replymate.core.json;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonTest {

    @Test public void roundTripNested() {
        JsonObj root = JsonObj.create()
            .put("name", "Reply\"Mate")
            .put("n", 42)
            .put("f", 1.5d)
            .put("ok", true)
            .put("nothing", null)
            .put("obj", JsonObj.create().put("inner", "x"))
            .put("arr", JsonArr.create().add(1).add("two").add(false));

        String json = root.toJson();
        JsonObj back = Json.parseObj(json);

        assertEquals("Reply\"Mate", back.str("name"));
        assertEquals(Long.valueOf(42), back.lng("n"));
        assertEquals(Double.valueOf(1.5d), back.dbl("f"));
        assertTrue(back.bool("ok", false));
        assertFalse(back.has("nothing"));
        assertEquals("x", back.obj("obj").str("inner"));
        assertEquals(3, back.arr("arr").size());
        assertEquals("two", back.arr("arr").str(1));
    }

    @Test public void unicodeAndEscapes() {
        String src = "Pidgin: How you dey? 😀 \\slash\\ \n \t ©";
        String json = Json.write(src);
        Object back = Json.parse(json);
        assertEquals(src, back);
        assertTrue(json.contains("\\n"));
        assertTrue(json.contains("\\\\"));
    }

    @Test public void numberTyping() {
        JsonArr a = Json.parseArr("[1, -7, 2147483648, 2.5, 1e3]");
        assertTrue(a.raw(0) instanceof Long);
        assertEquals(Long.valueOf(-7), (Long) a.raw(1));
        assertEquals(Long.valueOf(2147483648L), (Long) a.raw(2));
        assertTrue(a.raw(3) instanceof Double);
        assertEquals(Double.valueOf(1000d), (Double) a.raw(4));
    }

    @Test public void emptyContainers() {
        assertEquals("{}", Json.parseObj("{}").toJson());
        JsonArr a = Json.parseArr("[ ]");
        assertEquals(0, a.size());
    }

    @Test(expected = Json.JsonException.class)
    public void malformedUnterminatedString() {
        Json.parse("{\"a\":\"oops");
    }

    @Test(expected = Json.JsonException.class)
    public void trailingContentRejected() {
        Json.parse("{} true");
    }

    @Test(expected = Json.JsonException.class)
    public void badEscapeRejected() {
        Json.parse("\"bad\\qescape\"");
    }

    @Test public void writeUnsupportedTypeThrows() {
        try {
            Json.write(new Object());
            fail("expected JsonException");
        } catch (Json.JsonException expected) { }
    }

    @Test public void keyOrderStableForGoldens() {
        JsonObj o = JsonObj.create().put("b", 1).put("a", 2).put("c", 3);
        assertEquals("{\"b\":1,\"a\":2,\"c\":3}", o.toJson());
    }

    @Test public void nullSafeGetters() {
        JsonObj o = Json.parseObj("{\"s\":\"txt\",\"n\":5}");
        assertNull(o.str("missing"));
        assertEquals("dflt", o.str("missing", "dflt"));
        assertEquals(9, o.lng("missing", 9));
        assertEquals("5", o.str("n"));           // number read as string is lenient
        assertNull(o.obj("s"));                  // wrong type → null, no crash
    }

    @Test public void deepNestedArrayOfObjects() {
        Object v = Json.parse("[[{\"k\":[1,2,{\"z\":null}]}]]");
        assertTrue(v instanceof List);
        JsonArr outer = new JsonArr(Json.castList(v));
        JsonObj inner = outer.arr(0).obj(0);
        assertEquals(3, inner.arr("k").size());
        assertEquals(Long.valueOf(1), (Long) inner.arr("k").raw(0));
        JsonObj leaf = inner.arr("k").obj(2);   // {"z":null} — key present, value null
        assertNotNull(leaf);
        assertFalse(leaf.has("z"));             // has() treats null value as absent
        assertNull(leaf.raw("z"));
    }

    @Test public void mapAndListLeafTypesAreStandard() {
        Object v = Json.parse("{\"a\":[{\"b\":1}]}");
        assertTrue(v instanceof Map);
        Map<?, ?> m = (Map<?, ?>) v;
        assertTrue(m.get("a") instanceof List);
        assertTrue(((List<?>) m.get("a")).get(0) instanceof Map);
    }
}
