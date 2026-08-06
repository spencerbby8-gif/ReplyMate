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
    public final ProfileService.Profile profile;
    public final Contact contact;          // non-null
    public final String styleRules;        // global style or fallback
    public final List<Message> thread;     // oldest-first

    public PromptBundle(ProfileService.Profile profile, Contact contact, String styleRules,
                        List<Message> thread) {
        this.profile = profile;
        this.contact = contact;
        this.styleRules = styleRules == null ? "" : styleRules;
        List<Message> copy = new ArrayList<Message>();
        if (thread != null) copy.addAll(thread);
        this.thread = Collections.unmodifiableList(copy);
    }
}
