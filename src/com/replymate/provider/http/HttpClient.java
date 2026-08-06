package com.replymate.provider.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tiny blocking HTTPS client (no external deps). Timeouts per BLUEPRINT §2.2:
 *  connect 15s / read 45s. Used by providers from P1 onward. */
public final class HttpClient {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public HttpClient() { this(15_000, 45_000); }

    public HttpClient(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public HttpResponse post(String url, Map<String, String> headers, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    conn.setRequestProperty(h.getKey(), h.getValue());
                }
            }
            byte[] payload = (jsonBody == null ? "" : jsonBody).getBytes(UTF8);
            OutputStream os = conn.getOutputStream();
            try { os.write(payload); } finally { os.close(); }

            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(in);

            Map<String, String> respHeaders = new HashMap<String, String>();
            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    respHeaders.put(e.getKey(), e.getValue().get(0));
                }
            }
            return new HttpResponse(code, body, respHeaders);
        } catch (IOException ioe) {
            return HttpResponse.transportFailure(ioe.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF8));
        try {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } finally {
            r.close();
        }
        return sb.toString();
    }
}
