# P-intelligence-15 — GROUP UNDERSTANDING + CONTEXT + VOICE AUDIT (1.6.3)

Owner's bar, verbatim: *"Never claim a feature works without code, test, and
device proof."* Baseline entering this phase: main `0fde5c5`, gate 876/876 ⇒
**891/891 after this phase's additions** (+5 GroupHistory, +5
GroupChatUnderstanding, +5 EditContactWire). This phase commits `5130bd5`
(code) + checklist commit. Official docs re-checked online 2026-08-15; device
rows are the owner's to mark — I do not self-certify. 1.6.x stays unshipped;
Releases untouched.

Research applied (current official docs, 2026-08-15):
- **`Notification.MessagingStyle`**: `addHistoricMessage`/`getHistoricMessages`
  (API 26+) carry older context "not the main subject… may give context to a
  conversation"; extra key `android.messages.historic` (same per-message
  Bundle shape as `android.messages`). `isGroupConversation()` /
  `getConversationTitle()` / Person senders (name/key/uri) confirmed current.
  **No MessagingStyle version exposes quote/reply-to metadata** — see the
  honesty note in Item 2.

---

## Item 1 — Per-contact intelligence: audit verdict + wire proofs

**Audit result (every Edit Contact control, traced UI → saved → precedence →
prompt → request):** all 9 dials (tone, length, emoji, formality, humor,
confidence, slang, flirting, follow-ups), relationship chips, About Them,
tone note, language, custom instruction, pinned facts, memory/AI/private/
learning switches, auto-follow-up switch — all were wired; **no dead control
found**. The genuine gap was PROOF depth for the non-dial controls:

- **NEW `EditContactWireProofTest` (5)** — through real generation, asserting
  the exact ChatRequest: contact language verbatim on the wire with the honest
  default ("match the language of their latest message") when unset; the
  free-text tone note verbatim; relationship + About Them together; private /
  AI-off contacts refuse generation on BOTH the reply and intentional paths
  (zero provider calls, zero drafts); memory OFF keeps facts stored but off
  the wire, and flipping it back restores them.
- **Standing pins re-verified:** 9 dials dial-by-dial (`AllDialsWireProofTest`),
  custom instruction + learned-style gates + explicit-beats-learned
  (`VoicePromptProofTest`), contact isolation (`ContextIsolationTest`),
  overrides audit (`overrideNotes`).
- **Device proof (owner):** V rows §D (now covering every control).

## Item 2 — Group conversation understanding

- **Burst + history:** MessagingStyle HISTORIC messages are now extracted and
  parsed as flagged **context** (`NotifEvent.historic`): stored through the
  SAME dedupe (notifKey + near-dup collapse), sender-less system inserts
  dropped exactly like live ones, and **never allowed to ping** a burst or a
  draft — history grounds, live messages trigger. BatchWindow settles the
  meaningful burst before generation (unchanged, pinned).
- **The group fact is now capture evidence, not a guess:** schema **v7**
  (`contact.is_group`) persists `isGroupConversation` at ingest;
  `ConversationMatch`/links were already keyed on conversationId → per-group
  contacts ⇒ per-group memory/style/facts with 1:1 isolation by construction.
- **The model is TOLD the group:** `GroupContext.header` rides the prompt
  (reply AND intentional kinds) only when the persisted fact says group —
  group name, "Members speaking here: …" (distinct `sender_name`s, first-seen
  order, capped), the owner's unnamed lines marked as the owner's, and "never
  speak for another member". 1:1 prompts byte-identical (pinned).
- **Attribution in tasks:** CLARIFY in a group quotes the actual sender
  ("Musa's latest message — …"), 1:1 unchanged.
- **Honesty note (quotes/replies):** no shipping MessagingStyle version
  exposes quote/reply-to metadata (checked in current docs 2026-08-15) — any
  "quote extraction" would be faked regex, so ReplyMate does not pretend:
  the coalesced burst + sender attribution + group context is the honest
  ceiling, and the latest message is what gets answered. Device row GM4
  verifies the real behavior.
