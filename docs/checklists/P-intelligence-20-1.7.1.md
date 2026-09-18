# P-intelligence-20 — FINAL CONVERSATION INTELLIGENCE + BACKGROUND STABILIZATION (1.7.1 / vc918)

Owner mandate (verbatim anchors):
1. "The Background Generation toggle still only works partially… Research the current
   ColorOS route instead of guessing intents. The toggle must take the user to the real
   required system setting, recheck the actual state on return, and show Ready only when
   ReplyMate can genuinely operate. Then trace why generation is slow or sometimes
   missing and remove avoidable waits, queue blocking, duplicate work… Android explicitly
   allows manufacturers to impose additional background restrictions, so detect and
   handle the real device state rather than pretending the app can override Android."
2. "ReplyMate must understand who said what, who the owner is, who is being addressed…
   Fast group traffic must be collected into one coherent context before generation…
   Normal Discord/general and other group conversations must never be mistaken for
   announcements… Learn participant names and stable identities, keep group memory
   isolated… When a valid native reply target exists, preserve it through approval
   and delivery."
3. "Prove tone, confidence, formality, slang, humor, emoji, length, flirting, language,
   About Them, relationship, custom instructions, memory, learned style, follow-up, and
   every Off state. Explicit contact/group settings must beat global and learned behavior…
   Intentional Follow-up, Continue, Clarify, and Opener generation must use the same
   context and customization pipeline. Follow-up remains OFF by default and never
   auto-sends."

- Head: `59d4a0f` (1/2) → checklist commit at gate 1025/1025.
- Note: the previous PAT was revoked/expired mid-phase (401). A fresh PAT was supplied
  by the owner; used for push/CI only, shredded after.

## 0. Previous build verified first

