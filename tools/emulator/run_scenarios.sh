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
  if ! nls_get | grep -q "$RMPKG"; then
    local cmd_out
    cmd_out=$(adb shell cmd notification allow_listener "$NLS_COMPONENT" 2>&1 | tr -d '\r' | head -2)
    sleep 2
    say "    enable[2] cmd notification allow_listener (out=${cmd_out:-ok}) → get: '$(nls_get)'"
  fi
  nls_get | grep -q "$RMPKG" \
    && say "    enabled_notification_listeners PERSISTED ✓" \
    || say "    enabled_notification_listeners STILL NOT SET ✗"
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
  adb logcat -d -s PP RM-NLS RM-App 2>/dev/null > "$EV/logcat-$tag.txt"
  # bounded UNFILTERED logcat — the NMS enable/bind/reject reasons live on
  # system tags (NotificationManager/NotificationService), invisible in the
  # filtered slice; run 41341288412 could not explain "enabled (0)" without it
  adb logcat -b all -d -t 3000 2>/dev/null > "$EV/logcat-full-$tag.txt" || true
  grep -E "PP |RM-NLS" "$EV/logcat-$tag.txt" | tail -8 | sed 's/^/    logcat: /' | tee -a "$SUM"
  say "    trace snapshot: $EV/kv-$tag.txt"
  line ""
}

wait_trace() {  # $1=regex $2=timeout-s ; live kv poll (WAL-aware, light)
  local pat="$1" max="$2" i=0
  while [ $i -lt "$max" ]; do
    kv_snapshot /tmp/.harness-kv.db light
    if python3 -c "
import sqlite3,json,sys
try:
    con=sqlite3.connect('/tmp/.harness-kv.db')
    r=con.execute(\"SELECT value FROM app_kv WHERE key='listener.trace'\").fetchone()
    if r and r[0]:
        for o in json.loads(r[0]):
            import re
            if re.search('$1', o.get('line','')): sys.exit(0)
except Exception: pass
sys.exit(1)"; then return 0; fi
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
say "## S0 baseline (launch + access + first bind)"
adb shell pm grant "$RMPKG" android.permission.POST_NOTIFICATIONS 2>&1 | tee -a "$SUM" >/dev/null
adb shell pm grant "$WAPKG" android.permission.POST_NOTIFICATIONS 2>&1 | tee -a "$SUM" >/dev/null
adb shell pm grant "$DCPKG" android.permission.POST_NOTIFICATIONS 2>&1 | tee -a "$SUM" >/dev/null
grant_nls
adb shell monkey -p "$RMPKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
i=0; while [ $i -lt 20 ] && [ -z "$(adb_pid)" ]; do sleep 1; i=$((i+1)); done
say "  pid0=$(adb_pid || echo none)"
wait_trace "bound=true (onListenerConnected)" 30 \
  && say "  S0: first bind observed (bound=true) ✓" \
  || say "  S0: no bound=true within 30s of enable+launch ✗"
capture s0-baseline

# ---------------- S1 foreground ----------------
say "## S1 FOREGROUND (ReplyMate visibly open)"
adb shell monkey -p "$RMPKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 2
post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 30 && say "  S1: ingest boundary reached ✓" || say "  S1: NO ingest line within 30s ✗"
capture s1-foreground

# ---------------- S2 background ----------------
say "## S2 BACKGROUND (Home only — no swipe-kill)"
adb shell input keyevent KEYCODE_HOME; sleep 30
post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 30 && say "  S2: ingest boundary reached ✓" || say "  S2: NO ingest line within 30s ✗"
capture s2-background

# ---------------- S3 screen-off ----------------
say "## S3 SCREEN-OFF (sleep 60s → post → wake→read)"
adb shell input keyevent KEYCODE_SLEEP; sleep 60
post "$WAPKG" wa-11 $((NID+=1))
sleep 8
adb shell input keyevent KEYCODE_WAKEUP; sleep 3
wait_trace "ingest · $WAPKG" 20 && say "  S3: ingest boundary reached ✓" || say "  S3: NO ingest line ✗"
capture s3-screenoff

# ---------------- S4 am kill (trim) ----------------
say "## S4 PROCESS KILL — am kill (trim class; NOT force-stop)"
ADB_BEFORE=$(adb_pid); say "  pid before kill: $ADB_BEFORE"
adb shell am kill "$RMPKG"; sleep 3; say "  pid after kill: $(adb_pid || echo dead)"
post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 45 && say "  S4: capture resumed after am kill ✓" || say "  S4: NO capture after am kill ✗"
say "  pid now: $(adb_pid || echo none)"
capture s4-amkill

# ---------------- S5 force-stop ----------------
say "## S5 FORCE-STOP (hard stop; separate control — NOT a kill simulation)"
adb shell am force-stop "$RMPKG"; sleep 2; say "  pid after force-stop: $(adb_pid || echo dead)"
post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 20 && say "  S5: UNEXPECTED capture while force-stopped ✗" || say "  S5: no capture while force-stopped (honest stock-Android semantics) ✓"
capture s5-forcestop
say "  relaunching…"
adb shell monkey -p "$RMPKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 5
post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 30 && say "  S5b: capture after relaunch ✓" || say "  S5b: STILL no capture after relaunch ✗"
capture s5-relaunch

# ---------------- S6 listener toggle (disconnect while alive) ----------------
say "## S6 LISTENER TOGGLE (access OFF/ON — exercises onListenerDisconnected + requestRebind recovery)"
adb logcat -c
revoke_nls
sleep 4
post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 10 && say "  S6a: UNEXPECTED capture while disallowed ✗" || say "  S6a: no capture while disallowed ✓ (expected)"
capture s6-disallowed
grant_nls
say "  access re-enabled; watching for bound=true via requestRebind within 40s…"
wait_trace "bound=true" 40 && say "  S6b: LF-L1 reconnect sequence OBSERVED ✓" || say "  S6b: no bound=true within 40s after re-enable ✗"
post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 30 && say "  S6c: post-reconnect capture ✓" || say "  S6c: no post-reconnect capture ✗"
capture s6-reenabled

# ---------------- S7 doze / standby ----------------
say "## S7 DOZE + STANDBY RESTRICTED (temporary device states)"
# force-idle refuses on a charging device ("not enabled"; run 41336986254 hit
# exactly this) — unplug first, restore at the end.
adb shell dumpsys battery unplug >/dev/null 2>&1
adb shell input keyevent KEYCODE_SLEEP; sleep 3
adb shell dumpsys deviceidle force-idle; sleep 3
post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 25 && say "  S7a: capture under forced Doze ✓" || say "  S7a: no capture under forced Doze ✗"
adb shell dumpsys deviceidle unforce >/dev/null 2>&1
adb shell dumpsys battery reset >/dev/null 2>&1
adb shell input keyevent KEYCODE_WAKEUP; sleep 3
adb shell am set-standby-bucket "$RMPKG" restricted 2>/dev/null; sleep 3
post "$WAPKG" wa-11 $((NID+=1))
wait_trace "ingest · $WAPKG" 25 && say "  S7b: capture in RESTRICTED standby ✓" || say "  S7b: no capture in RESTRICTED standby ✗"
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

# end-of-run full state (unfiltered): NMS dump + complete logcat for the
# bind/enable forensics that per-scenario tails cannot reach
adb shell "dumpsys notification --noredact" > "$EV/dumpsys-notification-final.txt" 2>/dev/null || true
adb logcat -b all -d > "$EV/logcat-final-full.txt" 2>/dev/null || true

say ""
say "## DONE — evidence bundle in $EV"
echo "harvested: $(ls "$EV" | wc -l) files"
