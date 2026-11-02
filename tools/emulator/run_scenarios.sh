#!/usr/bin/env bash
# ReplyMate emulator listener harness — scenario runner (HARNESS ONLY).
# Runs INSIDE the ephemeral GHA/emulator environment against the
# RM_DEBUG_MODE engine build. Zero product changes; everything readout-only.
#
# Captures per scenario: PID deltas, dumpsys notification (listener stanza),
# logcat RM-* lines, ReplyMate's OWN kv diagnostics (run-as pull of
# databases/replymate.db, table app_kv; python3 sqlite3 extraction) — including
# the 1.7.2 boundary trace.
#
# `am kill` (process trim) and `am force-stop` (hard stop) are SEPARATE
# scenarios, exactly per owner order.
set -u

EV="${1:-evidence}"
mkdir -p "$EV"
RMPKG="com.replymate.app"
WAPKG="com.whatsapp"          # PingPoster WA-variant package (synthetic shapes)
DCPKG="com.discord"           # PingPoster DC-variant package (synthetic shapes)
NLS_COMPONENT="$RMPKG/com.replymate.app.listener.RmNotificationListener"
SUM="$EV/summary.md"
: > "$SUM"

say()  { echo "$*" | tee -a "$SUM"; }
line() { echo "$1" >> "$SUM"; }

adb_pid() { adb shell pidof "$RMPKG" 2>/dev/null | tr -d '\r'; }
say_kv() { say "    kv: $1"; }

# --- listener-access grant/revoke with VERIFICATION -------------------------
# Run 41341288412 proved silent enable is worthless: the S0 `settings put`
# never persisted (stderr was swallowed), and even after a persisted set the
# NMS enabled-list stayed (0). Every mutation below is echoed WITH the
# resulting secure-setting value, and an escalation ladder tries the
# sanctioned shell path too.
nls_get() { adb shell settings get secure enabled_notification_listeners 2>&1 | tr -d '\r'; }

# grant/revoke happens INSIDE a cleared-logcat window that is dumped
# immediately after — NMS logs its enable/BIND/reject reasoning in real time
# (the 3000-line per-scenario tails rotated past the enable window in run
# 41345057505 and the reason was lost). One evidence file per invocation.
NMS_WIN=0
nms_window_open() { adb logcat -c; NMS_WIN=$((NMS_WIN+1)); }
nms_window_close() {  # $1 = verb (grant|revoke)
  adb logcat -b all -d 2>/dev/null > "$EV/logcat-nms-$1-$NMS_WIN.txt" || true
}

grant_nls() {
  nms_window_open
  local put_out
  put_out=$(adb shell settings put secure enabled_notification_listeners "$NLS_COMPONENT" 2>&1 | tr -d '\r')
  sleep 2   # let the SettingsObserver round-trip before we verify
  say "    enable[1] settings put (out=${put_out:-ok}) → get: '$(nls_get)'"
  # ALWAYS also use the sanctioned shell API — run 41349417603 proved a
  # persisted setting is NOT sufficient for NMS to admit the component.
  local cmd_out
  cmd_out=$(adb shell cmd notification allow_listener "$NLS_COMPONENT" 2>&1 | tr -d '\r' | head -2)
  sleep 2
  say "    enable[2] cmd notification allow_listener (out=${cmd_out:-ok}) → get: '$(nls_get)'"
  nls_get | grep -q "$RMPKG" \
    && say "    enabled_notification_listeners PERSISTED ✓" \
    || say "    enabled_notification_listeners STILL NOT SET ✗"
  # service-resolution probe EXACTLY the way NMS looks providers up
  { adb shell cmd package query-services --components -a android.service.notification.NotificationListenerService 2>&1 \
    || adb shell pm query-services --components -a android.service.notification.NotificationListenerService 2>&1; } > "$EV/nls-providers-$NMS_WIN.txt"
  # NMS-side acceptance: is the component in the ENABLED set / LIVE list?
  adb shell "dumpsys notification --noredact listeners" 2>/dev/null \
    | grep -A4 "enabled for current profiles" | grep "RmNotificationListener" \
    && say "    NMS enabled-set contains ReplyMate ✓" \
    || say "    NMS enabled-set does NOT contain ReplyMate ✗"
  nms_window_close grant
}

