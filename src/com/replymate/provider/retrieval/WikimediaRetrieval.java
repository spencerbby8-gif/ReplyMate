package com.replymate.provider.retrieval;

import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;
import com.replymate.core.ports.RetrievalPort;
import com.replymate.core.search.WebEvidence;
import com.replymate.provider.http.HttpClient;
import com.replymate.provider.http.HttpResponse;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** P-intelligence-6 directive 2: the retrieval fallback for providers without a
 *  native search tool — OFFICIAL, FREE, KEYLESS Wikimedia endpoints only:
 *    Wikipedia  REST summary  https://en.wikipedia.org/api/rest_v1/page/summary/{t}
 *    Wiktionary MediaWiki API https://en.wiktionary.org/w/api.php?action=query…
 *  (both documented by the Wikimedia Foundation; no scraping, no unofficial API).
 *  Wikimedia policy requires a descriptive User-Agent — ours is honest and the
 *  app never fabricates one. Boundary, stated plainly: this is an ENCYCLOPEDIA,
 *  great for meanings/people/places/stable facts, weak for minute-live prices or
 *  scores; when nothing verifies, an empty list drives the anti-hallucination
 *  rule (the reply must not invent). Network failures degrade silently to empty. */
public final class WikimediaRetrieval implements RetrievalPort {

    /** P-background-8: this transport is NOT the shared provider client. A slow
     *  external lookup must never park a background draft, so the per-call
     *  limits are seconds, and a TOTAL elapsed budget guards even pathological
     *  transports (the 2nd endpoint is skipped once the 1st ate the budget). */
    public static final int CONNECT_MS = 2_500;
    public static final int READ_MS = 4_000;
    /** Wall-clock ceiling for the whole lookup (both endpoints together). */
    public static final long TOTAL_BUDGET_MS = 9_000;

    /** The client AppContainer must hand us — tight, honest, per-call. */
    public static HttpClient tightHttpClient() {
        return new HttpClient(CONNECT_MS, READ_MS);
    }

    /** Injectable transport so JVM tests see the exact requests + canned answers. */
    public interface Transport {
        HttpResponse get(String url, Map<String, String> headers);
    }

    private final HttpClient http;
    private final Transport transport;

    public WikimediaRetrieval(HttpClient http) {
        this.http = http;
        this.transport = null;
    }

    public WikimediaRetrieval(Transport transport) {
        this.http = null;
        this.transport = transport;
    }

    @Override public List<WebEvidence> lookup(String subject) {
        List<WebEvidence> out = new ArrayList<WebEvidence>();
        if (subject == null || subject.trim().isEmpty()) return out;
        String title = toTitle(subject);
        if (title.isEmpty()) return out;
        final long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;
        try {
            // 1) Wikipedia summary (people, places, teams, products, events).
            String wikiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/"
                + enc(title);
            HttpResponse wiki = getBounded(wikiUrl, deadline);
            if (wiki.code >= 200 && wiki.code < 300) {
                WebEvidence e = parseWikipedia(wiki.body);
                if (e != null) out.add(e);
            }
            // 2) Wiktionary (slang, Pidgin, abbreviations, meanings) — asked
            //    only when Wikipedia missed AND the first leg did not already
            //    eat the time budget: the draft never waits on a 2nd crawl.
            if (out.isEmpty() && System.currentTimeMillis() < deadline) {
                String wiktUrl = "https://en.wiktionary.org/w/api.php?action=query"
                    + "&prop=extracts&exintro&explaintext&redirects=1&format=json"
                    + "&titles=" + enc(title.toLowerCase(Locale.US));
                HttpResponse wikt = getBounded(wiktUrl, deadline);
                if (wikt.code >= 200 && wikt.code < 300) {
                    WebEvidence e = parseWiktionary(wikt.body);
                    if (e != null) out.add(e);
                }
            }
        } catch (RuntimeException ignored) {
            // retrieval must never hurt a reply — an empty list is the honest answer
        }
        return out;
    }

