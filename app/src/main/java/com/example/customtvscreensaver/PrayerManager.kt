package com.example.customtvscreensaver

import androidx.annotation.StringRes
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.Madhab
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.data.DateComponents
import java.util.Calendar
import java.util.Date

enum class PrayerName(@StringRes val labelRes: Int) {
    FAJR(R.string.prayer_fajr),
    SUNRISE(R.string.prayer_sunrise),
    DHUHR(R.string.prayer_dhuhr),
    ASR(R.string.prayer_asr),
    MAGHRIB(R.string.prayer_maghrib),
    ISHA(R.string.prayer_isha)
}

/**
 * Deliberately resource-id and millisecond based rather than pre-formatted: this class holds no
 * Context, so all presentation (localised names, countdown wording) stays in the view layer.
 */
data class PrayerSnapshot(
    val current: PrayerName,
    val next: PrayerName,
    val remainingMillis: Long
)

/** Stateless; cheap enough to construct on every overlay refresh. */
class PrayerManager(
    latitude: Double,
    longitude: Double,
    private val method: CalculationMethodOption
) {
    private val coordinates = Coordinates(latitude, longitude)

    fun snapshot(now: Date = Date()): PrayerSnapshot = checkNotNull(
        // Past Isha the next prayer is tomorrow's Fajr, which adhan can always supply.
        buildSnapshot(now, prayerSequence(now), prayerSequence(nextDay(now)))
    ) { "the adhan calculation always yields an upcoming prayer" }

    private fun prayerSequence(date: Date): List<TimedPrayer> {
        // CalculationMethod.getParameters() hands back a fresh instance, so mutating it is safe.
        val parameters = method.adhanMethod.parameters.also { it.madhab = MADHAB }
        val times = PrayerTimes(coordinates, DateComponents.from(date), parameters)
        return listOf(
            TimedPrayer(PrayerName.FAJR, times.fajr),
            TimedPrayer(PrayerName.SUNRISE, times.sunrise),
            TimedPrayer(PrayerName.DHUHR, times.dhuhr),
            TimedPrayer(PrayerName.ASR, times.asr),
            TimedPrayer(PrayerName.MAGHRIB, times.maghrib),
            TimedPrayer(PrayerName.ISHA, times.isha)
        )
    }

    private fun nextDay(from: Date): Date = Calendar.getInstance().apply {
        time = from
        add(Calendar.DATE, 1)
    }.time

    private companion object {
        val MADHAB = Madhab.SHAFI
    }
}
