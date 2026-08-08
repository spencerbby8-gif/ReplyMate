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

# P-intelligence-3: SOLO-BUILDER key injection — if (and only if) dev-secrets
# carries REPLYMATE_BUILTIN_KEY, the release binary bundles it (solo-owner demo
# asset). The values are written into the always-empty repo stub res file for
# the build and the stub is restored afterwards, so the key NEVER lands in git.
SECRETS=/home/user/dev-secrets/secrets.env
STUB="$ROOT/res/values/builtin_stub.xml"
BUILTIN_PROVIDER=""; BUILTIN_KEY=""
if [ -f "$SECRETS" ]; then
    BUILTIN_PROVIDER=$(grep '^REPLYMATE_BUILTIN_PROVIDER=' "$SECRETS" | cut -d= -f2- | tr -d '[:space:]' || true)
    BUILTIN_KEY=$(grep '^REPLYMATE_BUILTIN_KEY=' "$SECRETS" | cut -d= -f2- | tr -d '[:space:]' || true)
fi
INJECTED=0
restore_stub() { git -C "$ROOT" checkout -- "$STUB" 2>/dev/null || true; }
if [ -n "$BUILTIN_PROVIDER" ] && [ -n "$BUILTIN_KEY" ]; then
    echo "== solo-builder: injecting built-in provider '$BUILTIN_PROVIDER' (stub restored after build)"
    INJECTED=1
    trap restore_stub EXIT
    python3 - "$STUB" "$BUILTIN_PROVIDER" "$BUILTIN_KEY" <<'PYEOF'
import re, sys
path, wire, key = sys.argv[1], sys.argv[2], sys.argv[3]
src = open(path).read()
src = re.sub(r'(<string name="rm_builtin_provider_wire"[^>]*>)[^<]*(</string>)',
             lambda m: m.group(1) + wire + m.group(2), src)
src = re.sub(r'(<string name="rm_builtin_key"[^>]*>)[^<]*(</string>)',
             lambda m: m.group(1) + key + m.group(2), src)
open(path, 'w').write(src)
PYEOF
else
    echo "== solo-builder: no REPLYMATE_BUILTIN_* in dev-secrets → pure BYOK build"
fi

echo "==> 2/4 engine build (vc=$VC, name=$VN)"
VERSION_CODE="$VC" VERSION_NAME="$VN" \
    bash /home/user/apk-engine/build.sh "$ROOT" "$OUT"
if [ "$INJECTED" = "1" ]; then
    restore_stub
    trap - EXIT 2>/dev/null || true
fi
# hard proof, key or no key: the stub file may never hold a key after packaging
grep -q 'name="rm_builtin_key"[^>]*>[^<]\+' "$STUB" && {
    echo "FATAL: built-in key still present in repo stub after build — refusing to continue" >&2; exit 1; }

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
