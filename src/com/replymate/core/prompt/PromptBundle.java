package com.replymate.core.prompt;

import com.replymate.core.model.Contact;
import com.replymate.core.model.Message;
import com.replymate.core.usecase.ProfileService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Everything the PromptBuilder needs for ONE contact's draft. ISOLATION (decision #5):
 *  this type physically holds a single contact's data — cross-contact input is impossible. */
public final class PromptBundle {
    public final ProfileService.Profile profile;   // already toggle-filtered
    public final Contact contact;          // non-null
    public final String styleRules;        // global style or fallback (P1/P5 path)
    public final List<Message> thread;     // oldest-first
    /* P4 customization (all may be empty): */
    public final String voiceLine;         // resolved 9-control "Voice: …" line
    public final List<String> voiceExtra;  // custom prompt + learned hints lines
    public final String aboutExtra;        // filtered free-text "About me" box

    public PromptBundle(ProfileService.Profile profile, Contact contact, String styleRules,
                        List<Message> thread) {
        this(profile, contact, styleRules, thread, "", null, "");
    }

    public PromptBundle(ProfileService.Profile profile, Contact contact, String styleRules,
                        List<Message> thread, String voiceLine, List<String> voiceExtra,
                        String aboutExtra) {
        this.profile = profile;
        this.contact = contact;
        this.styleRules = styleRules == null ? "" : styleRules;
        List<Message> copy = new ArrayList<Message>();
        if (thread != null) copy.addAll(thread);
        this.thread = Collections.unmodifiableList(copy);
        this.voiceLine = voiceLine == null ? "" : voiceLine;
        List<String> ve = new ArrayList<String>();
        if (voiceExtra != null) ve.addAll(voiceExtra);
        this.voiceExtra = Collections.unmodifiableList(ve);
        this.aboutExtra = aboutExtra == null ? "" : aboutExtra;
    }
}
