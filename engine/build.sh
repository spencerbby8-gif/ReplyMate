#!/usr/bin/env bash
# ==================================================================
#  ReplyMate build engine (P-release-1) — IN-REPO, self-bootstrapping.
#  Replaces the old external /home/user/apk-engine dependency that the
#  2026-08-12 takeover audit flagged as missing + unversioned.
#
#  Usage:
#    bash engine/build.sh                     # dev build of this repo
#    bash engine/build.sh <project-root> <out.apk>
#    VERSION_CODE=40 VERSION_NAME=1.5.9 bash engine/build.sh
#
#  Guarantees:
#    * no hardcoded machine paths; everything derives from this file
#    * toolchain bootstraps itself into <repo>/.engine-sdk/ (gitignored)
#      from engine/TOOLS.txt, with sha256 pins verified on EVERY fetch
#    * signing key lives in <repo>/secrets/ (gitignored); generated once
#    * APK is zipaligned, v1+v2 signed, verified, hashed
#    * built-in-key leak gate: rm_builtin_key must be EMPTY in the binary
# ==================================================================
set -euo pipefail

ENGINE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$ENGINE_DIR/.." && pwd)"

ROOT="$(cd "${1:-$REPO_ROOT}" && pwd)"
VERSION_CODE="${VERSION_CODE:-0}"
VERSION_NAME="${VERSION_NAME:-dev-$(date -u +%Y%m%d)}"
OUT="${2:-$ROOT/ReplyMate-$VERSION_NAME.apk}"

SDK="${REPLYMATE_SDK:-${ANDROID_SDK_HOME:-${ANDROID_HOME:-$REPO_ROOT/.engine-sdk}}}"
SECRETS_DIR="${REPLYMATE_SECRETS:-$REPO_ROOT/secrets}"
BUILD="$ROOT/build/engine"

MIN_SDK="${MIN_SDK:-24}"
TARGET_SDK="${TARGET_SDK:-34}"

log()   { echo "==> $*"; }
fatal() { echo "FATAL: $*" >&2; exit 1; }
need()  { command -v "$1" >/dev/null 2>&1 || fatal "missing host tool: $1"; }

# ---------------------------------------------------------------- toolchain
bootstrap() {
  need curl; need unzip; need sha256sum; need javac; need zip; need openssl
  mkdir -p "$SDK/dl"
  local dest zipname url sha zip tgt inner
  while IFS='|' read -r dest zipname url sha; do
    case "$dest" in ''|\#*) continue ;; esac
    zip="$SDK/dl/$zipname"; tgt="$SDK/$dest"
    if [ ! -d "$tgt" ]; then
      log "fetch $zipname"
      curl -fSL --retry 3 -sS -o "$zip" "$url" || fatal "download failed: $url"
      echo "$sha  $zip" | sha256sum -c - >/dev/null \
        || fatal "sha256 PIN MISMATCH for $zipname — refusing to use untrusted bytes"
      log "pin verified: $zipname"
      rm -rf "$zip.x"; mkdir -p "$zip.x"
      unzip -q -o "$zip" -d "$zip.x"
      inner="$(find "$zip.x" -maxdepth 1 -mindepth 1 -type d | head -1)"
      [ -n "$inner" ] || fatal "unexpected zip layout: $zipname"
      mkdir -p "$(dirname "$tgt")"
      mv "$inner" "$tgt"
      rm -rf "$zip.x"
    fi
  done < "$ENGINE_DIR/TOOLS.txt"

  AAPT2="$(find "$SDK/build-tools" -name aapt2 -type f | head -1)"
  D8="$(find "$SDK/build-tools" -name d8 -type f | head -1)"
  ZIPALIGN="$(find "$SDK/build-tools" -name zipalign -type f | head -1)"
  APKSIGNER="$(find "$SDK/build-tools" -name apksigner -type f | head -1)"
  ANDROID_JAR="$(find "$SDK/platforms" -name android.jar -type f | head -1)"
  [ -n "$AAPT2" ] && [ -n "$D8" ] && [ -n "$ZIPALIGN" ] && [ -n "$APKSIGNER" ] && [ -n "$ANDROID_JAR" ] \
    || fatal "incomplete toolchain under $SDK"
  chmod +x "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER"
  log "toolchain: $(dirname "$AAPT2")"
  log "platform : $(dirname "$ANDROID_JAR")"
}

