# P-intelligence-18 — MESSAGE UNDERSTANDING + PERSONALIZATION + MANUAL CONTEXT (2026-08-16)

Ships as **1.6.8 / vc915**, proof name `ReplyMate-1.6.8-manualcontext-proof.apk`.

Permanent rule path: previous build re-verified (gate **991/991** @ `8557c1c`; vc914
artifact re-hashed `518768a8…cc0b` + release cert B1:5F:2F…6A:85:ED re-verified) →
code audited end-to-end → **official docs re-researched online** (Aug 2026) → plan →
regression review → implement → gate → devcheck → CI → off-CI verify. Device rows are
owner-marked, never self-certified; **no next phase until this build passes on-device.**

## 1. Personalization + learning (audit result)

Every Global Voice + Edit Contact control traced UI → store → precedence → prompt →
provider request → learning; the existing wire-proof suites were re-run inside the
gate and all hold:

- **9 dials** (tone, reply length, emoji, formality, humor, confidence, slang,
  flirting, follow-ups) — each OFF-capable with zero-prompt-phrase OFF semantics
  (`StyleControls`, pinned by `StyleSettingsTest`, `VoiceControlsOffTest`,
  `AllDialsWireProofTest`); visible effect pinned by `VoicePromptProofTest`,
  `DraftServiceToneTest`, `CustomizationEffectTest`.
