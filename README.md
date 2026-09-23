# Jordan Prayer Times (Android TV)

The Android TV edition of Jordan Prayer Times: a launcher app and a screensaver (DreamService)
showing the official Jordanian prayer times. It shares its package name and Play listing with the
phone and Wear OS apps. Native Kotlin, targeting Android 16 (API 36), minimum Android 8.0 (API 26).

## Features

- Official prayer times from the Ministry of Awqaf, via `mbanifawaz/Jordan_Prayer_Times_API_Data`.
  Nothing is calculated on the device.
- City list straight from the feed's `cities.json` (the first entry, "الرجاء الاختيار", is the
  default until a city is chosen, as on the phone).
- Every published month is downloaded and kept on the device, so the app keeps working offline.
- Home screen with clock, Gregorian and Hijri dates (formatted as in the phone app),
  next-prayer countdown and a card per prayer.
- Works fully offline after the first setup: times, cities and calendar are kept on the device;
  the screensaver switches to its built-in photos when there is no internet.
- Prayer Calendar: opens on today, step a day at a time, or list every downloaded day with
  Gregorian and Hijri (Umm al-Qura) dates.
- Screensaver: full-screen slideshow of Jordanian and Islamic landmarks from Wikimedia Commons,
  each credited on screen, or four built-in photos offline; 5–70 second interval with crossfade;
  clock, date, city and countdown overlay; 2–5 px overlay nudge every minute against burn-in.
- Settings › Screensaver: set the app as the TV's screensaver even on Google TV (which hides the
  picker), start it now, choose how long the TV waits before starting it, or restore the previous one.
  The first time needs USB debugging on once (the TV asks to allow it).
- Settings: city, language (English / Arabic, right-to-left), 12/24-hour clock, Hijri date
  adjustment (−2 to +2 days), online or offline photos, slideshow interval.
- Dark theme matching the phone app, D-pad navigable throughout.

## Build

JDK 17+ and the Android SDK are required.

```bash
./gradlew assembleDebug      # app-debug.apk, installs beside the release build
./gradlew assembleRelease    # R8 + resource shrinking
./gradlew bundleRelease      # app-release.aab for Google Play
```

After installing, open the app's **Settings › Screensaver** and select **Set as the TV's screensaver**.

## Data

Prayer times are read from the public `Jordan_Prayer_Times_API_Data` repository with no token, so
there is no secret in the app. Never commit release bundles or APKs (they are gitignored).

## Signing

Copy `keystore.properties.example` to `keystore.properties` (gitignored). Release builds must be
signed with the same upload key as the phone app, since they share the Play listing. Without that
file the release build still succeeds and produces an unsigned APK.

## Photo credits

Screensaver photos are from Wikimedia Commons under CC BY / CC BY-SA licenses; the place, author
and license are shown on screen with each photo, and listed in
`app/src/main/java/com/example/customtvscreensaver/PhotoRepository.kt`.

## Store screenshots

`screenshots/` holds the Play listing assets: eight 1920×1080 Android TV screenshots (Play's maximum),
mixing English (`_en_`) and Arabic (`_ar_`), numbered in upload order, and `tv_banner_1280x720.png`.
