# Calm Home

A clean, ad-free app launcher for an Amazon Fire TV Stick, written from scratch. It follows the
"Liquid Glass" design: a clock, softly moving colour scenes, glass tiles, your favourite apps,
and an All apps screen. It is a normal app you open from the Fire TV apps list (Fire OS does not
let any app replace Amazon's Home button).

- **No permissions at all.** No network, no storage, no accounts. 44 KB.
- **Reversible.** Uninstall it and nothing else on the stick has changed.
- **Pure Android, no libraries, no Gradle.** `aapt2 -> javac -> d8 -> zipalign -> apksigner`.

## Screens

| Screen | What it does |
|---|---|
| Home | Clock, date and greeting, scene control (previous/next), Favorites row, Settings and Customize buttons |
| All apps | Every installed app. Sort by Recently opened, A-Z or Pinned first. Press Menu on an app to pin or unpin it |
| Customize | Toggle Clock, Date, Greeting, Profile, Photo controls, Quiet hours. Pick scenes, motion, change interval, glass level and accent colour |

Remote: D-pad moves, Select opens, **Menu (the three-lines button)** or **holding Select** pins and
unpins, Back goes to Home (and from Home leaves the app).

## Build and test

```bash
./test.sh              # 1,594 desktop checks on the pure-Java core
./tools/mutate.sh      # plants 32 bugs one at a time; every one must be caught
./build.sh             # runs the tests, then builds build/CalmHome.apk
```

Needs JDK 17 and the Android SDK (`platforms;android-34`, `build-tools;34.0.0`):
`brew install openjdk@17 android-commandlinetools android-platform-tools`.
The signing key is created at `~/.android/calmhome.keystore`. Keep it: updates must use the same key.

Emulator checks (Android 7.1 phone image and Android 12 TV image): `tools/emu-shot.sh <name> [KEYCODE ...]`
sends keys and saves a screenshot to `build/shots/`.

## Install on the Fire Stick

1. Fire Stick: Settings > My Fire TV > Developer options > ADB debugging ON.
2. `adb connect <stick-ip>:5555`, accept the prompt on the TV.
3. `adb install -r build/CalmHome.apk`, then open **Calm Home** from the apps list.

Remove it: `adb uninstall com.jay.calmhome`.

## How it is organised

| File | Role |
|---|---|
| `Scenes`, `SpatialNav`, `LaunchLog`, `Options`, `AppOrder`, `Ambient`, `AppEntry` | Pure Java logic, fully tested without a device |
| `CalmView` | The whole UI on one Canvas, in the design's 1920x1080 units |
| `Gfx`, `Background` | Glass drawing helpers; pre-rendered scene bitmaps |
| `AppRepo`, `TileArt`, `Settings`, `MainActivity` | App discovery and icons, saved choices, the host |

## Left out on purpose

The design's Weather, Calendar, Now playing and Bluetooth Photo Drop need network, calendar or
Bluetooth permissions. Calm Home stays permission-free; they can be added later as opt-in features.
