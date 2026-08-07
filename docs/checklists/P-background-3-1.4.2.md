# P-background-3 — BLOCKING RemoteInput audit — 1.4.2 / vc23 (2026-08-07)

Device report: background generation works (1.4.1 fix confirmed in the field), but
on real WhatsApp ReplyMate reported "no usable reply action" while WhatsApp visibly
shows **Reply**. Audited the full path against the latest official docs before
changing anything; found and fixed the miss; added regression tests that replay the
exact failing shape.

## 📦 Downloads

| file | size (bytes) | sha256 |
|---|---|---|
| `releases/ReplyMate-1.4.2.apk` | 211,194 | `d60fefa615127e2b28bce728181748fefc38b9a06a2a61c29b7f4e5c74d18c3b` |

Cert `b15f2f37…` unchanged → updates in place.

## 🔍 Root cause of the false "no usable reply action"

Official docs (`Notification.Action`, page updated 2026-03) define two surfaces:
`getRemoteInputs()` (text-accepting inputs on the **standard actions array**) and,
documented by observed messenger behavior (Signal-style pattern), the
**WearableExtender** actions (`android.wearable.EXTENSIONS` bundle key) that Android
Auto/Wear reply through. Apps place a visible Reply button in the standard array
that merely *opens the chat* (no RemoteInput) and put the actual RemoteInput-bearing
action ONLY in the WearableExtender list.

**1.4.x read `Notification.actions` ONLY.** The device shape — plain "Reply"
(standard, no RemoteInput) + real RemoteInput action (wearable) — was therefore
honestly misdetected as NONE and fell back. Fix: extractor now scans BOTH surfaces
and tracks each action's source; the target records list+index+result key; the
receiver resolves the PendingIntent from the SAME surface it was captured from.

## 🧭 The audited path, stage by stage (all verified in code + docs)

| stage | verdict |
|---|---|
| WhatsApp notification → listener | ✅ `RmNotificationListener` (read-only), unchanged |
| → Notification.Action extraction | ✅ docs-complete: standard array + WearableExtender, source tracked, malformed entries degrade singly |
| → RemoteInput / result key | ✅ `getRemoteInputs()` doc: "Only returns inputs which accept a text input"; we require free-form + non-blank result key (choice-only reactions are NOT text replies — pinned by test) |
| → PendingIntent | ✅ never persisted; re-resolved live at tap time from `getActiveNotifications()` by sbn key |
| → injected text | ✅ official contract only: `Bundle.putCharSequence(resultKey, text)` + `RemoteInput.addResultsToIntent` + `actionIntent.send(ctx,0,fillIn)` — identical to Android Auto |

## ⚖️ "Is injecting text into WhatsApp's reply flow officially supported?"

- Pre-TYPING into another app's visible reply box while its UI sits open: **NO
  official API exists** — not implemented, not faked.
- Delivering text headlessly through the app's OWN RemoteInput action contract:
  **YES — this is precisely what RemoteInput exists for** ("creates a RemoteInput
  object to let other apps provide reply text", official messaging-notifications
  guide). We ship exactly that via human-approved Approve & send. If a future
  WhatsApp build closes even that, our approve path falls back honestly to copy.

## 🧪 Regression tests for THIS exact failure

- JVM (3 new planner pins, suite now **463/463**): wearable-only ⇒ DIRECT; standard
  preferred when both surfaces offer reply; null when nothing usable.
- Robolectric with REAL framework Bundles (suite now **29/29, 0 failures**), replaying:
  1. RemoteInput on standard array ⇒ DIRECT/standard/index/key + sbnKey captured;
  2. **RemoteInput ONLY in WearableExtender (the device-reported shape) ⇒ DIRECT/wearable** — 1.4.x returned NONE here;
  3. plain Reply/Mark-read ⇒ NONE **plus probe string shows observed geometry**;
  4. fixed-choice reaction input ⇒ NONE (never inject text into pickers).
- Harness re-bootstrapped after the sandbox wipe (maven + android-all + aar→jar installs), recipe unchanged.

## 🔁 Scenario matrix (owner's list)

- **Multiple WhatsApp notifications**: burst → one coalesced job; newest incoming answered; target re-captured from the latest sbn each time.
- **Regeneration**: same alert identity → in-place replace; draft purge-on-regen; superseded jobs abort pre-provider-call.
- **Dismissal of the WhatsApp notification**: approve-time live re-resolve fails → ledger reason + clipboard fallback, never silent.
- **Listener restart**: `ACTIVE` null → honest copy fallback recorded.
- **App restart / reboot**: kv-persisted dedupe + targets; no duplicate drafts; old alerts' PendingIntents still valid; stale sbn → fallback.

## 🩺 Self-evidencing device probe (no trust needed)

When capability is NONE, the ledger now records the observed geometry, e.g.
`std[Reply:ri0][Mark as read:ri0] wear[Reply:ri1ff/key_text_reply]` — every action
on both surfaces with its remote-input shape. If YOUR WhatsApp still shows NONE
after this, the ledger line tells us the exact surface reality, not a guess.
Approve taps record REMOTE_SEND outcomes the same way.

## ✅ Verification

- 463/463 JVM unit • 29/29 Robolectric (real framework code paths) • APK battery:
  vc23/1.4.2, minSdk 24/target 34, cert match, `WearableExtender` present in dex,
  zero secret leaks, vault absent.
- Only remaining device-side proof (as before): the literal send into WhatsApp —
  it exercises WhatsApp's private receiver, which we cannot run in the sandbox.
  It is now instrumented end-to-end (approve → ledger), so one tap on device
  closes the loop with evidence recorded.

## ⏳ Honest remainder

Some apps historically ignore RemoteInput results sent without extra app-specific
fields (old Hangouts note in community reports). If WhatsApp ever ignores the
send, the approve path lands in the honest copy fallback WITH the reason logged —
the user is never stranded and we never claim success we didn't see.
