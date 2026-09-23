package com.example.customtvscreensaver

import androidx.annotation.StringRes
import java.util.Date
import kotlin.math.max

enum class PrayerName(@StringRes val labelRes: Int) {
    FAJR(R.string.prayer_fajr),
    SUNRISE(R.string.prayer_sunrise),
    DHUHR(R.string.prayer_dhuhr),
    ASR(R.string.prayer_asr),
    MAGHRIB(R.string.prayer_maghrib),
    ISHA(R.string.prayer_isha)
}

/**
 * Deliberately resource-id and millisecond based rather than pre-formatted, so all presentation
 * (localised names, countdown wording) stays in the view layer.
 */
data class PrayerSnapshot(
    val current: PrayerName,
    val next: PrayerName,
    val remainingMillis: Long,
    /** Today's full timetable, for screens that list every prayer. */
    val today: List<TimedPrayer>
)

/** One prayer at an absolute instant. */
data class TimedPrayer(val name: PrayerName, val time: Date)

/** One day of the stored timetable; [date] is that day's first prayer (Fajr). */
data class CalendarDay(val date: Date, val prayers: List<TimedPrayer>)

/**
 * The single place that decides "which prayer is next".
 *
 * [tomorrow] is only consulted once [today] is exhausted (i.e. past Isha), so callers that can
 * still answer from today may pass an empty list. Returns null when neither list has anything
 * left, which is the signal for the caller to fall back to another source.
 */
fun buildSnapshot(
    now: Date,
    today: List<TimedPrayer>,
    tomorrow: List<TimedPrayer>
): PrayerSnapshot? {
    val upcoming = today.firstOrNull { it.time.after(now) }
        ?: tomorrow.firstOrNull { it.time.after(now) }
        ?: return null
    val previous = today.lastOrNull { !it.time.after(now) }
    return PrayerSnapshot(
        current = previous?.name ?: PrayerName.ISHA,
        next = upcoming.name,
        remainingMillis = max(0L, upcoming.time.time - now.time),
        today = today
    )
}
