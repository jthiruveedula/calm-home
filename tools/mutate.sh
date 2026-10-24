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
CORE="AppEntry AppOrder Ambient Scenes SpatialNav LaunchLog Options"

# file ^ original text ^ mutated text ^ what the bug would do
MUTANTS=(
  "SpatialNav^if (!vertical && !beam) continue;^^Left/Right jump to other rows"
  "SpatialNav^float score = 13f * major * major + minor * minor;^float score = minor * minor;^distance along the move is ignored"
  "SpatialNav^overlap(prefX - half, prefX + half, c.l, c.r)^overlap(s.l, s.r, c.l, c.r)^Up/Down forget the remembered column"
  "SpatialNav^(beam && !bestBeam)^(false)^straight-ahead no longer beats a closer diagonal"
  "SpatialNav^return Math.min(a1, b1) - Math.max(a0, b0) > 1f;^return true;^everything counts as overlapping"
  "SpatialNav^case DOWN: return c.cy() > s.cy() && c.t > s.t;^case DOWN: return c.cy() > s.cy();^Down accepts tiles that are not below"
  "SpatialNav^if (top + c < marginTop) c = marginTop - top;^^never scrolls back up to a focused tile"
  "SpatialNav^return Math.max(min, Math.min(0f, c));^return c;^scrolls past the content"
  "Scenes^if (from == to) return scene == to ? 1f : 0f;^if (from == to) return 1f;^all scenes show at once when idle"
  "Scenes^float e = ease(elapsedMs / (float) FADE_MS);^float e = ease(elapsedMs / 100f);^cross-fade far too fast"
  "Scenes^out[0] = 1.05f + 0.17f * e;^out[0] = 1.05f + 0.07f * e;^Ken Burns zooms too little"
  "Scenes^if (i < enabled.length && enabled[i]) return i;^return i;^disabled scenes still show"
  "Scenes^float ph = (float) (cyc < 26.0 ? cyc / 26.0 : (52.0 - cyc) / 26.0);^float ph = (float) (cyc / 26.0);^Ken Burns jumps back instead of easing"
  "Scenes^out[0] = x0 + b.l * cw;^out[0] = b.l * w;^blobs ignore the 120% box"
  "LaunchLog^list.remove(pkg);^^relaunching an app duplicates it"
  "LaunchLog^if (out.size() >= MAX) break;^if (out.size() > MAX) break;^history grows by one"
  "Options^if (out[i] && on <= 1) return out;^if (false) return out;^the last scene can be turned off"
  "Options^if (!any && n > 0) m[0] = true;^^an all-off mask hides every scene"
  "Options^return v < 0 || v >= n ? 0 : v;^return v;^bad saved values crash the UI"
  "AppOrder^if (ra != null) return -1;^if (ra != null) return 1;^recent apps sort last"
  "AppOrder^if (ia >= 0) return -1;^if (ia >= 0) return 1;^pinned apps sort last"
  "AppOrder^if (dock.size() >= maxDock) break;^if (dock.size() > maxDock) break;^dock overflows by one"
  "AppOrder^if (e != null && !HIDDEN.contains(e.pkg) && seen.add(e.pkg)) list.add(e);^if (e != null && seen.add(e.pkg)) list.add(e);^Amazon's home shows in All apps"
  "AppOrder^a.label.compareToIgnoreCase(b.label)^a.label.compareTo(b.label)^case-sensitive sorting"
  "AppOrder^if (dock.size() >= maxDock) return null;^if (dock.size() > maxDock) return null;^an eighth favourite is accepted"
  "AppOrder^ && !out.contains(p)^^duplicate pins"
  "AppOrder^if (inDock) {^if (false) {^unpin never works"
  "Ambient^if (t >= 1f) return b & 0xFFFFFF;^if (t >= 1f) return b;^alpha leaks into colours"
  "Ambient^(m - 1380) / 30f^(m - 1380) / 3f^quiet hours snap on instead of fading"
  "Ambient^if (h < 12) return \"Good morning\";^if (h <= 12) return \"Good morning\";^noon greets 'Good morning'"
  "Ambient^if (h < 18) return \"Good afternoon\";^if (h <= 18) return \"Good afternoon\";^6pm greets 'Good afternoon'"
  "Ambient^return QUIET_MAX * (1f - (m - 360) / 30f);^return QUIET_MAX;^dimming ends abruptly at 6:30"
)

killed=0; survived=0; broken=0
for m in "${MUTANTS[@]}"; do
  IFS='^' read -r file from to what <<<"$m"
  work=$(mktemp -d)
  mkdir -p "$work/src" "$work/out"
  for f in $CORE; do cp "$S/$f.java" "$work/src/"; done
  FROM="$from" TO="$to" perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/' "$work/src/$file.java"
  if cmp -s "$work/src/$file.java" "$S/$file.java"; then
    echo "NOT APPLIED  $file: $what"; broken=$((broken+1)); rm -rf "$work"; continue
  fi
  if ! javac --release 8 -nowarn -Xlint:-options -encoding UTF-8 -d "$work/out" "$work"/src/*.java test/Tests.java >/dev/null 2>&1; then
    echo "killed (compile)  $file: $what"; killed=$((killed+1))
  elif perl -e 'alarm shift; exec @ARGV' 90 java -cp "$work/out" Tests >/dev/null 2>&1; then
    echo "SURVIVED     $file: $what"; survived=$((survived+1))
  else
    echo "killed       $file: $what"; killed=$((killed+1))
  fi
  rm -rf "$work"
done
echo "mutants: $killed killed, $survived survived, $broken not applied"
[ "$survived" -eq 0 ] && [ "$broken" -eq 0 ]
