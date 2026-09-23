package com.example.customtvscreensaver

import androidx.annotation.StringRes
import com.batoulapps.adhan.CalculationMethod

/**
 * A user-selectable option that knows its own display string, so spinners can be built
 * straight from the enum. Adding an entry to any of the enums below is enough to make it
 * appear in the settings UI in the right position - there is no parallel list of labels to
 * keep in sync.
 */
interface LabelledOption {
    @get:StringRes
    val labelRes: Int
}

/**
 * All of these are persisted by [Enum.name] (see AppPreferences), so entries may be freely
 * reordered or inserted without corrupting saved settings. Renaming an entry does reset that
 * preference to its default, which is the intended failure mode.
 */
enum class LocationMode(@StringRes override val labelRes: Int) : LabelledOption {
    AUTO(R.string.location_mode_auto),
    MANUAL(R.string.location_mode_manual),

    /**
     * Uses the official Jordan timetable instead of calculating, for one of the areas that feed
     * publishes. Falls back to the adhan calculation whenever the timetable cannot answer.
     */
    JORDAN_OFFICIAL(R.string.location_mode_jordan)
}

enum class CalculationMethodOption(
    @StringRes override val labelRes: Int,
    val adhanMethod: CalculationMethod
) : LabelledOption {
    UMM_AL_QURA(R.string.method_umm_al_qura, CalculationMethod.UMM_AL_QURA),
    ISNA(R.string.method_isna, CalculationMethod.NORTH_AMERICA),
    MWL(R.string.method_mwl, CalculationMethod.MUSLIM_WORLD_LEAGUE)
}

/** Presets for [LocationMode.MANUAL]; declared alphabetically because that is spinner order. */
enum class City(
    @StringRes override val labelRes: Int,
    val latitude: Double,
    val longitude: Double
) : LabelledOption {
    ABU_DHABI(R.string.city_abu_dhabi, 24.4539, 54.3773),
    AMMAN(R.string.city_amman, 31.9539, 35.9106),
    BAGHDAD(R.string.city_baghdad, 33.3152, 44.3661),
    BEIRUT(R.string.city_beirut, 33.8938, 35.5018),
    BERLIN(R.string.city_berlin, 52.5200, 13.4050),
    CAIRO(R.string.city_cairo, 30.0444, 31.2357),
    CASABLANCA(R.string.city_casablanca, 33.5731, -7.5898),
    CHICAGO(R.string.city_chicago, 41.8781, -87.6298),
    DAMASCUS(R.string.city_damascus, 33.5138, 36.2765),
    DAMMAM(R.string.city_dammam, 26.4207, 50.0888),
    DOHA(R.string.city_doha, 25.2854, 51.5310),
    DUBAI(R.string.city_dubai, 25.2048, 55.2708),
    ISTANBUL(R.string.city_istanbul, 41.0082, 28.9784),
    JAKARTA(R.string.city_jakarta, -6.2088, 106.8456),
    JEDDAH(R.string.city_jeddah, 21.4858, 39.1925),
    JERUSALEM(R.string.city_jerusalem, 31.7683, 35.2137),
    KARACHI(R.string.city_karachi, 24.8607, 67.0011),
    KUALA_LUMPUR(R.string.city_kuala_lumpur, 3.1390, 101.6869),
    KUWAIT_CITY(R.string.city_kuwait_city, 29.3759, 47.9774),
    LAHORE(R.string.city_lahore, 31.5204, 74.3587),
    LONDON(R.string.city_london, 51.5074, -0.1278),
    MANAMA(R.string.city_manama, 26.2285, 50.5860),
    MECCA(R.string.city_mecca, 21.4225, 39.8262),
    MEDINA(R.string.city_medina, 24.4686, 39.6142),
    MUSCAT(R.string.city_muscat, 23.5880, 58.3829),
    NEW_YORK(R.string.city_new_york, 40.7128, -74.0060),
    PARIS(R.string.city_paris, 48.8566, 2.3522),
    RIYADH(R.string.city_riyadh, 24.7136, 46.6753),
    SYDNEY(R.string.city_sydney, -33.8688, 151.2093),
    TORONTO(R.string.city_toronto, 43.6532, -79.3832);

    fun toLocation(): ResolvedLocation = ResolvedLocation(latitude, longitude, labelRes)

    companion object {
        /** Used when nothing has been chosen and when automatic location is unavailable. */
        val DEFAULT = AMMAN
    }
}

/** Coordinates plus the label to show for them, whichever way they were obtained. */
data class ResolvedLocation(
    val latitude: Double,
    val longitude: Double,
    @StringRes val labelRes: Int
)
