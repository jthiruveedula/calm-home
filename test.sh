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
CORE="$P/AppEntry.java $P/AppOrder.java $P/Ambient.java $P/Scenes.java $P/SpatialNav.java $P/LaunchLog.java $P/Options.java $P/Motion.java $P/HomeLayout.java"
javac --release 8 -Xlint:all,-options -Werror -encoding UTF-8 -d build/test $CORE test/Tests.java
java -ea -cp build/test Tests
