# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Single-module native Kotlin Android TV app (`:app`, namespace `com.example.customtvscreensaver`, applicationId `com.mbf.jordan_prayer_times_app` — shared with the Flutter phone app and the watch app, so never change it; debug builds add `.debug`) that ships a `DreamService` screensaver: a full-screen photo slideshow with a clock / date / prayer-times overlay. See `README.md` for the user-facing feature list.

A git repository (`master`). There are no test source sets (`app/src/main` only) and no test dependencies — `./gradlew test` succeeds trivially. Adding tests means creating `app/src/test` (or `androidTest`) *and* adding the junit/androidx-test dependencies.

## Build & run

Requires JDK 17 (`sourceCompatibility`/`jvmTarget` are both 17). The wrapper pins Gradle 9.3.0 against AGP 8.5.2 — an unusual pairing, but it is the verified working combination; don't "fix" one without checking the other still builds.

```bash
./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/
./gradlew installDebug           # install on connected device/emulator
./gradlew assembleRelease        # R8 + resource shrinking
./gradlew lintDebug              # lint is strict, see below; lintFix applies safe fixes
./gradlew clean
```

Lint runs with `warningsAsErrors = true`, so a new warning fails the build. Only `GradleDependency` (newer AndroidX releases) is disabled in `app/build.gradle.kts`.

**compileSdk/targetSdk are 36 because Google Play requires it** for new releases (a 34 upload was rejected). Targeting 36 makes Arabic always use the taller "elegant" line metrics — `android:elegantTextHeight` is ignored at that target — so the home screen's vertical spacing is deliberately lean; check an Arabic 1080p render after any layout change there.

The manifest declares `android.software.leanback` **required**: Play's Android TV track rejects bundles that do not.

### No data token

The data repo `mbanifawaz/Jordan_Prayer_Times_API_Data` is **public** and the app reads it with no
credential: files from `raw.githubusercontent.com/<repo>/main/…` (no rate limit), and only the
`monthly/` directory listing from the REST API, unauthenticated (60 requests/hour per device). A
token used to be compiled into `BuildConfig`; when a build containing it was committed and pushed,
GitHub secret scanning revoked it and every installed phone app lost its data feed. **Never put a
token back into any app build**, and never commit an `.aab` or `.apk` (both are gitignored). The
scraper (`Jordan_Prayer_Times_API`) has its own write token on its server only.

### Release signing

`app/build.gradle.kts` reads an optional `keystore.properties` from the project root (gitignored; see `keystore.properties.example`). With it present the release variant is signed and lands as `app-release.apk`; without it the build still succeeds and produces `app-release-unsigned.apk`. Both paths are exercised — don't assume a missing keystore breaks the build.

### Exercising the screensaver on a device

The dream is not reachable from the launcher; `PrayerTimesActivity` is (`LEANBACK_LAUNCHER`), and opens `SettingsActivity`.

```bash
adb shell settings put secure screensaver_components com.mbf.jordan_prayer_times_app.debug/com.example.customtvscreensaver.CustomDreamService
adb shell settings put secure screensaver_enabled 1
adb shell am start -n com.mbf.jordan_prayer_times_app.debug/com.example.customtvscreensaver.PrayerTimesActivity
```

Actually **starting** the dream is the fiddly part, and the obvious commands mostly don't work:

- `cmd dreams start-dreaming` is the cleanest trigger but needs root, so it fails on production emulator images.
- `am start -n com.android.systemui/.Somnambulator` silently does nothing unless `DreamManagerService.canStartDreaming()` is satisfied — that means the device is awake *and* the `screensaver_activate_on_sleep` / `screensaver_activate_on_dock` settings match the actual charging/docked state (`adb shell dumpsys dreams` prints `mWhenToDream`, `mIsCharging`, `mIsDocked`).
- The `Television_4K` system image has no screensaver UI at all (`android.settings.DREAM_SETTINGS` does not resolve) and its DreamManager refuses to dream, so the dream cannot be previewed on it.

