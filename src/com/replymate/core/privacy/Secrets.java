package com.replymate.core.privacy;

import java.util.List;

/** P-background-11: the ONE pattern-level secret redactor for every durable
 *  diagnostics surface (provider errors, diagnostics ring, assistant ledger,
 *  last-error kv, prompt-audit display). ScrubLogger covers the live LOG stream
 *  with owner-registered fragments; this class covers the PERSISTED text with
 *  shape-based patterns, because a persisted line must be safe even when the
 *  process that wrote it never registered the fragment.
 *
 *  What is redacted (token SHAPES, from the providers' official key formats):
 *    - query credentials:            "?key=…" "&key=" "api_key=" "apikey=" "access_token="
 *    - header material echoed back:  "Authorization: Bearer …", "x-api-key: …",
 *                                    "x-goog-api-key: …"
 *    - OpenAI-family keys:           sk-…, sk-proj-…, sk-ant-…, xai-…
 *    - Google API keys:               AIza…
 *    - Supabase keys:                 sb_publishable_…, sb_secret_…, and JWT-shaped
 *                                    eyJ… blobs (anon/service tokens are JWTs)
 *    - URL userinfo:                  scheme://user:pass@host
 *  Pure JVM, allocation-cheap, never throws — diagnostics must survive hostile
 *  input, and a redactor that can crash would be worse than none. */
public final class Secrets {

    public static final String MASK = "***";

    private Secrets() { }

    /** Redact every known secret shape from {@code s}; null-safe. */
    public static String redact(String s) {
        return redact(s, null);
    }

    /** Redact known shapes plus owner-registered fragments (live keys the process
     *  knows about). Fragments shorter than 6 chars are ignored (under-redaction
     *  beats deleting common substrings from real diagnostics). */
    public static String redact(String s, List<String> extraFragments) {
        if (s == null || s.isEmpty()) return "";
        String out = s;
        if (extraFragments != null) {
            for (String f : extraFragments) {
                if (f != null && f.length() >= 6) out = out.replace(f, MASK);
            }
        }
        out = redactKeyedValues(out);
        out = redactTokenShapes(out);
        out = redactUserInfo(out);
        return out;
    }

    /* ------------------------------------------------- key=value constructions */