- **Language** via the voice charter (`VoiceCharterTest`); **About Them / relationship /
  custom instructions** via contact settings (`EditContactWireProofTest` proves the
  edit screen's dials actually reach the prompt).
- **Precedence:** explicit contact/group settings > global > learned
  (`LearningPrecedenceTest`, `VoiceCharterTest`); **learning isolation:** approved /
  edited / regenerated / manual signals teach ONLY their conversation
  (`LearningServiceTest`, `ContextIsolationTest`, memory `IsolationSuite`).
- **Off states** are absolute: AI-off/private gates win before any provider call
  (`BackgroundDraftGuardTest`, `GenerationHonestyTest`).
- **Change this phase (§3):** a send typed into ReplyMate's manual box now runs
  `ManualSendLearner.evaluate` exactly like an in-app manual send — identical words ⇒
  APPROVED, different words ⇒ EDITED (`manual:<tokens>`), per-draft dedupe marker
  prevents double-learning with the listener path.

## 2. Group + message understanding (audit result)

- Announcement is declared ONLY on positive self-announce evidence — **missing reply
  metadata is never announcement evidence** (explicit new pin:
  `ItemClassifierTest.missingReplyMetadataNeverMakesAnAnnouncement`); UNKNOWN fails
  closed (`FailClosedIngestTest`).
- Participants (stable keys, same-name aliases), topics/subtopics, who-addressed-owner,
  what-the-owner-answered: `ParticipantRegistryTest`, `TopicTrackerTest`,
  `EngagementClassifierTest`, `ConversationStateServiceTest`,
  `GroupChatUnderstandingTest`, `GroupEngagementGateTest` — all re-run green; verdicts
  (REPLY_REQUIRED/OPTIONAL/WAIT/NO_REPLY) are evidence-based with reasons; no reply
  target is ever invented (P-16b/P-17 guard chain intact).

## 3. Missed messages + manual context (new)

**`ManualEntryService`** (`core.usecase`, pure) — **+ Them** stores a missed INCOMING
message: attributed (1:1 ⇒ contact; group ⇒ the named member is REQUIRED), provenance
`Source.MANUALLY_ADDED`, origin channel kept for context/dedupe, conversation identity
**copied** from the contact's captured rows (never invented, `""` stays `""`),
`notifKey = null`, `itemClass = ""`, `senderKey = ""` — **no fabricated platform
metadata**. It bypasses ingest entirely: no ping, no burst job, no provider call; it
lands in burst/topic/context/memory/history like any incoming row, so the NEXT draft
answers it (`theNextDraftAnswersTheManuallyAddedMessage`: draft.inReplyToId == the
added row, provider request contains their words). A late notification of the same
words folds via near-dup (`aLateNotificationOfTheSameWordsCollapsesNotDuplicates`).
Group mentions added by hand drive REPLY_REQUIRED|MENTIONED|<member>
(`groupMentionAddedByHandDrivesReplyRequired`).

**Manual outgoing (audited + completed):** the composer already stored OUTGOING with
`Source.MANUAL` + real clock timestamp; it now also stamps the conversation identity
(copied) and teaches live drafts (above). Delivery keeps the P-17/17R guard chain —
no valid identical target ⇒ honest Copy + Open, never a fake send.

**UI (`ConversationActivity`):** a **+ Them** button beside the composer — dialog takes
their words (and, for groups, the member's name); the thread renders these bubbles
“… · added by you (not captured)”, never with a platform app label.

## 4. Missed-notification recovery (audit result, docs re-researched)

`RmNotificationListener.onListenerConnected → getActiveNotifications() → reconcile`
already re-reads everything still in the shade and ingests never-seen posts; MessagingStyle
**historic** entries inside an active notification are captured too. **What cannot be
recovered:** official NotificationListenerService semantics expose only *outstanding*
notifications ("useful when you don't know what's already been posted") — when the
messaging app was open and suppressed/posted no notification, there is NO platform API
that yields the message. We never fabricate it; **+ Them is the explicit fallback**,
exactly as mandated.

## 5. Safe delivery

Unchanged invariants, re-pinned this gate: never borrow another notification's
action/PendingIntent/channel/target (`DeliveryGuardTest`, `TargetRulesTest`,
`SendPathTest`, `ConversationMatchTest`); never send announcement/service/system
(`NonReplyableStopTest`); never cross apps; no valid target ⇒ no send.

## 6. Proof

| Check | Result |
|---|---|
| Previous build | gate 991/991 @ `8557c1c`; vc914 artifact sha + release cert re-verified |
| JVM gate | **1002/1002 ALL SUITES PASSED** (+11: ManualIncoming 10, missing-metadata-announcement pin 1) |
| Engine devcheck | vc915 / 1.6.8-devcheck18, debug cert `446310cc…d2` MATCH, APK+idsig deleted |
| Commit | `0c4e4f7` (this checklist in 2/2) |
| CI | run **31963315939 GREEN** (success @ 18:01Z); artifact **9267803152** `ReplyMate-1.6.8-manualcontext-proof-vc915`; logs: KEYSTORE-VERDICT MATCH ×2, APK-CERT-PROOF MATCH ×2 |
| Off-CI verify | artifact **661,834 B**; sha256 `490e514db9269bad1d13e6160a610efb9ac4845e5f3a8939690e95bc3c43da34`; cert **B1:5F:2F…6A:85:ED MATCH**; badging `com.replymate.app` vc915 / `1.6.8-manualcontext-proof` / minSdk 24 / target 35 |

### Regression tests — mandated trace (classification / provenance / direction / conv-id / target / provider / delivery)

| Test | Classification | Provenance | Direction | Conv identity | Reply target | Provider call | Delivery target |
|---|---|---|---|---|---|---|---|
| `storesIncomingAttributedWithManualProvenance` | unstamped (`""`) | MANUALLY_ADDED | INCOMING (Amara) | copied/empty | n/a (no ping) | NO | none |
| `noPlatformMetadataIsFabricated` | `""` (no notification evidence) | MANUALLY_ADDED | INCOMING | empty | none | NO | none |
| `conversationIdentityIsCopiedNeverInvented` | `""` | MANUALLY_ADDED | INCOMING | copied `…@s.whatsapp.net` | none | NO | none |
| `groupEntriesRequireTheNamedMember` | refused else | MANUALLY_ADDED | INCOMING (Chidi) | copied | none | NO | none |
| `theNextDraftAnswersTheManuallyAddedMessage` | `""` flows on | MANUALLY_ADDED | INCOMING | — | that message id | YES (1) | contact-scoped |
| `groupMentionAddedByHandDrivesReplyRequired` | group context | MANUALLY_ADDED | INCOMING | — | MENTIONED\|Chidi | YES (1) | group target |
| `aLateNotificationOfTheSameWordsCollapses…` | real_1to1 (listener copy) | listener dup fold | INCOMING | same | none | NO | none (duplicate) |
| `missingReplyMetadataNeverMakesAnAnnouncement` | group_message | — | — | — | — | — | — |
| existing guard suites (17/17R) | n/a | — | — | — | strict match only | — | refused on mismatch |

## Device proof (OWNER — never self-certified)

Report: **classification → provenance → direction → conv ID → target → provider call
yes/no → delivery target**.

| Row | Behavior | Verdict |
|---|---|---|
| P18-1 | + Them on a 1:1 (WhatsApp): add their missed message → appears as THEIR bubble with “added by you (not captured)” — no app label, correct time | ☐ OPEN |
| P18-2 | After + Them, tap Generate → the draft answers THAT message (their words visibly drive it); one provider call, one draft | ☐ OPEN |
| P18-3 | + Them in a group REQUIRES the member's name; a hand-added “Spencer …” mention from Chidi ⇒ REQUIRED alert naming Chidi | ☐ OPEN |
| P18-4 | If the missed message later arrives as a notification → no duplicate row, no second ping | ☐ OPEN |
| P18-5 | Manual reply (➤): stores as YOUR message with right identity; with a live draft it teaches (Diagnostics “learned · …”); delivery still guard-checked (target = this conversation or honest Copy+Open) | ☐ OPEN |
| P18-6 | Personalization sweep: set contact dials (e.g. emoji OFF, length short, Pidgin) → drafts visibly follow; globals unchanged; regenerate/learned precedence intact | ☐ OPEN |
| P18-7 | Group burst still coalesces; verdicts REPLY_REQUIRED/OPTIONAL/WAIT/NO_REPLY show reasons in Diagnostics; ordinary group chat never says “announcement” | ☐ OPEN |
| P18-8 | Cross-conversation sweep after + Them and manual sends: approved text never lands in another chat/app (P-17 chain) | ☐ OPEN |
| P18-9 | App-open missed chat: nothing appears automatically (platform truth); + Them is the recovery — no fabrications in thread/Diagnostics | ☐ OPEN |
| P18-10 | Search/reasoning unchanged: in-thread search finds manual + captured rows alike; Memory/About Them lines still attributed correctly | ☐ OPEN |

Earlier rows still owner-OPEN: P17R-1…6 (1.6.7), PI-17-1…10 (1.6.6), GCE-1…11 (1.6.5), FIN-1…9 (1.6.3).

## Regressions checked

Full P-16b/17/17R suites re-run green (engagement gate, classifier, delivery guard,
send path, memory isolation, voice/prompt wire proofs). Manual rows can never
satisfy the non-replyable stop (unstamped ⇒ pre-v9 flow, pinned) nor the UNKNOWN
fail-closed path (they bypass ingest). No auto-send anywhere; approval stands.