What does work: a **phone** emulator, where `am start -a android.settings.DREAM_SETTINGS` opens Screen saver and each tile has a preview (eye) button that binds the dream directly. The dream draws its own full-screen content, so the render is representative. Confirm with `adb shell dumpsys dreams | grep mCurrentDream`, and read the overlay text with `adb shell uiautomator dump /sdcard/ui.xml` rather than squinting at screenshots.

## Architecture

Twelve small Kotlin files, no DI, no ViewModels, no observable state. The settings UI and the dream communicate only through `SharedPreferences`, which the dream re-reads on each loop tick.

### One prayer-time source: the official Jordan feed

Every time shown comes from `mbanifawaz/Jordan_Prayer_Times_API_Data` (Ministry of Awqaf data).
**Nothing is calculated on the device** — adhan was removed on purpose. When the feed cannot
answer (offline with no cache, month not published, area has no file) the snapshot is `null` and both screens
show "not available" with `--:--`. Don't reintroduce a calculated fallback without asking.

`buildSnapshot()` in `PrayerSchedule.kt` is the single place that decides "which prayer is next".
`TimedPrayer` is the common currency: a `PrayerName` plus an absolute instant.

Facts about the feed that the code is shaped around, all verified against it directly:

- **Times are 12-hour with no AM/PM marker.** `"asr": "04:24"` means 16:24. `JordanTimetable`
  therefore resolves each time to whichever of its two candidates is the earliest that is not
  before the preceding prayer, exploiting the fact that the six prayers strictly increase through
  the day. Do *not* replace this with a per-prayer AM/PM table: it would look fine against the
  currently published months and then break in winter, when Dhuhr falls at 11:xx AM. The rule was
  checked against all 930 published rows plus winter values.
- **Only a rolling window of months is published.** In August 2026 the feed had exactly
  `2026_07` and `2026_08`; September 404s (by late September it had `2026_08`–`2026_12`).
  `prefetch()` stores every published month on disk, past months included, so the TV keeps
  working offline and across month ends and the calendar can look back, but it cannot help past
  the last published month.
- **`cities.json`'s first entry is `الرجاء الاختيار` ("please choose")**, a UI prompt rather than an
  area. It is kept in the list and is the default until the user picks, matching the phone app;
  while selected, `PrayerReading.needsCity` is true, nothing is fetched and the screens ask for a city.
- **At least one listed area has no monthly file at all** (`الشوبك والبتراء` 404s), so a valid
  selection can still have no timetable.
- Times are parsed in `Asia/Amman`, not the device zone, so the timetable stays correct on a
  device whose clock is set elsewhere.

`JordanPrayerRepository` never throws: every failure becomes `null`, meaning "no times". It is
cache-first on disk (`filesDir/jordan_timetable/`, keyed by `Uri.encode(area)` since area names are
Arabic), revalidates entries older than a week, prefers a stale cache over nothing, and remembers
failures for six hours so a permanently-missing month is not re-requested on every overlay tick —
which matters because the overlay ticks every minute.

`prefetch(area, now)` lists the feed's `monthly/` directory (one request, JSON accept header since
directories have no raw form) and downloads every `yyyy_MM_<area>.json` not already cached, past
months included as the phone app does, current month first. The launcher screen runs it on resume and after a city change; the
dream runs it once per session.

There is **no built-in city list**: the city picker shows `cities.json` verbatim (cached on disk
after the first fetch). Until it has loaded once, the picker is empty and the screens say so.

### `CustomDreamService` — the dream

Registered in the manifest with `BIND_DREAM_SERVICE` + `android.service.dream` meta-data pointing at `res/xml/sample_dream.xml`, which in turn names `SettingsActivity` as the dream's `settingsActivity`. So `SettingsActivity` serves double duty: opened from `PrayerTimesActivity` *and* as the system screensaver settings screen, which is why it stays `exported`. Renaming or moving it means editing the manifest and `sample_dream.xml`.

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

`updateOverlay()` is a **suspend** function because the Jordan source may touch disk or network; it stays on `Dispatchers.Main.immediate` and the repository switches to IO internally.

The slideshow interval is re-read from prefs every iteration, and `PrayerReader` re-reads the chosen area on every overlay tick, so settings changes apply to a running dream without a restart and without any listener.

### `PrayerTimesActivity` and `PrayerReader`

