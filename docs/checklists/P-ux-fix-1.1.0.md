# P-ux-fix — 1.1.0 / vc17 — completion report (2026-08-07)

Bug-fix-only phase ordered by the owner after rejecting trust in 0.9.0/1.0.0 behavior.
No new major features. Every item below maps to the owner's order; deferrals/behavior
changes are flagged honestly at the bottom.

## Downloads

| file | size (bytes) | sha256 |
|---|---|---|
| `releases/ReplyMate-1.1.0.apk` | 169,562 | `f6569c6958bf1383eb05be7578677693446b60bcbdcc9ffdeca5fd99c0117b4d` |

Signer cert SHA-256 `b15f2f37fce19b564683fe6b85725a9d3192df93181ef2f36286b5e21c6a85ed`
— unchanged → **updates in place over any previous install.**

## The 8 ordered issues → what changed

### 1. Notification grouping (duplicates when the same chat re-notifies)
- **Stable conversation key first** (existing): native conversation id (shortcut/JID),
  then Person keys, then title — tried in trust order via alias candidates.
- **NEW name-match attach** (`ContactService.matchByName`): when a chat arrives under a
  key we've never seen and its display name matches an existing contact that already
  lives on **this same app** — or a **manual-only** contact — the new key is LINKED to
  that contact instead of creating a second chat. Placeholder names (`Unknown (app)`,
  blank, <2 chars) never match. Same group title on two different apps stays two chats
  (a WhatsApp group ≠ a Discord server of the same name).
