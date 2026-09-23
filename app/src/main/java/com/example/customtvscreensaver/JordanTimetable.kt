package com.example.customtvscreensaver

import org.json.JSONObject
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * Parsing for the official Jordan prayer timetable feed.
 *
 * The feed publishes times on a **12-hour clock with no AM/PM marker** - `"asr": "04:24"` means
 * 16:24. Hardcoding which prayers are PM would break in winter, when Dhuhr falls at 11:xx, so
 * instead each time is resolved to whichever of its two candidates (AM or AM+12h) is the
 * earliest that is not before the preceding prayer. The six prayers increase strictly through
 * the day, which makes that unambiguous. Verified against all 930 currently published rows plus
 * winter values.
 *
 * Times are interpreted in Jordan's zone, not the device's, so the timetable stays correct on a
 * device whose clock is set elsewhere.
 */
object JordanTimetable {
    /** First entry of cities.json is a "please choose" prompt, not a real area. */
    const val PLACEHOLDER_AREA = "الرجاء الاختيار"

    val ZONE: TimeZone = TimeZone.getTimeZone("Asia/Amman")

    private val FIELDS = listOf(
        PrayerName.FAJR to "fajr",
        PrayerName.SUNRISE to "sunrise",
        PrayerName.DHUHR to "dhuhr",
        PrayerName.ASR to "asr",
        PrayerName.MAGHRIB to "maghrib",
        PrayerName.ISHA to "isha"
    )

    private const val MINUTES_PER_DAY = 24 * 60
    private const val HALF_DAY_MINUTES = 12 * 60

    fun parseAreas(json: String): List<String> {
        val array = JSONObject(json).optJSONArray("cities") ?: return emptyList()
        return (0 until array.length())
            .mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            .filter { it != PLACEHOLDER_AREA }
    }

    /** Maps each `dd/MM/yyyy` key in a monthly file to that day's prayers. */
    fun parseMonth(json: String): Map<String, List<TimedPrayer>> {
        val rows = JSONObject(json).optJSONArray("prayerData") ?: return emptyMap()
        val result = LinkedHashMap<String, List<TimedPrayer>>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val dateKey = row.optString("date").takeIf(String::isNotBlank) ?: continue
            val minutes = minutesOfDay(row) ?: continue
            val day = FIELDS.zip(minutes) { (name, _), minuteOfDay ->
                val time = instantAt(dateKey, minuteOfDay) ?: return@parseMonth result
                TimedPrayer(name, time)
            }
            result[dateKey] = day
        }
        return result
    }

    /**
     * Resolves the whole day at once: each prayer takes the earliest of its AM/PM candidates
     * that does not precede the previous prayer. Returns null for a row that cannot be made
     * monotonic, so a malformed day is skipped rather than silently mis-timed.
     */
    private fun minutesOfDay(row: JSONObject): List<Int>? {
        var previous = -1
        return FIELDS.map { (_, key) ->
            val raw = row.optString(key)
            val separator = raw.indexOf(':')
            if (separator <= 0) return null
            val hour = raw.substring(0, separator).trim().toIntOrNull() ?: return null
            val minute = raw.substring(separator + 1).trim().toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null

            val am = (hour % 12) * 60 + minute
            val pm = am + HALF_DAY_MINUTES
            val chosen = when {
                am >= previous -> am
                pm >= previous -> pm
                else -> return null
            }
            if (chosen >= MINUTES_PER_DAY) return null
            previous = chosen
            chosen
        }
    }

    private fun instantAt(dateKey: String, minuteOfDay: Int): Date? {
        val parts = dateKey.split('/')
        if (parts.size != 3) return null
        val day = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val year = parts[2].toIntOrNull() ?: return null
        return Calendar.getInstance(ZONE).apply {
            clear()
            set(year, month - 1, day, minuteOfDay / 60, minuteOfDay % 60, 0)
        }.time
    }
}
