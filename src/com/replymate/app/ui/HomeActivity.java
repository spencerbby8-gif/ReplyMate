package com.replymate.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.replymate.app.R;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.di.AppContainer;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.data.db.DbHealth;
import java.util.List;

/** S02 — Home inbox: contacts with last-message preview + entries to settings/new contact. */
public final class HomeActivity extends Activity {

    private AppContainer c;
    private LinearLayout list;
    private TextView empty;
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        c = ReplyMateApp.containerOf(this);

        list = (LinearLayout) findViewById(R.id.contact_list);
        empty = (TextView) findViewById(R.id.empty_state);
        status = (TextView) findViewById(R.id.home_status);

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
        // Storage fine → surface listener state (P2 health chip).
        boolean listening = com.replymate.app.listener.ListenerStatus.isServiceEnabled(this);
        status.setVisibility(View.VISIBLE);
        if (listening) {
            status.setTextColor(Ui.GREEN);
            status.setText("Listener on · WhatsApp + Telegram");
            status.setOnClickListener(null);
            status.setClickable(false);
        } else {
            status.setTextColor(android.graphics.Color.rgb(240, 180, 80)); // amber
            status.setText("Listening is off — tap to enable (or keep using manual mode)");
            status.setClickable(true);
            status.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    com.replymate.app.listener.ListenerStatus.openAccessSettings(HomeActivity.this);
                }
            });
        }
    }

    private void renderContacts() {
        list.removeAllViews();
        List<Contact> contacts = c.contacts().all();
        empty.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);

        for (final Contact contact : contacts) {
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
            sub += last.isEmpty() ? "no messages yet"
                : snippet(last.get(0));
            TextView subTv = Ui.sub(this, sub);
            subTv.setMaxLines(1);
            texts.addView(subTv);

            TextView chev = Ui.tv(this, "›", 24, Ui.DIM);
            row.addView(chev);

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
    }

    private static String snippet(Message m) {
        String who = m.direction == Direction.OUTGOING ? "You: " : "";
        String body = m.body.replace('\n', ' ').trim();
        if (body.length() > 40) body = body.substring(0, 40) + "…";
        return who + body;
    }
}
