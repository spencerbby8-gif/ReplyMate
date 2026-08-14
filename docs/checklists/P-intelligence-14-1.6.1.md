# P-intelligence-14 — THREE-TOPIC BLOCKING PHASE (1.6.1)

Owner's bar, verbatim: *"Never assume a previous claim is correct. Prove it."*
Baseline entering this phase: main `0c55f6a`, gate 855/855 ⇒ **866/866 after
this phase's additions** (+9 intentional-compose, +2 restart-persistence).
This phase commit `8212ce5`. Prior phase docs were re-read against CURRENT
code and CURRENT official docs (checked 2026-08-14); device rows are the
owner's to mark — I do not self-certify. 1.5.9/1.6.0/1.6.1 stay unshipped
until then; Releases stay untouched.

Research applied (current docs, checked 2026-08-14):
- **Notification listener truth:** `NotificationListenerService.requestRebind(
  ComponentName)` remains the ONLY rebind call safe both before
  `onListenerConnected` and after `onListenerDisconnected` — our rebind path
  already matches; no change needed, re-pinned by suites.
- **Background execution truth:** `isIgnoringBatteryOptimizations` (exemption
  dialog via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with package: URI)
  + `ActivityManager.isBackgroundRestricted()` (API 28+; no intent flips it —
  routed to app details) + `NotificationManagerCompat.getEnabledListenerIds`
  re-probed on every return. OEM extras cannot be flipped by intent —
  guidance row shows when a battery need is live.

---

## Re-audit findings (what changed vs what stood)

| # | Item | Verdict | This phase |
|---|------|---------|------------|
| 1 | Background toggle → Ready | **BUG FOUND + FIXED** | toggle persisted ON before prereqs were real |
| 2 | Memory + understanding + groups | **RESTART GAP CLOSED** | restart persistence now pinned byte-for-byte |
| 3 | Conversational generation | **GAP CLOSED** | follow-ups / clarifications / continuations / openers |
| 4 | Verification | DONE | 866/866 ×2 local; CI proof build (Gate status) |

## Item 1 — Background toggle: reproduced, root-caused, fixed

- **Reproduced (code-level, deterministic):** the Settings toggle wrote
  `assistant.enabled = "1"` UNCONDITIONALLY, then ran the approval flow. An
  owner could flip it ON, decline notification access, and the row still read
  enabled — a lie about background readiness (the exact failure class the
  phase mandate forbids: "Ready only when ReplyMate can genuinely operate").
- **Root cause:** persistence happened before the prerequisite check;
  `refreshRows()` then trusted the persisted flag.
- **Fix (`SettingsActivity`):**
  - Toggle ON now persists `"1"` + toasts "Background replies on — Ready ✓"
    ONLY when `AssistantPrereq.missing(this)` is empty.
  - Otherwise it persists `"0"`, reverts the switch under a guard flag
    (no listener recursion) and runs the approval flow that names each
    missing lever and deep-links its real system screen (notification-listener
    settings / POST_NOTIFICATIONS request / battery-exemption dialog /
    app-details for the Restricted bucket).
  - `refreshRows()` re-probes on every return and shows
    `"Ready ✓ — access ✓ notifications ✓ battery ✓ · you approve every send"`
    vs `"limited — blocked by: <chips> (tap to fix)"` — so a stale ON from an
    older build, or a later-revoked grant, can never render Ready.
- **Device proof (owner):** BG rows §D — incl. fresh install, reboot,
  screen-off, recents removal, listener rebind, slow network.

## Item 2 — Memory + understanding + groups: restart persistence closed

- **Audit result:** understanding (ReplyPlanner intents COMFORT/NEWS/
  CORRECT/DISAGREE/JOKE, 13-pin filler list), group sender attribution
  (`<sender>: <body>` per member) and the Off / Inherit / On group policy
  (KV `groups.enabled` default `"0"`, per-app `groups.enabled.<channel.wire>`)
  all stood re-verified by their P-intel-13 suites — still green in the gate.
- **Gap found and closed — restart persistence:** nothing previously pinned
  that months/years of memory SURVIVE a process death verbatim.
  `MemoryRestartTest` (2) now proves it end-to-end through real generation:
  - Build a 45-message thread, generate — rolling summary row persisted,
    pinned fact on the wire, older-message retrieval lines on the wire.
  - **Simulated restart:** drop EVERY service instance, rebuild the whole
    stack over the same stores. The provider-visible prompt is
    **BYTE-IDENTICAL** (summary row reused — no version churn; pinned facts
    and retrieval stable). Summary version advances exactly once, and only
    when older-than-window history really changed.
- **Device proof (owner):** M rows §D.

## Item 3 — Conversational generation: intentional kinds, same pipeline

- **What exists now:** `ComposeKind` FOLLOW_UP / CLARIFY / CONTINUE / OPENER
  (+ REPLY, which delegates to the classic anchored reply path byte-for-byte).
  `Conversation screen → "＋ Intentional draft"` button → picker →
  `DraftService.composeForContact`.
- **Same pipeline, provably:** each kind runs voice (global + contact
  overrides), memory (recall + learned style past its 3-signal gate + pinned
  facts + FEEDBACK counter credit), live context, the Search gate, the
  reasoning decision, capability audit, usage metering and one-current-draft
  save — via the SAME extracted helpers the reply path now also calls
  (`applyLiveSearchGate` / `applyReasoning` / `appendCapabilityAudit`; the
  refactor is behavior-preserving — all 855 pre-existing tests passed after
  extraction before any new test ran).
