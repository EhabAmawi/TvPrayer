package com.example.customtvscreensaver

import java.util.Date
import kotlin.math.max

/** One prayer at an absolute instant. */
data class TimedPrayer(val name: PrayerName, val time: Date)

/**
 * Shared by both prayer sources - the adhan calculation and the official Jordan timetable - so
 * that "which prayer is next" exists in exactly one place.
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
        remainingMillis = max(0L, upcoming.time.time - now.time)
    )
}
