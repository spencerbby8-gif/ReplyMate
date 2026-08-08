# P-background-9 — 1.4.8/vc29 — CORE PRODUCT AUDIT continued
(filtering · dismissal-safe send · burst · memory/learning · customization · banner Edit · multi-contact · cold start · ranking)

Owner directive: continue the core audit; re-audit before changing; no unrelated
features; prove on a real device with WhatsApp + another reply-capable app.

---

## 1 · Notification filtering — aggressive, evidence-based, no false kills

Stack re-verified (nothing assumed): parser category gate → StatusFilter
(self-status, self-titled only) → ListenerFilter verdicts → generation gates.

| Class the owner named | Behavior in 1.4.8 | Proof |
|---|---|---|
| backups / "checking for new messages" / sync-progress | IGNORED at the parser gate (self-status, self-titled + evidence-free only) | `StatusFilterTest`×10 + NEW parser-level cases in `MessagingStyleParserTest` |
| system/summary digests (package-/self-titled) | IGNORED (same gate) | NEW `systemStyleDigestFromTheAppItselfIsIgnored` |
| Discord announcements / channel broadcasts | parsed (memory may use them) but `#…`-titled ⇒ group ⇒ STORE_ONLY ⇒ **never ping, never draft** | NEW `discordAnnouncementChannelIsGroupAndNeverPings` (+ DM still pings) |
| reactions | NEW: dropped BEFORE storage/ping — WhatsApp/Signal `Reacted ❤️ to “…”`, IG/Messenger `liked/loved your message`; quote-required after "to" so human sentences survive | `ContentSignalsTest.reaction*` + `IngestCoordinatorTest.reactionNoticesAreDropped…` |
| calls | ringing/ongoing IGNORED; finished outcomes stored store-only (CALL kind), never pinged, never drafted | existing `callCategoryIsIgnored…`, `ListenerFilter` unreadable-kind verdict |
| media-only events | STORE_ONLY placeholder, never pinged/drafted | existing suites (kept green) |
| status updates / service alerts | self-status gate + category gate | as above |
| bots / broadcast lists | **honest boundary**: no official notification signal distinguishes a bot DM or a broadcast list from a person — ReplyMate does not invent one. Documented, not faked. | this file |

A REAL message (1:1, text) always passes — pinned by `aRealWhatsappMessageStillPassesTheSameGate`
and the whole existing real-message suite.

## 2 · Dismissal-safe send — unchanged from 1.4.7, re-proven

Live exact key → **same-conversation re-post (strict identity)** → cached
PendingIntent → honest Copy fallback. "Sent ✓ via cached target" stays explicitly
unwatched; nothing is lost; nothing claims Sent without firing. All 1.4.7 robo pins
green (+ new money test remains): `approveAfterDismissalSendsThroughRepostedSameConversation`,
`approveWithNoLiveNoCacheNoIdentityCopiesHonestly`, all `RemoteInputFillRoboTest` cases.

## 3 · Burst — locked, device-verifiable

Collect while typing (rolling 5s + 1.5s settle, one job/conversation) → ONE reply for
the whole burst (corrections win, newest topic wins, filler ignored) → refresh the
SAME card silently if more arrive → re-arm immediately after send. Pins: `BurstTaskTest`,
`AssistantRunnerRoboTest.nextIncomingAfterSendRearmsGeneration`, banner replace-in-place.

## 4 · Memory + learning — continuity without parroting

Memory layers (summary/facts/learned style/relationship) unchanged and isolated.
- NEW prompt rule: "memory informs, never dictates… never recycle an older reply —
  write every message fresh" (parrot guard) — pinned in `memoryInformsButNeverRecyclesOldReplies`.
- Learning signals cover approved (sent/copied), edited (delta-classified), regenerated,
  rejected — manual screen + banner + NEW edit screen all record through the same rules,
  contact-scoped, gate-respecting.

## 5 · Customization — full pipeline re-audit (AllSettings→prompt→audit)

All 9 controls + My Voice + contact overrides + About Them + custom rules +
learned hints + memory reach the final prompt (18-level control pin +
isolation pins green). Contact overrides win (`resolve()` contact-first).
Prompt Audit shows: global voice, overrides, contact profile credits, custom rules,
learned lines, memory, and now the **cold-start label** (see §7).

