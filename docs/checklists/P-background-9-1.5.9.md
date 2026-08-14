# P-background-9 — BLOCKING listener + background fix (1.5.9 / vc40)

APK for device verdicts: CI proof build on this commit range (signed with the
historical cert `b15f2f37…c6a85ed`, in-place update over 1.5.8 — all data kept).
Posted with the release message: artifact name, sha256, size.

*Phase-name note: "P-background-9" previously named the 1.4.8 phase
(`P-background-9-1.4.8.md`). This file is the 1.5.9 blocking-fix phase; the
owner's phase instruction for it is dated 2026-08-14.*

Each device item: what to do → what PROVES it worked. Reply per item: PASS / FAIL + what you saw.

---

## 0. The re-audit (proven in code BEFORE anything was fixed; baseline 757/757 green @ 16c64a2)

The full path was re-traced line by line on the current build:
NotificationListenerService → extraction → classification → identity/contact
resolution → dedupe → burst → ping → debounced draft generation → alert →
catch-up. Seven findings; the first four are the real breaks.

1. 🔴 **ONE 2-thread pool ran EVERYTHING** (`Tasks.BG`): listener capture
   callbacks, draft generation, live web research, retries and paid provider
   calls (15s connect / 45s read, up to 3 attempts each). Two slow drafts (bad
   network, research, reasoning prep) parked both threads for minutes while
   every WhatsApp callback queued behind them. "The listener stopped capturing",
   "background generation is slow" and "one crawling chat blocks the others"
   were the *same starvation* with three faces.
2. 🔴 **All scheduling state lived in process memory**
   (`AssistantRunner.PENDING`/`JobCoalescer` static maps + a main-looper
   Handler). Process death (recents-swipe on aggressive OEM builds, Doze kill,
   app update, reboot) lost every pending generation. The rebind self-heal
   (`reconcile`) re-processed still-active notifications — but dedupe correctly
   skipped already-STORED messages *before* scheduling anything, so their drafts
   silently never happened. The idempotent catch-up sweep (`retryUnanswered`)
   existed with exactly the right eligibility rules — wired ONLY to three UI
   events (permission grant, provider save, settings toggle). Unless the owner
   opened the app, dead-time messages never became drafts.
3. 🔴 **No failure recovery.** When the paid call failed terminally (network
   down, deep Doze), the conversation was abandoned until the next message —
   Diagnostics promised "it retries on the next message" and nothing else did.
4. 🟠 **Waiting drafts never came back after a restart/update.** Android wipes
   posted notifications on reboot; the sweep skipped every conversation whose
   done-hash matched ("answered") even though its GENERATED draft was still
   waiting and its card was gone.
5. 🟠 **In-chat system/service lines became messages.** WhatsApp's "🔒 Messages
   and calls are end-to-end encrypted…", "Your security code with X changed.",
   "Waiting for this message…" and no-category "Missed voice call" cards arrive
   UNDER THE CONTACT'S NAME with real MessagingStyle history — invisible to the
   self-title StatusFilter, the summary gate and the category gate. Sender-less
   MessagingStyle entries (app inserts) were stored as contact messages and
   could anchor bursts and trigger paid generations.
6. 🟡 `providerOrNull()` provider cache was unsynchronized while being callable
   from multiple background threads.
7. ✅ **Already solid at HEAD (pinned, unchanged):** groups/broadcast-flagged
   items and category-missed-calls drop pre-contact (NoiseGate); reactions drop
   pre-storage; media-only items are store-only and never *create*
   conversations; backup/sync/digest self-status drops under the self-title
   gate; summary geometry drops; JobCoalescer supersedes stale jobs including a
   post-research pre-paid abort; research runs on its own tight client with a
   9s total budget; reasoning has output-token headroom; one audible pop per
   draft cycle; a swipe-away never corrupts learning.

**Honest scope notes (unchanged truths, device-verified where possible):**
- A WhatsApp **broadcast-list** message is BY DESIGN indistinguishable from a
  personal one at the receiver — no notification field exposes it. It cannot be
  filtered without faking certainty. Channels/announcement posts that mark
  themselves group/broadcast ARE already dropped by the group gate. If an
  announcement still slips through on your build, §2 gives a capture drill so we
  can pin its exact payload shape.
