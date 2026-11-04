package com.replymate.core.privacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** P21-production §6: final sanitation pass for any diagnostics text the owner
 *  might paste into a chat, mail or issue. The diagnostics pipeline emits no
 *  message bodies by design; this pass removes what legitimately remains on
 *  screen:
 *    - API keys / bearer tokens / key= params          (Secrets.redact)
 *    - known contact & owner NAMES                     (ledger lines like
 *      "learned · Amara · …" carry them by design)
 *    - any visible run of ≥6 digits                    (phone numbers, chat/user/
 *      channel ids — diagnostics never needs them verbatim)
 *  Order matters: secrets first, then whole names (longest-first so "Amara
 *  Speaker" masks before "Amara"), then name tokens, then digit runs. */
public final class Redactor {

    private Redactor() { }

    /** @param privateNames names to mask (whole-word, case-insensitive; tokens
     *  with fewer than 3 letters are skipped so short/common words survive) */
    public static String redactForSharing(String text, Set<String> privateNames) {
        if (text == null) return "";
        if (text.isEmpty()) return text;
        String out = Secrets.redact(text);
        if (privateNames != null) {
            List<String> names = new ArrayList<String>();
            for (String n : privateNames) {
                if (n == null) continue;
                String t = n.trim();
                if (t.length() >= 3 && !names.contains(t)) names.add(t);
            }
            Collections.sort(names, new Comparator<String>() {   // longest first
                @Override public int compare(String a, String b) {
                    return b.length() - a.length();
                }
            });
            for (String full : names) {
                out = replaceWordIgnoreCase(out, full, "[name]");
                if (full.indexOf(' ') > 0) {
                    for (String tok : full.split("\\s+")) {
                        if (tok.replaceAll("[^\\p{L}]", "").length() >= 3) {
                            out = replaceWordIgnoreCase(out, tok, "[name]");
                        }
                    }
                }
            }
        }
        out = out.replaceAll("\\d{6,}", "[num]");
        return out;
    }

    private static String replaceWordIgnoreCase(String text, String word, String mask) {
        if (word == null || word.isEmpty()) return text;
        Pattern p = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(word)
            + "(?![\\p{L}\\p{N}])");
        return p.matcher(text).replaceAll(Matcher.quoteReplacement(mask));
    }
}
