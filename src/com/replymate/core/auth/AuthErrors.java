package com.replymate.core.auth;

import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;

/** GoTrue (Supabase Auth) error mapping — built on LIVE-probed response shapes, never
 *  assumptions (owner doctrine). Modern GoTrue errors are
 *  {"code": <http>, "error_code": "<snake>", "msg": "<text>"}; older shape is
 *  {"error": "…", "error_description": "…"}. We read both and always answer with
 *  an owner-actionable line (what happened + what to do), content-safe. */
public final class AuthErrors {

    public final int httpCode;
    public final String errorCode;     // exact GoTrue error_code ("" if unknown shape)
    public final String serverSaid;    // msg/error_description verbatim
    public final String userMessage;   // ReplyMate read + suggested fix

    private AuthErrors(int httpCode, String errorCode, String serverSaid, String userMessage) {
        this.httpCode = httpCode;
        this.errorCode = errorCode;
        this.serverSaid = serverSaid;
        this.userMessage = userMessage;
    }

    /** Maps an error response (HTTP code + body) to a friendly reading. */
    public static AuthErrors map(int httpCode, String body) {
        String errorCode = "";
        String serverSaid = "";
        try {
            JsonObj o = Json.parseObj(body);
            errorCode = o.str("error_code", o.str("error", ""));
            serverSaid = o.str("msg", o.str("error_description", o.str("message", "")));
        } catch (RuntimeException ignored) {
            serverSaid = body == null ? "" : (body.length() > 160 ? body.substring(0, 160) : body);
        }

        String friendly;
        if ("invalid_otp".equals(errorCode) || "otp_expired".equals(errorCode)) {
            friendly = "That code is wrong or expired — request a new one and enter it"
                + " within a few minutes.";
        } else if ("over_request_rate_limit".equals(errorCode) || httpCode == 429) {
            friendly = "Too many tries just now — wait about a minute, then try again.";
        } else if ("anonymous_provider_disabled".equals(errorCode)) {
            friendly = "Guest sign-in isn't enabled on the ReplyMate server yet"
                + " (Authentication → Providers → Anonymous). Google or email still works.";
        } else if ("provider_disabled".equals(errorCode)
                || ("validation_failed".equals(errorCode)
                    && (serverSaid.contains("provider is not enabled")
                        || serverSaid.contains("provider is disabled")))) {
            friendly = "This sign-in method is switched off on the ReplyMate server"
                + " (it gets enabled in Authentication → Providers) — try email or guest"
                + " for now.";
        } else if ("email_rate_limit_exceeded".equals(errorCode)
                || ("rate_limit".equals(errorCode))) {
            friendly = "Too many codes requested — give it a minute, then resend.";
        } else if ("email_address_invalid".equals(errorCode)) {
            friendly = "That email address can't receive codes — use a real inbox"
                + " (e.g. Gmail), not a placeholder domain.";
        } else if ("invalid_grant".equals(errorCode) || "flow_state_not_found".equals(errorCode)) {
            friendly = "That sign-in link/session expired — start the sign-in again.";
        } else if (httpCode == 401) {
            friendly = "Your session expired — sign in again.";
        } else if (httpCode == -1) {
            friendly = "Can't reach the server — check your internet and try again."
                + (serverSaid.isEmpty() ? "" : " (" + serverSaid + ")");
        } else if (httpCode >= 500) {
            friendly = "The auth server hiccuped (HTTP " + httpCode + ") — try again shortly.";
        } else {
            friendly = (serverSaid.isEmpty()
                ? "Sign-in failed (HTTP " + httpCode + "). Try again."
                : serverSaid);
        }
        return new AuthErrors(httpCode, errorCode, serverSaid, friendly);
    }

    /** Network-level failure (no HTTP at all). */
    public static AuthErrors transport(String detail) {
        return map(-1, detail == null ? "" : detail);
    }
}