| Check | Result |
|---|---|
| Repo state | fresh clone `.git` @ `69125c8` (P-19R 2/2); exec modes restored in index; tree clean |
| JVM gate @ HEAD | **1022/1022 OK** baseline re-run |
| Prior CI | run 32123702613 re-queried with the fresh PAT: `completed success` @ 66e978b |
| Prior artifact (ReplyMate-1.7.0-bgfix-proof.apk) | sha256 `85e256cd…ba57` MATCH; release cert `B1:5F:2F:37…6A:85:ED` MATCH off-CI (verified after this phase's devcheck made apksigner available) |

## 1. §1 Real background operation — audit + fixes

**Audit of the entire path (as built through 19R):** listener-access / POST_NOTIFICATIONS
verifiable probes ✓, battery allowlist ✓, Android 9+ background-restricted ✓, standby
bucket RESTRICTED ✓, ColorOS "Allow background activity" owner-confirm (unreadable by
Android — confirmed never inferred) ✓, recheck-on-return with pending-enable ✓, Ready
only from a FRESH verdict ✓, Doze fallback alarm (setAndAllowWhileIdle, idempotent
sweep) ✓, process death via listener-rebind reconcile + throttled sweeps on
connectivity/rebind/provider-save/permission-grant ✓, latency trace (queue/generate/
total) in Diagnostics ✓.

**Found this phase (real bugs, audited code, not guessed):**
1. **The GEN lane was NOT actually elastic.** `ThreadPoolExecutor` grows past
   `corePoolSize` only when the work queue refuses an offer; with an unbounded queue it
   runs at core size forever — the 19R "core 3, max 6" setting ran as 3 lanes in every
   real device moment. Fix: the pool now runs 6 REAL daemon lanes (parked idle costs
   nothing), and the comment in Tasks says so with the mechanism.
2. **Queue blocking by bulk recovery.** Listener-rebind / connectivity / Doze-fallback
   sweeps enqueue one generation per stale conversation; on a FIFO queue a sweep parks
   the LIVE conversation's draft (owner waiting) behind dozens of catch-up jobs. Fix:
   priority dequeue inside the GEN pool — live pings/regenerations always dequeue
   before catch-up work (FIFO order preserved *inside* each class; per-contact
   serialization + pre-call coalescer aborts unchanged, so nothing double-runs or
   double-bills). The sweep now rides `Tasks.genCatchup`.
3. **Access-granted ≠ bound was invisible.** ColorOS can leave the listener enabled in
   Settings but unbound after a kill; Diagnostics now prints `listener bound right now`
   (live truth) with the exact recovery step (toggle access OFF/ON).

**What is intentionally NOT changed (avoidable-wait tracing, honestly):** the 5 s batch
window + 1.5 s settle debounce stays — it is what makes "fast group traffic collected
into ONE coherent context before generation" and "one provider call per burst" true;
the measured latency line (`queue Xs · generate Ys · message→alert Zs`) is the evidence
channel now, so slowness has to show its numbers instead of being trimmed blind.

## 2. §2 Message + group understanding — repro, root cause, fix

**Reproduction (in-gate, at HEAD `69125c8`): 2/1025 FAILED** —
`DiscordGroupAttributionTest`:
1. `parserPrefersMessagingStyleHistoryWhenDiscordPublishesIt` — expected 2 events,
   got 1: a Discord conversation notification's per-sender lines were collapsed into ONE
   event attributed to `#general` — the CHANNEL — with every earlier burst line LOST.
2. `ingestLearnsMembersNeverTheChannelAndMentionsStillFire` — consequently the stored
   thread, participant registry, mention detection and engagement targets all read the
   channel as the speaker.

**Root cause (audited):** Discord posts server-channel messages as CONVERSATION
notifications (MessagingStyle + conversation shortcut + isGroupConversation).
`TitleTextParser` — Discord's registered parser — reads MessagingStyle history ONLY as
a last resort (when EXTRA_TEXT is empty); conversation notifications always carry an
EXTRA_TEXT shadow of the newest line, so every multi-message post took the title/text
path, which by design attributes the shadow to the conversation title. Bonus finding:
the parser also never stamped the native conversation id (MessagingStyleParser always
did) — the delivery-match tiers' strongest evidence was absent for Discord.

**Fix:** Discord's parser registration now prefers its own MessagingStyle history when
the post publishes it (new `TitleTextParser(Channel, requireCategory, preferHistory)`
mode, Discord-only — every other app byte-identical): per-sender names + stable Person
keys + per-message times + historic context, system-insert hygiene mirrored from
MessagingStyleParser, reply capability and native conversation-id stamped on every
event. The single-shot title/text fallback is byte-identical, so P-19 capability
honesty and P-19R late-capability re-open behave exactly as pinned. Pinned: members
(never the channel) are learned; owner-mention still yields REPLY_REQUIRED targeting
the right sender (`MENTIONED → Bim`); true single-shot shapes keep their evidence
honesty; group memory stays contact-scoped by construction (all reads/writes are
contact-id-keyed).

**Native reply target through approval/delivery:** unchanged P-17→19R machinery —
capture per conversation, decisive ConversationMatch tiers with the now-stamped convId,
unique-match rebind, conversation-bound cached token, honest fail. Discord delivery is
channel-bound (RemoteInput through the notification's own Reply action) — cross-channel
borrow remains pinned impossible.

## 3. §3 Personalization + learning + generation — audit verdict per control

Pipeline traced end-to-end: UI rows → `StyleSettings` storage → `StyleService`
resolution (explicit contact > explicit global > learned > defaults) → `PromptBuilder`
voice/memory/context blocks → `ChatRequest` → provider → output → learning signals.

| Mandate control | In-gate proof (suite) | Status |
|---|---|---|
| all voice dials reach the wire through real generation | AllDialsWireProofTest | pinned |
| Off strips the instruction; contact override wins; Storage rejects corruption | AllDialsWireProofTest, VoiceControlsOffTest (8) | pinned |
| contact beats global on the wire; custom instruction + About Them reach the wire; learned hints past the gate; explicit suppresses learned | VoicePromptProofTest (6) | pinned |
| learned-style precedence (explicit > global > learned) | LearningPrecedenceTest | pinned |
| follow-up: OFF by default; quotes the approved reply; waits for quiet; never chains; one per approved reply; re-arms on new approval; private/AI-off skip | AutoFollowUpTest (8) | pinned |
| approved/edited/manual context + learner honesty (exact-match vs edited) | ManualSendLearnerTest, PendingDraftContextTest | pinned |
| +Them storage/provenance (MANUALLY_ADDED, no fabricated notification metadata) | ManualIncomingTest | pinned |
| per-contact isolation (A's world never in B's request; group memory isolated) | ContextIsolationTest (4) | pinned |
| intentional kinds share the reply pipeline (FOLLOW_UP/CLARIFY/CONTINUE/OPENER: same style, memory, Search gate, reasoning) | IntentionalComposeTest (9), DraftService shared path (lines 516-790) | pinned |
| cold-start honesty line | ConversationContextBuilder.coldStartPromptLine, DraftServiceTest | pinned |
| engagement verdicts REQUIRED/OPTIONAL/WAIT/NO_REPLY (+pins for mentions, room questions, reply-to-yours, filler-only) | EngagementClassifierTest, ConversationStateServiceTest, GroupEngagementGateTest | pinned |

§3 added no new code this phase: the pipeline audit found no violation of the
mandate's precedence/off-state/isolation rules; the remaining proof burden is the
owner's device rows below (every control verified on the real Oppo + real provider).

## 4. Proof

| Item | Value |
|---|---|
| Version | `1.7.1-final-proof` / vc 918 |
| Commits | `59d4a0f` (1/2) + checklist commit (2/2) |
| JVM gate | pre-fix repro **2/1025 FAILED** (named above) → **1025/1025 OK** |
| Engine devcheck | vc918 / 1.7.1-devcheck1 builds + verifies (debug cert `446310cc…04e9d2`), cleaned after |
| CI release | run **35392806603** (completed success) |
| Artifact | **10565309912** `ReplyMate-1.7.1-final-proof-vc918` |
| CI keystore verdict | KEYSTORE-VERDICT: MATCH — historical identity confirmed; APK-CERT-PROOF: MATCH — update-in-place over ≤1.5.8 preserved (run logs 20:41–20:42 UTC) |
| Off-CI verify | **678,218 B**; sha256 `afa5a2474c1bc84e91cc109343d1a50c54b8cbc89863c8c3291b28a84af908e1`; cert `B1:5F:2F:37…6A:85:ED` **MATCH**; badging com.replymate.app · vc918 · `1.7.1-final-proof` · minSdk 24 · targetSdk 34 · compileSdk 35 |

## 5. Device rows (owner-marked — never self-certified)

Report per row: input → understanding/classification → context used → personalization
applied → Search/reasoning used → generated result → target → delivery result.

| Row | Scenario | Expected | Result |
|---|---|---|---|
| P20-1 | Discord #general, 2 members post quickly (watch+groups ON) | both lines stored under THEIR names; ping once; [mention]/[direct_reply] — never announcement; ring has no false "re-opened" spam | OPEN |
| P20-2 | P20-1 draft | burst shows Ada's line + Bim's line in order as context; target = the implementation target row (whoever triggered) with confidence label | OPEN |
| P20-3 | Approve P20-2 | delivery via #general's OWN Reply action; lands in #general; delivery target line names the conversation | OPEN |
| P20-4 | Discord single-shot channel post with NO MessagingStyle history, no Reply action | [announcement] → store-only → no provider call; Generate attempt explains honestly | OPEN |
| P20-5 | Group burst: someone asks the room "sunday still on?", owner silent 2 min | WAIT first (no draft), re-check once; then chime-in optional or silence with the reason visible in the conversation's why-line | OPEN |
| P20-6 | Owner was just active in the group, question lands after owner's own message | REPLY_REQUIRED ("they asked right after your message") → draft named "Reply to <sender> in #general" | OPEN |
| P20-7 | Background toggle ON on the Oppo (fresh state) | flow opens the REAL screens in order (notification access → notifications prompt → battery dialog → Battery usage page "Allow background activity"); Ready ✓ appears ONLY after the confirm step; leaving/returning re-checks live state | OPEN |
| P20-8 | Generation with listener killed (recents swipe) then message arrives | recovery via rebind/sweep; Diagnostics shows listener bound=true again; draft still lands (queue/generate/total line recorded) | OPEN |
| P20-9 | 6+ stale conversations recover while ONE live message lands | live draft is not parked behind the sweep (queue ≈0s on its latency line); catch-up completes after | OPEN |
| P20-10 | Slow provider/network: two conversations need drafts | the fast one completes without waiting for the slow lane; latency lines show the split | OPEN |
| P20-11 | Voice dials end-to-end (e.g., emoji ON global, emoji OFF contact) | contact's draft shows no emoji; a second contact keeps global behavior; About Them + custom instruction present in the audit; Off states leak nothing | OPEN |
| P20-12 | Follow-up craft: enable per-contact follow-up, approve a reply | ONE "Follow-up idea" draft quoting the approved reply; OFF elsewhere; nothing auto-sends | OPEN |
| P20-13 | + Them on Discord contact, then generate | the added line rides as their context ("added by you (not captured)"); no fabricated notification evidence; teaches this contact only | OPEN |
| P20-14 | Learning isolation: approve slang-heavy reply for A | a later draft for B contains none of A's learned phrasing; Contact A's style shows the learned hint | OPEN |

## 6. Regressions checked

- Gate 1025/1025: all pre-existing suites green (P-17/17R/18/19/19R pins included —
  announcement honesty, re-open one-way rule, cross-channel safety, regenerate/replace,
  manual context, engagement gates, follow-up policy, style precedence).
- `conversationId` now stamped on the title/text path too: key-candidate upgrade is the
  SAME semantics MessagingStyleParser apps already ran for six phases (alias adoption,
  no contact forks; near-dup collapse for drift) — ContactServiceTest/GroupHistoryTest/
  IngestCoordinatorTest all green.
- Other TitleTextParser apps (Slack/Instagram/X/TikTok) untouched: constructor default
  keeps history-as-last-resort; their suites green.
- No new permissions; GEN pool daemon threads only; priority is dequeue-order only
  (execution concurrency unchanged in the lanes-improvement commits' intent: capped).
