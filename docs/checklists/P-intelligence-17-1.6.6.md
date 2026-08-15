# P-intelligence-17 — MESSAGE UNDERSTANDING + SAFE DELIVERY AUDIT (2026-08-15)

Ships as **1.6.6 / vc913**, proof name `ReplyMate-1.6.6-delivery-proof.apk`.

Permanent rule path: previous build verified (gate 942/942 @ `fc7ef9d`, vc912 artifact
sha256 `a47b511b…` on disk + release cert B1:5F:2F…6A:85:ED re-verified) → every P-16b
claim re-audited in real code → **official docs re-researched online** (Android
`Notification`/`MessagingStyle`/`Person` reference + `RemoteInput`, Aug 2026) → plan →
regression review → implement → gate → devcheck → CI → off-CI verify. Device rows are
owner-marked, never self-certified; **no next phase until this build passes on-device.**

## 0. The bug this phase exists to kill (owner, verbatim)

> "A Discord server announcement with no Reply action still generated a draft. After
> approval, ReplyMate sent the text through another channel notification that DID have
> a Reply action. This is forbidden."

**Root cause — confirmed in code, two cooperating mechanisms:**

1. `AssistantTargetStore.refreshFromLive(...)` (called from `AssistantRunner.notifyDraft`
   when the stored target was unusable) adopted **the first active notification with ANY
   free-form action matched by packageName ONLY**. A Discord announcement posts with no
   Reply action, so at draft time the re-probe "borrowed" the live `#general`
   notification of the same app. Its legit purpose (capture-time raw can predate the
   visible actions — WhatsApp attaches them a beat later) had no identity constraint.
2. `AssistantTargetStore.save()` ran per ping and **unconditionally overwrote** geometry,
   dropping the cached PendingIntent — an actionless re-post *downgraded* a good target.

Nothing compared the captured target's conversation identity against the **message being
answered**. Now everything on that path does.

## 1. Message understanding — the classifier (mandate §1)

**`ItemClass`** (`core.listener`): `REAL_1TO1, GROUP_MESSAGE, DIRECT_REPLY, MENTION,
ANNOUNCEMENT, BROADCAST, SERVICE, SYSTEM, REACTION, CALL, MEDIA_ONLY, UNKNOWN`.
`ANNOUNCEMENT/BROADCAST/SERVICE/SYSTEM` are `isNonReplyable()`.