    /** "?key=XYZ", "&access_token=XYZ", "x-api-key: XYZ", "Bearer XYZ" — the
     *  value runs to the next delimiter (whitespace/&/'"/'}). */
    static String redactKeyedValues(String s) {
        final String[] keys = {
            "authorization: bearer ", "bearer ",
            "x-api-key:", "x-goog-api-key:",
            "key=", "api_key=", "apikey=", "access_token="
        };
        String low = s.toLowerCase(java.util.Locale.US);
        // single forward pass: the NEXT keyword match wins at every position, so
        // overlapping keywords ("authorization: bearer " ⊃ "bearer ") can never
        // emit out-of-order spans
        StringBuilder sb = null;
        int cursor = 0;
        int pos = 0;
        while (pos < s.length()) {
            int hit = -1; String hitKey = null;
            for (String k : keys) {
                int i = low.indexOf(k, pos);
                if (i < 0) continue;
                if (hit >= 0 && i > hit) continue;          // later start loses
                if (i > 0) {
                    char before = low.charAt(i - 1);
                    boolean wordy = (before >= 'a' && before <= 'z')
                        || (before >= '0' && before <= '9') || before == '_';
                    if (wordy) continue;                     // "monkey=business" is prose
                }
                if (hit < 0 || i < hit || (hitKey == null || k.length() > hitKey.length())) {
                    hit = i; hitKey = k;
                }
            }
            if (hit < 0) break;
            int valStart = hit + hitKey.length();
            // header form ("x-api-key: <value>") carries a space after the colon;
            // query form ("key=<value>") never does — only skip it for headers
            if (hitKey.endsWith(":")
                    && valStart < s.length() && s.charAt(valStart) == ' ') {
                valStart++;
            }
            if (valStart >= s.length()) break;
            char first = s.charAt(valStart);
            if (first == '*' || first == ' ' || first == '"' || first == '\''
                    || first == '&' || first == ',' || first == '}' || first == ')') {
                pos = valStart + 1;
                continue;
            }
            int valEnd = valStart;
            while (valEnd < s.length()) {
                char ch = s.charAt(valEnd);
                if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r'
                        || ch == '&' || ch == '"' || ch == '\'' || ch == ','
                        || ch == '}' || ch == ')') break;
                valEnd++;
            }
            if (valEnd - valStart < 4) { pos = valStart + 1; continue; }  // not a real token
            if (sb == null) sb = new StringBuilder(s.length());
            sb.append(s, cursor, valStart).append(MASK);
            cursor = valEnd;
            pos = valEnd;
        }
        if (sb == null) return s;
        sb.append(s, cursor, s.length());
        return sb.toString();
    }

    /* --------------------------------------------------- provider token shapes */

    static String redactTokenShapes(String s) {
        final String[] prefixes = {
            "sk-proj-", "sk-ant-", "sk-svcacct-", "sk-",
            "AIza", "xai-", "sb_publishable_", "sb_secret_", "eyJ"
        };
        // same single-forward-pass discipline as the keyed-value pass
        StringBuilder sb = null;
        int cursor = 0;
        int pos = 0;
        while (pos < s.length()) {
            int hit = -1; int hitLen = 0;
            for (String p : prefixes) {
                int i = s.indexOf(p, pos);
                if (i < 0) continue;
                if (hit >= 0 && i > hit) continue;
                // the prefix must START a token: preceded by a non-token char
                if (i > 0 && isTokenChar(s.charAt(i - 1))) continue;
                if (hit < 0 || i < hit || p.length() > hitLen) {
                    hit = i; hitLen = p.length();
                }
            }
            if (hit < 0) break;
            int end = hit + hitLen;
            while (end < s.length() && isTokenChar(s.charAt(end))) end++;
            int len = end - hit;
            // shapes pin a MINIMUM length so prose like "sk-in real life" or the
            // literal letters "eyJ" in a sentence never get eaten; the length floor
            // belongs to the LONGEST matching prefix family (sk-proj- ⊃ sk-)
            String matched = s.substring(hit, hit + hitLen);
            int floor = minLen(matched);
            if ("sk-proj-".equals(matched) || "sk-ant-".equals(matched)
                    || "sk-svcacct-".equals(matched)) {
                floor = Math.max(floor, 24);
            }
            if (len < floor) { pos = hit + hitLen; continue; }
            // "sk-audit…" style fixtures written with a mask already: skip
            if (s.substring(hit, end).contains(MASK)) { pos = end; continue; }
            if (sb == null) sb = new StringBuilder(s.length());
            sb.append(s, cursor, hit).append(MASK);
            cursor = end;
            pos = end;
        }
        if (sb == null) return s;
        sb.append(s, cursor, s.length());
        return sb.toString();
    }

    private static int minLen(String prefix) {
        // sk- alone is generic (matches "sk-…" prose); the full shapes are not
        if ("sk-".equals(prefix)) return 24;
        if ("eyJ".equals(prefix)) return 24;     // JWT header alone means nothing
        if ("AIza".equals(prefix)) return 20;
        if ("xai-".equals(prefix)) return 12;
        return 16;
    }

    private static boolean isTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9')
            || c == '-' || c == '_' || c == '.' || c == '=' || c == '/';
    }

    /* ---------------------------------------------------------- URL userinfo */

    /** scheme://user:pass@host → scheme://***:***@host (custom/self-hosted base
     *  URLs can embed basic-auth credentials; they must never persist). */
    static String redactUserInfo(String s) {
        int scheme = s.indexOf("://");
        StringBuilder sb = null;
        int cursor = 0;
        int from = 0;
        while (scheme >= 0) {
            int hostStart = scheme + 3;
            int at = -1;
            int end = hostStart;
            while (end < s.length()) {
                char ch = s.charAt(end);
                if (ch == '/' || ch == ' ' || ch == '\t' || ch == '\n'
                        || ch == '"' || ch == '\'' || ch == ')' || ch == '?') break;
                if (ch == '@') { at = end; break; }
                end++;
            }
            if (at > hostStart) {
                String creds = s.substring(hostStart, at);
                if (creds.contains(":")) {
                    if (sb == null) sb = new StringBuilder(s.length());
                    sb.append(s, cursor, hostStart).append(MASK).append(':').append(MASK).append('@');
                    cursor = at + 1;
                }
            }
            from = Math.max(at > 0 ? at + 1 : end, hostStart);
            scheme = s.indexOf("://", from);
        }
        if (sb == null) return s;
        sb.append(s, cursor, s.length());
        return sb.toString();
    }
}
