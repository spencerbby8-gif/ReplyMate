package com.replymate.app.platform;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Tiny background-runner with SEPARATE lanes (threading model per BLUEPRINT §1.3,
 *  P-background-9 lane split):
 *
 *    INGEST — single thread, listener capture ONLY (extract → parse → store →
 *             schedule). Single-threaded so notification order is preserved and
 *             a notification callback can NEVER queue behind a network call.
 *    GEN    — background generation (research, reasoning prep, paid provider
 *             calls with their 15s/45s timeouts and retries). Slow work lives
 *             here and ONLY here; it cannot starve capture and one crawling
 *             draft can delay at most one other conversation at a time.
 *    BG     — the original small pool for UI-initiated and other misc work
 *             (unchanged legacy callers).
 *
 *  The P-background-9 root cause this kills: INGEST/GEN/BG used to be ONE
 *  2-thread pool — two slow drafts (research + retries on a bad network) parked
 *  both threads for minutes and every WhatsApp callback queued behind them:
 *  "the listener stopped capturing" and "background generation is slow" were
 *  the same starvation with two faces. */
public final class Tasks {

    private static final ExecutorService BG = Executors.newFixedThreadPool(2,
        named("rm-bg"));

    private static final ExecutorService INGEST = Executors.newSingleThreadExecutor(
        named("rm-ingest"));

    // P-background-12: 3 lanes, not 2 — two simultaneously-slow conversations
    // (research crawl + provider retries can both park for tens of seconds) must
    // never queue-block every OTHER conversation's draft. Per-contact generation
    // is serialized inside DraftService, so widening the pool cannot double-run
    // one conversation; it only lets different conversations proceed in parallel.
    private static final ExecutorService GEN = Executors.newFixedThreadPool(3,
        named("rm-gen"));

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Tasks() { }

    /** Daemon threads: lanes die with the process (recovery after process death is
     *  the listener-(re)connect sweep, not thread persistence). */
    private static ThreadFactory named(final String name) {
        return new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        };
    }

    public interface Job<T> { T run(); }
    public interface Done<T> { void accept(T value); }

    /** Run {@code job} in the background, deliver the result on the main thread. */
    public static <T> void call(final Job<T> job, final Done<T> done) {
        BG.execute(new Runnable() {
            @Override public void run() {
                final T value = job.run();
                MAIN.post(new Runnable() {
                    @Override public void run() { done.accept(value); }
                });
            }
        });
    }

    public static void bg(Runnable r) { BG.execute(r); }

    /** Listener capture lane — ordered, never blocked by generation/network. */
    public static void ingest(Runnable r) { INGEST.execute(r); }

    /** Background generation lane — research/reasoning/provider calls. */
    public static void gen(Runnable r) { GEN.execute(r); }

    public static void main(Runnable r) { MAIN.post(r); }
}
