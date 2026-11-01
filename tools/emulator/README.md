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

## Local run (optional)

```
RM_DEBUG_MODE=1 VERSION_CODE=919 VERSION_NAME=1.7.2-emu-harness \
  REPLYMATE_SECRETS=/tmp/harness-keys bash engine/build.sh
PP_PKG=com.whatsapp bash tools/pingposter/build.sh build/pp-wa.apk
# boot an API-34 emulator (KVM required), then:
adb install -r ReplyMate-1.7.2-emu-harness.apk && adb install -r build/pp-wa.apk
bash tools/emulator/run_scenarios.sh evidence
```
