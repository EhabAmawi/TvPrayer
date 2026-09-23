package com.example.customtvscreensaver

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateFormat
import androidx.core.content.edit

class AppPreferences(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Until the user picks, follow whatever the device is set to. */
    private val system24Hour = DateFormat.is24HourFormat(context)

    /**
     * Stored as the feed's own Arabic area name rather than an enum, because the area list is
     * fetched at runtime. Null until the user picks; PrayerReader.area() then uses the feed's
     * first area.
     */
    var jordanArea: String?
        get() = preferences.getString(KEY_JORDAN_AREA, null)?.takeIf { it.isNotBlank() }
        set(value) = preferences.edit { putString(KEY_JORDAN_AREA, value) }

    /** 24-hour clock ("15:55") when true, 12-hour ("3:55 PM") when false. */
    var use24HourClock: Boolean
        get() = preferences.getBoolean(KEY_24_HOUR, system24Hour)
        set(value) = preferences.edit { putBoolean(KEY_24_HOUR, value) }

    /** AppLanguage.SYSTEM (follow the device), ENGLISH or ARABIC. */
    var appLanguage: String
        get() = preferences.getString(KEY_LANGUAGE, null)
            ?.takeIf { it in LANGUAGES } ?: AppLanguage.SYSTEM
        set(value) = preferences.edit { putString(KEY_LANGUAGE, value) }

    /** Days added before converting to Hijri, as the phone app's "Hijri date adjustment". */
    var hijriOffsetDays: Int
        get() = preferences.getInt(KEY_HIJRI_OFFSET, 0).takeIf { it in HIJRI_OFFSETS } ?: 0
        set(value) = preferences.edit { putInt(KEY_HIJRI_OFFSET, value.coerceIn(HIJRI_OFFSETS)) }

    /** The screensaver that was set before this app replaced it, so it can be put back. */
    var previousScreensaver: String?
        get() = preferences.getString(KEY_PREVIOUS_SCREENSAVER, null)
        set(value) = preferences.edit { putString(KEY_PREVIOUS_SCREENSAVER, value) }

    /** True: Wikimedia Commons photos over the network. False: only the four bundled ones. */
    var useOnlinePhotos: Boolean
        get() = preferences.getBoolean(KEY_ONLINE_PHOTOS, true)
        set(value) = preferences.edit { putBoolean(KEY_ONLINE_PHOTOS, value) }

    var slideshowIntervalSeconds: Int
        get() = preferences.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_SECONDS)
            .coerceIn(INTERVAL_RANGE_SECONDS)
        set(value) = preferences.edit {
            putInt(KEY_INTERVAL, value.coerceIn(INTERVAL_RANGE_SECONDS))
        }

    companion object {
        /** Single source of truth for the slideshow interval bounds; the SeekBar reads these. */
        const val MIN_INTERVAL_SECONDS = 5
        const val MAX_INTERVAL_SECONDS = 70
        const val DEFAULT_INTERVAL_SECONDS = 15
        val INTERVAL_RANGE_SECONDS = MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS

        private const val FILE_NAME = "screensaver_preferences"
        private const val KEY_JORDAN_AREA = "jordan_area"
        private const val KEY_24_HOUR = "use_24_hour_clock"
        private const val KEY_INTERVAL = "slideshow_interval"
        private const val KEY_ONLINE_PHOTOS = "use_online_photos"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_PREVIOUS_SCREENSAVER = "previous_screensaver"
        private const val KEY_HIJRI_OFFSET = "hijri_offset_days"

        /** The phone app's range: -2 to +2 days. */
        val HIJRI_OFFSETS = -2..2
        private val LANGUAGES = setOf(AppLanguage.SYSTEM, AppLanguage.ENGLISH, AppLanguage.ARABIC)
    }
}
