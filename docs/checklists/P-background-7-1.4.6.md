# P-background-7 — BLOCKING triple fix — 1.4.6 / vc27 (2026-08-08)

Mandates: (1) Approve must work even after the user clears the original WhatsApp
notification — cached conversation/target/key/PendingIntent must be used while
valid, fail honestly otherwise. (2) Human-like burst handling locked as product
rule. (3) Customization is core: audit the whole prompt pipeline; every control
must affect output with contact isolation + Prompt Audit credits.

## 📦 Download

| file | size (bytes) | sha256 |
|---|---|---|
| `releases/ReplyMate-1.4.6.apk` | 219,386 | `47d53e3132f62cbce0dcf44725480ee099c22a87be076a11c00cbac11ad14d99` |

Cert `b15f2f37…` unchanged → installs in place over 1.4.5.

## 1) Approval after notification dismissal

- **Cached at capture:** when a ping's notification is guaranteed live, the stored
  reply target's PendingIntent is resolved and cached (`writePendingIntentOrNullToParcel`
  → base64 in kv — a documented system token that survives our process death and
  often the notification itself). Also cached on live re-probe adoption (1.4.5).
  Cache invalidates the moment the geometry changes (new save ⇒ cleared, then
  re-cached from the fresher notification).
- **Approve flow order:** live notification present → key-matched send (1.4.4/1.4.5
  path, with post-send observation) → ELSE cached-target send: official
  `RemoteInput` results structure rebuilt from the stored result key and fired
  through the app's own cached PendingIntent.
- **Honest expiry:** canceled/expired/corrupt/absent cache → a precise reason
  (`…closed that reply box (the cached target expired too)`), the draft is KEPT,
  Copy/Open fallback — `Sent ✓` never appears without a real fire.
- Selection logic extracted to `ReplyActionResolver` (shared by receiver + caching)
  — one source of truth, index-drift safe by key match.

Pinned: `approveAfterDismissalSendsViaCachedPendingIntent` (probe receiver gets
the results after the notification is gone), `dismissalWithoutCachedTargetFallsBackHonestly`,
`captureTimeReplyTargetCachesADecodablePendingIntent`.

## 2) Human-like burst rule (locked)

- Collect while active (rolling re-arm), settle (5s batch + 1.5s), summarize the
  whole unread tail into ONE point, **ignore pure filler repeats** (explicit in
  the task now), write ONE natural reply.
- More messages before approval → the SAME banner refreshes (stable tag),
  unsaved drafts replaced — never stacked.
- After send: re-arm is immediate and indefinite (hash-dedupe + stateless runner,
  pinned in 1.4.5+1.4.6 tests).

## 3) Customization audit — every control is live, nothing is decorative

Audited UI → store → compose → prompt → audit for: 9 voice controls, My Voice
custom instruction, contact overrides, About Them (relationship/notes/tone/
language), contact custom instructions, learned style, memory, Profile toggles.
**No dead wiring found** (UI keys match the StyleSettings contract byte-for-byte).
Per-control proof now exists in `CustomizationEffectTest` (8 tests): 18 prompted
control-level phrases reach `ChatRequest.system`; contact overrides/custom
instructions/memory never leak across contacts; learned hints land only when the
gate is open; `PromptBuilder.snapshot` (Prompt Audit) credits each applied
customization (`global voice applied`, `contact override`,
`custom prompt for X applied`, `learned: …`).

About "generic 'bro'": with defaults active the model gets the neutral voice
line + charter — set Tone/Formality/Slang per person (or a contact custom
instruction like "never use 'bro' with her") and the 18-level pin guarantees it
reaches the prompt. If a *specific* control still doesn't change output on your
device, tell me which one — the pin map will say exactly where it died.

## 🧪 Totals

**476 unit + 65 robo, all green** (was 468 + 62).

## 📱 Owner device pass

1. Install in place. Dismissal test: WhatsApp message → banner appears →
   **clear the WhatsApp notification from the shade** → tap **Approve & send**.
   Expected: while WhatsApp's reply target still stands (usual case if you clear
   *quickly*), the text lands via the cached target; ledger says
   `sending via WhatsApp's cached reply PendingIntent (result key key_text_reply)`.
   If WhatsApp already closed it, the banner honestly says the box expired and
   copies instead — never a fake Sent.
2. Burst: 3–4 quick texts (mix a real question + filler like "?") → ONE banner,
   drafted to the real thought.
3. Customization: set My Voice (e.g. Humor higher, Emoji none) + one contact
   override (e.g. Ada → direct tone, custom instruction "never say 'busy'") →
   DM both Ada and another contact → only Ada's drafts show the direct tone;
   Settings → (conversation) → **why this reply**/Prompt Audit shows the credits.
4. Diagnostics trail intact; listener/app restart reconciliation (1.4.3) still armed.

## ⏳ Honest limits

- Cached-PendingIntent validity is the source app's choice: WhatsApp may cancel
  its reply target when YOU clear the notification *from inside WhatsApp* or when
  it re-posts; the fallback then is honest and immediate. Nothing is spoofable.
- Customization pins prove the prompt; the model's adherence is observable in
  generated drafts and recorded in Prompt Audit for review.
- Deferred unchanged: owner TODOs (rotations, dashboard toggles).
