package com.replymate.core.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Display formatting for timestamps (device timezone). */
public final class TimeFmt {

    private TimeFmt() { }

    public static String clock(long ts) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ts));
    }

    public static String dayTime(long ts) {
        return new SimpleDateFormat("d MMM, HH:mm", Locale.US).format(new Date(ts));
    }
}
