package com.replymate.core.ui;

/** P-splash-auth-polish (1.3.0): the launch choreography as pure data so the premium
 *  beat is pinned by JVM tests without a device. Owner brief: the app icon is the
 *  center; modern, premium, unique; not too fast, not too slow; loads cleanly and
 *  gives users time to see it. All times in milliseconds.
 *
 *  Beat map: glow blooms -> accent ring settles -> icon springs in (hero) ->
 *  light sweep crosses the icon -> wordmark rises with letter-spacing pull-in ->
 *  tagline fades -> accent underline grows. The last beat lands well before
 *  MIN_SHOW_MS so the frame rests instead of cutting away mid-motion. */
public final class SplashChoreo {

    private SplashChoreo() {
    }

    /** Minimum on-screen time even when routing is instant. */
    public static final long MIN_SHOW_MS = 1900;

    /** Backdrop radial glow behind the hero icon. */
    public static final long GLOW_DELAY = 0;
    public static final long GLOW_DUR = 950;

    /** Thin accent ring settling in around the icon. */
    public static final long RING_DELAY = 130;
    public static final long RING_DUR = 560;

    /** The hero: icon entrance (soft spring, scale + fade). */
    public static final long ICON_DELAY = 150;
    public static final long ICON_DUR = 640;

    /** Single light sweep across the icon face once it has landed. */
    public static final long SHIMMER_DELAY = 820;
    public static final long SHIMMER_DUR = 520;

    /** "ReplyMate" wordmark rise + letter-spacing pull-in. */
    public static final long WORD_DELAY = 640;
    public static final long WORD_DUR = 340;

    /** Tagline fade/rise. */
    public static final long TAG_DELAY = 800;
    public static final long TAG_DUR = 280;

    /** Accent underline grow. */
    public static final long LINE_DELAY = 960;
    public static final long LINE_DUR = 300;

    /** Every beat's (delay, duration) pair, in start order. */
    public static long[][] beats() {
        return new long[][] {
            {GLOW_DELAY, GLOW_DUR},
            {RING_DELAY, RING_DUR},
            {ICON_DELAY, ICON_DUR},
            {SHIMMER_DELAY, SHIMMER_DUR},
            {WORD_DELAY, WORD_DUR},
            {TAG_DELAY, TAG_DUR},
            {LINE_DELAY, LINE_DUR},
        };
    }

    /** The moment the last beat stops moving. */
    public static long lastBeatEnd() {
        long end = 0;
        for (long[] b : beats()) {
            end = Math.max(end, b[0] + b[1]);
        }
        return end;
    }
}
