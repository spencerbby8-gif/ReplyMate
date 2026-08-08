package com.replymate.core.prompt;

import com.replymate.core.model.Contact;
import com.replymate.core.usecase.ProfileService;

/** Builds the L0 system instruction (BLUEPRINT §5.3, P-polish revision):
 *  FOUNDATION = the owner's Voice Charter, VERBATIM (VoiceCharter.TEXT — never edited,
 *  never summarized; byte-identity is test-pinned). The existing voice system
 *  (9 controls), global + contact custom instructions, profile digest, contact block
 *  and learned hints are appended AFTER it — integration adds context, it never
 *  rewrites the charter's words or meaning. NEVER truncated by the budgeter. */
public final class SystemComposer {

    public static final String FALLBACK_STYLE =
        "natural, human, conversational texting style; match the tone and language of the chat";

    private SystemComposer() { }

    public static String compose(ProfileService.Profile profile, Contact contact, String styleRules) {
        return compose(profile, contact, styleRules, "", null, "");
    }

    /** P4: voiceLine = resolved 9-control "Voice: …" rule; voiceExtra = global custom
     *  instruction + contact custom prompt + learned hints; aboutExtra = filtered
     *  free-text about-me. P-polish: foundation is the owner's charter verbatim.
     *  P-memory-audit: memoryLines = this contact's recalled long-term memory
     *  (pinned facts, rolling summary, learned style) — one bullet per line. */
    public static String compose(ProfileService.Profile profile, Contact contact, String styleRules,
                                 String voiceLine, java.util.List<String> voiceExtra,
                                 String aboutExtra) {
        return compose(profile, contact, styleRules, voiceLine, voiceExtra, aboutExtra, null);
    }

    public static String compose(ProfileService.Profile profile, Contact contact, String styleRules,
                                 String voiceLine, java.util.List<String> voiceExtra,
                                 String aboutExtra, java.util.List<String> memoryLines) {
        String owner = profile == null ? "the owner of this phone" : profile.displayName();
        StringBuilder sb = new StringBuilder();

        // 1) FOUNDATION — owner-mandated charter, exactly as written.
        sb.append(VoiceCharter.TEXT);

        // 2) Who you write as (toggled-off profile sections are already filtered out).
        sb.append("\n\nYou write as ").append(owner)
          .append(", in your own private chat. Output only the exact message ").append(owner)
          .append(" would send — no quotes, no explanation, no commentary.");
        if (profile != null && !profile.isEmpty()) {
            StringBuilder about = new StringBuilder();
            if (!profile.languages.isEmpty()) about.append("\n- Languages: ").append(profile.languages);
            if (!profile.bio.isEmpty()) about.append("\n- About me: ").append(profile.bio);
            if (!profile.topics.isEmpty()) about.append("\n- I often talk about: ").append(profile.topics);
            if (aboutExtra != null && !aboutExtra.trim().isEmpty()) {
                about.append("\n- More about me: ").append(aboutExtra.trim());
            }
            if (about.length() > 0) sb.append("\n\nAbout ").append(owner).append(":").append(about);
        }

        // 3) Existing voice system: preset style rules + resolved 9-control voice line.
        sb.append("\n\nWriting style: ")
          .append(styleRules == null || styleRules.trim().isEmpty() ? FALLBACK_STYLE : styleRules.trim())
          .append('.');
        if (voiceLine != null && !voiceLine.trim().isEmpty()) {
            sb.append('\n').append(voiceLine.trim());
        }

        // 4) Contact block: partner, relationship, tone, language.
        sb.append("\n\nConversation partner: ").append(contact.displayName);
        if (!contact.relationshipType.isEmpty()) sb.append(" (").append(contact.relationshipType).append(')');
        sb.append('.');
        if (!contact.relationshipNotes.isEmpty()) {
            sb.append("\nAbout this person: ").append(contact.relationshipNotes);
        }
        if (!contact.toneOverride.isEmpty()) {
            sb.append("\nTone with them: ").append(contact.toneOverride);
        }
        if (!contact.languagePref.isEmpty()) {
            sb.append("\nReply language: ").append(contact.languagePref).append('.');
        } else {
            sb.append("\nReply language: match the language of their latest message.");
        }

        // 4.5) Long-term memory for THIS chat (P-memory-audit): pinned facts,
        //      rolling summary, learned style — framed as the owner's own memory so
        //      the reply never mentions notes, summaries or an app.
        if (memoryLines != null && !memoryLines.isEmpty()) {
            StringBuilder mem = new StringBuilder("\n\nWhat you remember about ")
                .append(contact.displayName)
                .append(" (your own memory — never mention that anything was noted,"
                    + " summarized or learned; never say you re-read the chat):");
            for (String line : memoryLines) {
                if (line != null && !line.trim().isEmpty()) {
                    mem.append('\n').append(line.trim());
                }
            }
            sb.append(mem);
        }

        // 5) Global custom instruction, contact custom prompt, learned hints (gated).
        if (voiceExtra != null) {
            for (String line : voiceExtra) {
                if (line != null && !line.trim().isEmpty()) {
                    sb.append('\n').append(line.trim());
                }
            }
        }

        // 6) Hard boundaries (compact — the charter carries the human-voice rules).
        //    P-background-8: the chosen voice must allow honest pushback — a direct
        //    tone is capable of firmness WITHOUT drifting into random hostility.
        sb.append("\n\nRules: reply in ").append(owner)
          .append("'s voice; usually 1–3 short sentences unless the context clearly needs more;")
          .append(" when the honest reply is to disagree, correct ").append(contact.displayName)
          .append(" or say no, say it plainly and give the real reason — firm is fine,")
          .append(" hostile or insulting never;")
          .append(" never invent facts about ").append(contact.displayName)
          .append("; stay in character at all times.");

        return sb.toString();
    }
}
