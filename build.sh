#!/usr/bin/env bash
# Builds Calm Home without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Output: build/CalmHome.apk. Runs the desktop tests first and refuses to build if any fail.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
export PATH="$JAVA_HOME/bin:$PATH"
KS="${KEYSTORE:-$HOME/.android/calmhome.keystore}"
VERSION_CODE="${VERSION_CODE:-$(git rev-list --count HEAD 2>/dev/null || echo 1)}"
VERSION_NAME="${VERSION_NAME:-0.1.$VERSION_CODE}"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$JAR"; do
  [ -e "$f" ] || { echo "missing $f (install: sdkmanager \"platforms;android-34\" \"build-tools;34.0.0\")" >&2; exit 1; }
done

[ "${SKIP_TESTS:-}" = 1 ] || ./test.sh

rm -rf build/res build/gen build/classes build/dex build/*.apk
mkdir -p build/res build/gen build/classes build/dex

"$BT/aapt2" compile --dir res -o build/res/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 22 --target-sdk-version 25 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java build/gen build/res/res.zip

# -Werror: any compiler warning fails the build.
javac --release 8 -Xlint:all,-options -Werror -encoding UTF-8 -classpath "$JAR" -d build/classes \
  $(find src build/gen -name '*.java')

"$BT/d8" --release --min-api 22 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass calmhome -keypass calmhome -alias calm \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Calm Home" >/dev/null 2>&1
  echo "created signing key $KS (keep it: updates must be signed with the same key)"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:calmhome --key-pass pass:calmhome \
  --out build/CalmHome.apk build/aligned.apk
"$BT/apksigner" verify build/CalmHome.apk
echo "built build/CalmHome.apk v$VERSION_NAME ($(du -k build/CalmHome.apk | cut -f1) KB)"
