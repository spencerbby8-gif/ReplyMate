package com.replymate.core.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Typed wrapper over a parsed/declared JSON array. */
public final class JsonArr {
    private final List<Object> list;

    public JsonArr() { this.list = new ArrayList<Object>(); }
    JsonArr(List<Object> list) { this.list = list; }

    List<Object> list() { return list; }

    public static JsonArr create() { return new JsonArr(); }

    public JsonArr add(Object value) { list.add(value); return this; }

    public int size() { return list.size(); }
    public Object raw(int i) { return list.get(i); }

    public String str(int i) {
        Object v = list.get(i);
        if (v == null) return null;
        if (v instanceof String) return (String) v;
        return String.valueOf(v);
    }

    public JsonObj obj(int i) {
        Object v = list.get(i);
        if (v instanceof Map) return new JsonObj(Json.castMap(v));
        return null;
    }

    public JsonArr arr(int i) {
        Object v = list.get(i);
        if (v instanceof List) return new JsonArr(Json.castList(v));
        return null;
    }

    public String toJson() { return Json.write(list); }
}
