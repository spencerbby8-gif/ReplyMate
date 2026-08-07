package com.replymate.app.auth;

import com.replymate.core.auth.AuthTransport;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/** Real HTTPS transport for Supabase Auth REST. Blocking, tiny, zero-dependency —
 *  same pattern as the provider HttpClient but with per-call bearer support and
 *  the GET/PUT/DELETE verbs auth needs. Base URL + publishable apikey come from
 *  res/values/auth_config.xml (public-by-design; RLS guards data). */
public final class HttpAuthTransport implements AuthTransport {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final String baseUrl;    // e.g. https://<project>.supabase.co (no trailing /)
    private final String anonKey;    // publishable key → the apikey header
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public HttpAuthTransport(String baseUrl, String anonKey) {
        this(baseUrl, anonKey, 15_000, 30_000);
    }

    public HttpAuthTransport(String baseUrl, String anonKey, int connectTimeoutMs, int readTimeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.anonKey = anonKey;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override public Response call(String method, String path, String jsonBody, String bearerJwt) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("apikey", anonKey);
            conn.setRequestProperty("Accept", "application/json");
            if (bearerJwt != null && !bearerJwt.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerJwt);
            }
            boolean hasBody = jsonBody != null && !jsonBody.isEmpty()
                && !"GET".equals(method);
            if (hasBody) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream os = conn.getOutputStream();
                try { os.write(jsonBody.getBytes(UTF8)); } finally { os.close(); }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Response(code, readAll(in));
        } catch (Exception e) {
            // transport failure surfaces as code -1 with a content-free reason
            return new Response(-1, e.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }
}
