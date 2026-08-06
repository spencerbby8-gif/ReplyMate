#!/usr/bin/env bash
# ==================================================================
#  ReplyMate release pipeline (BLUEPRINT §10)
#  tests → engine build → verify → sha256 → archive + RELEASES.md
#  Usage: bash scripts/release.sh <versionCode> <versionName> [notes]
#         e.g. bash scripts/release.sh 1 0.0.1 "P0 foundations"
# ==================================================================
set -euo pipefail

VC="${1:?usage: release.sh <versionCode> <versionName> [notes]}"
VN="${2:?usage: release.sh <versionCode> <versionName> [notes]}"
NOTES="${3:-}"

ROOT=/home/user/ReplyMate
OUT="ReplyMate-$VN.apk"
REL="$ROOT/releases"
mkdir -p "$REL"

echo "==> 1/4 test gate"
bash "$ROOT/scripts/run_tests.sh"

echo "==> 2/4 engine build (vc=$VC, name=$VN)"
VERSION_CODE="$VC" VERSION_NAME="$VN" \
    bash /home/user/apk-engine/build.sh "$ROOT" "$OUT"

echo "==> 3/4 archive"
mv "/home/user/$OUT" "$REL/$OUT"
SHA=$(sha256sum "$REL/$OUT" | awk '{print $1}')
SIZE=$(stat -c%s "$REL/$OUT")

if [ ! -f "$REL/RELEASES.md" ]; then
    {
        echo "# ReplyMate release log"
        echo ""
        echo "| date (UTC) | versionName | versionCode | sha256 | size | notes |"
        echo "|---|---|---|---|---|---|"
    } > "$REL/RELEASES.md"
fi
echo "| $(date -u +%F) | $VN | $VC | \`$SHA\` | $SIZE | $NOTES |" >> "$REL/RELEASES.md"

echo "==> 4/4 done"
echo "RELEASED: $REL/$OUT"
echo "sha256  : $SHA"
echo "Install on-device will update-in-place (arena.keystore signing, never regenerate)."
