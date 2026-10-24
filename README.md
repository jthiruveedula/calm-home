# Calm Home

A clean, ad-free app launcher for an Amazon Fire TV Stick, written from scratch. It follows the
"Liquid Glass" design: a clock, softly moving colour scenes, glass tiles, your favourite apps,
and an All apps screen. It is a normal app you open from the Fire TV apps list (Fire OS does not
let any app replace Amazon's Home button).

- **No permissions at all.** No network, no storage, no accounts. About 80 KB.
- **Reversible.** Uninstall it and nothing else on the stick has changed.
- **Pure Android, no libraries, no Gradle.** `aapt2 -> javac -> d8 -> zipalign -> apksigner`.

## Screens

| Screen | What it does |
|---|---|
| Home | Clock, date and greeting, scene control. **Continue**: a large card for the favourite you opened last, washed in that app's colour, with when you last opened it. Favorites row, All apps, Quick settings and Customize buttons |
| Quick settings | Gear button. Glass side panel: Wi-Fi & network, Remotes & Bluetooth, Applications, My Fire TV (updates, restart), Ambient screen, Customize, All settings |
| Ambient screen | After 3 idle minutes on Home (or from Quick settings): a large, slowly drifting clock over the scenes. The first button press only wakes it |
| All apps | Every installed app. Sort by Recently opened, A-Z or Pinned first. Press Menu on an app to pin or unpin it |
| Customize | Toggle Clock, Date, Greeting, Profile, Photo controls, Quiet hours, Ambient screen. Pick scenes, motion, change interval, glass level and accent colour |

Motion: focus moves on a damped spring with a soft accent glow, the background drifts a little
toward what you focus (parallax), screens enter with a short stagger, and Select gives a small press dip.
Measured on a 2018 Fire TV Stick 4K: about 1% janky frames while navigating.

Fire OS lets a third-party app open only some settings screens (Network, Applications, My Fire TV,
Remotes & Bluetooth). Display, sound, privacy and ads need Amazon's own permission, so they are
reached through All settings.

Remote: D-pad moves, Select opens, **Menu (the three-lines button)** or **holding Select** pins and
unpins, Back goes to Home (and from Home leaves the app).

## Build and test

```bash
./test.sh              # 1,867 desktop checks on the pure-Java core
./tools/mutate.sh      # plants 32 bugs one at a time; every one must be caught
./build.sh             # runs the tests, then builds build/CalmHome.apk
```

Needs JDK 17 and the Android SDK (`platforms;android-34`, `build-tools;34.0.0`):
`brew install openjdk@17 android-commandlinetools android-platform-tools`.
The signing key is created at `~/.android/calmhome.keystore`. Keep it: updates must use the same key.

Emulator checks (Android 7.1 phone image and Android 12 TV image): `tools/emu-shot.sh <name> [KEYCODE ...]`
sends keys and saves a screenshot to `build/shots/`.

Real-device audit: `SERIAL=<stick-ip>:5555 ./tools/stick-audit.sh` presses every button on the stick
and checks what really opened (foreground activity, or Calm Home's own log for in-app actions).

Logo: `python3 tools/make-logo.py` draws the banner and icon PNGs (Fire OS cannot show vector banners).

## Install on the Fire Stick

1. Fire Stick: Settings > My Fire TV > Developer options > ADB debugging ON.
2. `adb connect <stick-ip>:5555`, accept the prompt on the TV.
3. `adb install -r build/CalmHome.apk`, then open **Calm Home** from the apps list.

Remove it: `adb uninstall com.jay.calmhome`.

## How it is organised

| File | Role |
|---|---|
| `Scenes`, `SpatialNav`, `LaunchLog`, `Options`, `AppOrder`, `Ambient`, `AppEntry`, `Motion`, `HomeLayout` | Pure Java logic, fully tested without a device |
| `CalmView` | The whole UI on one Canvas, in the design's 1920x1080 units |
| `Gfx`, `Background` | Glass drawing helpers; pre-rendered scene bitmaps |
| `AppRepo`, `TileArt`, `Settings`, `MainActivity` | App discovery and icons, saved choices, the host |

## Left out on purpose

The design's Weather, Calendar, Now playing and Bluetooth Photo Drop need network, calendar or
Bluetooth permissions. Calm Home stays permission-free; they can be added later as opt-in features.
