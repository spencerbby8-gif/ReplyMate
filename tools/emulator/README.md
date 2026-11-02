# ReplyMate emulator listener harness (HARNESS ONLY — never shipped)

Lives outside the product. Workflow: `.github/workflows/emulator-listener.yml`.

**What it proves:** on stock AOSP Android, whether ReplyMate's real
`NotificationListenerService` (installed from a debuggable harness build of the
exact shipped tree) receives REAL `NotificationManager`-posted notifications in
foreground / background / screen-off / `am kill` / `am force-stop` / listener
disallow→allow / forced Doze / restricted-standby — with PID deltas, logcat,
dumpsys listener stanza, and ReplyMate's own kv trace (`listener.trace` boundary
lines incl. `callback→lane`, `shape`, `route`, `ingest`, `schedule`,
`bound=true/false`, `rebind …`) pulled via `run-as` and rendered into the
artifact bundle.

**What it deliberately does NOT prove:**
- genuine WhatsApp/Discord integration (no real app/account/server — PingPoster
  shapes are **synthetic** and labelled);
- anything about Oppo/ColorOS (that remains owner-device evidence);
- that the production keystore was used (it is explicitly NOT: the harness
  asserts harness-cert ≠ production cert on every run).

**Trace vocabulary (1.7.2 build):** see `docs/checklists/P-listener-foundation-1.7.2.md` §4.

## Hard-won harness facts (from CI run 41336986254 — do not regress)

1. **Remote-input actions need a MUTABLE PendingIntent.** Android 14's NMS
   (`checkDisqualifyingFeatures`) rejects the whole post with
   `IllegalArgumentException: … PendingIntents attached to actions with remote
   inputs must be mutable`. PingPoster's reply action therefore uses
   `FLAG_MUTABLE`. Before that fix, every MessagingStyle+Reply shape posted
   NOTHING and every downstream "no capture" verdict was garbage.
2. **replymate.db is WAL-mode.** Pulling `databases/replymate.db` alone via
   `run-as` returns a stale main file; not-yet-checkpointed kv writes (the
   entire `listener.trace` ring) live in `replymate.db-wal`. `kv_snapshot()`
   copies db + `-wal` together (SQLite replays the wal on open) and
   best-effort checkpoints on-device first.
3. **Every post is gated on landing.** `post()` reads PingPoster's own logcat
   line and marks `LANDED / REJECTED`, so a harness-side post failure can never
   be misread as a ReplyMate listener failure.
4. **`dumpsys deviceidle force-idle` refuses while the battery is "charging"**
   — S7 unplugs the (emulated) battery first and resets it afterwards.
5. **Never trust a silent `settings put`.** Run 41341288412 (after facts 1–4
   were fixed; every PingPoster shape LANDED) still showed NMS
   `All notification listeners (0) enabled` for the ENTIRE session and an
   empty `Has user set:` at S0 — the unverified enable write did not persist,
   and the later verified write persisted (`value={…RmNotificationListener}`)
   yet NMS kept the component out of the enabled/live sets. The harness now
   echoes every grant/revoke WITH the resulting `settings get`, escalates to
   `cmd notification allow_listener` when the setting didn't stick, and saves
   a bounded unfiltered logcat per scenario (+ a full final dump) so the NMS
   enable/reject reason is captureable instead of guesswork.
6. **`settings put secure <key> ""` fails with "Bad arguments"** over adb
   shell (empty arg swallowed) — revoke uses `settings delete`. Granted in the
   SAME run: even with the setting verified persisted from S0 on, ReplyMate
   NEVER entered NMS's enabled/live sets and no `bound=true` ever fired — that
   is now the primary forensic question, and grant/revoke therefore happens
   inside a cleared-logcat window dumped immediately after
   (`logcat-nms-grant-*.txt`), because per-scenario `-t 3000` tails rotated
   past the enable moment once (run 41345057505).
7. **Job timeout is 90 min** — 45 was insufficient once WAL snapshots + the
   verified grant ladder joined the loop (run 41345057505 was killed mid-S6).

## Proven vs not-proven (final, run 41384154178)

**Proven on AOSP-34 x86_64 userdebug (synthetic shapes, anchored evidence):**
- NMS admits + binds the listener; ingest across foreground / background /
  screen-off / forced Doze / RESTRICTED standby.
- Force-stop self-heal: process death -> NMS auto-restarts the ENABLED listener
  -> fresh `bound=true` -> backlog reconcile replays the posted-while-stopped
  notification. **Stock-Android baseline for the ColorOS/Oppo device question.**
- WhatsApp FULL chain on one trace (callback->lane -> shape -> PARSED -> ingest
  stored=3 -> schedule); groups OFF default drops (stored=0, filtered=2);
  noise gate IGNORED with category-only labels; dedupe across re-posts /
  late-action upgrades.
- Discord parser exercised via S9 lab toggle of the SAME `watch.discord` kv key
  the Settings switch writes: channel shape PARSED events=2 (group-filtered,
  groups OFF), no-history shape PARSED+stored with structural `reply=N`;
  restoring OFF re-arms the registry DISABLED fail-closed wall
  (`last_reason=watch disabled for discord`, counters consistent).

**Platform observations (reproduced, NOT app defects):**
- `settings delete` revoke of `enabled_notification_listeners` on AOSP-34 did
  NOT disconnect the live binding in two consecutive runs: no `bound=false`,
  callbacks kept arriving while `settings get` already returned null. S6
  branches on this; Settings-UI toggle teardown on real devices is a separate
  (device-row) question.
- `am kill` is a no-op here (pid unchanged) — a launcher-visited app with a
  bound NLS is not in the cached-kill set. Process-death recovery is covered
  by S5 force-stop only.

**Harness limitations (deliberately honest):**
- S0 trace-poll gap: while the app DB is born inside the S0 window, the
  on-device sqlite3 poll does not observe the just-written `bound=true`
  (capture-heavy path does). S0 verdict therefore ALSO accepts the logcat
  `RM-NLS: listener connected` line — three independent channels (dumpsys
  enabled-set + logcat + captured kv) prove the fact.
- Shapes are SYNTHETIC and labelled so; real WhatsApp/Discord payloads come
  only from the owner's device rows. AOSP emulator ≠ ColorOS: this harness
  proves the stock-Android baseline, never OEM behavior.

## Local run (optional)

```
RM_DEBUG_MODE=1 VERSION_CODE=919 VERSION_NAME=1.7.2-emu-harness \
  REPLYMATE_SECRETS=/tmp/harness-keys bash engine/build.sh
PP_PKG=com.whatsapp bash tools/pingposter/build.sh build/pp-wa.apk
# boot an API-34 emulator (KVM required), then:
adb install -r ReplyMate-1.7.2-emu-harness.apk && adb install -r build/pp-wa.apk
bash tools/emulator/run_scenarios.sh evidence
```
