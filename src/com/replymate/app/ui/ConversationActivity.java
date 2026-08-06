package com.replymate.app.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.replymate.app.R;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.di.AppContainer;
import com.replymate.app.platform.Tasks;
import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.DraftStatus;
import com.replymate.core.model.Message;
import com.replymate.core.model.Source;
import com.replymate.core.usecase.DraftOutcome;
import com.replymate.core.util.Result;
import com.replymate.core.util.TimeFmt;
import java.util.Iterator;
import java.util.List;


public final class ConversationActivity extends Activity {
    public static final String EXTRA_CONTACT_ID = "contact_id";
    private Button btnGen;
    private AppContainer c;
    private Contact contact;
    private LinearLayout draftList;
    private TextView draftsHeader;
    private TextView genStatus;
    private boolean generating;
    private EditText input;
    private LinearLayout msgList;
    private ScrollView scroll;

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_conversation);
        this.c = ReplyMateApp.containerOf(this);
        long longExtra = getIntent().getLongExtra("contact_id", -1L);
        Contact contact = longExtra > 0 ? this.c.contacts().get(longExtra) : null;
        this.contact = contact;
        if (contact == null) {
            finish();
            return;
        }
        this.msgList = (LinearLayout) findViewById(R.id.msg_list);
        this.draftList = (LinearLayout) findViewById(R.id.draft_list);
        this.genStatus = (TextView) findViewById(R.id.gen_status);
        this.draftsHeader = (TextView) findViewById(R.id.drafts_header);
        this.input = (EditText) findViewById(R.id.msg_input);
        this.scroll = (ScrollView) findViewById(R.id.conv_scroll);
        this.btnGen = (Button) findViewById(R.id.btn_gen);
        ((TextView) findViewById(R.id.conv_name)).setText(this.contact.displayName);
        ((TextView) findViewById(R.id.conv_rel)).setText(this.contact.relationshipType.isEmpty() ? "manual mode" : this.contact.relationshipType);
        findViewById(R.id.back_btn).setOnClickListener(new View.OnClickListener() { // from class: com.replymate.app.ui.ConversationActivity.1
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ConversationActivity.this.finish();
            }
        });
        findViewById(R.id.edit_btn).setOnClickListener(new View.OnClickListener() { // from class: com.replymate.app.ui.ConversationActivity.2
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                Intent intent = new Intent(ConversationActivity.this, (Class<?>) ContactEditActivity.class);
                intent.putExtra("contact_id", ConversationActivity.this.contact.id);
                ConversationActivity.this.startActivity(intent);
            }
        });
        findViewById(R.id.btn_them).setOnClickListener(new View.OnClickListener() { // from class: com.replymate.app.ui.ConversationActivity.3
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ConversationActivity.this.addMessage(Direction.INCOMING);
            }
        });
        findViewById(R.id.btn_me).setOnClickListener(new View.OnClickListener() { // from class: com.replymate.app.ui.ConversationActivity.4
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ConversationActivity.this.addMessage(Direction.OUTGOING);
            }
        });
        this.btnGen.setOnClickListener(new View.OnClickListener() { // from class: com.replymate.app.ui.ConversationActivity.5
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ConversationActivity.this.generate();
            }
        });
        refreshThread();
        refreshDrafts();
    }

    @Override // android.app.Activity
    protected void onResume() {
        AppContainer appContainer;
        Contact contact;
        super.onResume();
        if (this.contact != null && (appContainer = this.c) != null && (contact = appContainer.contacts().get(this.contact.id)) != null) {
            this.contact = contact;
            ((TextView) findViewById(R.id.conv_name)).setText(this.contact.displayName);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void addMessage(Direction direction) {
        String trim = this.input.getText().toString().trim();
        if (trim.isEmpty()) {
            Toast.makeText(this, "Type or paste a message first", 0).show();
            return;
        }
        Message message = new Message();
        message.contactId = this.contact.id;
        message.channel = Channel.MANUAL;
        message.direction = direction;
        message.body = trim;
        message.sentAt = this.c.clock().now();
        message.source = Source.MANUAL;
        this.c.messages().insert(message);
        this.input.setText("");
        refreshThread();
    }

    private void refreshThread() {
        this.msgList.removeAllViews();
        List<Message> lastMessages = this.c.messages().lastMessages(this.contact.id, 100);
        if (lastMessages.isEmpty()) {
            TextView sub = Ui.sub(this, "No messages yet.\nPaste what " + this.contact.displayName + " sent you (＋them), then tap ✨ Generate.");
            sub.setGravity(17);
            sub.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
            this.msgList.addView(sub);
            return;
        }
        Iterator<Message> it = lastMessages.iterator();
        while (it.hasNext()) {
            this.msgList.addView(bubble(it.next()));
        }
        scrollToBottom();
    }

    private View bubble(Message message) {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.topMargin = Ui.dp(this, 6);
        linearLayout.setLayoutParams(layoutParams);
        boolean z = message.direction == Direction.OUTGOING;
        TextView tv = Ui.tv(this, message.body, 15.0f, -1);
        tv.setBackgroundResource(z ? R.drawable.bg_bubble_out : R.drawable.bg_bubble_in);
        int dp = Ui.dp(this, 12);
        int dp2 = Ui.dp(this, 8);
        tv.setPadding(dp, dp2, dp, dp2);
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-2, -2);
        layoutParams2.gravity = z ? 8388613 : 8388611;
        int dp3 = Ui.dp(this, 48);
        int i = z ? dp3 : 0;
        if (z) {
            dp3 = 0;
        }
        layoutParams2.setMargins(i, 0, dp3, 0);
        linearLayout.addView(tv, layoutParams2);
        TextView sub = Ui.sub(this, (z ? "You" : this.contact.displayName) + " · " + TimeFmt.clock(message.sentAt));
        LinearLayout.LayoutParams layoutParams3 = new LinearLayout.LayoutParams(-2, -2);
        layoutParams3.gravity = z ? 8388613 : 8388611;
        layoutParams3.topMargin = Ui.dp(this, 2);
        linearLayout.addView(sub, layoutParams3);
        return linearLayout;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void scrollToBottom() {
        this.scroll.post(new Runnable() { // from class: com.replymate.app.ui.ConversationActivity.6
            @Override // java.lang.Runnable
            public void run() {
                if (ConversationActivity.this.scroll.getChildCount() > 0) {
                    ConversationActivity.this.scroll.scrollTo(0, ConversationActivity.this.scroll.getChildAt(0).getHeight());
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void generate() {
        if (this.generating) {
            return;
        }
        this.generating = true;
        this.btnGen.setEnabled(false);
        showStatus("Generating…", Ui.ACCENT);
        final long j = this.contact.id;
        Tasks.call(new Tasks.Job<Result<DraftOutcome>>() { // from class: com.replymate.app.ui.ConversationActivity.7
            /* JADX WARN: Can't rename method to resolve collision */
            @Override // com.replymate.app.platform.Tasks.Job
            public Result<DraftOutcome> run() {
                return ConversationActivity.this.c.draftService().generateForContact(j);
            }
        }, new Tasks.Done<Result<DraftOutcome>>() { // from class: com.replymate.app.ui.ConversationActivity.8
            @Override // com.replymate.app.platform.Tasks.Done
            public void accept(Result<DraftOutcome> result) {
                ConversationActivity.this.generating = false;
                ConversationActivity.this.btnGen.setEnabled(true);
                if (result.ok) {
                    ConversationActivity.this.showStatus("Ready — " + result.value.drafts.size() + " suggestions in " + (result.value.latencyMs / 1000.0d) + "s", Ui.GREEN);
                    ConversationActivity.this.refreshDrafts();
                    ConversationActivity.this.scrollToBottom();
                    return;
                }
                ConversationActivity.this.showStatus("Couldn't generate: " + result.error, Ui.RED);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void refreshDrafts() {
        this.draftList.removeAllViews();
        List<Draft> byContact = this.c.drafts().byContact(this.contact.id, 30);
        this.draftsHeader.setVisibility(byContact.isEmpty() ? 8 : 0);
        Iterator<Draft> it = byContact.iterator();
        while (it.hasNext()) {
            this.draftList.addView(draftCard(it.next()));
        }
    }

    private View draftCard(final Draft draft) {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setBackgroundResource(R.drawable.bg_card);
        int dp = Ui.dp(this, 12);
        linearLayout.setPadding(dp, dp, dp, dp);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.topMargin = Ui.dp(this, 8);
        linearLayout.setLayoutParams(layoutParams);
        final EditText editText = new EditText(this);
        editText.setText(draft.replyText);
        editText.setTextColor(-1);
        editText.setTextSize(2, 15.0f);
        editText.setBackground(null);
        editText.setPadding(0, 0, 0, Ui.dp(this, 6));
        linearLayout.addView(editText);
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(0);
        linearLayout2.setGravity(16);
        linearLayout.addView(linearLayout2);
        linearLayout2.addView(Ui.sub(this, draft.model + " · " + TimeFmt.dayTime(draft.createdAt) + " · " + draft.status.wire), new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button btn = Ui.btn(this, "Copy");
        btn.setTextSize(2, 12.0f);
        linearLayout2.addView(btn, new LinearLayout.LayoutParams(-2, -2));
        btn.setOnClickListener(new View.OnClickListener() { // from class: com.replymate.app.ui.ConversationActivity.9
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                String trim = editText.getText().toString().trim();
                if (trim.isEmpty()) {
                    Toast.makeText(ConversationActivity.this, "Nothing to copy", 0).show();
                    return;
                }
                if (!trim.equals(draft.replyText)) {
                    ConversationActivity.this.c.drafts().updateText(draft.id, trim);
                    ConversationActivity.this.c.drafts().updateStatus(draft.id, DraftStatus.EDITED);
                } else {
                    ConversationActivity.this.c.drafts().updateStatus(draft.id, DraftStatus.COPIED);
                }
                ((ClipboardManager) ConversationActivity.this.getSystemService("clipboard")).setPrimaryClip(ClipData.newPlainText("replymate draft", trim));
                Toast.makeText(ConversationActivity.this, "Copied", 0).show();
            }
        });
        return linearLayout;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showStatus(String str, int i) {
        this.genStatus.setVisibility(0);
        this.genStatus.setText(str);
        this.genStatus.setTextColor(i);
    }
}