revoke_nls() {
  nms_window_open
  # NOTE: `settings put secure <key> ""` fails with "Bad arguments" over adb
  # shell quoting (proven in run 41345057505) — `delete` is the reliable clear.
  local del_out
  del_out=$(adb shell settings delete secure enabled_notification_listeners 2>&1 | tr -d '\r')
  sleep 2
  say "    revoke via settings delete (out=${del_out:-ok}) → get: '$(nls_get)'"
  nls_get | grep -q "$RMPKG" \
    && say "    listener access STILL SET after delete ✗ (S6a invalid)" \
    || say "    listener access revoked ✓"
  nms_window_close revoke
}

# The app DB runs in WAL mode (PRAGMA journal_mode=wal — verified in run
# 41336986254: run-as cat of replymate.db alone returned a stale main file
# with 0 kv rows while the app had already written listener.* keys). A live-db
# copy WITHOUT the -wal file silently misses every not-yet-checkpointed write,
# which made S0..S8 trace verdicts read garbage. Snapshot db + -wal together
# (SQLite replays the wal on open); best-effort on-device checkpoint first.
kv_snapshot() {  # $1=local db path $2=heaviness[heavy|light]
  # light: db + -wal (poll path — checkpoints costed ~17min in run 41345057505)
  # heavy: + best-effort on-device wal_checkpoint first (per-scenario path)
  local db="$1"
  case "${2:-heavy}" in heavy)
    adb shell "run-as $RMPKG /system/bin/sqlite3 databases/replymate.db 'PRAGMA wal_checkpoint(FULL);'" >/dev/null 2>&1 || true ;;
  esac
  adb shell "run-as $RMPKG cat databases/replymate.db" > "$db" 2>/dev/null || true
  rm -f "$db-wal"
  adb shell "run-as $RMPKG cat databases/replymate.db-wal" > "$db-wal" 2>/dev/null || true
  [ -s "$db-wal" ] || rm -f "$db-wal"
}

kv_pull() {  # $1=tag ; pulls app_kv and extracts listener/assistant keys
  local tag="$1" db="$EV/kv-$tag.db" out="$EV/kv-$tag.txt"
  kv_snapshot "$db"
  python3 - "$db" "$out" << 'PY'
import sqlite3, sys, json
db, out = sys.argv[1], sys.argv[2]
try:
    con = sqlite3.connect(db)
    rows = con.execute(
        "SELECT key, value FROM app_kv WHERE key LIKE 'listener.%' "
        "OR key LIKE 'assistant.latency%' OR key LIKE 'assistant.pending%' "
        "ORDER BY key").fetchall()
    ring = con.execute("SELECT value FROM app_kv WHERE key='listener.trace'").fetchone()
    with open(out, "w") as f:
        for k, v in rows: f.write("%s = %s\n" % (k, v))
        f.write("\n--- listener.trace lines (newest first) ---\n")
        if ring and ring[0]:
            for o in json.loads(ring[0]):
                f.write("%s | %s\n" % (o.get("ts"), o.get("line")))
        else:
            f.write("(empty)\n")
    print("kv ok")
except Exception as e:
    with open(out, "w") as f: f.write("kv pull/parse failed: %r\n" % e)
    print("kv fail: %r" % e)
PY
}

pull_anrs() {  # $1=tag ; gunzip procexitstore records mentioning our pkgs
  local tag="$1" z
  mkdir -p "$EV/_anr"
  adb pull /data/system/procexitstore "$EV/_anr" >/dev/null 2>&1 || true
  for z in "$EV/_anr"/procexitstore/anr_*.gz; do
    [ -e "$z" ] || continue
    if gunzip -c "$z" 2>/dev/null | grep -qiE "com.replymate.app|com.android.systemui"; then
      gunzip -c "$z" > "$EV/anr-$tag-$(basename "$z" .gz).txt" 2>/dev/null || true
    fi
  done
  rm -rf "$EV/_anr"
}

