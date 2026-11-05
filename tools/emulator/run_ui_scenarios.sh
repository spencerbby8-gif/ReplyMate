#!/usr/bin/env bash
# ReplyMate P22 UI-emulator suite — wrapper (HARNESS ONLY, PR #2 tree).
# Runs INSIDE the GHA emulator step on a stock AOSP image (API 30 — the owner's
# OPPO A16K / Android 11 class). Installs nothing but the app-under-test +
# PingPosters; drives the REAL UI with adb + uiautomator-dump evidence;
# captures logcat + dumpsys for every verdict. Zero product-code changes.
#
#   bash tools/emulator/run_ui_scenarios.sh <evidence-dir>
set -u

EV="${1:-evidence}"
mkdir -p "$EV/ui"
SUM="$EV/ui/results.md"
RMPKG="com.replymate.app"
cd "$(dirname "$0")"   # tools/emulator — python driver + assets live here

echo "== ui-suite: tools/emulator @ $(git rev-parse --short HEAD 2>/dev/null || echo n/a)"
echo "== app-under-test ref: ${RM_APP_REF:-<branch-local>}"

# sanity: device up
adb wait-for-device
adb shell getprop sys.boot_completed | tr -d '\r' > "$EV/ui/boot_completed.txt"
echo "== device: $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r')) — $(adb shell getprop ro.build.type | tr -d '\r')"
sleep 8   # post-boot settle

adb logcat -c || true
python3 ui_driver.py "$EV"
DRV=$?
adb logcat -b all -d > "$EV/ui/logcat-ui.txt" 2>/dev/null || true
adb shell dumpsys notification --noredact > "$EV/ui/dumpsys-notification-ui.txt" 2>/dev/null || true
adb shell dumpsys activity services $RMPKG > "$EV/ui/dumpsys-services.txt" 2>/dev/null || true

echo "== ui scenarios done (driver exit=$DRV); results:"
cat "$SUM" 2>/dev/null || true
exit "$DRV"