**`ItemClassifier`** (pure, JVM-pinned) maps each parsed event using ALL platform
metadata we honestly have — category, `isGroupConversation`, sender identity
(name/**Person key**/uri), conversation identity (shortcut id/title), content kind,
reaction/service/system text shapes, **reply capability (free-form `RemoteInput` on the
item's OWN notification)**, owner-name mention. Order: SYSTEM → SERVICE → REACTION →
CALL → MEDIA_ONLY → **UNKNOWN (fail closed)** → ANNOUNCEMENT (positive evidence only:
the item speaks AS the channel/conversation itself) → MENTION → DIRECT_REPLY →
REAL_1TO1/GROUP_MESSAGE.

**Docs honesty (re-verified against the official reference, Aug 2026):** Android exposes
**no announcement/broadcast flag** to third-party watchers, and `MessagingStyle` exposes
**no reply linkage**. So: announcement is declared ONLY on positive (self-announce)
evidence — a human post in a read-only channel is indistinguishable pre-generation from
a group message, and safety there is carried by the DeliveryGuard + capability honesty,
**never by a guessed class**. UNKNOWN must fail closed, not become a normal message —
it now stops before contact, row, ping, ledger (`FailClosedIngestTest`).

**Schema v9:** `message.item_class` (what it was), `message.conv_id`/`conv_title` (the
message's OWN conversation identity — the delivery guard's refusal evidence). Stamped at
ingest; pre-v9 rows default `''` and are honestly "unverifiable at conversation level"
in the ledger instead of pretending verification. Ingest ring lines now carry the class
(`… [mention]`).

## 2. Safe delivery — the guard chain (mandates §2/§4)

- **`TargetRules.shouldReplaceOnCapture`** — an actionless re-post of the SAME
  conversation no longer wipes a usable stored target; fresh geometry WITH a real
  free-form action always replaces; a different conversation always replaces.
- **`TargetRules.mayAdoptLive`** — the generate-time live re-probe may adopt a live
  notification ONLY when the stored target is identifiable AND the live notification is
  the SAME conversation (strict `ConversationMatch`: package + convId→convTitle→title,
  exact, non-empty) AND it really exposes a free-form action. **The cross-notification
  borrow is structurally dead.**
- **`DeliveryGuard.check`** — before ANY approved text fires (live action, re-posted
  action, cached PendingIntent), the target identity is compared against the answered
  message's schema-v9 identity: different package ⇒ `REFUSE_DIFFERENT_APP`; identified
  message + mismatch ⇒ `REFUSE_DIFFERENT_CONVERSATION`; unusable ⇒ `REFUSE_NOT_USABLE`;
  identified + match ⇒ `ALLOW` (verified); message without identity (1:1 / pre-v9 row)
  ⇒ `ALLOW_UNVERIFIED` — allowed by contact scope but honestly marked **unverified**,
  never a silent "verified". Wired into `AssistantReceiver.approveAndSend` AND
  `DirectDelivery.deliver` (new 8-arg signature; `ManualComposer` passes the answered
  message identity). Refusals state the human reason in Diagnostics.
- **Immutable target:** target identity is decided at capture and only replaced by the
  rules above — a later notification never silently re-aims an existing draft's target.

## 3. Non-replyable stop (mandate §3)

`DraftService` hard-stops on the v9 stamp BEFORE research and long before the paid
provider call: *"This message can't be replied to from ReplyMate — it came through as
&lt;class&gt;, not something you can answer in a chat. No reply was written."* — no
draft, no provider call, no send. Stamped real messages and honestly-unstamped pre-v9
rows flow on (both pinned).

## 4. Learning isolation (mandate §5)

No new learning surfaces were added; the audit ran the EXISTING isolation suites inside
the gate — learning store per-contact isolation, memory (per-contact, group↔1:1
non-bleed), EditContact wire proof, `LearningToPromptTest`, P-16b prompt/engine suites —
**all GREEN at 980**, so approved/edited/regenerated/manual signals still teach only the
conversation they happened on. Device confirmation rides on PI-17-9 below.

## Proof

| Check | Result |
|---|---|
| JVM gate | **980/980 ALL SUITES PASSED** (+38 net: ItemClassifier 10, FailClosedIngest 5, DeliveryGuard 8, TargetRules 5, NonReplyableStop 5, SchemaV9 4, +1 ingest companion; SchemaV8 suite updated for chain position — DDL untouched, LATEST moved past 8) |
| Engine devcheck | vc913 / 1.6.6-devcheck17, debug cert `446310cc…d2` MATCH, APK+idsig deleted |
| Commit | `c0d1f61` (this checklist follows in 2/2) |
| CI | run **31897437237 GREEN** (success @ 17:08Z); artifact **9250183738** `ReplyMate-1.6.6-delivery-proof-vc913`; logs: KEYSTORE-VERDICT MATCH ×2, APK-CERT-PROOF MATCH ×2 |
| Off-CI verify | artifact **657,738 B**; sha256 `ebf10cfac9ccc17e723af141a23d85e8ee0808f45990566429ca223711e59dc5`; cert **B1:5F:2F:37…6A:85:ED MATCH**; badging `com.replymate.app` vc913 / `1.6.6-delivery-proof` / minSdk 24 / target 35 |

## Device proof (OWNER — never self-certified; next phase waits on these)

Report each row as: **classification → reason → source message ID → reply target →
provider call yes/no → send target**, using real Discord/WhatsApp/Telegram threads
(1:1 AND group). Enable group chats in Sources for the group rows.

| Row | Behavior | Verdict |
|---|---|---|
| PI-17-1 | **Discord announcement, no Reply action** (the reported bug): post in a server announcement channel → classified `announcement`, draft attempt stops with "This message can't be replied to from ReplyMate…", **no provider call, no draft**; if a draft existed from an older build, Approve & send **refuses** (Diagnostics: DIFFERENT_CONVERSATION/NOT_USABLE) — **nothing is sent to any channel** | ☐ OPEN |
| PI-17-2 | **Cross-channel borrow attempt**: while #general (Reply action present) is live, trigger the #announcements flow → delivery never fires through #general's action; send target shown is the announcement's own channel or NONE | ☐ OPEN |
| PI-17-3 | **Rapid group burst** (10+ msgs/30 s): one classified row per message (class + sender in Diagnostics ring), ONE ping, engagement gate unchanged (GCE-1 posture) | ☐ OPEN |
| PI-17-4 | **Direct mention** in a group → `mention` class, REQUIRED draft citing the mentioning message (source message ID in Diagnostics), target = that conversation | ☐ OPEN |
| PI-17-5 | **Native reply** (swipe-reply in WhatsApp/Telegram) → draft sends via the source app's own reply action into the SAME conversation; never a faked quote | ☐ OPEN |
| PI-17-6 | **Ambiguous target**: notification re-posted without actions after target loss → Approve & send shows the honest no-usable-target explanation instead of guessing; Copy still works | ☐ OPEN |
| PI-17-7 | **Topic change** mid-chat → draft follows the new topic (GCE-7 posture), class stays `group_message` | ☐ OPEN |
| PI-17-8 | **Same-name members** → registry aliases hold (`Chidi` / `Chidi 2`), drafts attribute to the right person | ☐ OPEN |
| PI-17-9 | **Learning isolation**: approve/edited/manual replies in group A never shape drafts for group B or any 1:1; manual answer still supersedes | ☐ OPEN |
| PI-17-10 | **Wrong-channel delivery regression sweep**: approve drafts across WhatsApp 1:1, Telegram group, Discord channel in sequence → every send lands in the conversation the draft was written for (send target in Diagnostics = source conversation) | ☐ OPEN |

Still owner-OPEN from earlier phases: **GCE-1…11** (1.6.5), **FIN-1…9** (1.6.3). This
phase does not close or supersede them — they remain owed device verdicts.

## Regressions checked

- Groups default-OFF still drops group items before any contact/row (`groupDefaultOffStillDrops…`).
- Media-only/call/reaction/system pre-P-17 filters unchanged (classifier runs first and
  agrees: those suites untouched, all green).
- 1:1 flow byte-identical: `1:1` classes stamp `real_1to1`/`direct_reply`;
  pre-v9 rows (stamp `''`) run the exact pre-17 path (`anUnstampedPreV9RowStillGenerates`).
- Approve/Copy unchanged for ordinary contacts; guard only refuses NEVER-legal sends.
- No auto-send anywhere on the new path — approval remains required for every draft.

**Honesty limits, restated:** read-only-channel posts with no self-announce evidence are
classified `group_message` (the platform gives no announcement flag); safety for those
rides on the DeliveryGuard refusing unverifiable cross-conversation sends, on the
capture rules above, and on the fact that a target captured for THAT conversation *is*
the right conversation. Classification = what we can prove, never what we can guess.