bound_state() {  # app-side truth via kv + system-side via dumpsys
  local tag="$1"
  adb shell "dumpsys notification --noredact listeners" \
    | grep -i -A4 "RmNotificationListener" \
    | sed 's/^/    dumpsys: /' | tee -a "$SUM" >/dev/null || true
  adb shell "dumpsys notification --noredact listeners 2>/dev/null" \
    | grep -i -m2 "RmNotificationListener" | tee -a "$SUM" >/dev/null || true
}

post() {  # $1=pkg $2=shape $3=nid ; verify the post LANDED at NMS (run
          # 41336986254 learnt: a failed enqueue looks identical to a
          # listener drop downstream — always gate scenario verdicts on this)
  adb logcat -c
  adb shell am start -W -n "$1/com.pingposter.MainActivity" --es shape "$2" --ei nid "$3" >/dev/null 2>&1
  sleep 6
  local st
  st=$(adb logcat -d -s PP 2>/dev/null | tr -d '\r' | grep "shape=$2" | tail -1)
  case "$st" in
    *"posted shape="*)      say "  post $2: LANDED at NotificationManager ✓" ;;
    *"post failed shape="*) say "  post $2: REJECTED by NMS ✗ ($(echo "$st" | sed 's/.*post failed shape/post failed shape/' | cut -c1-150))" ;;
    *)                      say "  post $2: PP log unseen (instrumentation gap)" ;;
  esac
}

capture() {  # $1=scenario-tag
  local tag="$1"
  say "### evidence: $tag  (pid=$(adb_pid || echo none))"
  kv_pull "$tag"; cp "$EV/kv-$tag.txt" "$EV/" 2>/dev/null || true
  bound_state "$tag"
  adb shell "dumpsys notification --noredact listeners" > "$EV/dumpsys-listeners-$tag.txt" 2>/dev/null || true
  # fix 12: error-forensics channels — process restarts get their reason from
  # ApplicationExitInfo (survives logcat rotation); run 41386158198 killed BOTH
  # com.android.systemui (pid 925, -800) and ReplyMate (2409->3720) 0.8s apart
  # with 'user request after error' while the rotating -t 3000 tails carried no
  # crash record. exit-info is per-scenario, dropbox slice is end-of-run.
  adb shell "dumpsys activity exit-info $RMPKG" > "$EV/exit-info-$tag.txt" 2>/dev/null || true
  adb shell "dumpsys activity exit-info com.android.systemui" >> "$EV/exit-info-$tag.txt" 2>/dev/null || true
  pull_anrs "$tag"
  adb logcat -d -s PP RM-NLS RM-App 2>/dev/null > "$EV/logcat-$tag.txt"
  # bounded UNFILTERED logcat — the NMS enable/bind/reject reasons live on
  # system tags (NotificationManager/NotificationService), invisible in the
  # filtered slice; run 41341288412 could not explain "enabled (0)" without it
  adb logcat -b all -d -t 3000 2>/dev/null > "$EV/logcat-full-$tag.txt" || true
  grep -E "PP |RM-NLS" "$EV/logcat-$tag.txt" | tail -8 | sed 's/^/    logcat: /' | tee -a "$SUM"
  say "    trace snapshot: $EV/kv-$tag.txt"
  line ""
}

