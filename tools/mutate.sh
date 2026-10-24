#!/usr/bin/env bash
# Mutation testing: plant one realistic bug at a time in the core and check that test/Tests.java
# catches it. A "SURVIVED" line means the tests have a blind spot.
set -uo pipefail
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
export PATH="$JAVA_HOME/bin:$PATH"
S=src/com/jay/calmhome

# file | original text | mutated text | what the bug would do
MUTANTS=(
  "NavModel|if (col > 0) {|if (col >= 0) {|grid Left wraps to the previous row"
  "NavModel|col < cols - 1 && |col < cols && |grid Right wraps to the next row"
  "NavModel|Math.min((row + 1) * cols + gridCol, gridCount - 1)|(row + 1) * cols + gridCol|Down onto a short row goes out of range"
  "NavModel|else if (dockCount > 0) zone = DOCK;|else if (false) zone = DOCK;|Up from the grid skips the favourites"
  "NavModel|gridIdx = Math.min(gridCol, gridCount - 1);|gridIdx = 0;|grid forgets its column"
  "NavModel|if (dockCount > 0) return DOCK;|if (dockCount > 0) return GRID;|wrong starting zone"
  "NavModel|row - (Math.max(1, visibleRows) - 1)|row - visibleRows|grid scrolls a row too late"
  "NavModel|if (topCount > 0) zone = TOP;|zone = TOP;|Up lands on a missing Settings button"
  "NavModel|if (count(zone) == 0) zone = fallbackZone();|if (false) zone = fallbackZone();|focus lost when apps disappear"
  "AppOrder|if (dock.size() >= maxDock) break;|if (dock.size() > maxDock) break;|dock overflows by one"
  "AppOrder|!HIDDEN.contains(e.pkg) && ||Amazon's home shows as a tile"
  "AppOrder|a.label.compareToIgnoreCase(b.label)|a.label.compareTo(b.label)|case-sensitive sorting"
  "AppOrder|if (dock.size() >= maxDock) return null;|if (dock.size() > maxDock) return null;|a sixth favourite is accepted"
  "AppOrder| && !out.contains(p)||duplicate pins"
  "AppOrder|if (inDock) {|if (false) {|unpin never works"
  "Ambient|(m - 1380) / 30f|(m - 1380) / 3f|quiet hours snap on instead of fading"
  "Ambient|h >= 5 && h < 12|h > 5 && h < 12|5am greets 'Good night'"
  "Ambient|v = Math.max(0.55f,|v = Math.max(0.0f,|black apps cast no glow"
  "Ambient|if (t >= 1f) return b & 0xFFFFFF;|if (t >= 1f) return b;|alpha leaks into colours"
  "Ambient|if (m >= a && m < b)|if (m > a && m < b)|sky colour jumps at keyframes"
)

killed=0; survived=0; broken=0
for m in "${MUTANTS[@]}"; do
  IFS='|' read -r file from to what <<<"$m"
  work=$(mktemp -d)
  mkdir -p "$work/src" "$work/out"
  cp $S/NavModel.java $S/AppEntry.java $S/AppOrder.java $S/Ambient.java "$work/src/"
  FROM="$from" TO="$to" perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/' "$work/src/$file.java"
  if cmp -s "$work/src/$file.java" "$S/$file.java"; then
    echo "NOT APPLIED  $file: $what"; broken=$((broken+1)); rm -rf "$work"; continue
  fi
  if ! javac --release 8 -nowarn -Xlint:-options -encoding UTF-8 -d "$work/out" "$work"/src/*.java test/Tests.java >/dev/null 2>&1; then
    echo "killed (compile)  $file: $what"; killed=$((killed+1))
  elif perl -e 'alarm shift; exec @ARGV' 60 java -cp "$work/out" Tests >/dev/null 2>&1; then
    echo "SURVIVED     $file: $what"; survived=$((survived+1))
  else
    echo "killed       $file: $what"; killed=$((killed+1))
  fi
  rm -rf "$work"
done
echo "mutants: $killed killed, $survived survived, $broken not applied"
[ "$survived" -eq 0 ] && [ "$broken" -eq 0 ]
