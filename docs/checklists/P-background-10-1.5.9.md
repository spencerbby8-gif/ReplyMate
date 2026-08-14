# P-background-10 — BLOCKING REGRESSION AUDIT (1.5.9 proof round 2)

APK for device verdicts: CI proof build on this commit range (historical cert
`b15f2f37…c6a85ed`, in-place update — all data kept). Artifact name + sha256 are
in the gate-status block below and mirrored on the release message.

*Phase-name note: "P-background-10" previously named the 1.4.9 phase
(`P-background-10-1.4.9.md`). This is the 1.5.9 regression-audit round,
instructed 2026-08-14.*

Each device item: what to do → what PROVES it worked. Reply per item: PASS / FAIL
+ what you saw.

---

## 0. The regression audit (what could actually produce the on-device symptoms)

### Duplicates — identical message twice, same draft twice
Four concrete paths found in the 1.5.9-proof code:

1. **Remote-key flip.** A chat whose first notification carries no native
   conversation id is stored under the display-title key; the re-post a beat
   later carries the native thread id and keys as `cid:…`. The exact content
   hash `channel|remoteKey|sender|body|ts` no longer matches → second row,
   second ping, second generation job.
2. **Timestamp rebase.** MessagingStyle entries with no per-message time inherit
   the notification POST time — which CHANGES on every re-post → new hash → new
   row + new job.
3. **Mid-call supersede race.** The pre-paid-call abort gate (1.5.8) cannot see
   a message that lands DURING the provider call: the older, slower job then
   saved its stale draft and alerted it on top of the newer job's work —
   "the same draft twice", or yesterday's answer pinned to today's question.
4. (Not a path, verified safe: `reconcile`, the connect/connectivity sweeps and
   the debounce are all idempotent under the hash + coalescer rules.)

### Filtering — noise still generating drafts
Newly identified leak shapes (all previously 1:1-READABLE to the pipeline):

5. **Announcement/broadcast conversations** (WhatsApp Channels, broadcast lists,
   status surfaces): they publish their own native conversation ids
   (`…@newsletter`, `…@broadcast`) but otherwise LOOK like a 1:1 chat.
6. **The app's own service chat** (WhatsApp's system chat, Telegram's service
   account): 1:1-shaped, titled exactly like the app label, carries system
   notices (login codes, registration checks) — it created conversations and
   could drive drafts.
7. **Time-tailed call cards** ("Missed voice call at 2:14 pm") slipped past the
   exact whole-card shapes added in P-background-9.

Already closed at 1.5.9 and now pinned end-to-end again (§2 battery): backup
cards, summary digests + geometry, encryption/security/waiting system lines,
category missed-calls, reactions, media-only first contact, groups.

### Search + reasoning honesty
8. Trigger-layer was already pinned both ways (Pidgin meaning-asks incl.
   `wetin be/dey mean`, sports/results/prices/breaking-news markers, UNKNOWN
   carried-word rule; plus strong negative pins for everyday Pidgin, proper
   nouns, names, thread vocabulary). The GAP was claim-vs-execution: the audit
   said "native web search grounds this reply" at REQUEST time, and said
   nothing when the provider ran zero searches or billed zero reasoning tokens
   — an overclaim.

## 1. The fixes (mapped)

