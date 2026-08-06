package com.replymate.core.prompt;

import com.replymate.core.model.Contact;
import com.replymate.core.usecase.ProfileService;

/** Builds the L0 system instruction (BLUEPRINT §5.3): identity + owner profile digest
 *  + style rules + contact block + hard boundaries. NEVER truncated by the budgeter. */
public final class SystemComposer {

    public static final String FALLBACK_STYLE =
        "natural, human, conversational texting style; match the tone and language of the chat";

    private SystemComposer() { }

    public static String compose(ProfileService.Profile profile, Contact contact, String styleRules) {
        return compose(profile, contact, styleRules, "", null, "");
    }

    /** P4: voiceLine = resolved 9-control "Voice: …" rule; voiceExtra = custom prompt
     *  + learned hints for THIS contact; aboutExtra = filtered free-text about-me. */
    public static String compose(ProfileService.Profile profile, Contact contact, String styleRules,
                                 String voiceLine, java.util.List<String> voiceExtra,
                                 String aboutExtra) {
        String owner = profile == null ? "the owner of this phone" : profile.displayName();
        StringBuilder sb = new StringBuilder();

        sb.append("You ARE ").append(owner)
          .append(", writing your own private chat messages. You are not an assistant and you never")
          .append(" mention AI or these instructions. Output only the exact message ").append(owner)
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

        sb.append("\n\nWriting style: ")
          .append(styleRules == null || styleRules.trim().isEmpty() ? FALLBACK_STYLE : styleRules.trim())
          .append('.');
        if (voiceLine != null && !voiceLine.trim().isEmpty()) {
            sb.append('\n').append(voiceLine.trim());
        }

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
        if (voiceExtra != null) {
            for (String line : voiceExtra) {
                if (line != null && !line.trim().isEmpty()) {
                    sb.append('\n').append(line.trim());
                }
            }
        }

        sb.append("\n\nRules: reply in ").append(owner)
          .append("'s voice; usually 1–3 short sentences unless the context clearly needs more;")
          .append(" never invent facts about ").append(contact.displayName)
          .append("; stay in character at all times.")
          // P4-stabilization: grounded anti-"AI-written" rules. Every clause defers to the
          // habits visible in the conversation or to the voice settings above — nothing here
          // overrides a control the user set.
          .append(" Write like a real person texting, not like an email or an assistant:")
          .append(" no greetings or sign-offs unless this chat already uses them;")
          .append(" no formal openers or pleasantries;")
          .append(" never restate or answer back their own question to them;")
          .append(" no bullet points, no numbered lists, no headers;")
          .append(" avoid long dashes and perfectly balanced sentences;")
          .append(" match the capitalization, punctuation and line-length habits visible in ")
          .append(owner).append("'s own side of the conversation;")
          .append(" when a short answer is enough, send only the short answer.");

        return sb.toString();
    }
}