- **NEW legacy fork heal** (`ContactMerger`): duplicates already created on 1.0.0 merge
  back into ONE contact the next time a notification arrives for that chat — messages,
  drafts, learning signals, style overrides, memory facts all move collision-aware
  (kept contact's value wins); the duplicate's rolling summary is dropped (partial-history
  artifact) and stale learned-style caches are invalidated. Zero data loss.
- Evidence: `ContactServiceTest` (+6), real-SQL merge proof in
  `MemoryPersistenceRoboTest.forkHealMovesEverythingOnRealSql`, RequestDump section (11)
  scenarios a–c.

### 2. Non-message notifications (WhatsApp backup, syncing, progress) created fake chats
- **NEW `StatusFilter`**: self-titled (app-name) cards with no message history are
  ignored when they are ongoing, carry a progress bar, have no body, or match narrow
  machine phrases (backing up / restoring / checking for new / syncing / waiting for
  network / verifying / …). Human-plausible text is never matched.
- Wired into BOTH parsers (`MessagingStyleParser` before the single-shot fallback,
  `TitleTextParser` after the call gate) — a WhatsApp "Backing up messages: 45%" card
  can never create or update a chat again. `NotifExtractor` now captures the
  ongoing/foreground-service flags + `EXTRA_PROGRESS_MAX` as evidence.
- Evidence: `StatusFilterTest` (10), robo (`extractorCapturesOngoingFlagAndProgressMax`,
  `whatsappBackupCardIgnoredBeforeCreatingAChat`, `realChatWhileBackupRunningStillParses`),
  RequestDump (11)d prints `IGNORE / IGNORE ("app self-status (backup/sync/progress)")`.

### 3. Tone controls were confusing (tap-to-cycle, vague labels)
- Every control row (global Voice screen AND per-contact overrides) now opens a **real
  picker dialog**: each of the 3 levels is its own tappable row showing the level name,
  a plain-English line of what it does, and a concrete example reply; ✓ marks the
  current choice. Contact screen adds an "Inherit global voice" row on top.
- All 9 controls gained per-level descriptions + examples in `StyleControls`
  (content is pure data, unit-covered through the existing style suites).
- Row subtitles always state the current choice:
  global `current: warm (default)` · contact `current: direct (overrides global)` /
  `inheriting global: warm`. No dead rows, no cycling.

### 4. Provider/model name visible on draft cards
- Card meta is now `★ time · status`. The model/provider details stay where they belong:
  the "Why this reply?" panel and Prompt Audit (unchanged, full transparency on demand).

### 5. +THEM / +ME removed — one Manual flow
- The button pair is gone (layout + handlers + `R.id`s). What remains in the input row:
  **✍ Manual** and **✨ Generate**.
- Manual = a normal outgoing message: your text is saved to the thread as a message from
  you. If the contact is connected to an app, the status line offers "tap here to open it
  in WhatsApp" (deep-link with the text; clipboard fallback if the app won't open).
- ⚠️ **Behavior change to confirm**: pasting an incoming message into a pure-manual
  contact is no longer possible (that was the +them role). Contacts discovered via
  WhatsApp/Discord are unaffected — incoming arrives via the listener. If you want a
  paste-incoming path for pure-manual contacts back, tell me and I'll add a clearly
  labeled toggle (not a hidden mode).

### 6. Regenerate duplicated cards
- (Re)generate now **replaces** the current untyped draft: only `GENERATED` + non-★
  drafts are cleared, and only AFTER the provider answered (a failed regen never wipes
  your current draft). Starred (★) drafts and used ones (copied/edited/sent) always
  survive. The audit snapshot notes `regenerate replaced N unsaved draft(s)`.
- Tone transforms intentionally still add variants (that's their purpose, unchanged).

### 7. Open in app after manual send or AI draft
- Draft cards keep the "Open in WhatsApp/Discord" action (unchanged).
- Manual send now offers the same open-in-app via the one-shot status tap (above).

### 8. Stale UI after saving
- `ConversationActivity.onResume` now re-reads the contact (name + relationship),
  the thread AND the drafts — edits from Contact Edit, newly-arrived notifications and
  merged/renamed chats show immediately on return.
- Audited the other screens: Settings rows already refreshed on resume (my earlier
  note claiming otherwise was wrong — verified in code); Home re-renders on resume;
  Provider screens invalidate correctly. Voice/contact pickers update their subtitle
  instantly after a pick.

### "Recheck prompt/context pipeline" (your order)
- Re-verified end-to-end, unchanged and still honest: generation answers the LATEST
  readable incoming message (quoted verbatim in the task turn + audit snapshot), media
  latest = honest gate (never sent), no stale/unrelated thread. RequestDump gates
  (1)(5) + all memory/isolation/restart gates re-run and pass.

## Test evidence
- **421 JVM unit tests green** (was 403; +10 StatusFilter, +6 ContactService grouping/merge,
  +2 DraftService regen-replace).
- **25 Robolectric green on real Android 34 framework** (was 21; +3 extractor/status,
  +1 real-SQL merge).
- **RequestDump transcript: 16/16 gates** incl. grouping/heal/backup-ignore/regen-replace
  and the live Gemini wire send (HTTP 400 API_KEY_INVALID with a dummy key, exactly as
  Diagnostics predicts) → `docs/provider-probes/requestdump-uxfix-1.1.0.txt`.
- APK battery: vc17/vn1.1.0, minSdk 24, target 34; signer digest matches (updates in
  place); `btn_them` absent from dex AND resources.arsc, `btn_manual` present; new UX
  strings present; model-id dex scan = exactly 1 hit, the **declared known item** (the
  immutable SchemaV1 provider_def DDL default string — frozen migration history, never
  runtime-active).

## Deferrals / honest flags
1. **Pure-manual incoming paste removed** (see §5) — awaiting your call.
2. `docs/provider-probes/requestdump-memory-1.0.0.txt` (the 1.0.0 transcript) was lost
   during the interrupted-session commit and is NOT in git; all 13 of its gates were
   re-run and pass inside the new 1.1.0 transcript (superset). Flagged, not silently
   rewritten.
3. Channel-row UNIQUE-collision defense in `ContactDao.reassignContact` is provably
   present but unreachable in practice (the schema already prevents two rows with the
   same (channel, remote_key)) — defense in depth, noted for completeness.
