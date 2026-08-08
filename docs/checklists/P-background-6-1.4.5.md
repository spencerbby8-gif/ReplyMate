# P-background-6 — BLOCKING burst + permission-flow repair — 1.4.5 / vc26 (2026-08-08)

Device report (two issues): (1) rapid multiple texts from one chat get answered
separately instead of as one burst; (2) the background-generation toggle needs a
real Android approval flow. Full flow audit mandated: capture → burst grouping →
background generation → reply-action discovery → first draft → banner → Approve
visibility → Regenerate → send success → next-message re-arming → diagnostics.

## 📦 Download

| file | size (bytes) | sha256 |
|---|---|---|
| `releases/ReplyMate-1.4.5.apk` | 215,290 | `4a81864ffe1c707db04a57f8ce25d172e5185f029fd379fdb00223b8e3049383` |

Cert `b15f2f37…` unchanged → installs in place over 1.4.4.

## 🛠 What shipped

**1) Burst state machine (one topic, one reply).**
- Timing: rolling 5s batch window (+1.5s assistant settle) re-arms on every new
  ping of the conversation — a rapid burst coalesces into ONE scheduled job.
- Prompt: the task turn now quotes the whole UNREAD tail (incoming since the
  owner's last outgoing; ≤6 messages × 180 chars, media placeholders skipped) as
  a numbered burst and instructs the model to *summarize it into its single point
  and write ONE reply* (topic summary done by the model over the whole quoted
  burst). Single-message tails keep the exact previous task shape (pinned).
- Refresh-before-send: a later burst message hashes differently → the SAME banner
  updates in place (stable tag) with the refreshed draft — never stacked alerts.
- Re-arm: after any cycle, the next incoming message hashes differently vs the
  stored done-hash and generates again; the machine never turns itself off.

**2) First-draft Approve (no more Regenerate-to-reveal).**
- Root cause of the symptom: capture-time raw can predate the visible actions —
  WhatsApp commonly posts the notification first and attaches the Reply action a
  beat later, so the first banner inherited "no usable action".
- Fix: at generate time, if the stored target is unusable, ReplyMate re-probes
  the LIVE shade once (same-package notifications only — never another app's),
  and adopts a real RemoteInput action with its surface/index/key + probe, all
  ledgered (`capture-time raw had no usable action → live re-probe found it`).

**3) Real Settings permission flow (`AssistantPrereq`).**
- Toggle ON → plain-English dialog listing every missing approval (what each is
  *for*), requests POST_NOTIFICATIONS in-app (runtime permission), and opens the
  correct system screens: `ACTION_NOTIFICATION_LISTENER_SETTINGS` and
  `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`.
- The switch mirrors the real kv master state; the subtitle mirrors real
  capability: `on ✓ — access ✓ notifications ✓ battery ✓` or
  `on — blocked by: <named needs> (tap to fix)`. No fake "on".
- Diagnostics already shows listener enabled/connected + battery flag (1.4.3+).

## 🧪 Regression pins (owner's list, run on this tree)

| mandate | pin |
|---|---|
| burst → one topic reply | `BurstTaskTest` ×5 (JVM): tail collection back to last outgoing, burst task quotes all + demands ONE reply, single-message shape unchanged, outgoing splits burst, media placeholders skipped |
| background permission flow | `AssistantPrereqRoboTest` ×5: fresh install ⇒ 3 needs; each grant removes only its need; screen intents = the exact system actions; POST_NOTIFICATIONS is in-app only (never a fake screen); explanations/chips real |
| first draft has real Approve when reply exists | `AssistantTargetRefreshRoboTest.liveReprobeAdoptsTheRealReplyActionForTheFirstDraft` (+2 honest-negative pins: no live action ⇒ NONE; another app's action never arms this app's send) |
| Regenerate not needed to reveal | same re-probe pin (runs before the first banner posts) |
| next incoming after send triggers new draft | `AssistantRunnerRoboTest.nextIncomingAfterSendRearmsGeneration` (ledger grows for wave 2) |
| apps with/without reply | standing fixtures (WhatsApp standard+wearable, Telegram, Google Messages DIRECT; Instagram NONE) |
| listener restart / app restart | standing `ListenerPipelineRoboTest` suite (reconcile idempotent, watch-gated, restart state) |

**Totals: 468 unit + 62 robo, all green** (was 463 + 53).

## 📱 Owner device pass

1. Install in place. Settings → Background reply assistant → toggle **on**: the
   dialog explains what's needed and offers the correct screens; grant battery
   **Unrestricted** if asked. Subtitle should end at `on ✓ — access ✓
   notifications ✓ battery ✓`.
2. Burst test: have a friend fire 3 quick WhatsApp texts. Expect: **one** banner
   after the burst settles (~6.5s), drafted to the burst's point — and it should
   show **Approve & send** on the FIRST draft now (no Regenerate tap needed).
3. Approve & send → text lands in the chat; ledger shows resolve → send →
   post-send observation.
4. Another message later → a fresh draft generates (re-armed). Scroll Settings →
   Diagnostics to see the whole trail if anything looks off.

## ⏳ Honest limits

- Topic summarization happens in the model over the fully-quoted burst (by
  design) — diagnostics' prompt audit shows exactly what was quoted.
- Client-side send corroboration remains: send-returned + post-send observation;
  final truth is the chat row in the source app.
- Deferred unchanged: owner TODOs (rotations, dashboard toggles).
