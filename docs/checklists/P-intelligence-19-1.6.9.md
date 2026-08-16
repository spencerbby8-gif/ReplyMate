# P-intelligence-19 — DISCORD DELIVERY + REGENERATE/EDIT + NOTIFICATION UX (2026-08-16)

Ships as **1.6.9 / vc916**, proof name `ReplyMate-1.6.9-discordfix-proof.apk`.

Permanent rule path: previous build re-verified (gate 1002/1002 @ `babb98f`; vc915
artifact sha `490e514d…` + release cert re-verified) → reproduce first
(**2/1007 pins FAILED pre-fix**: `discordServerChannelWithAReplyActionIsConversational`,
`replyCapableServerChannelMessagePingsNormally`) → audit → docs research → fix →
regression sweep → gate **1007/1007** → CI → off-CI verify.

## 1. Discord conversation delivery

**Root cause (reproduced in-gate before fixing):** the P-17 announcement heuristic
fired on the self-announce shape (`group` + sender name == conversation title) WITHOUT
considering capability. Discord single-shot server notifications produce exactly that
shape (sender falls back to the channel title), so a normal #general message carrying
a REAL free-form Reply action was stamped `announcement` → stored silently, generation
hard-stopped.

**Docs basis (re-researched Aug 2026):** Discord's own model — replies are ordinary
channel-bound messages (type 19 + `message_reference` carrying channel_id/guild_id);
"announcement" is a CHANNEL property (GUILD_ANNOUNCEMENT) that Android's notification
surface never exposes to a listener. Android exposes per-notification ACTIONS instead:
a real RemoteInput-bearing Reply action IS the platform's replyability signal.

**Fix (one tier, honest):** `ItemClassifier` — ANNOUNCEMENT now requires the
self-announce shape **AND the absence of a free-form Reply action** on the item's own
notification. Capability wins: #general-with-Reply ⇒ `direct_reply`, generates and
pings normally (ingest pin: stored + pings + `[direct_reply]` ring). A true
announcement still arrives with NO reply action ⇒ still `announcement`, still blocked
(control pins kept green). Delivery carries unchanged P-17/17R guarantees: the guard
fires only the target whose identity matches the answered channel — never another
channel's action or PendingIntent (decisive-tier + unique-match rules intact);
safety never depended on the announcement class.

## 2. Regenerate + Edit

**Regenerate (banner + in-app):** banner → `regenerateNow` (force; supersedes stale
jobs; immediate — see latency) → `generateAndNotify` → purge-replaces the untouched
draft (pinned: `sequentialRegeneratesLeaveExactlyOneCurrentDraft` — one current draft,
newest take, same answered message). In-app generate now ALSO re-posts the shade
alert in place via the new `AssistantRunner.refreshAlert` bridge (same stable
tag+id, fresh draft primary, no pop, never a duplicate card — the old in-app path
left the alert stale).

**Edit:** banner inline Edit (RemoteInput) → same draft `updateText`+`EDITED`+learning,
same alert re-posted silently; fallback opens the editor screen. `DraftEditActivity`
now preloads the **store's current text** for the exact draft (never an empty/stale
composer) and retargets a replaced draft to the contact's newest generated one; Save
persists text + EDITED-status + learning and hands off to the SAME guarded
approve/copy pipeline.

**Latency trace (measured, not guessed):** tap → broadcast (`goAsync`, BG lane) →
`regenerateNow` → `JOBS.begin` token swap (0 ms) → GEN pool (**3 threads**, no serial
queue) → local research → **provider network call = the only dominant cost** →
purge+insert (local) → `postWithLabels` (same tag+id). The ping-path debounce
(5 s + 1.5 s settle) does NOT apply to regenerate (force). No artificial wait exists
on this path; nothing to remove. Stale-job aborts (pre-call + post-call) are the
only "waits", and they protect paid calls — kept.

## 3. Notification UX (audit; structure confirmed correct)

The draft is already the PRIMARY content (collapsed text + leads expanded body);
the source message is a clearly-divided secondary context block with its own time
("Replying to X · time: “…”"). Every action (Approve / Send / Edit / Regenerate /
Copy) carries the SAME (contactId, draftId, draftText) — immutable draft +
conversation identity — and every re-issue posts to the SAME `(notifTag(contactId),
NOTIF_ID)` pair → Android replaces in place; cards can never stack. These are
structural invariants; on-device rows below verify their rendering.

