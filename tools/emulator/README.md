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

## Local run (optional)

```
RM_DEBUG_MODE=1 VERSION_CODE=919 VERSION_NAME=1.7.2-emu-harness \
  REPLYMATE_SECRETS=/tmp/harness-keys bash engine/build.sh
PP_PKG=com.whatsapp bash tools/pingposter/build.sh build/pp-wa.apk
# boot an API-34 emulator (KVM required), then:
adb install -r ReplyMate-1.7.2-emu-harness.apk && adb install -r build/pp-wa.apk
bash tools/emulator/run_scenarios.sh evidence
```