## 6 · ReplyMate banner — Edit added (owner-mandated)

- Actions are now **Approve & send / Edit / Regenerate** — Android hard-caps 3 actions;
  OPEN stays one tap away as the card body tap (captions say "Tap the card to open this chat").
  OPEN also remains on settled cards.
- NEW `DraftEditActivity`: edit the generated reply in-app → **Save & send** routes the
  EDITED text through the exact approve pipeline (live → repost → cached → honest fallback);
  **Save & copy** (manual-screen parity: records EDITED, not a double approval);
  **Discard & regenerate**.
- Money test: `editedDraftSendsThroughTheSameApprovePipeline` — real click → verified
  dispatched SEND broadcast → delivered via OS-hop → probe PI receives the edited text
  through official RemoteInput results → EDITED + APPROVED counters → "Sent ✓".
- One card per conversation, stable tag, updates never stack (long-standing pin).

## 7 · Multiple contacts + ranking + cold start

- Isolation suites unchanged-green (no cross-contact anything).
- NEW `ChatRanker`: Chats screen orders by **real latest message activity**
  (incoming OR outgoing), no-message contacts below, edits don't count — `updated_at`
  order is gone from UX. Pins: `ChatRankerTest` ×4.
- NEW cold start: unknown contacts generate from neutral defaults immediately; Prompt
  Audit labels `cold start — new contact, neutral assumptions`; the label retires the
  moment anything is customized. Pins: `unknownContactColdStartIsHonestInTheAudit`,
  `IngestCoordinatorTest.unknownConversationStillWorks`.

## 8 · Owner's verification checklist → test mapping

| Owner ask | Suite(s) |
|---|---|
| non-message filtering, WA backup/system notices, Discord announcements, real WA messages | StatusFilterTest, MessagingStyleParserTest (incl. NEW), TitleTextParserTest (incl. NEW), ListenerFilterTest |
| multiple contacts | ContextIsolationTest, IsolationSuite, IngestCoordinatorTest |
| burst aggregation / topic changes / next burst after send | BurstTaskTest, AssistantRunnerRoboTest |
| unknown-contact cold start | CustomizationEffectTest (NEW), IngestCoordinatorTest |
| memory | MemoryContinuityTest, MemoryPersistenceRoboTest, MemoryMergeTest |
| My Voice / contact profile / About Them / custom instructions | CustomizationEffectTest (all green) |
| learned approvals and edits | LearningEngine/ServiceTests, AssistantLearningTest, edit money test |
| Prompt Audit | PromptAuditCompletenessTest, CustomizationEffectTest audit pins |
| dismissal-safe send | RemoteInputFillRoboTest, AssistantHeadsUpRoboTest |
| listener restart | ListenerPipelineRoboTest (reconcile path) |
| app restart | FNV-stable dedupe + MemoryPersistenceRoboTest + reconcile pins |

512 unit + 74 robo, ALL GREEN. APK battery: vc29/1.4.8, 227,658 B,
sha256 `cb646c501e09560a6ab87f9457404d1e610240ff7391005e72c1309e486cfc9c`,
cert `b15f2f37…85ed` (unchanged → in-place update), secrets scan clean.

## ON-DEVICE (owner) — WhatsApp + a second app (Signal/Telegram suggested)

1. **Filtering**: let a WhatsApp backup cycle run — ReplyMate must NOT draft/notify
   off it. Same for Discord `#announcements`. Real 1:1 texts must still pop.
2. **Edit**: banner → Edit → change words → Save & send → confirm the EDITED text
   lands in the chat app. Then Contact → learning shows the edit signal.
3. **Dismiss-after-edit**: clear the WA notification, then Edit → Save & send →
   confirm delivery + ledger line (`RE-POSTED`/`cached`).
4. **Burst**: rapid fragments incl. a correction ("no wait, 6") → ONE draft, newest
   topic answered, silent in-place refresh, second burst pops again after send.
5. **Ranking**: several chats → newest activity on top after each wave.
6. **Cold start**: a brand-new contact texts you → draft arrives; Prompt Audit shows
   the cold-start label; set About Them/rules → next draft reflects them.
7. **Reactions**: react to your own message from the other phone — no draft, no card.
8. Re-verify the 1.4.7 heads-up/dismissal checklist (all pins unchanged).

Gate: next phase only after these pass on-device.
