# P-intelligence-1 — BLOCKING PRODUCT AUDIT → intelligence arc — 1.5.0/vc31

Rule kept: **PART A verification of shipped 1.4.9 ran BEFORE any change** — the exact
release commit (`339015e`) re-ran both gates cold: 515 JVM + 79 robo, all green. No
regressions in code. (A literal on-device re-audit is the owner's side of the gate —
steps below — the sandbox has no real device; every device-visible behavior is
mirrored through the robo harness, which caught and fixed real issues this phase.)

## Per-item delivery (audit → build)

| # | Item | What existed (verified) | What 1.5.0 ADDs |
|---|------|--------------------------|------------------|
| 1 | Message understanding | Raw thread + ad-hoc labels went straight to the model. | **NEW `core.understanding` layer**: every generation now consumes a `ConversationContext` — sender (+ group attribution), app, content type, burst state + mechanics, the owner's last reply, cold-start flag, signal count. Built pre-prompt in DraftService; credited line-by-line in Prompt Audit ("understanding: …"). |
| 2 | Burst & topic | Burst=one task, filler/correction/topic-shift instructions (model-semantic), same-card refresh, re-arm after send (all pinned 1.4.5–1.4.8). | **Mechanical burst reading** (`BurstSignals`): conservative detectors for self-correction markers, filler-heavy bursts, multi-question bursts → grounded "Mechanical read:" annotation lines land in the task ONLY when a signal fires (annotation-free tasks are byte-identical to 1.4.9). Topic shifts stay model-semantic (no fake classifier). |
| 3 | Memory & contact intelligence | Per-contact recall (summary, facts, learned style), About Them/relationship/tone/custom rules, cold-start audit credit (1.4.8+). | Cold start is now **one shared definition** (`StyleService.isColdStart`) powering BOTH the audit credit AND an explicit system-prompt line — a new contact gets "stay warm, plain, lightly reserved; never fake shared history" until customization/memory/signals exist (applied on the very next reply — composition re-reads everything every time). |
| 4 | Learning from behavior | APPROVED/EDITED/REGENERATED/REJECTED + copied channels, thresholded hints: repeated-edit corrections (shorter/longer/emoji), regen-fatigue, rejection-conservatism, approval-strengthening ("keep it consistent"). All per-contact gated. | **NEW manual-send channel** (`ManualSendLearner`): when the owner answers by hand inside the chat app (visible via MessagingStyle history) while a live untouched draft predates it (≤72h), we record APPROVED (identical words) or EDITED "manual:&lt;tokens&gt;" (correction learned) — feeding the SAME hint counters. Deduped per draft, standard gate honored, per-contact isolated, ring-logged honestly. |
| 5 | Customization must matter | Full pipeline audited green (CustomizationEffectTest/P4Prompt/StyleService suites); contact rules override global (override-framed line); Prompt Audit shows why-lines. | Understanding lines added to the audit trail; cold-start condition unified so audit and prompt can never disagree. No dead settings found. |
| 6 | Reply quality | Firm-when-needed rule, no-parrot rule, no inventing, 1–3 sentence discipline (1.4.8). | Grounded annotations sharpen the point being answered; cold contacts are told to prefer short honest answers over assumption. |
| 7 | Chat ranking | `ChatRanker` (real last-message activity; settings edits don't rank), pinned. | Unchanged. |
| 8 | Verification | — | +28 JVM pins (543 total), +1 robo money test (80 total). See map below. |

## Regression coverage map (item 8)

- message understanding → `ConversationContextBuilderTest` (5), `PromptUnderstandingTest` (6)
- burst summarization / topic mechanics → `BurstSignalsTest` (6) + existing `BurstTaskTest`,
  `JobCoalescerTest`, `AssistantRunnerRoboTest`
- cold-start contacts → `ConversationContextBuilderTest` (cold×known×memory×signals),
  `PromptUnderstandingTest` (explicit line present/absent), existing `StyleServiceTest`
- contact memory → existing `MemoryPersistenceRoboTest`, memory suites in run_tests registry
- My Voice / contact profile / About Them / custom instructions → existing
  `CustomizationEffectTest`, `StyleServiceTest`, `P4PromptTest`
- learned approvals and edits → `ManualSendLearnerTest` (9) + existing
  `AssistantLearningTest`, `LearningEngine/ServiceTest`
- Prompt Audit → `PromptUnderstandingTest` + existing `PromptAuditCompletenessTest`
- manual-send e2e → `ListenerPipelineRoboTest#manualSendInsideTheChatAppTeachesTheLearner`
- chat ranking → `ChatRankerTest`

## Honesty ledger (found & fixed this phase)

1. JVM pins caught my own over-eager expectations twice (classifyEdit math re-derived;
   placeholder must be the real ContentKind placeholder) — tests fixed to match mechanics.
2. Robo caught the fixture's missing conversation identity — bare history-only styles
   resolve to the OWNER's name (probe-proven); fixture now mirrors real notification
   identity. No product-code change needed.
3. `Intent.resolveActivity` explicit-short-circuit hazard (from P-background-10) stayed
   fixed — `canLaunch` keeps querying the PackageManager directly.

## Gates

- JVM: **543 tests OK** (515 → +28).
- Robo harness: **80 tests OK** (79 → +1), consecutive green after fixes.

## ON-DEVICE VERIFICATION (owner — reply per number OK/FAIL)

1. Cold start: add a BRAND-NEW contact (fresh chat) → first draft is neutral/plain;
   Prompt Audit shows "new contact — cold start". Then set About Them/relationship/tone
   → the VERY NEXT regenerate reflects them, audit credits each.
2. Burst intelligence: have 3+ rapid messages arrive incl. a correction ("no wait…") →
   one draft answers the corrected point; Prompt Audit shows the "Mechanical read" line.
3. Filler burst: "you there?", "??", real question → draft answers the question only.
4. Manual-send learning: with a live draft in the banner, answer by hand INSIDE WhatsApp
   (different words) → after the next notification for that chat, ring/Signals show a
   manual EDITED correction (Settings → Diagnostics ring: "learned · <name> · manual send
   corrected the draft"). Send your reply IDENTICAL to a draft once → APPROVED signal.
5. Repeated corrections: shorten drafts by hand 3+ times across chats → later drafts for
   that contact trend shorter; Prompt Audit credits the learned hint.
6. Isolation: do steps 1–4 for contact A; contact B's drafts/audit show nothing of A.
7. Ranking: new real message lifts a chat; editing its settings does not.
8. Regression sweep: heads-up once per burst, Update-in-place card, dismiss-then-approve
   still re-posts/cached-sends honestly, filtering unchanged (backup/Discord noise silent).
