#!/usr/bin/env bash
# Presses every Calm Home control on a real Fire TV and checks what it really did:
# the foreground activity for launches and settings, Calm Home's own log for in-app actions.
# Usage: SERIAL=192.168.1.146:5555 ./tools/stick-audit.sh
set -uo pipefail
ADB="${ADB:-/opt/homebrew/bin/adb}"
S="${SERIAL:-192.168.1.146:5555}"
APP=com.jay.calmhome
pass=0; fail=0

k() { for key in "$@"; do "$ADB" -s "$S" shell input keyevent "$key"; sleep 0.55; done; }
fg() { "$ADB" -s "$S" shell dumpsys window | grep -m1 mCurrentFocus; }
logs() { "$ADB" -s "$S" logcat -d -s CalmHome:I; }
reset() {
  "$ADB" -s "$S" shell am force-stop $APP
  "$ADB" -s "$S" logcat -c
  "$ADB" -s "$S" shell am start -n $APP/.MainActivity >/dev/null
  sleep 3.5
}
ok() { if [ "$1" = 0 ]; then pass=$((pass + 1)); echo "PASS  $2"; else fail=$((fail + 1)); echo "FAIL  $2  [$3]"; fi; }
expect_fg() { sleep "${3:-4}"; local f; f=$(fg); echo "$f" | grep -q "$1"; ok $? "$2" "$f"; }
expect_log() { sleep "${3:-0.8}"; logs | grep -q "$1"; ok $? "$2" "$(logs | tail -2 | tr '\n' ' ')"; }
stop() { "$ADB" -s "$S" shell am force-stop "$1"; }

echo "== Home: hero and every favourite opens the app it shows"
for n in 0 1 2 3; do
  reset
  for ((d = 0; d < n; d++)); do k KEYCODE_DPAD_RIGHT; done
  k KEYCODE_DPAD_CENTER; sleep 4
  pkg=$(logs | grep -oE "select app:[A-Za-z0-9_.]+" | tail -1 | cut -d: -f2)
  f=$(fg); echo "$f" | grep -q "${pkg:-none}"; ok $? "Tile $n opens ${pkg:-?}" "$f"
  [ -n "$pkg" ] && stop "$pkg"
done

echo "== Home: All apps, photo arrows"
reset; k KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER
expect_log "screen apps" "All apps tile opens All apps"
k KEYCODE_BACK; expect_log "screen home" "Back returns Home"
reset; k KEYCODE_DPAD_UP KEYCODE_DPAD_CENTER; expect_log "select scene-" "Photo arrow changes the scene"

echo "== Quick settings rows"
names=("Wi-Fi" "Remotes & Bluetooth" "Applications" "My Fire TV" "Ambient screen" "Photo Drop" "Customize" "All settings")
want=("NetworkActivity" "ControllersAndBluetoothActivity" "ApplicationsActivity" "DeviceActivity" "#ambient" "#drop" "#custom" "#all")
for i in 0 1 2 3 4 5 6 7; do
  reset; k KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_CENTER
  [ $i = 0 ] && expect_log "screen quick" "Gear opens Quick settings"
  for ((d = 0; d < i; d++)); do k KEYCODE_DPAD_DOWN; done
  k KEYCODE_DPAD_CENTER
  case "${want[$i]}" in
    "#ambient")
      sleep 2.5
      k KEYCODE_DPAD_CENTER
      expect_log "ambient off (key)" "Ambient row shows the clock; a key wakes it"
      expect_fg $APP "Waking key does not launch anything" 1.5 ;;
    "#custom") expect_log "screen custom" "Customize row opens Customize" ;;
    "#drop")
      expect_log "screen drop" "Photo Drop row opens Photo Drop"
      sleep 1; "$ADB" -s "$S" exec-out screencap -p > /tmp/calm-drop.png
      url=$(zbarimg -q --raw /tmp/calm-drop.png 2>/dev/null)
      echo "$url" | grep -qE '^http://[0-9.]+:[0-9]+/[0-9]{6}$'; ok $? "QR code on the TV decodes to the upload link" "$url"
      code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$url"); [ "$code" = 200 ]; ok $? "Upload page loads from the QR link" "$code"
      bad=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "${url%/*}/000000"); [ "$bad" = 403 ]; ok $? "A wrong code is refused" "$bad"
      junk=$(curl -s -m 5 -o /dev/null -w '%{http_code}' -X POST --data-binary 'not a photo at all' "$url/upload"); [ "$junk" = 415 ]; ok $? "Non-images are refused" "$junk"
      k KEYCODE_BACK; sleep 1
      gone=$(curl -s -m 3 -o /dev/null -w '%{http_code}' "$url" || true); [ "$gone" = 000 ]; ok $? "Server stops when you leave Photo Drop" "$gone" ;;
    "#all") expect_fg "com.amazon.tv.launcher" "All settings opens Fire TV Settings (inside Amazon's home)"
      reset ;;
    *)
      expect_fg "${want[$i]}" "${names[$i]} row opens ${want[$i]}"
      k KEYCODE_BACK; expect_fg $APP "Back from ${names[$i]} returns to Calm Home" 2.5 ;;
  esac
done

echo "== Customize"
reset; k KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER
expect_log "screen custom" "Sliders button opens Customize"
k KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
c=$(logs | grep -c "select tg:"); [ "$c" -ge 2 ]; ok $? "Toggle flips and flips back" "count=$c"
k KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
c=$(logs | grep -cE "select (sc|tg):"); [ "$c" -ge 4 ]; ok $? "Scene and toggle controls respond" "count=$c"
k KEYCODE_BACK; expect_log "screen home" "Back from Customize returns Home"

echo "== All apps: sort chips, Home button, pin and unpin"
reset; k KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER
k KEYCODE_DPAD_UP KEYCODE_DPAD_CENTER; expect_log "select sort:" "Sort chip responds"
for ((d = 0; d < 6; d++)); do k KEYCODE_DPAD_DOWN; done
for ((d = 0; d < 6; d++)); do k KEYCODE_DPAD_RIGHT; done
k KEYCODE_MENU; sleep 1; k KEYCODE_MENU
c=$(logs | grep -c "menu app:"); [ "$c" -ge 2 ]; ok $? "Menu pins and unpins an app" "$(logs | grep 'menu app:' | tail -1)"
for ((d = 0; d < 8; d++)); do k KEYCODE_DPAD_UP; done
for ((d = 0; d < 6; d++)); do k KEYCODE_DPAD_RIGHT; done
k KEYCODE_DPAD_CENTER; expect_log "select home" "Home button returns Home"

echo "== Crashes"
c=$("$ADB" -s "$S" logcat -d -b crash | grep -c "Process: $APP"); [ "$c" = 0 ]; ok $? "No Calm Home crashes" "count=$c"
reset
echo "RESULT: $pass passed, $fail failed"
[ "$fail" = 0 ]