# fix 8 — anchored, atomic verdict polls. Runs ≤41352701042 evidence of two
# poll-path defects and their exact symptoms: (1) wait_trace matched ANY line
# in the 12-deep ring with no timestamp anchor → stale committed lines made
# S5/S6a read ✗ ('UNEXPECTED capture') and S6b read ✓ off an old bound=true;
# (2) the light snapshot cats db and -wal separately → salt mismatch silently
# discards fresh WAL rows, which is how S0 read ✗ while the captured (heavy)
# file visibly contained bound=true written at 21:46:44.9, inside the window.
# Polls now read the key on-device through sqlite3 (one atomic WAL-aware view,
# no checkpoint cost — the ~17min blowout was FULL checkpoints, not reads)
# and only accept lines with ts newer than a watermark taken just before the
# gated action.
trace_hwm() {  # prints newest listener.trace ts (0 when ring empty/unreadable)
  adb shell "run-as $RMPKG /system/bin/sqlite3 databases/replymate.db \"SELECT value FROM app_kv WHERE key='listener.trace'\"" 2>/dev/null \
    | python3 -c "
import sys, json
raw = sys.stdin.read().strip()
try:
    print(max(int(o.get('ts', 0)) for o in json.loads(raw)) if raw else 0)
except Exception:
    print(0)"
}

wait_trace() {  # $1=regex $2=timeout-s $3=hwm ; anchored on-device poll
  local pat="$1" max="$2" hwm="${3:-0}" i=0
  while [ $i -lt "$max" ]; do
    if adb shell "run-as $RMPKG /system/bin/sqlite3 databases/replymate.db \"SELECT value FROM app_kv WHERE key='listener.trace'\"" 2>/dev/null \
      | python3 -c "
import sys, json, re
pat, hwm = sys.argv[1], int(sys.argv[2])
raw = sys.stdin.read().strip()
try:
    for o in json.loads(raw) if raw else []:
        if int(o.get('ts', 0)) > hwm and re.search(pat, o.get('line', '')):
            sys.exit(0)
except Exception:
    pass
sys.exit(1)" "$pat" "$hwm"; then return 0; fi
    sleep 1; i=$((i+1))
  done
  return 1
}

NID=2000
say "# ReplyMate emulator listener harness — SCENARIOS (AOSP emulator, synthetic shapes)"
say ""
say "device: $(adb shell getprop ro.product.model | tr -d '\r') · sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r') · build=$(adb shell getprop ro.build.display.id | tr -d '\r')"
say ""

# ---------------- baseline ----------------
# fix 13: enable root for /data/system/procexitstore ANR-trace pulls (userdebug
# image; graceful no-op elsewhere). Run 41404621261 exit-info proved the pid
# churn is ANR error-handling ('executing service ...RmNotificationListener',
# trace anr_*.gz recorded 07:19:23, kill executed 07:23:49) — this pulls the
# actual stack record so app-vs-environment attribution is made FROM THE TRACE.
adb root >/dev/null 2>&1; adb wait-for-device; sleep 3

say "## S0 baseline (launch + access + first bind)"
adb shell pm grant "$RMPKG" android.permission.POST_NOTIFICATIONS 2>&1 | tee -a "$SUM" >/dev/null
adb shell pm grant "$WAPKG" android.permission.POST_NOTIFICATIONS 2>&1 | tee -a "$SUM" >/dev/null
adb shell pm grant "$DCPKG" android.permission.POST_NOTIFICATIONS 2>&1 | tee -a "$SUM" >/dev/null
adb logcat -c
HWM=$(trace_hwm)
grant_nls
adb shell monkey -p "$RMPKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
i=0; while [ $i -lt 20 ] && [ -z "$(adb_pid)" ]; do sleep 1; i=$((i+1)); done
say "  pid0=$(adb_pid || echo none)"
adb shell "dumpsys package $RMPKG" > "$EV/dumpsys-package-$RMPKG.txt" 2>/dev/null || true
if wait_trace "bound=true (onListenerConnected)" 60 "$HWM"; then
  say "  S0: first bind observed (trace bound=true) ✓"
elif adb logcat -d -s RM-NLS 2>/dev/null | tr -d '\r' | grep -q "listener connected"; then
  say "  S0: first bind observed (logcat RM-NLS listener connected; trace line not yet visible to polls — S0 db-birth observability gap, see fix-8/9 notes) ✓"
