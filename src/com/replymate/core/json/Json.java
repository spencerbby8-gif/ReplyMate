package com.replymate.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal recursive-descent JSON parser/writer. Parsed values are
 *  Map<String,Object> (LinkedHashMap) / List<Object> / String / Long / Double / Boolean / null.
 *  Numbers that fit a long and have no fraction/exponent parse to Long, else Double. */
public final class Json {

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
    }

    private Json() { }

    // ---------------- reading ----------------

    public static Object parse(String s) {
        if (s == null) throw new JsonException("cannot parse null");
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (!p.end()) throw p.err("trailing content after JSON value");
        return v;
    }

    public static JsonObj parseObj(String s) {
        Object v = parse(s);
        if (!(v instanceof Map)) throw new JsonException("expected JSON object");
        return new JsonObj(castMap(v));
    }

    public static JsonArr parseArr(String s) {
        Object v = parse(s);
        if (!(v instanceof List)) throw new JsonException("expected JSON array");
        return new JsonArr(castList(v));
    }

    // ---------------- writing ----------------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeInto(sb, value);
        return sb.toString();
    }

    private static void writeInto(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof JsonObj) { writeInto(sb, ((JsonObj) v).map()); return; }
        if (v instanceof JsonArr) { writeInto(sb, ((JsonArr) v).list()); return; }
        if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeInto(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                writeInto(sb, item);
            }
            sb.append(']');
            return;
        }
        if (v instanceof String) { writeString(sb, (String) v); return; }
        if (v instanceof Boolean || v instanceof Number) { sb.append(v.toString()); return; }
        throw new JsonException("unsupported value type: " + v.getClass().getName());
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked")
    static List<Object> castList(Object o) { return (List<Object>) o; }

    // ---------------- parser ----------------

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        boolean end() { return pos >= s.length(); }
        JsonException err(String m) { return new JsonException(m + " at char " + pos); }

        void ws() {
            while (!end()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object value() {
            ws();
            if (end()) throw err("unexpected end of input");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default:  return number();
            }
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw err("expected '" + lit + "'");
            pos += lit.length();
        }

        private Map<String, Object> object() {
            pos++; // {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            ws();
            if (!end() && s.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                ws();
                if (end() || s.charAt(pos) != '"') throw err("expected object key");
                String key = string();
                ws();
                if (end() || s.charAt(pos) != ':') throw err("expected ':'");
                pos++;
                Object val = value();
                map.put(key, val);
                ws();
                if (end()) throw err("unterminated object");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return map; }
                throw err("expected ',' or '}'");
            }
        }

        private List<Object> array() {
            pos++; // [
            List<Object> list = new ArrayList<Object>();
            ws();
            if (!end() && s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(value());
                ws();
                if (end()) throw err("unterminated array");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return list; }
                throw err("expected ',' or ']'");
            }
        }

        private String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (!end()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (end()) throw err("unterminated escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"':  sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'u': {
                            if (pos + 4 > s.length()) throw err("bad \\u escape");
                            String hex = s.substring(pos, pos + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException nfe) {
                                throw err("bad \\u escape");
                            }
                            pos += 4;
                            break;
                        }
                        default: throw err("bad escape '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw err("unterminated string");
        }

        private Object number() {
            int start = pos;
            if (!end() && s.charAt(pos) == '-') pos++;
            while (!end() && Character.isDigit(s.charAt(pos))) pos++;
            boolean isDouble = false;
            if (!end() && s.charAt(pos) == '.') {
                isDouble = true;
                pos++;
                while (!end() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (!end() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isDouble = true;
                pos++;
                if (!end() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (!end() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (pos == start) throw err("expected a value");
            String num = s.substring(start, pos);
            try {
                if (isDouble) return Double.valueOf(num);
                return Long.valueOf(num);
            } catch (NumberFormatException nfe) {
                throw err("bad number '" + num + "'");
            }
        }
    }
}
