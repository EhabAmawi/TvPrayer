package com.example.customtvscreensaver

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class AppPreferences(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var locationMode: LocationMode
        get() = preferences.getEnum(KEY_LOCATION_MODE, LocationMode.AUTO)
        set(value) = preferences.putEnum(KEY_LOCATION_MODE, value)

    var calculationMethod: CalculationMethodOption
        get() = preferences.getEnum(KEY_CALCULATION_METHOD, CalculationMethodOption.UMM_AL_QURA)
        set(value) = preferences.putEnum(KEY_CALCULATION_METHOD, value)

    /** Only consulted when [locationMode] is [LocationMode.MANUAL]. */
    var manualCity: City
        get() = preferences.getEnum(KEY_MANUAL_CITY, City.DEFAULT)
        set(value) = preferences.putEnum(KEY_MANUAL_CITY, value)

    /**
     * Only consulted when [locationMode] is [LocationMode.JORDAN_OFFICIAL]. Stored as the feed's
     * own Arabic area name rather than an enum, because the area list is fetched at runtime.
     */
    var jordanArea: String?
        get() = preferences.getString(KEY_JORDAN_AREA, null)?.takeIf { it.isNotBlank() }
        set(value) = preferences.edit { putString(KEY_JORDAN_AREA, value) }

    var slideshowIntervalSeconds: Int
        get() = preferences.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_SECONDS)
            .coerceIn(INTERVAL_RANGE_SECONDS)
        set(value) = preferences.edit {
            putInt(KEY_INTERVAL, value.coerceIn(INTERVAL_RANGE_SECONDS))
        }

    companion object {
        /** Single source of truth for the slideshow interval bounds; the SeekBar reads these. */
        const val MIN_INTERVAL_SECONDS = 15
        const val MAX_INTERVAL_SECONDS = 70
        const val DEFAULT_INTERVAL_SECONDS = MIN_INTERVAL_SECONDS
        val INTERVAL_RANGE_SECONDS = MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS

        private const val FILE_NAME = "screensaver_preferences"
        private const val KEY_LOCATION_MODE = "location_mode_name"
        private const val KEY_CALCULATION_METHOD = "calculation_method_name"
        private const val KEY_MANUAL_CITY = "manual_city_name"
        private const val KEY_JORDAN_AREA = "jordan_area"
        private const val KEY_INTERVAL = "slideshow_interval"
    }
}

/**
 * Enums are stored by name rather than ordinal: an unknown or renamed name falls back to the
 * default instead of silently resolving to whatever entry now occupies that position.
 */
private inline fun <reified T : Enum<T>> SharedPreferences.getEnum(key: String, default: T): T =
    getString(key, null)
        ?.let { stored -> runCatching { enumValueOf<T>(stored) }.getOrNull() }
        ?: default

private inline fun <reified T : Enum<T>> SharedPreferences.putEnum(key: String, value: T) =
    edit { putString(key, value.name) }
