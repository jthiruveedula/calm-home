#!/usr/bin/env bash
# Emulator helper: ./tools/emu-shot.sh <name> [KEYCODE ...]
# Sends the key events, waits for the animation to settle, saves build/shots/<name>.png.
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-/opt/homebrew/bin/adb}"
SERIAL="${SERIAL:-emulator-5554}"
name="$1"; shift
for k in "$@"; do
  "$ADB" -s "$SERIAL" shell input keyevent "$k"
  sleep 0.45
done
sleep 0.9
mkdir -p build/shots
"$ADB" -s "$SERIAL" exec-out screencap -p > "build/shots/$name.png"
echo "build/shots/$name.png"