else
  say "  S0: no bound evidence within 60s of enable+launch ✗"
fi
capture s0-baseline

# ---------------- S1 foreground ----------------
say "## S1 FOREGROUND (ReplyMate visibly open)"
adb shell monkey -p "$RMPKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 2
HWM=$(trace_hwm); post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 30 "$HWM" && say "  S1: ingest boundary reached ✓" || say "  S1: NO ingest line within 30s ✗"
capture s1-foreground

# ---------------- S2 background ----------------
say "## S2 BACKGROUND (Home only — no swipe-kill)"
adb shell input keyevent KEYCODE_HOME; sleep 30
HWM=$(trace_hwm); post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 30 "$HWM" && say "  S2: ingest boundary reached ✓" || say "  S2: NO ingest line within 30s ✗"
capture s2-background

# ---------------- S3 screen-off ----------------
say "## S3 SCREEN-OFF (sleep 60s → post → wake→read)"
adb shell input keyevent KEYCODE_SLEEP; sleep 60
HWM=$(trace_hwm); post "$WAPKG" wa-11 $((NID+=1))
sleep 8
adb shell input keyevent KEYCODE_WAKEUP; sleep 3
wait_trace "ingest · $WAPKG" 20 "$HWM" && say "  S3: ingest boundary reached ✓" || say "  S3: NO ingest line ✗"
capture s3-screenoff

# ---------------- S4 am kill (trim) ----------------
say "## S4 PROCESS KILL — am kill (trim class; NOT force-stop)"
ADB_BEFORE=$(adb_pid); say "  pid before kill: $ADB_BEFORE"
adb shell am kill "$RMPKG"; sleep 3; ADB_AFTER=$(adb_pid); say "  pid after kill: ${ADB_AFTER:-dead}"
[ "$ADB_BEFORE" = "$ADB_AFTER" ] && [ -n "$ADB_AFTER" ] \
  && say "  S4: am kill was a NO-OP on this stack (pid unchanged — kill only reaps cached procs; a launcher-visited app with a bound NLS survives). Process-death recovery is covered by S5 force-stop instead." || true
HWM=$(trace_hwm); post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 45 "$HWM" && say "  S4: capture (re)confirmed ✓" || say "  S4: NO capture after am kill ✗"
say "  pid now: $(adb_pid || echo none)"
capture s4-amkill

# ---------------- S5 force-stop ----------------
say "## S5 FORCE-STOP (hard stop; separate control — NOT a kill simulation)"
adb shell am force-stop "$RMPKG"; sleep 2; say "  pid after force-stop: $(adb_pid || echo dead)"
# run 41352701042 proved AOSP-34 NMS auto-restarts an ENABLED listener from
# stopped state (force-stop ~21:55:02 → am_proc_start 21:55:22 → bound=true
# 21:55:45 → backlog reconcile delivered the posted-while-stopped notif). So
# post-force-stop capture is legitimate ONLY through a fresh rebind chain —
# verify that chain instead of blanket-flagging any capture.
HWM=$(trace_hwm); post "$WAPKG" wa-11 $((NID+=1))
if wait_trace "ingest · $WAPKG" 25 "$HWM"; then
  wait_trace "bound=true" 5 "$HWM" >/dev/null 2>&1 \
    && say "  S5: capture after force-stop WITH a fresh bound=true — NMS auto-rebind recovery confirmed ✓ (stock self-heal baseline for the ColorOS comparison)" \
    || say "  S5: capture after force-stop WITHOUT new bound=true — UNEXPLAINED, investigate ✗"
else
  say "  S5: no capture while force-stopped (listener stayed down — valid on builds without NMS auto-rebind)"
fi
capture s5-forcestop
say "  relaunching…"
adb shell monkey -p "$RMPKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 5
HWM=$(trace_hwm); post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 30 "$HWM" && say "  S5b: capture after relaunch ✓" || say "  S5b: STILL no capture after relaunch ✗"
capture s5-relaunch

