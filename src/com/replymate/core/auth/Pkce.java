package com.replymate.core.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;

/** PKCE (RFC 7636) helpers for the mobile OAuth code flow — pure JRE crypto. */
public final class Pkce {

    private static final String UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    private Pkce() { }

    /** A fresh 64-char code verifier (43..128 chars, unreserved set). */
    public static String newVerifier(SecureRandom random) {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            sb.append(UNRESERVED.charAt(random.nextInt(UNRESERVED.length())));
        }
        return sb.toString();
    }

    /** BASE64URL-ENCODE(SHA256(verifier)) without padding — the "s256" challenge. */
    public static String s256Challenge(String verifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(verifier.getBytes("UTF-8"));
            StringBuilder b64 = new StringBuilder();
            final String tbl =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
            for (int i = 0; i < digest.length; i += 3) {
                int b0 = digest[i] & 0xff;
                int b1 = i + 1 < digest.length ? digest[i + 1] & 0xff : 0;
                int b2 = i + 2 < digest.length ? digest[i + 2] & 0xff : 0;
                b64.append(tbl.charAt(b0 >> 2));
                b64.append(tbl.charAt(((b0 & 0x3) << 4) | (b1 >> 4)));
                if (i + 1 < digest.length) {
                    b64.append(tbl.charAt(((b1 & 0xf) << 2) | (b2 >> 6)));
                }
                if (i + 2 < digest.length) b64.append(tbl.charAt(b2 & 0x3f));
            }
            return b64.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** A URL-safe random state token for OAuth CSRF protection. */
    public static String newState(SecureRandom random) {
        return newVerifier(random).substring(0, 32);
    }
}
