# Custom TV Screensaver & Prayer Times

Native Kotlin Android TV DreamService targeting Android 14 (API 34).

## Features

- Full-screen nature slideshow with a 15–70 second configurable interval and crossfade transitions.
- Curated remote landscape images with bundled drawable fallback imagery when offline.
- Offline prayer calculations for Fajr, Sunrise, Dhuhr, Asr, Maghrib, and Isha.
- Optional official Jordan timetable for any of the areas that feed covers, with automatic
  fallback to the calculation whenever a month or area is not published.
- Automatic location using Google Play Services, or a manual pick from 30 preset cities.
- Settings activity navigable with a TV remote D-pad, including the location permission prompt.
- Current time, date, location, next prayer, and countdown overlay, refreshed on the minute
  and following the device's 12/24-hour and locale settings.
- Automatic 2–5 pixel overlay movement every 60 seconds to reduce OLED burn-in.
- Correct DreamService registration and Android screensaver settings metadata.

## Open and run

Open `android-tv-screensaver` in Android Studio, allow Gradle sync to download dependencies, then run the `app` configuration on an Android TV device or emulator. JDK 17 is required.

After installation, enable **Custom TV Screensaver & Prayer Times** from the device's screen saver / dream settings. The app settings can also be opened from the Android TV launcher, or via **Customize** on the screensaver tile.

## Location

Automatic location reads the device's current balanced-accuracy location. A DreamService cannot
request permissions, so the settings screen asks for it; if the grant is missing or denied the
prayer engine falls back to the selected preset city (Amman, Jordan by default — 31.9539, 35.9106).

Choose **Manual location** to skip the device lookup entirely and calculate for a preset city.

## Official Jordan timetable

Selecting **Jordan (official timetable)** as the location mode uses the published timetable from
`mbanifawaz/Jordan_Prayer_Times_API_Data` instead of calculating. That repository is private, so
the feature needs a token: copy `secrets.properties.example` to `secrets.properties` (gitignored)
and set `jordanApiToken`.

Use a GitHub **fine-grained** token with repository access limited to that one repo and
`Contents: Read-only`. Anything placed there is compiled into the APK and can be extracted from
it, so the token's scope is the only thing limiting the damage — never use an account-wide or
classic token.

Without the token the app still builds and runs; it simply calculates every prayer time and says
so on the settings screen. It also falls back to calculating when the feed has no data for the
selected area or the current month, since only a rolling window of months is published. Fetched
months are cached on device, so the screensaver keeps working offline.

## Release builds

`./gradlew assembleRelease` runs R8 and resource shrinking. To get a signed APK, copy
`keystore.properties.example` to `keystore.properties` (gitignored) and fill in your keystore
details:

```
keytool -genkeypair -v -keystore release.jks -alias tv-screensaver \
        -keyalg RSA -keysize 2048 -validity 10000
```

Without that file the release build still succeeds and produces an unsigned APK.
