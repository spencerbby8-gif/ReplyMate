#!/usr/bin/env bash
# PingPoster builder — HARNESS ONLY. Builds the tiny poster app under a chosen
# package id (com.whatsapp / com.discord) so a CLEAN emulator routes its REAL
# framework notifications through ReplyMate's REAL listener pipeline. Shapes are
# synthetic and labelled. Signed with the engine's CURRENT key: in CI that key is
# a fresh ephemeral harness key (see emulator-listener.yml) — production signing
# secrets are never referenced or touched.
#
#   PP_PKG=com.whatsapp bash tools/pingposter/build.sh [out.apk]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SDK="${REPLYMATE_SDK:-${ANDROID_SDK_HOME:-${ANDROID_HOME:-$ROOT/.engine-sdk}}}"
SECRETS_DIR="${REPLYMATE_SECRETS:-$ROOT/secrets}"
PKG="${PP_PKG:-}"; [ -n "$PKG" ] || { echo "FATAL: PP_PKG required (com.whatsapp|com.discord)" >&2; exit 1; }
OUT="${1:-$ROOT/build/pingposter-$PKG.apk}"

AAPT2="$(find "$SDK/build-tools" -name aapt2 -type f | sort -V | tail -1)"
D8="$(find "$SDK/build-tools" -name d8 -type f | sort -V | tail -1)"
ZIPALIGN="$(find "$SDK/build-tools" -name zipalign -type f | sort -V | tail -1)"
APKSIGNER="$(find "$SDK/build-tools" -name apksigner -type f | sort -V | tail -1)"
ANDROID_JAR="$(find "$SDK/platforms" -name android.jar -type f | sort -V | tail -1)"
for t in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$ANDROID_JAR"; do
  [ -n "$t" ] || { echo "FATAL: engine SDK incomplete ($SDK)" >&2; exit 1; }
done

BUILD="$ROOT/build/pingposter-$PKG"
rm -rf "$BUILD"; mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

# stamp the package id into a copy of the manifest (source file untouched)
sed "s/__PPKG__/$PKG/g" "$ROOT/tools/pingposter/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"

"$AAPT2" link -o "$BUILD/unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$BUILD/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 1 --version-name "1.0-pingposter" \
  --debug-mode

javac -encoding UTF-8 -nowarn -cp "$ANDROID_JAR" -d "$BUILD/classes" \
  "$ROOT/tools/pingposter/src/com/pingposter/MainActivity.java"

CLASSES=$(find "$BUILD/classes" -name '*.class')
# shellcheck disable=SC2086
"$D8" --release --min-api 26 --lib "$ANDROID_JAR" --output "$BUILD/dex" $CLASSES

(cd "$BUILD/dex" && zip -0 -X "$BUILD/unsigned.apk" classes.dex >/dev/null)
"$ZIPALIGN" -p -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

KEY="$SECRETS_DIR/arena.pk8"; CERT="$SECRETS_DIR/arena.pem"
[ -s "$KEY" ] && [ -s "$CERT" ] || { echo "FATAL: harness signing key missing (build.sh engine run should have generated it)" >&2; exit 1; }
"$APKSIGNER" sign --key "$KEY" --cert "$CERT" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT" "$BUILD/aligned.apk"

echo "OK: $OUT  (pkg=$PKG, ephemeral-harness signing)"