- An app that NEVER names message senders keeps legacy behavior (nothing is
  dropped) — the sender-less rule only fires when the same payload does name
  senders, so it can never swallow a whole real thread.

## 1. The fixes (each mapped to a finding)

1. **Lane split** (F1): `Tasks` now has an ordered single-thread INGEST lane
   (listener capture only) and a separate 2-thread GEN lane (research, prep,
   paid calls, retries). Capture can never queue behind network again; one
   crawling draft can at most delay one other conversation. `BLUEPRINT §1.3`
   updated.
2. **Catch-up on every rebind** (F2): after `reconcile`, the listener fires the
   same throttled, idempotent sweep the UI used to trigger — recovering
   dead-time messages and killed generations with no app-open. Sweep throttle
   15s on connect. Plus the official `requestRebind` nudge at every process
   start (the MIUI/Transsion silent-unbind class of failure).
3. **Connectivity-return recovery** (F3): a default-network callback runs the
   same sweep (throttled 45s) the moment the network comes back.
4. **Restart/update re-alert** (F4): the sweep decision is now a pure,
   JVM-pinned matrix (`CatchupPolicy`): a still-waiting draft whose alert flag
   is armed but whose card was wiped is re-posted SILENTLY — no pop, no
   duplicate provider call. A swipe clears the flag first, so dismissed drafts
   are never re-shown. Sweep re-generation is age-guarded (48h) so ancient
   unanswered messages never cause a surprise draft or bill; live-path
   generation is never age-capped.
5. **System-line gate** (F5): `SystemLines` (canonical whole-card shapes only —
   encryption/security-code/waiting lines, bare missed/declined-call cards)
   drops these BEFORE any contact, chat row, ping, or provider call; and the
   MessagingStyle parser drops sender-less inserts inside otherwise-named
   histories.
6. **Provider cache** (F6): synchronized.

Nothing sends by itself; the human Approve remains the only send path. No new
permissions, no manifest delta.

## 2. Engineering evidence (reproducible from repo)

- JVM gate: **785/785 PASS** (baseline 757 + 28 new). New/changed suites:
  `SystemLinesTest` (9), `CatchupPolicyTest` (12), `MessagingStyleParserTest`
  (+3: sender-less inserts drop from mixed history; all-sender-less app loses
  nothing), `IngestCoordinatorTest` (+5: system lines/call cards never create
  contacts or pings; human "sorry i missed your call 🙏" stores + pings; system
  line inside a real burst drops without touching the rest).
- Whole-app compile proof: `engine/build.sh` local end-to-end build GREEN
  (aapt2 → javac incl. every app-layer edit → d8 → sign → verify).
- aura→Arsenal topic-reset regression kept green (`BurstWatermarkTest`,
  `PendingDraftContextTest` — inside the 785).

## 3. Device verdicts — capture reliability (§1 of the brief)

1. **After install:** clean install → grant notification access + alerts → one
   WhatsApp 1:1 message while ReplyMate is closed → capture + draft alert
   within ~10s (burst rules from 1.5.8 still apply: ONE pop, ONE draft).
2. **After app removal from recents:** swipe ReplyMate away; send another 1:1
   message → draft alert still arrives (listener rebind + reconcile + sweep).
3. **After listener rebind:** toggle notification access off/on (or reboot the
   phone), then a new message → capture resumes with no app open.
4. **Screen off:** phone locked/asleep a few minutes → message arrives → alert
   fires (battery: Unrestricted per Settings prompt).
5. **Starvation drill (the 1.5.8 failure shape):** on a slow network, fire a
   research-triggering question in chat A (forces a slow draft) while a normal
   message lands in chat B → B's capture + draft alert must NOT wait on A.
   Diagnostics shows both honestly.

## 4. Device verdicts — filtering (§2 of the brief)