# ---------------- S6 listener toggle (disconnect while alive) ----------------
say "## S6 LISTENER TOGGLE (access OFF/ON — exercises onListenerDisconnected + requestRebind recovery)"
adb logcat -c
# run 41352701042 proved a settings-delete revoke may NOT tear down the live
# binding on AOSP-34 ('Deleted 1 rows', get → null, yet NO bound=false ever
# and a callback k#b3243f20 delivered 21:57:46 AFTER the delete). Detect the
# disconnect first and branch honestly — a 'capture while disallowed' verdict
# is only meaningful when the listener was actually disconnected.
HWM=$(trace_hwm)
revoke_nls
if wait_trace "bound=false" 30 "$HWM"; then
  say "  S6a: onListenerDisconnected fired after revoke ✓ (revoke really torn down)"
  DISC=1
else
  say "  S6a: NO bound=false within 30s of settings-delete revoke — platform observation: AOSP-34 adb-level revoke keeps the live binding (differs from Settings-UI toggle teardown on devices)"
  DISC=0
fi
HWM2=$(trace_hwm); post "$WAPKG" wa-11 $((NID+=1))
if [ "$DISC" = 1 ]; then
  wait_trace "ingest · $WAPKG" 10 "$HWM2" && say "  S6a-post: UNEXPECTED capture while genuinely disconnected ✗" || say "  S6a-post: no capture while disconnected ✓ (expected)"
else
  wait_trace "ingest · $WAPKG" 10 "$HWM2" \
    && say "  S6a-post: callback still delivered — consistent with the never-disconnected binding (platform state, not an app defect)" \
    || say "  S6a-post: no callback while disallowed"
fi
capture s6-disallowed
HWGRANT=$(trace_hwm)
grant_nls
say "  access re-enabled"
if [ "$DISC" = 1 ]; then
  wait_trace "rebind requested" 15 "$HWM" && say "  S6b: ListenerRebind fired on disconnect ✓" || say "  S6b: disconnect seen but NO rebind-requested line ✗"
  wait_trace "bound=true" 40 "$HWGRANT" && say "  S6b: LF-L1 reconnect sequence OBSERVED ✓" || say "  S6b: no bound=true within 40s after re-enable ✗"
else
  say "  S6b: n/a on this stack — binding never dropped, so continuity (not reconnect) is being observed; disconnect→requestRebind recovery on AOSP is only exercised via S4/S5 process-death binds"
fi
HWM4=$(trace_hwm); post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 30 "$HWM4" && say "  S6c: capture after re-enable ✓" || say "  S6c: no capture after re-enable ✗"
capture s6-reenabled

# ---------------- S6d disconnect-while-GRANTED (cross-app requestRebind) ----------------
# Stock AOSP delivers onListenerDisconnected to a bound, granted listener only
# on revoke / package-update / user-switch — every adb-level control hits one
# of those (a kill or a grant flip). The one remaining legitimate trigger is
# requestRebind itself, driven here cross-app by pingposter; if the platform
# rejects third-party callers the path is marked UNTESTED with the rejection
# as evidence (owner rule: no fake success, no product hooks to force it).
say "## S6d DISCONNECT-WHILE-GRANTED via requestRebind (cross-app attempt)"
HWM=$(trace_hwm)
adb shell am start -W -n "$WAPKG/com.pingposter.MainActivity" --es shape rebind-rm >/dev/null 2>&1
sleep 3
RB=$(adb logcat -d -s PP 2>/dev/null | tr -d '\r' | grep "rebind-rm result=" | tail -1)
say "  $RB"
case "$RB" in
  *result=accepted*)
    if wait_trace "bound=false" 20 "$HWM"; then
      say "  S6d: onListenerDisconnected FIRED while access still granted ✓ — the target path is reachable"
      wait_trace "rebind requested" 15 "$HWM" && say "  S6d: product ListenerRebind answered the disconnect ✓" || say "  S6d: disconnect fired but no rebind request within 15s ✗"
    else
      say "  S6d: request accepted but no bound=false within 20s (rebind may be deferred) — check kv/logcat"
    fi
    wait_trace "bound=true" 60 "$HWM" && say "  S6d: reconnect observed ✓" || say "  S6d: no reconnect within 60s ✗"
    ;;
  *result=rejected*)
    say "  S6d: cross-app requestRebind REJECTED by the platform (see logcat line above) → disconnect-while-granted is NOT externally exercisable on stock AOSP-34 without a product-side test hook. Marking LF-L1 disconnect→requestRebind→reconnect UNTESTED-in-harness (process-death binds S5 + full NMS admission S0 remain verified; genuine disconnected-state recovery is exercised only by real OEM unbind events — i.e. on the owner device)."
    ;;
  *)
    say "  S6d: PP rebind-rm log line unseen (instrumentation gap) ✗"
    ;;
