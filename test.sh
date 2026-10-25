#!/usr/bin/env bash
# Compile and run the desktop tests for the pure-Java core (no Android SDK needed).
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
export PATH="$JAVA_HOME/bin:$PATH"
rm -rf build/test && mkdir -p build/test
P=src/com/jay/calmhome
CORE="$P/AppEntry.java $P/AppOrder.java $P/Ambient.java $P/Scenes.java $P/SpatialNav.java $P/LaunchLog.java $P/Options.java $P/Motion.java $P/HomeLayout.java $P/DropHttp.java $P/QrCode.java"
javac --release 8 -Xlint:all,-options -Werror -encoding UTF-8 -d build/test $CORE test/Tests.java
java -ea -cp build/test Tests
# QR codes must decode with an independent reader (skipped when zbarimg is not installed).
if command -v zbarimg >/dev/null 2>&1; then
  javac --release 8 -Xlint:all,-options -Werror -encoding UTF-8 -cp build/test -d build/test test/QrCheck.java
  java -cp build/test QrCheck >/dev/null
  bad=0
  while IFS=$'\t' read -r f v m want; do
    got=$(zbarimg -q --raw "build/qr/$f" 2>/dev/null || true)
    [ "$got" = "$want" ] || { echo "QR FAIL $f ($v $m): got [$got]"; bad=1; }
  done < build/qr/expected.txt
  [ $bad = 0 ] && echo "QR: $(wc -l < build/qr/expected.txt | tr -d ' ') codes decoded by zbarimg" || exit 1
fi
