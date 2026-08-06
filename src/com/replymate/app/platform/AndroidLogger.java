package com.replymate.app.platform;

import android.util.Log;
import com.replymate.core.util.Logger;


public final class AndroidLogger implements Logger {
    private static final String PREFIX = "RM-";

    @Override // com.replymate.core.util.Logger
    public void d(String str, String str2) {
        Log.d(PREFIX + str, safe(str2));
    }

    @Override // com.replymate.core.util.Logger
    public void i(String str, String str2) {
        Log.i(PREFIX + str, safe(str2));
    }

    @Override // com.replymate.core.util.Logger
    public void w(String str, String str2) {
        Log.w(PREFIX + str, safe(str2));
    }

    @Override // com.replymate.core.util.Logger
    public void e(String str, String str2) {
        Log.e(PREFIX + str, safe(str2));
    }

    @Override // com.replymate.core.util.Logger
    public void e(String str, String str2, Throwable th) {
        Log.e(PREFIX + str, safe(str2), th);
    }

    private static String safe(String str) {
        return str == null ? "(null)" : str;
    }
}