`PrayerTimesActivity` is the launcher screen: clock, date, a City button (opens an `AlertDialog`
list of areas), a Settings button, the next-prayer countdown and a card per `PrayerName` for
today, the next one marked with `isSelected`. It refreshes on the minute
boundary between `onResume` and `onPause`, so returning from settings re-reads prefs.

`PrayerReader` owns "chosen area → `PrayerReading`" (label + nullable snapshot) plus
`nextPrayerText()`, `formatCountdown()` and `millisUntilNextMinute()`. Both the dream and this screen go through it, so they cannot disagree.
`PrayerSnapshot.today` carries the full day's list for this screen; past Isha it is still today's
list while `next` is tomorrow's Fajr.

### `PrayerCalendarActivity`

The phone app's Prayer Calendar, opened from the home screen's Prayer Calendar button (not
exported, so `am start` cannot reach it; go through the home screen). `JordanPrayerRepository.calendar(area)`
lists every month file on disk for the area and returns `CalendarDay`s oldest first, so it works
offline and shows exactly what `prefetch()` stored. Opens on today (or the nearest stored day),
Previous/Next step through the days that exist, and All days / One day toggles to a row per day
that lands focus on today. Hijri dates use ICU's `islamic-umalqura` calendar in `Asia/Amman`.

### Preferences and the settings screen

`AppPreferences` holds only `jordanArea` (the feed's Arabic name, null until chosen; `PrayerReader.area()` then uses the feed's first entry),
`use24HourClock` (defaults to the device's `is24HourFormat` until the user picks) and
`slideshowIntervalSeconds`. Every clock and prayer time goes through `clockFormat()` in
`PrayerReader.kt`, rebuilt per tick so a change applies on the next refresh. Keys from older builds (`location_mode_name`,
`calculation_method_name`, `manual_city_name`) are simply ignored.

The **5–70 s interval bounds (default 15 s) live only in `AppPreferences`** (`MIN_INTERVAL_SECONDS`, `MAX_INTERVAL_SECONDS`, `INTERVAL_RANGE_SECONDS`). `SettingsActivity.setupInterval()` assigns them to `SeekBar.min`/`max` (API 26, which is minSdk), so the control and the clamp cannot disagree and the SeekBar carries no hardcoded range in XML.

`SettingsActivity` is, top to bottom: a Back button, the City spinner (`bindStrings`, filled
from `PrayerReader.areas()`), a status line from `refreshJordanStatus()` saying whether the area
has a timetable right now, the 12/24-hour clock spinner, the slideshow interval, and an About panel (version from
`BuildConfig.VERSION_NAME`, credits Ehab Amawi then Munes Bani Fawaz — that order is intentional —
and the Ministry of Awqaf source line). The About panel is focusable only so the remote can scroll
to it. `SettingsActivity` owns a plain `uiScope` cancelled in `onDestroy` rather than pulling in a
lifecycle-ktx dependency.

`enableDpadActivation()` translates `DPAD_CENTER` / `ENTER` / `BUTTON_A` into `performClick()` on the spinner — needed because this screen uses plain AppCompat widgets rather than Leanback fragments. It is deliberately not applied to the SeekBar, which handles D-pad left/right natively.

### `PhotoRepository`

Two hardcoded lists of `Photo` (place string, author, license, Coil `data`):
`ONLINE` is 21 hand-picked Wikimedia Commons photos of Jordanian and Islamic landmarks, hotlinked
as Commons' own 1920px thumbnails; `OFFLINE` is four of them bundled in `res/drawable-nodpi`
(1920px, about 1.1 MB total). Settings' "Screensaver photos" (`AppPreferences.useOnlinePhotos`,
re-read per photo) picks the list; an online photo that fails to load is replaced by
`fallback(index)`. Every photo is CC BY / BY-SA, so the dream shows a credit line
(`photo_credit`: place · author · license) bottom-start, nudged with the burn-in shift too. The
authors and licenses were pulled from the Commons API, not typed; keep it that way when adding
photos. Coil's OkHttp client sets a `User-Agent` because Wikimedia rejects generic ones.

### Branding

