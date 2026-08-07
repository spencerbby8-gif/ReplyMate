#!/usr/bin/env bash
# Live provider-audit probe: runs ReplyMate's REAL provider classes against the REAL
# endpoint and prints the full transcript (URL, method, redacted headers, body,
# status, raw response, and ReplyMate's mapping). Keys are never printed (last 4 only).
#
# Usage:  bash scripts/live_probe.sh <wireType> <baseUrl> <model> <apiKey|-> [badModelProbe]
# Example (dummy key — verifies error mapping against the live server):
#   bash scripts/live_probe.sh gemini https://generativelanguage.googleapis.com gemini-2.5-flash AIzaSyDUMMY badModelProbe
set -euo pipefail
ROOT=/home/user/ReplyMate
OUT="$ROOT/build/probe-classes"
mkdir -p "$OUT"
CP_SRC=$(find "$ROOT/src/com/replymate/core" "$ROOT/src/com/replymate/provider" -name '*.java')
javac -encoding UTF-8 -nowarn -d "$OUT" $CP_SRC "$ROOT/tools/probe/LiveProbe.java"
java -Dfile.encoding=UTF-8 -cp "$OUT" LiveProbe "$@"