# ---------------------------------------------------------------- signing key
arena_key() {
  mkdir -p "$SECRETS_DIR"; chmod 700 "$SECRETS_DIR" 2>/dev/null || true
  KEY="$SECRETS_DIR/arena.pk8"; CERT="$SECRETS_DIR/arena.pem"
  if [ ! -s "$KEY" ] || [ ! -s "$CERT" ]; then
    {
      echo "══════════════════════════════════════════════════════════════"
      echo " SIGNING: fresh local key generated at $SECRETS_DIR"
      echo " ⚠  The ≤1.5.8 release key lived in the old workspace and is LOST."
      echo "    Builds from this key will NOT update-in-place over ≤1.5.8 —"
      echo "    devices need a clean install (app data reset), unless the"
      echo "    original key is recovered into secrets/."
      echo "══════════════════════════════════════════════════════════════"
    } >&2
    openssl req -x509 -newkey rsa:2048 -keyout "$SECRETS_DIR/.k.pem" -out "$CERT" \
      -days 10950 -nodes -subj "/CN=ReplyMate,O=ReplyMate,C=NG" 2>/dev/null
    openssl pkcs8 -topk8 -inform PEM -outform DER \
      -in "$SECRETS_DIR/.k.pem" -out "$KEY" -nocrypt
    rm -f "$SECRETS_DIR/.k.pem"
    chmod 600 "$KEY" "$CERT"
  fi
}

# ---------------------------------------------------------------- pipeline
bootstrap

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex-out"

log "1/6 aapt2 compile  (vc=$VERSION_CODE name=$VERSION_NAME)"
"$AAPT2" compile --dir "$ROOT/res" -o "$BUILD/res.zip"

log "2/6 aapt2 link (+R.java)"
ASSETS=()
[ -d "$ROOT/assets" ] && ASSETS=(-A "$ROOT/assets")
"$AAPT2" link -o "$BUILD/unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  "${ASSETS[@]}" \
  --java "$BUILD/gen" \
  "$BUILD/res.zip" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

log "3/6 javac"
find "$ROOT/src" -name '*.java' > "$BUILD/sources.txt"
find "$BUILD/gen" -name '*.java' >> "$BUILD/sources.txt"
javac -encoding UTF-8 -nowarn -cp "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

log "4/6 d8 (Dalvik bytecode, min-api $MIN_SDK)"
CLASSES=$(find "$BUILD/classes" -name '*.class')
# shellcheck disable=SC2086 # deliberate word-splitting: paths contain no spaces
"$D8" --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
  --output "$BUILD/dex-out" $CLASSES

log "5/6 pack + align + sign"
(cd "$BUILD/dex-out" && zip -0 -X "$BUILD/unsigned.apk" classes.dex >/dev/null)
"$ZIPALIGN" -p -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
mkdir -p "$(dirname "$OUT")"
if [ -n "${REPLYMATE_JKS:-}" ] && [ -f "${REPLYMATE_JKS:-}" ]; then
  # Recovery path (P-release-2): if the original arena.keystore is ever
  # recovered, point REPLYMATE_JKS at it and update-continuity over ≤1.5.8
  # is restored. Pass defaults to the historic 'android'.
  log "signing with PROVIDED keystore (recovery path): $REPLYMATE_JKS"
  "$APKSIGNER" sign --ks "$REPLYMATE_JKS" \
    --ks-pass "pass:${REPLYMATE_JKS_PASS:-android}" \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$OUT" "$BUILD/aligned.apk"
else
  arena_key
  "$APKSIGNER" sign --key "$KEY" --cert "$CERT" \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$OUT" "$BUILD/aligned.apk"
fi

log "6/6 verify"
"$ZIPALIGN" -c -p 4 "$OUT" || fatal "zipalign verification failed"
"$APKSIGNER" verify "$OUT" || fatal "signature verification failed"
# built-in-key leak gate — the stub must ship empty in every binary
if "$AAPT2" dump resources "$OUT" 2>/dev/null | grep -A1 "rm_builtin" | grep -qE '\(\) ".+"'; then
  fatal "rm_builtin_* is NON-EMPTY in the APK — refusing to ship an embedded key"
fi

CERT_SHA=$("$APKSIGNER" verify --print-certs "$OUT" | sed -n 's/.*SHA-256 digest: //p' | head -1)
SHA=$(sha256sum "$OUT" | awk '{print $1}')
SIZE=$(stat -c%s "$OUT")
echo "----------------------------------------------------------------"
"$AAPT2" dump badging "$OUT" 2>/dev/null | head -2
echo "----------------------------------------------------------------"
echo "APK=$OUT"
echo "SHA256=$SHA"
echo "SIZE=$SIZE"
echo "VERSION_CODE=$VERSION_CODE"
echo "VERSION_NAME=$VERSION_NAME"
echo "CERT_SHA256=$CERT_SHA"
echo "BUILD OK"