- **Control respected:** GroupPolicy Off/Inherit/On per app, default off —
  re-verified; nothing from groups generates while disabled (NoiseGate +
  ListenerFilter overloads green).
- **Tests:** `GroupHistoryTest` (5 — parser ordering/flags, sender-less
  historic drop, store-without-ping + dedupe, persisted group fact, 1:1 stays
  non-group), `GroupChatUnderstandingTest` (5 — header on the wire,
  attribution in turns, 1:1 absence, group memory isolation, intentional
  kinds in groups).
- **Device proof (owner):** GM rows §D.

## Item 3 — Background + natural flow

- Carried over and re-verified green in this gate: ColorOS battery routing
  ("Oppo battery usage page" chain, resolve-guarded, honest unreadable-switch
  wording), 3-lane GEN isolation, supersede gates, settle window, natural
  burst/correction/filler understanding (ReplyPlanner + watermark pins),
  auto-follow-up OFF-by-default + never-auto-send (8 pins).
- **Device proof (owner):** BG rows §D incl. BG7 (ColorOS) + slow-network row.

## §D — Device verification rows (owner — mark PASS/FAIL)

Voice / Edit Contact:
- V1  Flip every dial for one contact → the next draft visibly reflects each.
      OFF a dimension → no trace of it in the Prompt Audit voice line.
- V2  Set contact language (e.g. "Naija pidgin") → drafts follow it; another
      contact without it follows the match-their-language default.
- V3  Tone note + About Them + relationship → all three shape the draft;
      Prompt Audit credits them.
- V4  Private ON → no drafts, ever. AI off → no drafts. Memory off → no
      memory block in the audit. Flip each back → behavior restored.
- V5  Explicit contact override beats the learned hint for that dimension
      (audit notes the suppression).

Group understanding:
- GM1 Enable groups (Sources, per app) → a group burst → ONE draft after the
      settle, Prompt Audit shows the group line + member names.
- GM2 Two groups + one 1:1 active in the same hour → each draft stays in its
      own conversation; Prompt Audit of each shows only its own facts.
- GM3 After a reboot, reopen: group memory lines (summary/facts) still on the
      wire for that group.
- GM4 Quote/reply a message in WhatsApp → the draft answers the coalesced
      latest message and names the right member; ReplyMate does NOT claim to
      see the quoted block (platform carries no such data).
- GM5 Groups OFF → zero group drafts/alerts; 1:1 unaffected.

Background:
- BG7 ColorOS battery page routing + reboot persistence (carried from P-14).
- BG8 Slow network: draft eventually lands; unrelated chats generate in
      parallel (send near-simultaneous DMs from two contacts).

## Gate status — updated 2026-08-15

- JVM **891/891** (876 + 15 new pins). Local engine build GREEN
  (`VERSION_CODE=910` devcheck, local debug cert `446310cc…`, discarded).
- CI proof build: **GREEN** — run **31884648127** on commit `5130bd5`
  (release.yml dispatch); in-run gates **KEYSTORE-VERDICT: MATCH**,
  **APK-CERT-PROOF: MATCH**. Artifact **9246933080**
  `ReplyMate-1.6.3-groups-proof-vc910` → APK
  `ReplyMate-1.6.3-groups-proof.apk`, **624,970 bytes**, vc **910** /
  `1.6.3-groups-proof`, minSdk 24 / target 35.
- Independently re-verified OFF-CI: sha256
  **`1f82a6d962e9ab048dd54fad83beeb3634f590137283816119f8f460ba514613`**,
  cert SHA-256 `b15f2f37fce19b564683fe6b85725a9d3192df93181ef2f36286b5e21c6a85ed`
  (historical identity; update-in-place over ≤1.5.8 preserved).
- **Owner device verdicts — OPEN.** Prior phases (P-14 incl.) remain
  owner-marked; per the phase rule, no next phase until those and this
  phase pass on-device. Releases stay untouched.
