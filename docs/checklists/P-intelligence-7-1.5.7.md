# P-intelligence-7 — device verdict checklist (1.5.7 / vc38)

APK: `releases/ReplyMate-1.5.7.apk` · **280,906 bytes**
sha256: **`5c13af2b2b018107740bd26fbc4140f455e2e180d7333ba7fabfbd53116beaf4`**
In-place update over 1.5.6 (same signing cert `b15f2f37…c6a85ed`). All data kept.
For the FRESH-INSTALL verdicts (§1, §2): wipe/uninstall once and install clean —
that is the exact bug being proven fixed.

Each item: what to do → what PROVES it worked. Reply per item: PASS / FAIL + what you saw.

What this build fixes (from the code audit — both owner bugs traceable to real causes):

- **First message after install was silently swallowed.** Android 13+ denies the
  "show notifications" approval until it is asked — and generation was gated on
  that approval *even though the draft lives in-app*. Fix: a draft now generates
  with or without the approval (visible in ReplyMate), the approval is asked ONCE
  on the first home-screen open, and a catch-up sweep re-alerts caught-up
  messages the moment the approval (or a provider key) lands.
- **Non-message noise could create conversations.** Group/broadcast items were
  stored (cluttering home), missed calls stored placeholder rows, media-first
  notices created empty conversations, and app service shapes like "23 new
  messages" / "You have new messages" / backup-card variants could become fake
  messages from a "WhatsApp" contact. Fix: all of these are dropped BEFORE any
  contact, row, ping, or draft exists. Real 1:1 messages (incl. a captioned
  photo from a new sender) are untouched.

---

## 1. Fresh install — the first message
1. Uninstall ReplyMate, install 1.5.7 clean. Open it — set up account, add a
   provider key (Settings → AI providers), enable Background generation.
   First open of the home screen: Android should ask once to **show
   notifications** — ALLOW it.
2. Have someone send you ONE WhatsApp message (with the app closed).
   → Expected: within ~10s, a "Reply ready" alert appears, and the draft is
   correct — correct person, right topic, your voice. Open the draft trail: why
   credits (plan line, live-context line) are present.
3. Approve/Correct as usual from the alert.
   → Expected: approve/edit/manual flow behaves exactly as before.

## 2. Fresh install — approve LATE (the silent-swallow path)
1. Fresh install again. This time set up everything but when Android asks to
   show notifications, DENY it. Have someone message you.
   → Expected: no alert (nothing to post honestly), BUT the draft IS generated —
   open ReplyMate: the conversation + pending draft exist. Settings → Diagnostics
   shows an assistant event: "'show notifications' approval missing… no alert posted".
2. Now grant the approval (App info → Notifications → allow).
   → Expected: the alert for that waiting draft appears WITHOUT a second message
   arriving, and no duplicate draft was created (one version of the reply).

## 3. Restart resilience
1. With a working setup: message arrives → before opening anything, force-stop
   ReplyMate, then reopen it (or just wait for Android to rebind the listener).
   → Expected: the draft arrives ( reconcile-on-rebind + debounce ) — same
   behavior as 1.5.6 or better, no duplicates.
2. Toggle the notification-access permission OFF and ON, message again.
   → Expected: after re-grant, the next message generates normally.

## 4. Noise — nothing may create conversations
For each: have notifications arrive while ReplyMate watches, then check home +
Settings → Diagnostics ring. **PASS = no new home entry, no draft, no alert
from that item.**
1. WhatsApp backup card ("Backing up messages…" progress card).
2. Group summary ("23 new messages" style) / "You have new messages".
3. A WhatsApp GROUP message (not a 1:1 chat with you).
4. A missed voice/video call notification from someone who has never messaged
   you in ReplyMate (or a first-time caller).
5. A reaction-only notice ("Reacted 👍 to …"), if your chat app re-posts them.
6. A FIRST-ever contact sending only 📷 Photo / 🎤 voice note (no text).
   → Expected: nothing is created. (If that same person then sends real text,
   the conversation opens normally and the media/call context policy from before
   applies inside it.)

## 5. Real messages — nothing broken
1. 1:1 message, new person → conversation + draft (§1 behavior).
2. Existing thread: burst of 3 rapid texts → ONE draft answering the whole burst
   (watermark rule intact).
3. Aura-farming-style regression: draft A (not approved) → new topic B → the
   next draft answers B only.
4. A captioned photo from a NEW sender ("Bro check this 📷") → conversation
   DOES open (that is a real message), draft answers the caption.
5. Manual compose + manual send copied through your normal flow → works as before.

## 6. Intelligence pieces intact
1. Planning depth (Basic/Normal/Deep) still changes the why-trail plan line.
2. "who won the arsenal game last night?" on a native-search provider (Gemini/
   OpenAI) → live-search credit in the trail; ordinary messages never trigger it.
3. Deep-set provider (DeepSeek/Mistral) phrase ("wetin be kaku abeg") →
   encyclopedia evidence line or the honest lookup-failed line — never an excuse.
4. Prompt Audit opens, shows provider/model/search credits, no secrets.
5. Contact customization (tone/emoji/formality/About-Them) still steers drafts.
6. A second contact's thread never influences the first's (isolation smoke).

## 7. Residual/intentional (expected, not bugs)
- Previously-stored group/media/call rows on upgraded installs remain (history
  is never rewritten); only NEW ingest is blocked. Home search may still find
  old ones.
- With alerts denied, the draft shows IN-APP only — that is the intended honest
  behavior; Diagnostics explains exactly why.
- Catch-up runs when: the approval is granted (home/settings result) or a
  provider is first saved. It pays no duplicate provider calls for drafts that
  already wait.
