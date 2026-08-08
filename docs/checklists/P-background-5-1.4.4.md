# P-background-5 — BLOCKING send-path audit — 1.4.4 / vc25 (2026-08-08)

Device report: generation + preview card now work, but the send path was wrong —
text sat in ReplyMate's own preview space instead of going through the app's real
reply action when one exists. Mandate: separate preview vs RemoteInput send,
prove each audit stage, never fake a send, keep background generation working.

## 📦 Download

| file | size (bytes) | sha256 |
|---|---|---|
| `releases/ReplyMate-1.4.4.apk` | 215,290 | `56d877695dece912641620f59c4ca32985d6702bdea45085b35cd9b3d9b485bc` |

Cert `b15f2f37…` unchanged → installs in place over 1.4.3.

## 🔍 What the audit found (2 real defects + 1 hardening)

1. **A button that lied.** "Open conversation" was wired to the receiver broadcast
   with `actionFor(Btn.OPEN)` falling through `default` → `ACTION_REGEN`. Tapping
   it silently **regenerated** (text in the preview card changed) instead of
   opening the conversation — matching the "send path acts on the preview space"
   feel. Fixed: OPEN now uses the same activity PendingIntent as tapping the body.
2. **Drift-prone index trust.** The approve path resolved the stored action by
   bare list index. Notifications are live: re-orders/updates shift indices, so a
   reply could fire the wrong action (or die honestly with confusing wording).
   Fixed: resolution matches the stored **exact RemoteInput result key** on the
   same documented surface; the stored index is only the first guess. Key absent
   → honest fallback, never a best-guess send. Choice-only actions with the key
   are rejected (free-form required).
3. **Per-stage ledger for the owner's audit list.** Approve now records:
   observed geometry (probe, since 1.4.2) → resolved action title+surface+key
   match → result keys filled → PendingIntent fired → **post-send observation**
   at +1.5s (source notification closed / still visible / shade link gone) —
   recorded as *corroboration*, never presented as proof.

## ⚖️ Path separation (owner's rules → shipped behavior)

- **Preview card** = banner body + ConversationActivity drafts. It *shows* the
  generated draft and is never a send target: its only actions are UI openers.
- **Real reply path (DIRECT):** Approve & send → RemoteInput results → the app's
  own PendingIntent, re-resolved live by key. No text is ever typed into, pasted
  into, or injected through preview UI.
- **No reply action (NONE):** banner is Copy / Regenerate / Open conversation
  with a caption that says exactly why. Nothing is faked, no "send" label exists.
- Every Approve outcome is ledger-verifiable: sent / copied-fallback + the
  precise reason. `Sent ✓` only appears when the firing returned success.

## 🧪 Proof (run on this tree)

- **JVM: 463/463** · **Robolectric: 53/53** (+7 new pins):
  - `resolveByResultKeySurvivesActionReorder` — stale index → key-match still
    lands on the real Reply.
  - `missingResultKeyResolvesNullHonestly` / `choiceOnlyActionWithKeyDoesNotQualify`.
  - `openButtonOpensConversationNeverTheSendBroadcast` +
    `approveButtonTargetsTheSendBroadcastNeverThePreview` — separation, pinned.
  - `postSendObservationIsLedgeredHonestly`.
  - `googleMessagesStandardRemoteInputReplyShapeIsDirect` — third app with reply.
- Fill-contract suite (unchanged, still green): real PendingIntent broadcast +
  Parcel round-trip, read back via official `RemoteInput.getResultsFromIntent()`
  on both surfaces — WhatsApp & Telegram shapes; Instagram-shape stays NONE.

## 📱 Owner device pass (proof in the real reply flow)

1. Install 1.4.4 in place. Get a WhatsApp message → banner shows Approve & send.
2. Tap **Approve & send**: expect the text to land in the chat through WhatsApp's
   quick-reply, banner settles `Sent ✓`, and the WhatsApp notification usually
   closes itself (WhatsApp treats it as a typed reply).
3. Settings → Diagnostics → Assistant ledger now shows the full trail:
   `approve_resolve: resolved 'Reply' @standard key-match=key_text_reply` →
   `remote_send: —` → post-send observation line. If anything falls back, the
   reason names the exact stage (recycled / never-offered(+probe) / dismissed /
   moved / not-a-text-reply / expired / rejected).
4. Repeat on **Telegram** (real reply) → same expectation. **Instagram**
   (enable in Notification sources): Copy / Regenerate / Open, honest caption —
   no send pretended.
5. "Open conversation" button now actually opens the conversation. 🙃

If Approve still doesn't land the text in WhatsApp after this: the ledger's
approve_resolve + remote_send + post-send lines will say exactly where it broke —
send me a screenshot. The contract side is proven byte-exact in the harness; the
only remaining variable is WhatsApp's private receiver, which the ledger now
makes visible.

## ⏳ Honest limits

- Client-side, "the message actually went through" can only be *corroborated*
  (send returned + source notification behavior) — final truth is the chat row
  in the source app. The device pass above is the real verdict, as mandated.
- Deferred unchanged: owner TODOs (PAT + DB password rotation, dashboard toggles).
