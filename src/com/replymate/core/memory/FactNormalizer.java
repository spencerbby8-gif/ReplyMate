package com.replymate.core.memory;

import java.util.Locale;

/** Fact text → merge key (BLUEPRINT §5.6). text_norm = case/punctuation/whitespace-folded
 *  form, unique per contact (DB UNIQUE(contact_id, text_norm)). Pure JVM. */
public final class FactNormalizer {

    /** Minimum confidence an extracted fact must carry to be stored at all. */
    public static final double MIN_CONFIDENCE = 0.4;

    private FactNormalizer() { }

    /** Normalized merge/dedupe key: lowercase, punctuation → space, whitespace collapsed. */
    public static String normalize(String text) {
        if (text == null) return "";
        String lower = text.toLowerCase(Locale.US);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean lastSpace = true;                    // leading trim for free
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                sb.append(ch);
                lastSpace = false;
            } else if (!lastSpace) {                 // punctuation/whitespace → single space
                sb.append(' ');
                lastSpace = true;
            }
        }
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == ' ') sb.setLength(len - 1);   // trailing trim
        return sb.toString();
    }

    public static int clampImportance(int importance) {
        return importance < 1 ? 1 : (importance > 5 ? 5 : importance);
    }

    public static double clampConfidence(double confidence) {
        if (confidence < 0.0) return 0.0;
        if (confidence > 1.0) return 1.0;
        return confidence;
    }
}
