package com.replymate.provider.http;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Raw HTTP response value. code == -1 signals a transport failure (no response at all). */
public final class HttpResponse {
    public final int code;
    public final String body;
    public final Map<String, String> headers;

    public HttpResponse(int code, String body, Map<String, String> headers) {
        this.code = code;
        this.body = body == null ? "" : body;
        Map<String, String> copy = new HashMap<String, String>();
        if (headers != null) copy.putAll(headers);
        this.headers = Collections.unmodifiableMap(copy);
    }

    public static HttpResponse transportFailure(String reason) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("x-error", reason == null ? "transport failure" : reason);
        return new HttpResponse(-1, "", h);
    }

    public String header(String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}
