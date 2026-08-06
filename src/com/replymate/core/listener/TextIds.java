package com.replymate.core.listener;

import java.security.MessageDigest;

/** Content-based dedupe keys (NOT sbn-key based — messaging apps constantly re-post the
 *  same notification with an extended MessagingStyle; the SAME message must dedupe to the
 *  SAME key across those re-posts). Key = SHA-1(channel|remoteKey|sender|body|timestamp). */
public final class TextIds {

    private TextIds() { }

    public static String computeNotifKey(String channelWire, String remoteKey, String sender,
                                         String body, long timestampMs) {
        String seed = safe(channelWire) + "|" + safe(remoteKey) + "|" + safe(sender)
            + "|" + safe(body) + "|" + timestampMs;
        return sha1Hex(seed);
    }

    static String sha1Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-1/UTF-8 are guaranteed on all our targets; this is a belt-and-braces fallback.
            return "fallback-" + Integer.toHexString(s.hashCode()) + "-" + s.length();
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
