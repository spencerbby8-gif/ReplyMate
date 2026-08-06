package com.replymate.core.style;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Resolution + prompt rendering for the 9 controls (pure JVM).
 *  Storage contract (style_setting table): value rows keyed by control key —
 *    global:  contact_id IS NULL,  value = "0"|"1"|"2"
 *    contact: contact_id = <id>,   value = level (override) — ABSENT means inherit.
 *  Custom prompt box: key "custom.prompt", value = free text (capped at render time). */
public final class StyleSettings {

    /** Key under which the per-contact custom prompt box is stored. */
    public static final String CUSTOM_PROMPT_KEY = "custom.prompt";
    /** Hard cap so a giant paste can't blow the prompt budget. */
    public static final int CUSTOM_PROMPT_MAX = 400;

    private StyleSettings() { }

    /** Effective levels for one contact: contact override wins, else global, else default.
     *  Returns 9 ints in StyleControls.all() order. */
    public static int[] resolve(Map<String, String> globalRows, Map<String, String> contactRows) {
        List<StyleControls.Control> all = StyleControls.all();
        int[] out = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            StyleControls.Control c = all.get(i);
            Integer contact = level(contactRows, c.key);
            Integer global = level(globalRows, c.key);
            out[i] = contact != null ? contact.intValue()
                : (global != null ? global.intValue() : StyleControls.defaultLevel(c.key));
        }
        return out;
    }

    /** Which overrides actually engaged (audit "why"), e.g. "length: short (contact override)". */
    public static List<String> overrideNotes(Map<String, String> globalRows,
                                             Map<String, String> contactRows) {
        List<String> notes = new ArrayList<String>();
        for (StyleControls.Control c : StyleControls.all()) {
            Integer contact = level(contactRows, c.key);
            if (contact == null) continue;
            Integer global = level(globalRows, c.key);
            int base = global != null ? global.intValue() : StyleControls.defaultLevel(c.key);
            if (contact.intValue() != base) {
                notes.add(c.label + ": " + c.levelLabel(contact)
                    + " (contact override of " + c.levelLabel(base) + ")");
            } else {
                notes.add(c.label + ": " + c.levelLabel(contact) + " (contact, same as global)");
            }
        }
        return notes;
    }

    /** Compact one-line voice rule for the prompt, e.g.
     *  "Voice: warm and friendly, casual chat register; keep replies short (…); no emoji; …". */
    public static String renderVoiceLine(int[] levels) {
        List<StyleControls.Control> all = StyleControls.all();
        StringBuilder sb = new StringBuilder("Voice: ");
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(all.get(i).phrase(levels[i]));
        }
        sb.append('.');
        return sb.toString();
    }

    /** Custom prompt for a contact, trimmed + capped; "" when unset. */
    public static String customPrompt(Map<String, String> contactRows) {
        if (contactRows == null) return "";
        String v = contactRows.get(CUSTOM_PROMPT_KEY);
        if (v == null) return "";
        String t = v.trim();
        return t.length() > CUSTOM_PROMPT_MAX ? t.substring(0, CUSTOM_PROMPT_MAX) : t;
    }

    /* ------------------------------------------------------------- parsing */

    /** Parsed level or null when absent/invalid — out-of-range values are treated
     *  as INVALID (inherit), so a corrupt row can never silently pin a hard level. */
    public static Integer level(Map<String, String> rows, String key) {
        if (rows == null) return null;
        String v = rows.get(key);
        if (v == null) return null;
        try {
            int i = Integer.parseInt(v.trim());
            if (i < 0 || i > 2) return null;
            return i;
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    /** UI/read-model: current level to DISPLAY for a control (what the prompt will use). */
    public static Map<String, String> levelsToRows(int[] levels) {
        Map<String, String> out = new HashMap<String, String>();
        List<StyleControls.Control> all = StyleControls.all();
        for (int i = 0; i < all.size(); i++) out.put(all.get(i).key, String.valueOf(levels[i]));
        return out;
    }
}
