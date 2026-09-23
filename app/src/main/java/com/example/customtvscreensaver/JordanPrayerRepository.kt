package com.example.customtvscreensaver

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
 * published (September is typically absent in August), one area has no file at all, and the
 * token may not be configured in this build. Every one of those cases means "caller should use
 * the adhan calculation instead", which is why nothing is surfaced as an error.
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

    /** False when this build has no data token, in which case the feed is simply unavailable. */
    val isConfigured: Boolean get() = BuildConfig.JORDAN_API_TOKEN.isNotBlank()

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

    /** Null when the timetable cannot answer for [now]; the caller should then use adhan. */
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

    private fun dayTimes(area: String, date: Date): List<TimedPrayer>? =
        month(area, monthKey(date))?.get(dateKey(date))?.takeIf { it.isNotEmpty() }

    private fun month(area: String, monthKey: String): Map<String, List<TimedPrayer>>? {
        val key = "$area|$monthKey"
        parsedMonths[key]?.let { return it }
        val fileName = "${monthKey}_$area$JSON_SUFFIX"
        val body = readOrFetch(
            key = key,
            remotePath = "$MONTHLY_PATH/$fileName",
            cacheFile = File(cacheDir, "${Uri.encode(area)}_$monthKey$JSON_SUFFIX")
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
        if (!isFresh && shouldAttemptFetch(key)) {
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

    private fun shouldAttemptFetch(key: String): Boolean {
        if (!isConfigured) return false
        val failedAt = lastFailureAt[key] ?: return true
        return System.currentTimeMillis() - failedAt >= RETRY_AFTER_MILLIS
    }

    private fun fetch(remotePath: String): String? {
        val encoded = remotePath.split('/').joinToString("/") { Uri.encode(it) }
        val request = Request.Builder()
            .url("$API_BASE/$REPO/contents/$encoded")
            .header("Authorization", "Bearer ${BuildConfig.JORDAN_API_TOKEN}")
            .header("Accept", "application/vnd.github.raw+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
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
        const val REPO = "mbanifawaz/Jordan_Prayer_Times_API_Data"
        const val CITIES_PATH = "cities"
        const val CITIES_FILE = "cities.json"
        const val MONTHLY_PATH = "monthly"
        const val JSON_SUFFIX = ".json"
        const val CACHE_DIR = "jordan_timetable"
        const val AREAS_KEY = "areas"
        const val MONTH_PATTERN = "yyyy_MM"
        const val DATE_PATTERN = "dd/MM/yyyy"
        const val TIMEOUT_SECONDS = 15L
        val REFRESH_AFTER_MILLIS = TimeUnit.DAYS.toMillis(7)
        val RETRY_AFTER_MILLIS = TimeUnit.HOURS.toMillis(6)
    }
}
