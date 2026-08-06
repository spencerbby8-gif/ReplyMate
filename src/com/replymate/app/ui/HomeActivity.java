package com.replymate.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.replymate.app.R;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.di.AppContainer;
import com.replymate.app.listener.ListenerStatus;
import com.replymate.core.listener.ParserRegistry;
import com.replymate.core.listener.WatchedApps;
import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.data.db.DbHealth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** S02 — Home inbox: contact/conversation search, contacts with last-message preview,
 *  listener health chip, entries to settings/new contact. */
public final class HomeActivity extends Activity {

    /** Mirrors the listener's default-on set for the health chip label. */
    private static final Set<Channel> DEFAULTS_ON =
        EnumSet.of(Channel.WHATSAPP, Channel.TELEGRAM);

    private AppContainer c;
    private LinearLayout list;
    private TextView empty;
    private TextView status;
    private EditText search;
    private String query = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        c = ReplyMateApp.containerOf(this);

        list = (LinearLayout) findViewById(R.id.contact_list);
        empty = (TextView) findViewById(R.id.empty_state);
        status = (TextView) findViewById(R.id.home_status);
        search = (EditText) findViewById(R.id.home_search);

        findViewById(R.id.settings_btn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.new_btn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, ContactEditActivity.class));
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString().trim();
                renderContacts();
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (c == null) return;
        renderHealth();
        renderContacts();
    }

    private void renderHealth() {
        DbHealth.Report r = DbHealth.check(c.db());
        if (!r.ok) {
            status.setVisibility(View.VISIBLE);
            status.setTextColor(Ui.RED);
            status.setText("Storage issue: " + r.shortReason());
            status.setOnClickListener(null);
            status.setClickable(false);
            return;
        }
        // Storage fine → surface listener state (health chip).
        boolean listening = ListenerStatus.isServiceEnabled(this);
        status.setVisibility(View.VISIBLE);
        if (listening) {
            status.setTextColor(Ui.GREEN);
            status.setText("Listener on · " + enabledSummary());
            status.setOnClickListener(null);
            status.setClickable(false);
        } else {
            status.setTextColor(Color.rgb(240, 180, 80)); // amber
            status.setText("Listening is off — tap to enable (or keep using manual mode)");
            status.setClickable(true);
            status.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ListenerStatus.openAccessSettings(HomeActivity.this);
                }
            });
        }
    }

    /** e.g. "WhatsApp, Signal" — the channels currently enabled for monitoring. */
    private String enabledSummary() {
        Set<Channel> enabled = ParserRegistry.enabledFromKv(c.kv(), DEFAULTS_ON);
        if (enabled.isEmpty()) return "0 apps watched";
        List<String> names = new ArrayList<String>();
        for (WatchedApps.AppDef def : WatchedApps.all()) {
            if (enabled.contains(def.channel) && !names.contains(def.label)) {
                names.add(def.label);
            }
        }
        if (names.size() <= 3) return join(names);
        return names.get(0) + ", " + names.get(1) + " +" + (names.size() - 2) + " more";
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ search */

    private void renderContacts() {
        list.removeAllViews();
        List<Contact> contacts = c.contacts().all();

        Set<Long> bodyHits = new HashSet<Long>();
        String needle = query.toLowerCase(Locale.US);
        boolean searching = !needle.isEmpty();
        if (searching) {
            // Conversation search: contacts whose stored messages match (UI pick-list only).
            for (Message hit : c.messages().searchByBody(query, 50)) {
                bodyHits.add(hit.contactId);
            }
        }

        int shown = 0;
        for (final Contact contact : contacts) {
            if (searching) {
                boolean nameHit = contact.displayName != null
                    && contact.displayName.toLowerCase(Locale.US).contains(needle);
                if (!nameHit && !bodyHits.contains(contact.id)) continue;
            }
            addContactRow(contact, searching && bodyHits.contains(contact.id)
                && !(contact.displayName != null
                    && contact.displayName.toLowerCase(Locale.US).contains(needle)));
            shown++;
        }

        if (searching) {
            empty.setText(shown == 0
                ? "No contacts or messages match “" + query + "”."
                : "");
            empty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
        } else {
            empty.setText(R.string.home_empty);
            empty.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void addContactRow(final Contact contact, boolean matchedByBody) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = Ui.tv(this, contact.displayName, 17, Ui.PRIMARY);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        texts.addView(name);

        String sub = contact.relationshipType.isEmpty() ? "" : contact.relationshipType + " · ";
        List<Message> last = c.messages().lastMessages(contact.id, 1);
        sub += last.isEmpty() ? "no messages yet" : snippet(last.get(0));
        if (matchedByBody) {
            Message hit = firstHit(contact.id);
            if (hit != null) sub = "match: " + snippet(hit);
        }
        TextView subTv = Ui.sub(this, sub);
        subTv.setMaxLines(1);
        texts.addView(subTv);

        row.addView(Ui.tv(this, "›", 24, Ui.DIM));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(HomeActivity.this, ConversationActivity.class);
                i.putExtra(ConversationActivity.EXTRA_CONTACT_ID, contact.id);
                startActivity(i);
            }
        });
        list.addView(row);
        list.addView(Ui.divider(this));
    }

    /** Newest matching message for the contact (for the "match: …" preview line). */
    private Message firstHit(long contactId) {
        for (Message m : c.messages().searchByBody(query, 50)) {
            if (m.contactId == contactId) return m;
        }
        return null;
    }

    private static String snippet(Message m) {
        String who = m.direction == Direction.OUTGOING ? "You: " : "";
        String body = m.body.replace('\n', ' ').trim();
        if (body.length() > 40) body = body.substring(0, 40) + "…";
        return who + body;
    }
}