    /** One HTTP leg with a WALL-CLOCK bound on top of the transport's own
     *  socket timeouts — a hung endpoint (connected, never answering) can park
     *  a blocking read beyond the draft's patience, so the leg runs on a tiny
     *  daemon pool and the draft thread waits only until the lookup deadline.
     *  On timeout the leg is cancelled and reads as a failure → honest empty. */
    private HttpResponse getBounded(String url, long deadlineMs) {
        long wait = deadlineMs - System.currentTimeMillis();
        if (wait <= 0) return new HttpResponse(0, "", java.util.Collections.<String, String>emptyMap());
        java.util.concurrent.Future<HttpResponse> f =
            LEGS.submit(new java.util.concurrent.Callable<HttpResponse>() {
                @Override public HttpResponse call() { return get(url); }
            });
        try {
            return f.get(wait, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            f.cancel(true);
            return new HttpResponse(0, "", java.util.Collections.<String, String>emptyMap());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new HttpResponse(0, "", java.util.Collections.<String, String>emptyMap());
        } catch (java.util.concurrent.ExecutionException ee) {
            return new HttpResponse(0, "", java.util.Collections.<String, String>emptyMap());
        }
    }

    private static final java.util.concurrent.ExecutorService LEGS =
        java.util.concurrent.Executors.newCachedThreadPool(
            new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "rm-retrieval");
                    t.setDaemon(true);
                    return t;
                }
            });

    private HttpResponse get(String url) {
        Map<String, String> h = new HashMap<String, String>();
        // Wikimedia policy: a descriptive UA is REQUIRED for API consumers.
        h.put("User-Agent", "ReplyMate/1.5 (on-device Android reply assistant;"
            + " contact: github.com/spencerbby8-gif/ReplyMate)");
        return transport != null ? transport.get(url, h) : http.get(url, h);
    }

    /** Term → plausible page title ("odogwu" stays; "who won the arsenal game last
     *  night" is not an encyclopedia title — the fallback honestly declines). */
    public static String toTitle(String subject) {
        String s = subject.trim().replaceAll("\\s+", " ");
        // multi-sentence / question subjects are not encyclopedia titles
        if (s.split(" ").length > 6 || s.contains("?")) return "";
        return s;
    }

    public static WebEvidence parseWikipedia(String body) {
        JsonObj o = Json.parseObj(body);
        String type = o.str("type");
        if ("disambiguation".equals(type) || "no-extract".equals(type)) return null;
        String extract = o.str("extract");
        if (extract == null || extract.trim().length() < 20) return null;
        String title = o.str("title");
        return new WebEvidence(title == null ? "Wikipedia" : title,
            firstSentences(extract, 2), "Wikipedia");
    }

    public static WebEvidence parseWiktionary(String body) {
        JsonObj o = Json.parseObj(body);
        JsonObj query = o.obj("query");
        if (query == null) return null;
        Object pagesRaw = query.raw("pages");
        if (!(pagesRaw instanceof Map)) return null;
        // pages = {"12345": {title, extract}} — unknown id, take the first entry.
        for (Map.Entry<?, ?> e : ((Map<?, ?>) pagesRaw).entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Object extract = ((Map<?, ?>) e.getValue()).get("extract");
            if (!(extract instanceof String) || ((String) extract).trim().length() < 10) {
                continue;
            }
            Object title = ((Map<?, ?>) e.getValue()).get("title");
            return new WebEvidence(title instanceof String ? (String) title : "Wiktionary",
                firstSentences((String) extract, 2), "Wiktionary");
        }
        return null;
    }

    static String firstSentences(String text, int max) {
        String t = text.replaceAll("\\s+", " ").trim();
        int cut = -1, count = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c == '.' || c == '!' || c == '?')
                    && (i + 1 >= t.length() || t.charAt(i + 1) == ' ')) {
                count++;
                if (count >= max) { cut = i + 1; break; }
            }
        }
        if (cut > 0) t = t.substring(0, cut);
        return t.length() > 220 ? t.substring(0, 219).trim() + "…" : t;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s.replace(' ', '_'), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s.replace(' ', '_');
        }
    }
}
