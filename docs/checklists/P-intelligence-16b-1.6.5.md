# P-intelligence-16b — CONVERSATION INTELLIGENCE + NATURAL GROUP AI (2026-08-15)

(Owner reused the P-16 number; the first P-16 = Supabase integrity audit / 1.6.4.
This phase ships as **1.6.5 / vc912**.)

Permanent rule path: previous build verified (gate 891/891 @ `94a9f66`, vc911 artifact
sha256 `595f99ac…` on disk + cert B1:5F:2F…6A:85:ED re-verified) → code audited → **official
docs re-researched** → plan → implement → gate → devcheck → CI → off-CI verify.

## 1. Group conversation engine (mandate §1)

**`ConversationState`** (`core.convo`) — one platform-agnostic object per conversation:
group title + capture-time flag, owner (profile name), participants (stable ids +
collision-safe labels), recent speakers, meaningful burst (incoming since owner's last
reply, sender-attributed, ≤6 + overflow), current topic + active subtopic + replaced
topic, owner's last own line. Built from STORED rows (per-contact isolation structurally
kept) by `ConversationStateService`; learnables persist in kv (`convo.registry.<cid>`,
`convo.topic.<cid>`) and survive process death (test-pinned).

**Native metadata honesty (researched — official Android reference, Aug 2026):**
`Notification.MessagingStyle` exposes messages, senders as `Person` (name/**key**/uri),
historic messages (API 26), `isGroupConversation` (API 28), conversation title, and the
owner `Person` (getUser). It exposes **no reply-to/quote linkage** — so reply
relationships are derived from deterministic engagement evidence (explicit reasons),
never from invented metadata. Sender identity uses key → uri → name; when a platform
exposes nothing, NO phantom identity is created (sender-less entries stay system chrome).

**Stable sender ids — schema v8:** `message.sender_key` persists the platform Person key
at ingest (`SchemaV8`, `Migrations.LATEST=8`, DAO round-trip; ingest pins it). Names can
change/collide; ids can't. `ParticipantRegistry` refines display names over time and
assigns deterministic aliases (`Chidi`, `Chidi 2`) to different people sharing a name —
prompted explicitly as DIFFERENT people.

**Do not generate from every notification:** the burst pipeline (storage → dedupe →
one ping/conversation → JobCoalescer + 5s+1.5s settle) stays; groups now pass the
**engagement gate** inside `DraftService` BEFORE any research/provider call —
`EngagementClassifier` (pure, reason-pinned): `REPLY_REQUIRED` (name mention HIGH;
question right after owner's message, 30-min window MEDIUM; 1:1 always) /
`REPLY_OPTIONAL` (room question after wait, active member) / `WAIT` (fresh room
question or filler — ONE deferred 90 s re-check, never a loop) / `NO_REPLY`
(not addressed, filler after wait). WAIT/NO_REPLY ⇒ **zero provider calls, zero drafts**;
verdict + reason land in Diagnostics; same-content catch-up stays quiet (skip marker).

## 2. Reply target + natural delivery (mandate §2)

`ReplyTarget` = conversation id, sender stable id + label, message notifKey + snippet,
confidence (HIGH/MEDIUM), reason. A target exists ONLY on evidence (mention / question
following owner's message / open room question); general conversation gets NO forced
quote; ambiguity gets none. On the wire: HIGH → situation line "X is talking to YOU
directly: …" + burst annotation naming the targeted line; MEDIUM (room question) →
"the open question being answered is X's: …". Optional drafts carry join-in honesty
("Nobody asked for you by name… keep it light and additive"). Delivery stays the
platform's REAL mechanism — the source app's own quick-reply RemoteInput
(`DirectDelivery` LIVE/CONVERSATION/CACHED, capability-ledgered). **No fake quoting,
no auto-send** — approval (Approve/Copy) remains required for every draft; alerts are
verdict-salient ("Reply to Amara in Family", "You could chime in — Family").

## 3. Context + customization (mandate §3)

Pipeline order per generation: burst tail → ConversationState (topic/participants) →
contact/group profile + voice dials + custom prompts (Edit Contact) → history window →
long-term memory (contact-scoped) → learned style → global voice → per-contact controls
(explicit > learned, pinned by AllDialsWireProof/EditContactWireProof — re-run in gate) →
live-search gate + reasoning → task (burst/target-quoted). Group contacts use the SAME
Edit Contact controls (tone/language/relationship/AI-off/private/memory) — private/AI-off
still win BEFORE engagement (gate test). Memory isolation re-pinned (group vs 1:1 vs
group). 1:1 prompts byte-identical (wire test asserts zero new bytes + legacy task text).

## Proof

| Check | Result |
|---|---|
| JVM gate | **942/942 ALL SUITES PASSED** (+51: ParticipantRegistry 7, TopicTracker 6, EngagementClassifier 13, ConversationStateService 7, GroupEngagementGate 7, GroupPromptWire 8, SchemaV8 3) |
| Engine devcheck | vc912 / 1.6.5-devcheck16b, sha `263a1f0a…`, cert `446310cc…d2` MATCH, deleted |
| Commit | `f0f61ee` (+ checklist completion) |
| CI | run **31891558924 GREEN** (success @ 15:02Z); artifact **9248677395** `ReplyMate-1.6.5-conversation-proof-vc912`; logs: KEYSTORE-VERDICT MATCH ×2, APK-CERT-PROOF MATCH ×2 |
| Off-CI verify | artifact 645,450 B; sha256 `a47b511b01c82351a57c11c936452c183c1fc693baf7b4a79747c13798f4bb18`; cert **B1:5F:2F:37…6A:85:ED MATCH**; badging vc912 / 1.6.5-conversation-proof / minSdk 24 / target 35 |

## Device proof (OWNER — never self-certified; next phase waits on these)

| Row | Behavior | Verdict |
|---|---|---|
| GCE-1 | Rapid group burst (10+ msgs/30 s) → ONE draft, coalesced | ☐ OPEN |
| GCE-2 | Direct mention by name → REQUIRED draft quoting THAT message, alert says "Reply to X in …" | ☐ OPEN |
| GCE-3 | Reply/question right after your own message → drafted | ☐ OPEN |
| GCE-4 | Room question not for you → no draft at first pop (WAIT), optional only if unanswered | ☐ OPEN |
| GCE-5 | Group chatter not addressing you → silence; Diagnostics shows NO_REPLY:NOT_ADDRESSED | ☐ OPEN |
| GCE-6 | Two members sharing a first name → numbered aliases, never merged | ☐ OPEN |
| GCE-7 | Topic shifts mid-chat → draft follows the NEW topic | ☐ OPEN |
| GCE-8 | Group memory never bleeds into 1:1 or other groups (and vice versa) | ☐ OPEN |
| GCE-9 | Quick-reply approve sends via the source app's real reply box where offered; copy path otherwise; NEVER a faked quote, NEVER auto-sent | ☐ OPEN |
| GCE-10 | Edit Contact dials (group contact) visibly shape the group draft; AI-off/private stay absolute | ☐ OPEN |
| GCE-11 | Search/reasoning behavior unchanged on 1:1 and applies to group drafts identically | ☐ OPEN |

## Regressions checked

- 1:1 byte-identity (wire test); 891 pre-existing suites green inside 942.
- Follow-up/intentional kinds bypass the gate (approval-driven by design).
- Cloud untouched (P-16 audit state stands); local schema v8 migrates from v7 exactly one statement.
- Notifier private-post signature unchanged (labels added via new public flavor only).
