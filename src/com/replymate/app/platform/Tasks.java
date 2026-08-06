package com.replymate.app.platform;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Tiny background-runner: single-process executor + main-thread poster
 *  (threading model per BLUEPRINT §1.3). */
public final class Tasks {

    private static final ExecutorService BG = Executors.newFixedThreadPool(2,
        new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "rm-bg");
                t.setDaemon(true);
                return t;
            }
        });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Tasks() { }

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
    public static void main(Runnable r) { MAIN.post(r); }
}
