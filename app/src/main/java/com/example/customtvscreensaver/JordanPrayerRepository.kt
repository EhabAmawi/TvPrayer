package com.example.customtvscreensaver

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The official Jordan timetable, with disk caching so the screensaver keeps working offline.
 *
 * Everything here degrades to null rather than throwing: only a rolling window of months is
 * published (September is typically absent in August), one area has no file at all, and  Every one of those cases means "no times
 * available", which callers show as such, so nothing is surfaced as an error.
 */
class JordanPrayerRepository(context: Context) {
    private val cacheDir = File(context.filesDir, CACHE_DIR)
    private val mutex = Mutex()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val parsedMonths = mutableMapOf<String, Map<String, List<TimedPrayer>>>()
    private val lastFailureAt = mutableMapOf<String, Long>()
    private var cachedAreas: List<String>? = null

    /** Area names exactly as the feed publishes them; empty when never fetched and offline. */
    suspend fun areas(): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            cachedAreas?.let { return@withLock it }
            val body = readOrFetch(AREAS_KEY, "$CITIES_PATH/$CITIES_FILE", File(cacheDir, CITIES_FILE))
                ?: return@withLock emptyList()
            runCatching { JordanTimetable.parseAreas(body) }
                .getOrDefault(emptyList())
                .also { if (it.isNotEmpty()) cachedAreas = it }
        }
    }

    /** Null when the timetable cannot answer for [now]. */
    suspend fun snapshot(area: String, now: Date): PrayerSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val today = dayTimes(area, now) ?: return@withLock null
            // Tomorrow is only needed once today is exhausted, so a missing next month does not
            // cost us the official times for the rest of the current month.
            val tomorrow = if (today.any { it.time.after(now) }) {
                emptyList()
            } else {
                dayTimes(area, dayAfter(now)).orEmpty()
            }
            buildSnapshot(now, today, tomorrow)
        }
    }

    /**
     * Downloads every month the feed has published for [area], past months included (as the
     * phone app does), so the TV keeps official times offline, across month boundaries, and has
     * them all for the calendar. The current month goes first so today is on disk soonest. Costs
     * one directory listing per call; months already on disk are not downloaded again. Returns
     * false when the listing could not be fetched, in which case whatever is cached keeps being
     * used.
     */
    suspend fun prefetch(area: String, now: Date): Boolean = withContext(Dispatchers.IO) {
        val listing = fetch(MONTHLY_PATH, listing = true) ?: return@withContext false
        val names = runCatching {
            val array = JSONArray(listing)
            List(array.length()) { array.getJSONObject(it).optString("name") }
        }.getOrDefault(emptyList())
        val currentMonth = monthKey(now)
        val suffix = "_$area$JSON_SUFFIX"
        names.asSequence()
            .filter { it.endsWith(suffix) }
            .map { it.removeSuffix(suffix) }
            .filter { MONTH_KEY.matches(it) }
            // yyyy_MM sorts lexically; the current month first, then oldest to newest.
            .sortedWith(compareBy<String> { it != currentMonth }.thenBy { it })
            .forEach { monthKey ->
                mutex.withLock {
                    val cacheFile = monthFile(area, monthKey)
                    if (cacheFile.exists()) return@withLock
                    val body = fetch("$MONTHLY_PATH/${monthKey}$suffix") ?: return@withLock
                    runCatching {
                        cacheDir.mkdirs()
                        cacheFile.writeText(body)
                    }
                    lastFailureAt.remove("$area|$monthKey")
                }
            }
        true
    }

    /**
     * Every day stored on disk for [area], oldest first, for the calendar. Only months [prefetch]
     * has stored are listed, so nothing new is downloaded here; a week-old month may still be
     * revalidated through [month] like any other read.
     */
    suspend fun calendar(area: String): List<CalendarDay> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefix = "${Uri.encode(area)}_"
            cacheDir.listFiles().orEmpty()
                .map { it.name }
                .filter { it.startsWith(prefix) && it.endsWith(JSON_SUFFIX) }
                .map { it.removePrefix(prefix).removeSuffix(JSON_SUFFIX) }
                .filter { MONTH_KEY.matches(it) }
                .flatMap { monthKey -> month(area, monthKey).orEmpty().values }
                .filter { it.isNotEmpty() }
                .map { prayers -> CalendarDay(prayers.first().time, prayers) }
                .sortedBy { it.date }
        }
    }

    private fun dayTimes(area: String, date: Date): List<TimedPrayer>? =
        month(area, monthKey(date))?.get(dateKey(date))?.takeIf { it.isNotEmpty() }

    private fun month(area: String, monthKey: String): Map<String, List<TimedPrayer>>? {
        val key = "$area|$monthKey"
        parsedMonths[key]?.let { return it }
        val fileName = "${monthKey}_$area$JSON_SUFFIX"
        val body = readOrFetch(
            key = key,
            remotePath = "$MONTHLY_PATH/$fileName",
            cacheFile = monthFile(area, monthKey)
        ) ?: return null
        return runCatching { JordanTimetable.parseMonth(body) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.also { parsedMonths[key] = it }
    }

    /**
     * Cache-first with revalidation: a cache entry younger than [REFRESH_AFTER_MILLIS] is used as
     * is, and a stale one is still preferred over nothing when the network fails. Failures are
     * remembered so a missing month is not re-requested on every overlay tick.
     */
    private fun readOrFetch(key: String, remotePath: String, cacheFile: File): String? {
        val cachedAt = if (cacheFile.exists()) cacheFile.lastModified() else 0L
        val isFresh = cachedAt > 0 && System.currentTimeMillis() - cachedAt < REFRESH_AFTER_MILLIS
        if (!isFresh && shouldAttemptFetch(key, hasCache = cachedAt > 0)) {
            val fetched = fetch(remotePath)
            if (fetched != null) {
                lastFailureAt.remove(key)
                runCatching {
                    cacheDir.mkdirs()
                    cacheFile.writeText(fetched)
                }
                return fetched
            }
            lastFailureAt[key] = System.currentTimeMillis()
        }
        if (!cacheFile.exists()) return null
        return runCatching { cacheFile.readText() }.getOrNull()
    }

    private fun monthFile(area: String, monthKey: String) =
        File(cacheDir, "${Uri.encode(area)}_$monthKey$JSON_SUFFIX")

    /**
     * With nothing cached (first setup on a flaky connection) a failure is retried after a minute,
     * so the city list and times appear as soon as the network does; with a cache to fall back on,
     * after six hours, so a permanently missing month is not re-requested every overlay tick.
     */
    private fun shouldAttemptFetch(key: String, hasCache: Boolean): Boolean {
        val failedAt = lastFailureAt[key] ?: return true
        val retryAfter = if (hasCache) RETRY_AFTER_MILLIS else FIRST_FETCH_RETRY_MILLIS
        return System.currentTimeMillis() - failedAt >= retryAfter
    }

    /**
     * The data repo is public and read without any credential, so no app ever carries a token
     * (a token baked into a build was revoked by GitHub secret scanning once it was pushed).
     * Files come from raw.githubusercontent.com, which has no API rate limit; only the monthly/
     * directory listing needs the REST API, which allows 60 unauthenticated requests an hour per
     * device — far more than one listing per session.
     */
    private fun fetch(remotePath: String, listing: Boolean = false): String? {
        val encoded = remotePath.split('/').joinToString("/") { Uri.encode(it) }
        val request = if (listing) {
            Request.Builder()
                .url("$API_BASE/$REPO/contents/$encoded")
                .header("Accept", ACCEPT_JSON)
                .header("X-GitHub-Api-Version", "2022-11-28")
        } else {
            Request.Builder().url("$RAW_BASE/$REPO/$BRANCH/$encoded")
        }.build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun monthKey(date: Date) = format(MONTH_PATTERN, date)

    private fun dateKey(date: Date) = format(DATE_PATTERN, date)

    // Built per call: SimpleDateFormat is not thread safe and this runs on the IO dispatcher.
    private fun format(pattern: String, date: Date) =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = JordanTimetable.ZONE }.format(date)

    private fun dayAfter(date: Date): Date = Calendar.getInstance(JordanTimetable.ZONE).apply {
        time = date
        add(Calendar.DATE, 1)
    }.time

    private companion object {
        const val API_BASE = "https://api.github.com/repos"
        const val RAW_BASE = "https://raw.githubusercontent.com"
        const val BRANCH = "main"
        const val REPO = "mbanifawaz/Jordan_Prayer_Times_API_Data"
        const val CITIES_PATH = "cities"
        const val CITIES_FILE = "cities.json"
        const val MONTHLY_PATH = "monthly"
        const val JSON_SUFFIX = ".json"
        const val CACHE_DIR = "jordan_timetable"
        const val AREAS_KEY = "areas"
        const val MONTH_PATTERN = "yyyy_MM"
        const val DATE_PATTERN = "dd/MM/yyyy"
        val MONTH_KEY = Regex("""\d{4}_\d{2}""")
        const val TIMEOUT_SECONDS = 15L

        /** Directory listings, which have no raw form. */
        const val ACCEPT_JSON = "application/vnd.github+json"
        val REFRESH_AFTER_MILLIS = TimeUnit.DAYS.toMillis(7)
        val RETRY_AFTER_MILLIS = TimeUnit.HOURS.toMillis(6)
        val FIRST_FETCH_RETRY_MILLIS = TimeUnit.MINUTES.toMillis(1)
    }
}
