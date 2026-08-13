#!/usr/bin/env bash
# ==================================================================
#  Print an APK's signing-certificate SHA-256, normalized:
#  uppercase hex, no separators — ready for string comparison.
#
#  Usage: scripts/apk_cert_fingerprint.sh path/to.apk
#  Exit: 0 + fingerprint on stdout · 1 = unsigned / no apksigner
#
#  Used by .github/workflows/release.yml to prove that a build carries
#  the historical ReplyMate identity (B15F2F37…6A85ED) before publish.
# ==================================================================
set -euo pipefail

APK="${1:?usage: apk_cert_fingerprint.sh <path-to.apk>}"
[ -f "$APK" ] || { echo "FATAL: not found: $APK" >&2; exit 1; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APKSIGNER=""
for base in "${REPLYMATE_SDK:-}" "${ANDROID_SDK_HOME:-}" "${ANDROID_HOME:-}" "$ROOT/.engine-sdk"; do
  [ -n "$base" ] && [ -d "$base" ] || continue
  APKSIGNER="$(find "$base/build-tools" -name apksigner -type f 2>/dev/null | head -1)"
  [ -n "$APKSIGNER" ] && break
done
[ -n "$APKSIGNER" ] || { echo "FATAL: apksigner not found — run engine/build.sh once to bootstrap the toolchain" >&2; exit 1; }

LINE="$("$APKSIGNER" verify --print-certs "$APK" | sed -n 's/.*SHA-256 digest: //p' | head -1)"
[ -n "$LINE" ] || { echo "FATAL: APK carries no signing certificate" >&2; exit 1; }
printf '%s' "$LINE" | tr -d ':' | tr 'a-f' 'A-F'
echo ""
