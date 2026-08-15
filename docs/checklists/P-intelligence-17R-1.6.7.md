# P-intelligence-17R — DELIVERY REGRESSION FIX (2026-08-15)

Ships as **1.6.7 / vc914**, proof name `ReplyMate-1.6.7-deliveryfix-proof.apk`.
Owner-reproduced on 1.6.6 (vc913): *"ReplyMate can identify a real message as
belonging to a known chat, but approval incorrectly says the message is not meant
for that chat."* Reproduced in the gate BEFORE any code change: the two legit-chat
pins below failed on `805fa8f` (2 failures / 983), everything else green.

## 1. Root cause (audited: DeliveryGuard → TargetRules → AssistantTargetStore →
DirectDelivery → re-post reconciliation → identity stamping → PI matching)

Two different identity conventions collided inside the P-17 guard call:

- **Message rows (schema v9)** are stamped by the PARSERS:
  `convTitle := firstNonBlank(EXTRA_CONVERSATION_TITLE, EXTRA_TITLE)` — a 1:1 chat
  carries the partner NAME in `convTitle` (`"Amara"`).
- **Captured targets** keep the raw fields separate: `convTitle` raw (`""` on 1:1),
  `title = EXTRA_TITLE`.

`DeliveryGuard.check` passed the message-side title as hardcoded `""`, so the match's
title tier compared `"Amara"` against `""` → every **title-only identified chat**
(normal 1:1s whose notifications carry no shortcut id, or whose stored row predates
the shortcut-id re-post — the drift IngestCoordinator itself documents) refused with
DIFFERENT_CONVERSATION. Groups escaped because both sides carry a real conversation
title. That is the whole regression: the guard was right to exist, wrong on ONE wire.