1/2. **One source event = one row.** Near-identity collapse in the ingest
pipeline, on the stable facts only: same contact (alias-healed), channel,
direction, exact body, within a 120s window. Text-class bodies only. A human's
deliberate repeat minutes later survives (pinned).
3. **Second staleness gate, AFTER the paid call.** A job superseded mid-wire
returns before purge/save/alert; Diagnostics records the honest cost ("the
interrupted call was paid once; the newer job answers").
5. **Announcement/broadcast native ids die at the registry**, before parsing —
no events, rows, contacts, pings or jobs (diagnostic reason recorded).
6. **App-label-exact service chats die in the noise gate** (a contact that
merely contains the app's name never drops — pinned).
7. **Call cards tolerate a fixed "at <time>" tail.**
8. **Claim-vs-execution honesty.** Search line now records the REQUEST as a
request; post-call the audit credits execution ONLY from provider response
metadata (search count + public source titles), states plainly when zero
searches ran ("answered from its own knowledge, unverified live"), and marks
deeper-thinking UNCONFIRMED unless reasoning tokens were billed.
P-background-9 preserved: lanes, rebind/connectivity sweeps, silent re-alert,
swipe-respect, age guard — touched by nothing here (their suites stay green).

## 2. Engineering evidence (reproducible)

- JVM gate: **804/804 PASS** (785 baseline + 19 new): +
  `NoiseEndToEndTest` — the full 12-class noise battery through the REAL
  registry+parsers+coordinator (zero contacts/rows/pings; zero provider calls
  by construction — jobs exist only for pings), with 1:1 controls.
  `IngestCoordinatorTest` +5 (key-flip, timestamp-rebase, banter-window,
  cross-chat, direction scoping) · `BackgroundDraftGuardTest` +1 (flip-mid-call
  saves nothing) · `NoiseGateTest` +2 (service chat) · `SystemLinesTest` +2
  (announcement ids, time-tailed cards) · `GenerationHonestyTest` 5
  (requested-vs-executed search; unconfirmed-vs-billed reasoning;
  ordinary-message control).
- Whole-app compile proof: local `engine/build.sh` GREEN on this tree.

## 3. Device verdicts — duplicates

1. One WhatsApp 1:1 message → ONE row in the chat, ONE alert, ONE draft —
   including after the app's quick "second post" (the actions-attached refresh).
2. Rapid-fire the SAME short text twice within a minute from the chat app →
   ReplyMate shows it once; ~3 minutes apart → twice (deliberate-repeat rule).
3. Slow network + a newer message mid-generation → only the NEWER message's
   draft survives; Diagnostics shows the older job "discarded before any draft
   or alert".

## 4. Device verdicts — filtering

Replay §4 of the 1.5.9 list PLUS: a WhatsApp Channel you follow, a
broadcast-list message, the WhatsApp/Telegram service chat if it pings, a
missed call card with a timestamp. → No new contacts, no chats, no drafts, no
pings, zero provider cost; Diagnostics keeps honest drop reasons. Real 1:1
messages — including "sorry i missed your call 🙏" — keep working. Any noise
that still slips: screenshot title+text (the exact shape becomes the next pin).

## 5. Device verdicts — Search + reasoning proof (brief §4/§5)

On your REAL provider/model, from background:
1. Slang/Pidgin: "wetin be 'odogwu' abeg" → draft trail reads WHICH path ran
   (native search with executed-search count + sources, or encyclopedia with ms,
   or cache) — and an explicit "no web search ran — unverified" line when the
   provider declined to search. Anything else = FAIL.
2. Current/sports: "who won the Arsenal game last night?" / "how much is fuel
   this week?" → live-verified answer or the honest unverified line.
3. Ordinary control: "lol you dey mad 😂" → NO search lines at all.
4. Complex/ambiguous: a multi-question or correction burst → trail shows the
   deeper-thinking level AND billed reasoning tokens, else the UNCONFIRMED line.
5. Facts rule: where verification failed or nothing ran, the reply must visibly
   hedge/refuse — never invent.

## 6. Preserved (P-background-9 rows stay binding)

Capture after kill/rebind/screen-off, one-pop discipline, swipe-respect (never
re-alert dismissals), inline Edit, manual Send honesty, memory/customization in
the trail, aura→Arsenal topic reset (JVM-pinned), 48h sweep age guard.

---

## Gate status — updated 2026-08-14

- Regression audit → fixes → pins: DONE (§0–§2). JVM **804/804**; local engine
  build GREEN (`VERSION_CODE=904` dev-check, artifact discarded after compile
  verification).
- CI proof build: **GREEN** — run **31800924794** on commit `1dc3cf2`
  (release.yml dispatch). In-run cert gates: **KEYSTORE-VERDICT: MATCH**,
  **APK-CERT-PROOF: MATCH**. Artifact **9219231315**
  `ReplyMate-1.5.9-background-proof-2-vc904` → APK
  `ReplyMate-1.5.9-background-proof-2.apk`, 588,106 bytes, versionCode 904 /
  versionName `1.5.9-background-proof-2`. Independently re-verified OFF-CI:
  sha256 **`adda056b44c5045128447de8ee05ff3ec84ff179eba61af43b6f66343a5bee89`**
  and signing cert SHA-256
  `b15f2f37fce19b564683fe6b85725a9d3192df93181ef2f36286b5e21c6a85ed`
  (B1:5F:2F:37…6A:85:ED — the historical ReplyMate identity; update-in-place
  over ≤1.5.8 preserved).
- **Owner device verdicts — OPEN.** §3–§5 are real-phone rows; no next phase
  until duplicates, noise filtering, Search-execution and reasoning-execution
  all PASS on-device. 1.5.9 stays unshipped (releases/RELEASES.md untouched)
  until then.
