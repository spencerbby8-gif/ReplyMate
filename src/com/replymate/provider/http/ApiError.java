package com.replymate.provider.http;

/** Classified provider error with user-facing copy (BLUEPRINT §5.5).
 *  P-provider-audit: constructors may now override retryability — e.g. a 429 whose
 *  body says the quota LIMIT IS 0 can never succeed on retry, and a 402 (no balance)
 *  needs a top-up, not a retry. */
public final class ApiError {
    public enum Type { AUTH, QUOTA, NETWORK, SERVER, PARSE, UNKNOWN }

    public final Type type;
    public final String message;
    public final long retryAfterSeconds;   // -1 unknown
    private final boolean retryable;

    public ApiError(Type type, String message, long retryAfterSeconds) {
        this(type, message, retryAfterSeconds,
            type == Type.QUOTA || type == Type.SERVER || type == Type.NETWORK);
    }

    public ApiError(Type type, String message, long retryAfterSeconds, boolean retryable) {
        this.type = type;
        this.message = message;
        this.retryAfterSeconds = retryAfterSeconds;
        this.retryable = retryable;
    }

    /** Classify from an HTTP status (use -1 for transport failures). */
    public static Type classify(int statusCode) {
        if (statusCode < 0) return Type.NETWORK;
        if (statusCode == 401 || statusCode == 403) return Type.AUTH;
        if (statusCode == 429) return Type.QUOTA;
        if (statusCode >= 500 && statusCode < 600) return Type.SERVER;
        if (statusCode >= 200 && statusCode < 300) return Type.PARSE; // caller handles bad body
        return Type.UNKNOWN;
    }

    public static ApiError of(int statusCode, String body) {
        Type t = classify(statusCode);
        long retryAfter = -1;
        if (t == Type.QUOTA && body != null) retryAfter = parseRetryAfter(body);
        return new ApiError(t, defaultMessage(t), retryAfter);
    }

    static long parseRetryAfter(String body) {
        if (body == null) return -1;
        // look for  "retryDelay": "17s"  (Google style) — cheap string scan, no regex cost
        int i = body.indexOf("\"retryDelay\"");
        if (i < 0) return -1;
        int j = body.indexOf('"', i + 12);
        if (j < 0) return -1;
        int k = body.indexOf('"', j + 1);
        if (k < 0) return -1;
        String val = body.substring(j + 1, k);
        try {
            if (val.endsWith("s")) return Long.parseLong(val.substring(0, val.length() - 1));
            return (long) (Double.parseDouble(val) / 1000.0); // ms fallback
        } catch (NumberFormatException nfe) {
            return -1;
        }
    }

    public static String defaultMessage(Type t) {
        switch (t) {
            case AUTH:    return "API key rejected — re-check it in Settings";
            case QUOTA:   return "Busy or daily limit reached — retrying shortly";
            case NETWORK: return "No connection — tap to try again when you're back online";
            case SERVER:  return "Provider is having a problem — will retry";
            case PARSE:   return "Unexpected reply format from provider";
            default:      return "Something went wrong talking to the provider";
        }
    }

    public boolean retryable() {
        return retryable;
    }
}
