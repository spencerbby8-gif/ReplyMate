# P-intelligence-2 — BLOCKING INTELLIGENCE AUDIT — 1.5.1/vc32

Rule kept: the exact 1.5.0 commit (`86013f8`) re-ran both suites COLD before any
change — 543 JVM + 80 robo, all green. Then every P-intelligence-1 claim was
cross-checked against code paths (not test colors). Green tests did NOT hide these:

## CONFIRMED BREACHES FOUND + FIXED (green tests notwithstanding)

1. **Prompt Audit lie (item 8).** A single message carrying a correction marker
   ("no wait…") had `ConversationContext.whyLines()` credit "burst signal:
   self-correction" — but the single-message task carries no such annotation.
   Fixed: signal why-lines are gated by `burstDetected` (same reachability rule as
   the task), so the audit can never display a signal as applied when it didn't
   reach the prompt. Pin: `singleMessageNeverClaimsABurstSignalItDidNotSend`.

2. **Explicit-vs-learned precedence missing (item 4).** Learned length/emoji hints
   shipped alongside explicit contact settings for the same dimension — both
   LearningEngine hints AND StyleProfiler's approved-text derivations. Fixed:
   hints/derived lines are now tagged with the voice control they touch
   (`Hint.control`, `Derived.control`); StyleService + MemoryService suppress any
   line whose control the owner set EXPLICITLY for that contact, with an audit
   credit for the suppression. Restarts safe: style cache bumped to v2 storing
   full tagged derivations; suppression is per-call (un-setting a control restores
   the learned line on the very next generation). Pins: `LearningPrecedenceTest`,
   `explicitContactSettingBlocksTheLearnedGuessFromThePrompt`.

3. **Audit completeness (item 8).** Raw feedback counters are now credited verbatim
   on every generation: "feedback so far for X: N approved · M edited · K
   regenerated · J rejected (recent window)". Pins: `LearningToPromptTest`.

## Verified-intact clamps (item-by-item, from code)

- **1 Middle-of-conversation understanding** — older incoming becomes attributed
  user turns ("Name: …"), owner's replies model turns; the burst tail can never
  cross back across the owner's last reply; newest message leads the task. New
  pin: `middleOfConversationKeepsRolesContextAndBurstBoundaries`.
- **2 Isolation** — learning/prompt stores all contact-scoped; new pin
  `signalsReachOnlyTheirOwnContactsPrompt` joins the suite.
- **3 Voice + behavior both affect output** — explicit 9-control voice (existing
  pins) + StyleProfiler: reply length, trailing-stop habit, lowercase openings,
  emoji posture — ALL thresholded with evidence. **Honest boundary:** vocabulary,
  specific slang words, humor and firmness calibration are NOT mechanically
  learnable from few samples without faking it — those dimensions ride the
  explicit My Voice controls + contact rules. Not pretending otherwise.
- **4 Contact adaptation** — cold start (unified definition, explicit prompt line),
  then signals/memory/customization adapt it; explicit contact settings now beat
  learned guesses everywhere measurable.
- **5 Reply reasoning** — task quotes the exact point, names the app, discloses
  media honestly, guidance covers answer/ask-back/explain/joke/comfort/
  disagree/correct/refuse (rules block). Firmness rule standing since 1.4.8.
- **6 Burst intelligence** — one burst → one reply, filler/correction/multi-question
  mechanical annotations (only when fired), same-card refresh, re-arm after send.
  **Honest boundary:** distinct-topic *separation* within one burst remains
  model-semantic (a mechanical topic splitter would guess; we don't guess).
- **7 Learning changes future output** — previously pinned only at engine level;
  NOW proven end-to-end: signals → actual provider ChatRequest contains the hint
  AND the snapshot shows hint + evidence (`LearningToPromptTest`, 6 cases:
  shorter-edits, approval-strengthening, regen-fatigue, reject-conservatism,
  explicit-suppression, cross-contact isolation).
- **9 No invented history** — rule ("never invent facts about <name>") + cold-start
  line ("never fake shared history or inside jokes") pinned by
  `coldStartAddsAnExplicitSituationLineToTheSystemPrompt` + media/call honesty pins.
  Uncertain context → memory framed as the owner's own recollection, sources shown.

## Regression coverage (item 10)

middle-of-conversation → PromptUnderstandingTest(+1) · cold start →
ConversationContextBuilderTest, PromptUnderstandingTest · isolation →
LearningToPromptTest(+1), ManualSendLearnerTest · My Voice → StyleServiceTest,
CustomizationEffectTest · contact voice/About Them → idem + contactProfileNotes pins ·
custom instructions → StyleService suite · approved/edited learning →
LearningToPromptTest, LearningPrecedenceTest · regen/reject learning →
LearningToPromptTest · manual-send learning → ManualSendLearnerTest +
ListenerPipelineRoboTest money test · burst corrections/filler → BurstSignalsTest ·
topic changes → model-semantic checklist step (honest boundary noted) · next-burst
re-arm → AssistantPlanner/Runner suites · Prompt Audit accuracy →
singleMessageNeverClaimsABurstSignal… + LearningToPromptTest snapshots · no invented
history → cold-start pin · no cross-contact leakage → LearningToPromptTest +
ManualSendLearnerTest.

## Gates

- JVM: **558 green** (543 → +15).
- Robo: **80 green** (unchanged suites, all passing after core edits).

## ON-DEVICE VERIFICATION (owner — wire/compliance steps, reply per number OK/FAIL)

Use at least TWO contacts with real conversations (rule), e.g. A = close friend,
B = a newer/stricter contact (customer/boss-style).

1. Middle-of-conversation: install 1.5.1 fresh state already having chat history —
   generate on a new incoming burst; verify the draft answers the NEWEST point, not
   the already-settled older question; Prompt Audit shows the burst head + your last
   reply credited.
2. Explicit-vs-learned: for A, set Reply length explicitly (contact voice). After 3+
   manual shortenings/approvals, confirm drafts still follow YOUR explicit length and
   Prompt Audit shows "learned hint suppressed (your explicit length setting…)".
3. Feedback trail: Prompt Audit of A shows the counters line with the real numbers
   you produced (approve/edit/regen once each and watch the counts change).
4. Learning effect: for A, approve 5+ drafts untouched → later Prompt Audit credits
   "keep it consistent"; shorten 3 drafts by hand → the shorter-hint appears AND the
   drafts actually trend shorter.
5. Isolation: B never shows A's learned lines/counters; A's cold-start behavior
   differs from B's customized one.
6. Single correction message: one lone "no wait, Friday" → draft answers it; Prompt
   Audit shows NO "burst signal" credit (that would have been the old lie).
7. Safety: fresh cold contact — Prompt Audit shows "never fake shared history" line;
   media-only latest item is disclosed honestly, never guessed.
8. Burst refresh: rapid messages → one card updates in place; after approve+send,
   the next burst pops a fresh heads-up.