Named "Jordan Prayer Times" (debug: "(Debug)"), injected per build type via `resValue` in
`app/build.gradle.kts` exactly as the phone app does, so there is no `app_name` in strings.xml.
The launcher icon is the phone app's `assets/images/icon.png` as an adaptive icon on white
(`mipmap-anydpi/ic_launcher.xml`); the TV banner `drawable-nodpi/banner_tv.png` (640x360) is that
icon plus the name, drawn for TV rather than reusing the phone's Play feature graphic. The launch
splash follows the Wear OS app (`androidx.core:core-splashscreen`, `Theme.JordanPrayerTimes.Starting`
on `PrayerTimesActivity` only, `installSplashScreen()` before `super.onCreate`).

### Theme

`res/values/colors.xml` mirrors the phone app's Material 3 **dark** scheme (`lib/core/constants/app_colors.dart` in Jordan_Prayer_Times_App): `#121212` surfaces, teal `accent_teal` for accents/focus/headings, `primary_container` for focused and selected fills. `accent_gold` is reserved for the next prayer and the countdown, as the phone uses gold for the current prayer. Drawables reference these tokens rather than raw hex.

### Language, dates and offline

- **Language** (`AppPreferences.appLanguage`: system / `en` / `ar`) is applied by `AppLanguage.wrap()`
  in `attachBaseContext` of every activity (`LocalizedActivity`, which also recreates on resume
  after a change) and of `CustomDreamService`. Not `AppCompatDelegate.setApplicationLocales`: below
  API 33 that never reaches the dream. Arabic is `ar-u-nu-arab` so formatters use Eastern Arabic
  digits like the phone app. **Formatters must take `appLocale` (the context's configuration),
  never `Locale.getDefault()`** — Android resets the process default, which once left the dream
  in English. The dream root sets `layoutDirection` itself, and its panel/credit use start/end
  margins so Arabic mirrors. The AAB has `bundle { language { enableSplit = false } }`, otherwise
  Play would strip the other language and the switch would do nothing.
- **Dates match the phone app**: `HEADER_DATE` / `DAY_DATE` / `SHORT_DATE` patterns in
  `PrayerReader.kt`, and `hijriDate()` = ICU Umm al-Qura plus the phone's month names and order
  (`hijri_months`, `hijri_date_format`) shifted by `hijriOffsetDays` (−2..+2, "Hijri date
  adjustment" in settings).
- **Offline after first setup**: times, cities and the calendar come from `filesDir` (fresh for a
  week, stale preferred over nothing). The dream uses the bundled photos when online is chosen but
  the network is not validated, and falls back per photo on a failed load, keeping the current
  photo as placeholder/error so the background never flashes. Verified on the emulator by
  breaking DNS (`settings put global private_dns_mode hostname` + an invalid
  `private_dns_specifier`); airplane mode and `http_proxy` do not cut the emulator's Ethernet.

### Strings

All user-visible text is in `strings.xml` with an Arabic `values-ar/strings.xml` (wording shared with the phone app), including the overlay (`overlay_next_prayer`, `countdown_*`, `prayer_*`) and the About panel. The interval label is a `<plurals>`, read via `resources.getQuantityString`. Jordan area names are the exception and deliberately so — they come from the feed verbatim, so a new area the feed adds appears with no code change.

## Testing the Jordan source without network

Both local emulators have broken DNS (`10.0.2.3` does not resolve), so the HTTP call cannot be
exercised on them. Seed the disk cache instead and the whole parse → snapshot → overlay path runs
offline:

```bash
# Area names are Arabic; the cache filename is Uri.encode(area) + "_" + yyyy_MM + ".json".
B=/data/data/com.mbf.jordan_prayer_times_app.debug/files/jordan_timetable
adb shell run-as com.mbf.jordan_prayer_times_app.debug mkdir -p $B
adb push cities.json /data/local/tmp/ && adb shell chmod 644 /data/local/tmp/cities.json
adb shell run-as com.mbf.jordan_prayer_times_app.debug cp /data/local/tmp/cities.json $B/cities.json
```

A freshly written file counts as fresh, so no fetch is attempted. To prove the cached file is what
is driving the display, edit a row to an obviously different time and confirm the overlay follows it.