esac
capture s6d-rebind-granted

# ---------------- S7 doze / standby ----------------
say "## S7 DOZE + STANDBY RESTRICTED (temporary device states)"
# force-idle refuses on a charging device ("not enabled"; run 41336986254 hit
# exactly this) — unplug first, restore at the end.
adb shell dumpsys battery unplug >/dev/null 2>&1
adb shell input keyevent KEYCODE_SLEEP; sleep 3
adb shell dumpsys deviceidle force-idle; sleep 3
HWM=$(trace_hwm); post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 25 "$HWM" && say "  S7a: capture under forced Doze ✓" || say "  S7a: no capture under forced Doze ✗"
adb shell dumpsys deviceidle unforce >/dev/null 2>&1
adb shell dumpsys battery reset >/dev/null 2>&1
adb shell input keyevent KEYCODE_WAKEUP; sleep 3
adb shell am set-standby-bucket "$RMPKG" restricted 2>/dev/null; sleep 3
HWM=$(trace_hwm); post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 25 "$HWM" && say "  S7b: capture in RESTRICTED standby ✓" || say "  S7b: no capture in RESTRICTED standby ✗"
adb shell am set-standby-bucket "$RMPKG" active 2>/dev/null; sleep 2
capture s7-doze-standby

# ---------------- S8 group + burst + late action + self-status + discord ----------------
say "## S8 shape scenarios (synthetic shapes; groups OFF default noted)"
post "$WAPKG" wa-burst $((NID+=1)); capture s8-wa-burst
post "$WAPKG" wa-group $((NID+=1)); capture s8-wa-group
post "$WAPKG" wa-noaction $((NID+=1)); sleep 2; post "$WAPKG" wa-withaction $((NID+=1)); capture s8-wa-lateaction
post "$WAPKG" wa-selfstatus $((NID+=1)); capture s8-wa-selfstatus
say "  Discord variant (watch.discord defaults OFF — route honesty evidence):"
post "$DCPKG" dc-channel $((NID+=1)); capture s8-dc-channel
post "$DCPKG" dc-nohistory $((NID+=1)); capture s8-dc-nohistory