## 4. Regression tests (mandated set)

| Area | Pin |
|---|---|
| Discord #general (Reply action present) | `ItemClassifierTest.discordServerChannelWithAReplyActionIsConversational`, `…aNamedSpeakerInAServerChannelWasNeverClassedAnnouncement`, `FailClosedIngestTest.replyCapableServerChannelMessagePingsNormally` |
| Real announcement / no reply | `ItemClassifierTest.selfAnnouncingChannelIsAnAnnouncement` + new `…aTrueAnnouncementStillHasNoReplyAction`; `FailClosedIngestTest.announcementStoresAsContextButNeverPings`; `NonReplyableStopTest` |
| Cross-channel target safety | `DeliveryGuardTest.*` (borrow refuse, cross-app, same-name decisive ids), `TargetRulesTest`, `SendPathTest` — untouched, green |
| Regenerate synchronization | `OneDraftPerMessageTest.sequentialRegeneratesLeaveExactlyOneCurrentDraft` (+ existing concurrency pins) |
| Edit prefill/save synchronization | store-first preload + retarget (app-side; engine-compiled; device row P19-4/5) — learning parity pinned by `ManualSendLearnerTest`/banner EDITED path |
| Notification replacement | stable `notifTag+NOTIF_ID` structural; in-place update on regenerate via `refreshAlert` (device row P19-3) |

## Proof

| Check | Result |
|---|---|
| Previous build | gate 1002/1002 @ `babb98f`; vc915 artifact sha + cert re-verified |
| Repro before fix | **2/1007 FAILED** (the two Discord pins) — pre-fix classifier |
| JVM gate | **1007/1007 ALL SUITES PASSED** (+5) |
| Engine devcheck | vc916 / 1.6.9-devcheck19, debug cert `446310cc…d2` MATCH, APK+idsig deleted |
| Commit | `c46c311` (this checklist in 2/2) |
| CI | run **31968320488 GREEN** (success @ 19:41Z); artifact **9269095651** `ReplyMate-1.6.9-discordfix-proof-vc916`; logs: KEYSTORE-VERDICT MATCH ×2, APK-CERT-PROOF MATCH ×2 |
| Off-CI verify | artifact **661,834 B**; sha256 `86197b7c59ecb656fb0dc13658dfffcec04ba721d1c37a8e43272d89c4bf2643`; cert **B1:5F:2F…6A:85:ED MATCH**; badging `com.replymate.app` vc916 / `1.6.9-discordfix-proof` / minSdk 24 / target 35 |

## Device proof (OWNER — never self-certified)

| Row | Behavior | Verdict |
|---|---|---|
| P19-1 | Discord #general with a Reply action → normal ping + draft; classification line reads `[direct_reply]`, NOT announcement | ☐ OPEN |
| P19-2 | Approve that draft → text lands in THE SAME Discord channel via its own action; Diagnostics send target = source channel; no other channel touched | ☐ OPEN |
| P19-3 | In-app ✨ (re)generate while the shade alert is up → alert text updates in place, ONE card only; regenerated in-app draft = alert text = same draft id | ☐ OPEN |
| P19-4 | Banner Edit (or editor-screen fallback) → composer preloaded with the CURRENT reply text, never empty; Save updates that same draft | ☐ OPEN |
| P19-5 | Regenerate ⇒ old take disappears from app list (one current draft), no stale duplicates ever | ☐ OPEN |
| P19-6 | True announcement channel (read-only, no Reply action) → still stored silently, generation stops with the mandated explanation | ☐ OPEN |
| P19-7 | Cross-channel sweep (Discord #general vs #announcements, WhatsApp 1:1 interleaved) → every approval's send target = its source conversation | ☐ OPEN |
| P19-8 | Latency feel: banner Regenerate starts immediately (status/ping refresh), no multi-second artificial wait; provider time only | ☐ OPEN |

Earlier rows still owner-OPEN: P18-1…10 (1.6.8), P17R-1…6 (1.6.7), PI-17-1…10 (1.6.6),
GCE-1…11 (1.6.5), FIN-1…9 (1.6.3).

## Regressions checked

Unknown fail-closed, service/system/reaction/call/media tiers, engagement gate,
guard/send-path, + Them, manual send — every P-16b…P-18 suite re-run green inside
the 1007. The announcement safety net keeps zero new escapes: capability evidence
only ever RECLASSIFIES toward conversational; delivery safety is enforced by the
guard chain, not by the class.