From a quiet Home, send/arrive: WhatsApp backup card · "3 new messages" summary
· encryption notice and security-code line inside a real chat · missed
voice-call card · a reaction-only notice · media-only notice · a group message
· a broadcast "channel updates" card if you follow any.
→ No new contacts on Home, no drafts, no pings, zero provider cost
(Diagnostics → the assistant ring stays empty for these). A normal 1:1 text
between them still generates normally, and **"sorry i missed your call 🙏" is
never dropped** (human sentences about calls are messages, not cards).
If any announcement/channel card DOES slip through: screenshot its exact
title+text (that payload's shape becomes the next pinned test).

## 5. Device verdicts — background generation speed/reliability (§3)

1. With ReplyMate closed, ordinary message → draft alert within ~10s.
2. Research question + reasoning-heavy moment from background: draft arrives;
   the prompt-audit trail says which path (native search / encyclopedia+ms /
   cache / honesty line) and which thinking level.
3. Kill the network (airplane), trigger a generation, restore the network and
   DON'T open ReplyMate → the catch-up sweep delivers the draft on its own
   (throttled; give it ~a minute).
4. Kill the app mid-generation (recents swipe while a draft is pending), never
   reopen → the rebind sweep regenerates and alerts by itself.
5. Two chats at once on a bad network → each gets its own alert; neither waits
   permanently behind the other; Diagnostics records any superseded job.

## 6. Preserved-behavior sweep (§4 of the brief — regression rows)

Approve & send (live + after dismissal via cached target) · swipe-away re-arms
the next pop and is NOT learned as rejection · inline Edit on the alert · manual
Send from the conversation screen (live/cached/copy fallback honesty) · chat
timeline intact · Approve flow ledger · memory/customization visible in the
draft trail · Home Search · reasoning lines only when warranted · topic reset
after an answered burst (the aura→Arsenal rule — JVM-pinned too).

## 7. Known/accepted behaviors (unchanged, intentional)

- Sweep-triggered catch-up skips unanswered messages older than 48h (logged
  once per message in Diagnostics; open the chat to answer by hand).
- A deliberately swiped-away draft card is never re-shown by any sweep.
- After a device reboot, still-waiting draft cards return SILENTLY (no sound).
- Broadcast DMs (1:1-shaped) can't be told apart from personal DMs — by the
  sending app's design, not by our choice.
- Old noise rows created before 1.5.7 stay in history (only new ingest filters).

---

## Gate status — created 2026-08-14

- Workspace cleanup (root): unrelated 88MB `calculator/` project, stale
  signing-phase `proof/` experiments (incl. stand-in keystores + a leftover
  password file — shredded), a superseded format-patch, the old audit doc and a
  redundant branch bundle (heads still on the remote) removed. Kept: source,
  tests, release tooling, docs, release history, engine + signing setup.
  Verified: repo root clean, `git status` clean.
- Re-audit + three blocking fixes + regression tests: DONE (this file §0–§2).
- JVM gate **785/785**; full-app local build GREEN (commit `1c97f63`).
- **CI proof build GREEN — run `31770815912`** (dispatched on `1c97f63`):
  - `KEYSTORE-VERDICT: MATCH` — materialized PKCS12 (owner's keystore from
    Actions secrets) carries the historical identity.
  - `APK-CERT-PROOF: MATCH` — artifact `ReplyMate-1.5.9-background-proof.apk`
    signs with cert SHA-256 `B15F2F37…6A85ED` ⇒ in-place update over ≤1.5.8
    preserved; all chats/drafts/settings kept.
  - Artifact `ReplyMate-1.5.9-background-proof-vc902` (id `9208057812`), APK
    size 584,010 bytes; APK sha256 **recomputed off-CI in the sandbox**:
    `0e91c7910ad933077348c9733130f04bf9efa13cf88415742d865128c00e9d4d`;
    embedded cert re-extracted off-CI = `b15f2f37…6a85ed` ✓.
- **Owner device verdicts — OPEN.** §3–§6 are real-phone PASS/FAIL rows; the
  next phase must not start until capture, filtering and fast background
  generation all PASS on-device. Nothing in engineering verification marks
  them.
