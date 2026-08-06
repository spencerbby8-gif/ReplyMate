package com.replymate.core.json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed wrapper over a parsed/declared JSON object with null-safe getters. */
public final class JsonObj {
    private final Map<String, Object> map;

    public JsonObj() { this.map = new LinkedHashMap<String, Object>(); }
    JsonObj(Map<String, Object> map) { this.map = map; }

    Map<String, Object> map() { return map; }

    public static JsonObj create() { return new JsonObj(); }

    public JsonObj put(String key, Object value) { map.put(key, value); return this; }

    public boolean has(String key) { return map.containsKey(key) && map.get(key) != null; }
    public Object raw(String key) { return map.get(key); }

    public String str(String key) { return str(key, null); }

    public String str(String key, String def) {
        Object v = map.get(key);
        if (v == null) return def;
        if (v instanceof String) return (String) v;
        return String.valueOf(v);
    }

    public Long lng(String key) {
        Object v = map.get(key);
        if (v instanceof Long) return (Long) v;
        if (v instanceof Double) return ((Double) v).longValue();
        return null;
    }

    public long lng(String key, long def) {
        Long v = lng(key);
        return v == null ? def : v;
    }

    public Double dbl(String key) {
        Object v = map.get(key);
        if (v instanceof Double) return (Double) v;
        if (v instanceof Long) return ((Long) v).doubleValue();
        return null;
    }

    public boolean bool(String key, boolean def) {
        Object v = map.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        return def;
    }

    public JsonObj obj(String key) {
        Object v = map.get(key);
        if (v instanceof Map) return new JsonObj(Json.castMap(v));
        return null;
    }

    public JsonArr arr(String key) {
        Object v = map.get(key);
        if (v instanceof List) return new JsonArr(Json.castList(v));
        return null;
    }

    public String toJson() { return Json.write(map); }
}
