# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Single-module native Kotlin Android TV app (`:app`, package `com.example.customtvscreensaver`) that ships a `DreamService` screensaver: a full-screen photo slideshow with a clock / date / prayer-times overlay. See `README.md` for the user-facing feature list.

Not a git repository. There are no test source sets (`app/src/main` only) and no test dependencies — `./gradlew test` succeeds trivially. Adding tests means creating `app/src/test` (or `androidTest`) *and* adding the junit/androidx-test dependencies.

## Build & run

Requires JDK 17 (`sourceCompatibility`/`jvmTarget` are both 17). The wrapper pins Gradle 9.3.0 against AGP 8.5.2 — an unusual pairing, but it is the verified working combination; don't "fix" one without checking the other still builds.

```bash
./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/
./gradlew installDebug           # install on connected device/emulator
./gradlew assembleRelease        # R8 + resource shrinking
./gradlew lintDebug              # lint is strict, see below; lintFix applies safe fixes
./gradlew clean
```

Lint runs with `warningsAsErrors = true`, so a new warning fails the build. Two checks are disabled in `app/build.gradle.kts` because satisfying either would force a `compileSdk` bump: `OldTargetApi` (wants targetSdk > 34, which is this project's stated target) and `GradleDependency` (newer AndroidX releases that need compileSdk 35+).

### The Jordan data token

`app/build.gradle.kts` also reads an optional gitignored `secrets.properties` (see
`secrets.properties.example`) and exposes `jordanApiToken` as `BuildConfig.JORDAN_API_TOKEN`.
Absent, the build still succeeds and `JordanPrayerRepository.isConfigured` is false, so every
prayer time comes from adhan and the settings screen says so.

**Only ever put a fine-grained token scoped to `Contents: Read-only` on
`mbanifawaz/Jordan_Prayer_Times_API_Data` in that file.** Whatever goes in is compiled into the
APK and is trivially extractable, so the scope *is* the security boundary. Never an account-wide
or classic token.

### Release signing

`app/build.gradle.kts` reads an optional `keystore.properties` from the project root (gitignored; see `keystore.properties.example`). With it present the release variant is signed and lands as `app-release.apk`; without it the build still succeeds and produces `app-release-unsigned.apk`. Both paths are exercised — don't assume a missing keystore breaks the build.

### Exercising the screensaver on a device

The dream is not reachable from the launcher; only `SettingsActivity` is (`LEANBACK_LAUNCHER`).

```bash
adb shell settings put secure screensaver_components com.example.customtvscreensaver/.CustomDreamService
adb shell settings put secure screensaver_enabled 1
adb shell am start -n com.example.customtvscreensaver/.SettingsActivity
```

Actually **starting** the dream is the fiddly part, and the obvious commands mostly don't work:

- `cmd dreams start-dreaming` is the cleanest trigger but needs root, so it fails on production emulator images.
- `am start -n com.android.systemui/.Somnambulator` silently does nothing unless `DreamManagerService.canStartDreaming()` is satisfied — that means the device is awake *and* the `screensaver_activate_on_sleep` / `screensaver_activate_on_dock` settings match the actual charging/docked state (`adb shell dumpsys dreams` prints `mWhenToDream`, `mIsCharging`, `mIsDocked`).
- The `Television_4K` system image has no screensaver UI at all (`android.settings.DREAM_SETTINGS` does not resolve) and its DreamManager refuses to dream, so the dream cannot be previewed on it.

What does work: a **phone** emulator, where `am start -a android.settings.DREAM_SETTINGS` opens Screen saver and each tile has a preview (eye) button that binds the dream directly. The dream draws its own full-screen content, so the render is representative. Confirm with `adb shell dumpsys dreams | grep mCurrentDream`, and read the overlay text with `adb shell uiautomator dump /sdcard/ui.xml` rather than squinting at screenshots.

Automatic location needs a grant. `SettingsActivity` now prompts for it, or:

```bash
adb shell pm grant com.example.customtvscreensaver android.permission.ACCESS_FINE_LOCATION
```

## Architecture

Twelve small Kotlin files, no DI, no ViewModels, no observable state. The settings UI and the dream communicate only through `SharedPreferences`, which the dream re-reads on each loop tick.

### Two prayer-time sources

`LocationMode` picks the source. `AUTO` and `MANUAL` calculate with adhan; `JORDAN_OFFICIAL` uses
the published Jordan timetable for one of the areas that feed covers, **falling back to adhan
whenever the timetable cannot answer**. That fallback is not an edge case — see below — so treat
it as a normal path, not error handling.

Both sources produce a `PrayerSnapshot` through the single `buildSnapshot()` in
`PrayerSchedule.kt`, so "which prayer is next" exists in one place. `TimedPrayer` is the common
currency: a `PrayerName` plus an absolute instant.

Facts about the feed that the code is shaped around, all verified against it directly:

- **Times are 12-hour with no AM/PM marker.** `"asr": "04:24"` means 16:24. `JordanTimetable`
  therefore resolves each time to whichever of its two candidates is the earliest that is not
  before the preceding prayer, exploiting the fact that the six prayers strictly increase through
  the day. Do *not* replace this with a per-prayer AM/PM table: it would look fine against the
  currently published months and then break in winter, when Dhuhr falls at 11:xx AM. The rule was
  checked against all 930 published rows plus winter values.
- **Only a rolling window of months is published.** In August 2026 the feed had exactly
  `2026_07` and `2026_08`; September 404s. So the adhan fallback runs for part of every month.
- **`cities.json`'s first entry is `الرجاء الاختيار` ("please choose")**, a UI prompt rather than an
  area, filtered out by `JordanTimetable.parseAreas`.
- **At least one listed area has no monthly file at all** (`الشوبك والبتراء` 404s), so a valid
  selection can still have no timetable.
- Times are parsed in `Asia/Amman`, not the device zone, so the timetable stays correct on a
  device whose clock is set elsewhere.

`JordanPrayerRepository` never throws: every failure becomes `null`, meaning "use adhan". It is
cache-first on disk (`filesDir/jordan_timetable/`, keyed by `Uri.encode(area)` since area names are
Arabic), revalidates entries older than a week, prefers a stale cache over nothing, and remembers
failures for six hours so a permanently-missing month is not re-requested on every overlay tick —
which matters because the overlay ticks every minute.

`JordanAreas` maps the known area names to coordinates. It exists *only* to make the adhan
fallback accurate: the feed carries no coordinates, and defaulting every area to Amman would put
Aqaba ~300 km off. Display names always come from the feed, never from this table.

### `CustomDreamService` — the dream

Registered in the manifest with `BIND_DREAM_SERVICE` + `android.service.dream` meta-data pointing at `res/xml/sample_dream.xml`, which in turn names `SettingsActivity` as the dream's `settingsActivity`. So `SettingsActivity` serves double duty: launcher entry point *and* system screensaver settings screen. Renaming or moving it means editing the manifest and `sample_dream.xml`. It is also why the runtime location prompt lives there — a `DreamService` cannot request permissions, it can only check them (`LocationPermissions`).

`proguard-rules.pro` keeps `CustomDreamService` explicitly: it is instantiated by name from the manifest and from `Settings.Secure.screensaver_components`, which R8 cannot see.

**The dream's view tree is half code, half XML.** `buildContentView()` programmatically creates the root `FrameLayout` + full-screen `ImageView`, then inflates `res/layout/layout_screensaver_overlay.xml` as the bottom-end panel. There is no XML layout for the dream root. Panel placement lives in Kotlin, panel contents in the layout.

**Lifecycle split matters.** `onAttachedToWindow` sets dream flags and constructs `AppPreferences` + the Coil `ImageLoader`; `onDreamingStarted` calls `setContentView` and starts the loops. Both `onDreamingStopped` and `onDetachedFromWindow` call the idempotent `releaseDreamResources()` — the latter can fire without the former, hence the `::imageView.isInitialized` guards. Any new periodic work must start in `onDreamingStarted` and be cancelled in `releaseDreamResources()`, or it leaks across dream sessions.

**Three coroutine loops** on `serviceScope` (`Dispatchers.Main.immediate + SupervisorJob`), each in its own nullable `Job`:

| Job | Cadence | Work |
|---|---|---|
| `slideshowJob` | `preferences.slideshowIntervalSeconds` | show next photo, preload the one after |
| `overlayJob` | next minute boundary | rebuild clock, date, location, countdown |
| `burnInJob` | 60 s | nudge overlay `translationX/Y` by 2–5 px |

`overlayJob` sleeps to the next minute boundary (`millisUntilNextMinute()`) rather than on a fixed period, so the clock is never stale and the countdown changes exactly when the minute does. The burn-in nudge uses `translationX/Y`, not margins, to avoid a layout pass.

`updateOverlay()` is a **suspend** function because the Jordan source may touch disk or network; it stays on `Dispatchers.Main.immediate` and the repository switches to IO internally. The fused-location callback is not a coroutine context, so it re-enters through `serviceScope.launch { updateOverlay() }`.

The slideshow interval is re-read from prefs every iteration, and `activeLocation()` re-reads the location mode and city on every overlay tick, so settings changes apply to a running dream without a restart and without any listener.

### `PrayerManager` — stateless prayer math

Constructed fresh on each overlay refresh and holds no state or `Context`. It returns `@StringRes` ids and raw millis (`PrayerSnapshot`), never formatted text — all presentation (localised prayer names, countdown wording, the uppercase in `overlay_next_prayer`) is the dream's job. `snapshot()` rolls over to tomorrow's Fajr once today's Isha has passed. Madhab is hardcoded to `SHAFI`, which materially affects Asr; it is not user-configurable.

### Options, preferences, and the settings screen

`SettingsOptions.kt` holds `LocationMode`, `CalculationMethodOption`, and `City`, all implementing `LabelledOption` (a `@StringRes labelRes`). Three deliberate consequences:

1. **Spinners are built from the enums** via `Spinner.bindOptions()` in `SettingsActivity`, so entry order and labels cannot drift apart. Adding an enum entry is sufficient to add a settings option.
2. **Enums are persisted by `name`, not ordinal** (`AppPreferences.getEnum`/`putEnum`), so entries may be reordered or inserted freely. Renaming an entry resets that one preference to its default, which is the intended failure mode. Note the earlier ordinal-keyed release used different keys (`location_mode`, `calculation_method`); those are simply ignored, and installs from before this change silently fall back to defaults once.
3. `CalculationMethodOption` carries its adhan `CalculationMethod` directly, so there is no `when` mapping to keep in sync.

The **15–70 s interval bounds live only in `AppPreferences`** (`MIN_INTERVAL_SECONDS`, `MAX_INTERVAL_SECONDS`, `INTERVAL_RANGE_SECONDS`). `SettingsActivity.setupInterval()` assigns them to `SeekBar.min`/`max` (API 26, which is minSdk), so the control and the clamp cannot disagree and the SeekBar carries no hardcoded range in XML.

`LocationMode.MANUAL` reveals a `City` spinner; `AUTO` reveals a permission button whose label flips to "Open system settings" once a denial comes back with no rationale left to show; `JORDAN_OFFICIAL` reveals an area spinner. `renderLocationState()` is the single place that reconciles all of that, and `onResume()` calls it because the grant may have changed in system settings.

The Jordan area spinner is the one control **not** built from a resource or an enum: its entries are Arabic strings from the feed, so it is populated asynchronously by `loadJordanAreas()` (hence `bindStrings` alongside `bindOptions`) and is hidden entirely when the list cannot be loaded. `refreshJordanStatus()` then reports whether the selected area actually has a timetable right now, so the silent fallback is at least visible somewhere. `SettingsActivity` owns a plain `uiScope` cancelled in `onDestroy` rather than pulling in a lifecycle-ktx dependency.

`enableDpadActivation()` translates `DPAD_CENTER` / `ENTER` / `BUTTON_A` into `performClick()` on the spinners — needed because this screen uses plain AppCompat widgets rather than Leanback fragments. It is deliberately not applied to the SeekBar, which handles D-pad left/right natively.

### `PhotoRepository`

Three hardcoded Unsplash URLs, each with `R.drawable.dream_preview` as its offline fallback. `at(index)` wraps with `index.mod(size)`. Coil's `listener(onError = ...)` swaps in the local drawable, which is the only offline path for imagery — worth knowing because emulators generally have no DNS, so the gradient background is the expected result there, not a bug.

### Strings

All user-visible text is in `strings.xml`, including the overlay (`overlay_next_prayer`, `countdown_*`, `prayer_*`) and the 30 `city_*` presets. The interval label is a `<plurals>`, read via `resources.getQuantityString`. Jordan area names are the exception and deliberately so — they come from the feed verbatim, so a new area the feed adds appears with no code change.

## Testing the Jordan source without network

Both local emulators have broken DNS (`10.0.2.3` does not resolve), so the HTTP call cannot be
exercised on them. Seed the disk cache instead and the whole parse → snapshot → overlay path runs
offline:

```bash
# Area names are Arabic; the cache filename is Uri.encode(area) + "_" + yyyy_MM + ".json".
B=/data/data/com.example.customtvscreensaver/files/jordan_timetable
adb shell run-as com.example.customtvscreensaver mkdir -p $B
adb push cities.json /data/local/tmp/ && adb shell chmod 644 /data/local/tmp/cities.json
adb shell run-as com.example.customtvscreensaver cp /data/local/tmp/cities.json $B/cities.json
```

A freshly written file counts as fresh, so no fetch is attempted. To prove the *feed* rather than
adhan is driving the display, edit a row to an obviously different time and confirm the overlay
follows it — the two sources otherwise often agree to within the same minute, which makes a plain
A/B inconclusive.
