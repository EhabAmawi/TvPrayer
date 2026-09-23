package com.example.customtvscreensaver

import android.content.Context
import android.icu.util.IslamicCalendar
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import android.icu.util.TimeZone as IcuTimeZone

/**
 * The chosen area plus its times; [snapshot] is null when the feed has no data for today.
 * [needsCity] is true while the feed's "please choose" entry is selected; [citiesMissing] while
 * cities.json has never loaded (first launch without internet).
 */
data class PrayerReading(
    val label: String,
    val snapshot: PrayerSnapshot?,
    val needsCity: Boolean = false,
    val citiesMissing: Boolean = false
)

/**
 * Resolves the chosen Jordan area into a [PrayerReading]. Shared by the dream overlay and the
 * launcher screen so both always agree on which times are shown.
 *
 * Preferences are read on every call, so settings changes apply without any listener.
 */
class PrayerReader(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(context)
    private val jordanRepository = JordanPrayerRepository(context)

    /**
     * Times come only from the official feed. Nothing is calculated on the device, so a month the
     * feed has not published (or a build without a token) yields a null snapshot.
     */
    suspend fun read(now: Date): PrayerReading {
        val area = area()
            // The label goes on the City button, so keep it short; the explanation is the status line.
            ?: return PrayerReading(
                appContext.getString(R.string.jordan_area_title), null, citiesMissing = true
            )
        if (area == JordanTimetable.PLACEHOLDER_AREA) return PrayerReading(area, null, needsCity = true)
        return PrayerReading(area, jordanRepository.snapshot(area, now))
    }

    /** Stores every published month for the chosen area on the device; see the repository. */
    suspend fun prefetch(now: Date): Boolean =
        area()?.takeIf { it != JordanTimetable.PLACEHOLDER_AREA }
            ?.let { jordanRepository.prefetch(it, now) } ?: false

    /**
     * The chosen area, or the feed's first entry (its "please choose" prompt, as the phone app
     * does) until the user picks. Null only while cities.json has never loaded: there is
     * deliberately no built-in list.
     */
    suspend fun area(): String? = preferences.jordanArea ?: areas().firstOrNull()

    /** Every stored day for the chosen area; empty while no real area is chosen. */
    suspend fun calendar(): List<CalendarDay> =
        area()?.takeIf { it != JordanTimetable.PLACEHOLDER_AREA }
            ?.let { jordanRepository.calendar(it) }.orEmpty()

    /** The feed's list verbatim, "please choose" included; empty until fetched once. */
    suspend fun areas(): List<String> = jordanRepository.areas()
}

fun Context.formatCountdown(remainingMillis: Long): String {
    val totalMinutes = remainingMillis / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) {
        getString(R.string.countdown_hours_minutes, hours, minutes)
    } else {
        getString(R.string.countdown_minutes, minutes)
    }
}

fun millisUntilNextMinute(): Long =
    (60_000L - System.currentTimeMillis() % 60_000L).coerceAtLeast(1_000L)

/** "NEXT PRAYER: ASR IN 3h 8m", or an explanation when the feed has no times. */
fun Context.nextPrayerText(reading: PrayerReading): String {
    val snapshot = reading.snapshot
    return if (reading.citiesMissing) {
        getString(R.string.jordan_areas_unavailable)
    } else if (reading.needsCity) {
        getString(R.string.choose_city_prompt)
    } else if (snapshot == null) {
        getString(R.string.times_unavailable)
    } else {
        getString(
            R.string.overlay_next_prayer,
            getString(snapshot.next.labelRes).uppercase(appLocale),
            formatCountdown(snapshot.remainingMillis)
        )
    }
}

/**
 * [date] as the phone app shows it: Umm al-Qura (the table its hijri package uses), shifted by
 * [offsetDays], with that package's month names — "Rabi' Al-Thani 12 1448" in English,
 * "١٢ ربيع الآخر ١٤٤٨" in Arabic (order from R.string.hijri_date_format). Jordan's zone decides
 * the day.
 */
fun Context.hijriDate(date: Date, offsetDays: Int): String {
    val calendar = IslamicCalendar(IcuTimeZone.getTimeZone(JordanTimetable.ZONE.id)).apply {
        calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
        timeInMillis = date.time + offsetDays * MILLIS_PER_DAY
    }
    val month = resources.getStringArray(R.array.hijri_months)[calendar.get(IslamicCalendar.MONTH)]
    return getString(
        R.string.hijri_date_format,
        String.format(appLocale, "%d", calendar.get(IslamicCalendar.DAY_OF_MONTH)),
        month,
        String.format(appLocale, "%d", calendar.get(IslamicCalendar.YEAR))
    )
}

/**
 * The phone app's Gregorian patterns (intl DateFormat there), in the app language and Jordan's
 * zone: [HEADER_DATE] under the clock, [DAY_DATE] for a calendar day, [SHORT_DATE] in lists.
 */
fun Context.gregorianFormat(pattern: String) = SimpleDateFormat(pattern, appLocale).apply {
    timeZone = JordanTimetable.ZONE
}

const val HEADER_DATE = "EEEE, MMM d y"
const val DAY_DATE = "EEEE, MMMM d, y"
const val SHORT_DATE = "EEE, MMM d"
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/**
 * Clock formatter for the user's 12/24-hour choice, in the device locale. Built per tick rather
 * than cached so a change in settings applies on the next refresh.
 */
fun Context.clockFormat(use24Hour: Boolean): SimpleDateFormat = SimpleDateFormat(
    DateFormat.getBestDateTimePattern(appLocale, if (use24Hour) "Hm" else "hm"),
    appLocale
)
