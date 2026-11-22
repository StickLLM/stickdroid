#!/usr/bin/env bash
# Fetch the Vosk small US-English model (Apache-2.0, Alpha Cephei) into the
# app's assets so it ships inside the APK. Build-time only: the phone never
# downloads anything for speech-to-text. Checksum-pinned.
set -euo pipefail
NAME=vosk-model-small-en-us-0.15
SHA=30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498
URL=https://alphacephei.com/vosk/models/$NAME.zip
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/$NAME"
CACHE="${VOSK_CACHE:-$ROOT/.cache}"
mkdir -p "$CACHE"
if [ -f "$DEST/am/final.mdl" ]; then echo "model present: $DEST"; exit 0; fi
ZIP="$CACHE/$NAME.zip"
[ -f "$ZIP" ] || curl -fL -o "$ZIP" "$URL"
echo "$SHA  $ZIP" | sha256sum -c -
TMP="$(mktemp -d)"; unzip -q "$ZIP" -d "$TMP"
mkdir -p "$(dirname "$DEST")"; rm -rf "$DEST"; mv "$TMP/$NAME" "$DEST"; rm -rf "$TMP"
echo "model installed: $DEST"