# ---------------- S9 discord watch-ON parser evidence (lab kv toggle) ----------------
# S8 records honesty only: watch.discord defaults OFF so the route verdict is
# DISABLED (registry fail-closed, stats-only, no route line). To actually
# EXERCISE TitleTextParser(DISCORD, preferHistory) + announcement fail-closed
# on a real OS, toggle the SAME user-facing kv key the Settings switch writes
# ('watch.<wire>' per ParserRegistry.enabledFromKv, re-read per notification —
# no caching), post the two discord shapes, then restore OFF and verify the
# DISABLED wall returns. Lab-only toggle; the shipped default stays OFF.
say "## S9 DISCORD WATCH-ON (synthetic shapes; lab kv toggle of the settings switch)"
adb shell "run-as $RMPKG /system/bin/sqlite3 databases/replymate.db \"INSERT OR REPLACE INTO app_kv(key,value) VALUES('watch.discord','1')\"" 2>/dev/null
say "  watch.discord now: $(adb shell "run-as $RMPKG /system/bin/sqlite3 databases/replymate.db \"SELECT value FROM app_kv WHERE key='watch.discord'\"" 2>/dev/null | tr -d '\r')"
HWM=$(trace_hwm); post "$DCPKG" dc-channel $((NID+=1))
wait_trace "route · $DCPKG" 20 "$HWM" && say "  S9a: discord channel shape reached route with watch ON ✓ (disposition in kv)" || say "  S9a: no discord route line with watch ON ✗"
capture s9-dc-channel-on
HWM=$(trace_hwm); post "$DCPKG" dc-nohistory $((NID+=1))
wait_trace "route · $DCPKG" 20 "$HWM" && say "  S9b: discord no-history shape reached route with watch ON ✓ (FAIL/announcement disposition in kv)" || say "  S9b: no route line for no-history shape ✗"
capture s9-dc-nohistory-on
adb shell "run-as $RMPKG /system/bin/sqlite3 databases/replymate.db \"INSERT OR REPLACE INTO app_kv(key,value) VALUES('watch.discord','0')\"" 2>/dev/null
HWM=$(trace_hwm); post "$DCPKG" dc-channel $((NID+=1))
wait_trace "route · $DCPKG" 10 "$HWM" && say "  S9c: UNEXPECTED route after watch restored OFF ✗" || say "  S9c: watch OFF again → no route line (registry DISABLED fail-closed) ✓"
capture s9-dc-off-again

# end-of-run full state (unfiltered): NMS dump + complete logcat for the
# bind/enable forensics that per-scenario tails cannot reach
#_VALIDATION-BRANCH-ONLY (not part of PR #3): when the app DB never materialized
# (two consecutive silent-capture runs on the ANR-fix tree vs 4 consecutive good
# runs on 1.7.2) take the definitive thread state: kill -3 forces a full-JNI
# thread dump for the app, then pull /data/anr records + list the db dir so the
# wedge frame (if any) is evidence, not inference.
TRACE_OK=$(adb shell "run-as $RMPKG /system/bin/sqlite3 databases/replymate.db \"SELECT count(*) FROM app_kv WHERE key='listener.trace'\"" 2>/dev/null | tr -d "\r")
if [ -z "$TRACE_OK" ]; then
  say "(silent-capture diagnostic: listener.trace unreadable at end-of-run — dumping app threads)"
  RPID=$(adb_pid)
  if [ -n "$RPID" ]; then
    adb shell "kill -3 $RPID" 2>/dev/null || true
    sleep 4
    mkdir -p "$EV/_anrq"
    adb pull /data/anr "$EV/_anrq" >/dev/null 2>&1 || true
    for t in "$EV/_anrq"/anr/*; do
      [ -e "$t" ] || continue
      gunzip -c "$t" 2>/dev/null > "$EV/threads-kill3-$(basename "$t" .gz).txt" 2>/dev/null \
        || cp "$t" "$EV/threads-kill3-$(basename "$t").txt" 2>/dev/null || true
    done
    rm -rf "$EV/_anrq"
  fi
  adb shell "run-as $RMPKG ls -la databases/" > "$EV/dbdir-final.txt" 2>/dev/null || true
fi
adb shell "dumpsys notification --noredact" > "$EV/dumpsys-notification-final.txt" 2>/dev/null || true
adb shell "dumpsys dropbox --print" 2>/dev/null | grep -A30 -iE "data_app_crash|data_app_anr|system_app_crash|system_app_anr|system_server_anr" > "$EV/dropbox-errors.txt" || true
adb logcat -b all -d > "$EV/logcat-final-full.txt" 2>/dev/null || true

say ""
say "## DONE — evidence bundle in $EV"
echo "harvested: $(ls "$EV" | wc -l) files"