Two adjacent holes were found in the same audit and closed with it:
1. `ConversationMatch.same` **fell through** to weaker tiers when both sides carried
   DIFFERENT native ids — two same-name chats could match on title (cross-send hole,
   owner test #7).
2. `findConversationMatch`/generate-time adoption picked the most recent of
   **several** identity matches — a guess, forbidden by mandate ("ambiguous target →
   do not guess").

## 2. Fixes (all slices pure + JVM-pinned; Android shell is a thin executor)

- **`DeliveryGuard`** — the message-side title now follows the parser convention
  (the stamped `convTitle` feeds the title tier). Legit title-only 1:1 ⇒ `ALLOW`
  (verified); decisive tiers still refuse the mismatches.
- **`ConversationMatch` v2 — decisive tiers:** when BOTH sides carry a field (convId,
  then conversationTitle, then title), equality decides that tier; a mismatch can
  never be resurrected by a weaker tier. One-sided/empty falls through (shortcut-id
  drift across re-posts is real and must NOT refuse legit chats). All 8 pre-existing
  pins still green; 3 new pins added.
- **`SendPath`** (new pure core) — approve-time order: **live original → UNIQUE
  same-conversation re-post (changed notification key/id never blocks delivery) →
  conversation-bound cached token → honest failure.** Wired into
  `AssistantReceiver.approveAndSend`; `DirectDelivery` (manual composer) shares the
  fixed guard + unique-match rebind.
- **Ambiguity hard-stop:** `conversationMatchCount` (0/1/2-capped) feeds the plan;
  ≥2 identity matches ⇒ **never rebind** — the cached reply PendingIntent is
  conversation-bound BY THE SOURCE APP and is preferred; zero matches ⇒ cached;
  nothing ⇒ honest fail with a real reason. `refreshFromLive` (generate-time
  adoption) equally requires a unique match.
- **PendingIntent discipline (unchanged, re-verified):** PIs are keyed per
  ReplyMate contact == one conversation slot (`targetKvKey(contactId)` + in-process
  `LIVE_PI` map); `save()` clears the cached PI whenever geometry changes; a cached
  PI is only ever fired for the conversation whose own notification carried it —
  ReplyMate never creates or retargets reply PIs.

**Docs basis (re-researched Aug 2026):** official *Create a notification → direct
reply* guidance — *"If you reuse a PendingIntent, a user might reply to a different
conversation than the one they intend. You must provide a request code that is
different for each conversation or provide an intent that doesn't return true when
you call equals() on the reply intent of any other conversation."* — exactly why the
cached token is only ever fired for its own conversation and why ambiguous live
matches fall through to it.

## 3. Mandated regression tests (7/7 pinned, in the gate)

| # | Owner scenario | JVM pin |
|---|---|---|
| 1 | normal known chat → approve → send | `DeliveryGuardTest.aKnownOneOnOneChatWithTitleOnlyIdentityMUSTAllow` (FAILED pre-fix — the repro) |
| 2 | same chat re-post, changed key → still sends | `SendPathTest.sameChatRepostWithChangedKeyRebindsToItsCurrentAction` + `ConversationMatchTest.oneSidedNativeIdArbitratesOnTheSharedTitle` (drift — FAILED pre-fix) |
| 3 | dismissed notification → cached target sends | `SendPathTest.dismissedNotificationUsesTheCachedConversationBoundToken` |
| 4 | another app → hard reject | `DeliveryGuardTest.aTargetFromAnotherAppIsRefused` |
| 5 | announcement, no reply action → hard reject | `DeliveryGuardTest.anUnusableTargetIsRefused` + `NonReplyableStopTest.announcementStopsBeforeGeneration…` (pre-generation stop) |
| 6 | action from another conversation → hard reject | `DeliveryGuardTest.theCrossChannelBorrowIsRefused` + `…differentConversationIdsRefuseEvenWhenTitlesCollide` |
| 7 | two conversations, same package/name → never cross-send | `ConversationMatchTest.differentNativeIdsNeverMatchEvenWhenTitlesCollide` + `SendPathTest.ambiguousIdentityMatchesNeverGuessALiveTarget` |

## Proof

| Check | Result |
|---|---|
| Repro before fix | 2/983 FAILED (both legit-chat pins) on `805fa8f` |
| JVM gate | **991/991 ALL SUITES PASSED** (+11: DeliveryGuard 3, ConversationMatch 3, SendPath 5) |
| Engine devcheck | vc914 / 1.6.7-devcheck17R, debug cert `446310cc…d2` MATCH, APK+idsig deleted |
| Commit | `33d8058` (this checklist in 2/2) |
| CI | run **31900321651 GREEN** (success @ 18:09Z); artifact **9250902085** `ReplyMate-1.6.7-deliveryfix-proof-vc914`; logs: KEYSTORE-VERDICT MATCH ×2, APK-CERT-PROOF MATCH ×2 |
| Off-CI verify | artifact **657,738 B**; sha256 `518768a84f897e177cdbb047c4a3d7dbf21754a547e72912399296462a36cc0b`; cert **B1:5F:2F:37…6A:85:ED MATCH**; badging `com.replymate.app` vc914 / `1.6.7-deliveryfix-proof` / minSdk 24 / target 35 |

## Device proof (OWNER — never self-certified)

Report each row as: **classification → reason → source message ID → reply target →
provider call yes/no → send target**.

| Row | Behavior | Verdict |
|---|---|---|
| P17R-1 | Normal 1:1 chat (WhatsApp/Telegram) → draft → Approve & send → text lands in THAT chat (the 1.6.6 failure) | ☐ OPEN |
| P17R-2 | Same chat re-posted under a NEW notification key (update the app / dismiss→new message) → approval rebinds and still sends into the same chat | ☐ OPEN |
| P17R-3 | Dismiss the source notification, approve later → cached conversation-bound token still sends; Diagnostics says CACHED/unwatched honestly | ☐ OPEN |
| P17R-4 | Two same-name chats on one app (or a contact both 1:1 and in a group): approving chat A never lands in chat B; ambiguous live state ⇒ cached token or honest no-send, never a guess | ☐ OPEN |
| P17R-5 | Discord/server announcement with no Reply action → stops pre-generation ("can't be replied to from ReplyMate"); any stale draft's approval hard-refuses | ☐ OPEN |
| P17R-6 | Mixed sweep (WhatsApp 1:1 → Telegram group → Discord channel approvals) → every send target in Diagnostics equals the draft's source conversation; zero cross-sends | ☐ OPEN |

Earlier rows still owner-OPEN and unclosed by this phase: PI-17-1…10 (1.6.6),
GCE-1…11 (1.6.5), FIN-1…9 (1.6.3).

## Regressions checked

- All 8 pre-17R ConversationMatch pins + all P-17 guard/target/classifier/stop pins
  green unchanged; group default-off, media/call/reaction/system filters untouched.
- `ALLOW_UNVERIFIED` (1:1/pre-v9 rows) path unchanged; manual composer shares the fix.
- No auto-send introduced anywhere; approval remains required for every draft.