- **Per-kind deterministic task on the wire:**
  - FOLLOW_UP quotes MY last unanswered outgoing — "still UNANSWERED by
    <name>", no guilt-tripping, never a repeat.
  - CLARIFY quotes THEIR ambiguous message — one clarifying question in the
    owner's voice, never answers it.
  - CONTINUE anchors the latest usable message — same topic forward, no
    re-answer, no pivot.
  - OPENER needs no anchor — fresh opener from relationship/notes/memory;
    no Search attached (nothing to look up).
- **Honest admission:** FOLLOW_UP with no outgoing → "Follow-up needs a
  message FROM YOU…"; their message still fresh → told to reply instead;
  CLARIFY with nothing from them; CONTINUE with an empty thread — each fails
  with a plain-language reason, zero provider calls, zero drafts.
- **Never auto-send:** every intentional draft is `GENERATED`,
  `inReplyToId == null`, lands in the same approve/copy flow; the audit
  snapshot records the kind (`compose:follow_up` etc.) and the full
  capability trail. Latent bug fixed on the way: the prompt-audit context
  NPE'd for anchor-free openers — now renders "Intentional opener — no
  message is being answered".
- **Test proof (gate):** `IntentionalComposeTest` (9) — wire-level task text
  per kind, admission honesty, voice override parity, learned-style parity,
  one-current-draft, REPLY delegation, never-a-send.
  `MemoryRestartTest` (2) above.
- **Device proof (owner):** COMPOSE rows §D.

## §D — Device verification rows (owner — mark PASS/FAIL)

Background:
- BG1  FRESH INSTALL: open Settings → toggle Background replies → each missing
       lever is named and its real system screen opens; decline one, return →
       toggle is OFF and the row says "limited — blocked by: …", NOT Ready.
- BG2  Grant every lever and return → row re-reads the real state and only
       then shows "Ready ✓ — access ✓ notifications ✓ battery ✓".
- BG3  Toggle ON, then revoke Notification access in system settings →
       return → Ready is gone (limited row names the revoked lever).
- BG4  Ready state survives: reboot the phone, screen off 10 min, remove the
       app from recents → receive a WhatsApp DM → draft alert still fires.
- BG5  Kill the listener (disable/enable the app or battery-kill it) → the
       scheduled rebind restores capture without toggling anything.
- BG6  On a slow network (throttled): an incoming DM eventually produces its
       draft alert; the toggle row never claims Ready while levers are missing.

Memory:
- M1  Long chat: an old detail replaced months later → ask about it → the
      draft uses the NEW value; Prompt Audit shows retrieval lines
      (latest on this / earlier) with attribution.
- M2  Force-stop the app, reopen → the same question generates the SAME
      memory-backed draft; the Prompt Audit summary line is unchanged
      (no new version row for unchanged history).
- M3  Memory off for a contact → next prompt (reply OR intentional) has no
      memory block.

Groups:
- G1  Fresh install: group message → captured? NO. Master ON in Sources →
      next group message captured, member-attributed ("<sender>: <body>"),
      draft fires; master OFF again → nothing generated.
- G2  Per-app cycle Inherit → On → Off on WhatsApp with master ON →
      WhatsApp groups silent on Off while other sources stay live.
- G3  1:1 chats behave identically in every mode.

Conversational generation:
- C1  "＋ Intentional draft" → each of the four intentions composes a draft
      that lands in the timeline as "● AI draft — waiting for your approval";
      nothing is sent without an explicit approve/copy/send action.
- C2  FOLLOW_UP on a chat where MY last message is unanswered → bump draft;
      on a chat where THEIR message is fresh → honest refusal ("reply to it
      instead"), no provider call, no draft.
- C3  OPENER on a brand-new contact with pinned facts → opener references
      the relationship/notes; Prompt Audit says "no message is being
      answered" and shows `compose:opener`.
- C4  CLARIFY on an ambiguous message → one question, not an answer; audit
      shows the same Search/reasoning trail a reply would.

Search + reasoning:
- S1  Ask a live-fact question (reply or intentional) → audit shows executed
      search(es) with sources — or the honest "ran NO web search" line.
- S2  An OPENER shows NO search lines (no anchor, nothing to look up).

## Gate status — updated 2026-08-14

- Audit → fix → pins: DONE (Items 1–4). JVM **866/866**×2
  (855 baseline + 11 new pins: IntentionalComposeTest 9,
  MemoryRestartTest 2 — both registered in `scripts/run_tests.sh`).
  Local engine build GREEN (`VERSION_CODE=908` devcheck, local debug cert
  `446310cc…`, artifact discarded after verification).
- CI proof build: **GREEN** — run **31841194524** on commit `8212ce5`
  (release.yml dispatch). In-run cert gates: **KEYSTORE-VERDICT: MATCH**,
  **APK-CERT-PROOF: MATCH**. Artifact **9234308191**
  `ReplyMate-1.6.1-intentional-proof-vc908` → APK
  `ReplyMate-1.6.1-intentional-proof.apk`, **616,778 bytes**, versionCode
  **908** / versionName `1.6.1-intentional-proof`, minSdk 24, target 35.
- Independently re-verified OFF-CI: sha256
  **`e7b04a34b3f3032ef78707e42750cdda1280c06ad987a6c576164fedcabe3bab`** and
  apksigner-extracted cert SHA-256
  `b15f2f37fce19b564683fe6b85725a9d3192df93181ef2f36286b5e21c6a85ed`
  (B1:5F:2F:37…6A:85:ED — the historical ReplyMate identity; update-in-place
  over ≤1.5.8 preserved).
- **Owner device verdicts — OPEN.** Prior phases' device rows remain open
  and are unchanged by this phase; no next feature phase until the previous
  build AND this one pass on-device. Releases stay untouched.
