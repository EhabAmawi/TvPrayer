package com.example.customtvscreensaver

import android.annotation.SuppressLint
import android.graphics.Color
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import coil.ImageLoader
import coil.request.ImageRequest
import com.example.customtvscreensaver.databinding.LayoutScreensaverOverlayBinding
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class CustomDreamService : DreamService() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val jordanRepository by lazy { JordanPrayerRepository(this) }

    // Honours the user's 12/24-hour setting and locale date conventions.
    private val timeFormat by lazy { DateFormat.getTimeFormat(this) }
    private val dateFormat by lazy {
        SimpleDateFormat(
            DateFormat.getBestDateTimePattern(Locale.getDefault(), DATE_SKELETON),
            Locale.getDefault()
        )
    }

    private lateinit var imageLoader: ImageLoader
    private lateinit var imageView: ImageView
    private lateinit var overlay: LayoutScreensaverOverlayBinding
    private lateinit var preferences: AppPreferences

    private var slideshowJob: Job? = null
    private var overlayJob: Job? = null
    private var burnInJob: Job? = null
    private var photoIndex = 0

    /** Populated asynchronously; null until (and unless) a device fix arrives. */
    private var autoLocation: ResolvedLocation? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferences = AppPreferences(this)
        imageLoader = ImageLoader.Builder(this)
            .crossfade(CROSSFADE_MILLIS)
            .build()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        setContentView(buildContentView())
        startSlideshow()
        startOverlayUpdates()
        startBurnInProtection()
        requestAutoLocation()
    }

    override fun onDreamingStopped() {
        releaseDreamResources()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        releaseDreamResources()
        serviceScope.cancel()
        super.onDetachedFromWindow()
    }

    /**
     * Called from both teardown callbacks - onDreamingStopped fires when the dream ends, and
     * onDetachedFromWindow can fire without it - so it must be idempotent.
     */
    private fun releaseDreamResources() {
        slideshowJob?.cancel()
        overlayJob?.cancel()
        burnInJob?.cancel()
        slideshowJob = null
        overlayJob = null
        burnInJob = null
        if (::imageView.isInitialized) imageView.setImageDrawable(null)
        if (::imageLoader.isInitialized) imageLoader.memoryCache?.clear()
    }

    /**
     * The dream root is built in code; only the info panel comes from XML
     * (layout_screensaver_overlay.xml). Panel placement lives here, panel contents live there.
     */
    private fun buildContentView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.dream_preview)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(
            imageView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        overlay = LayoutScreensaverOverlayBinding.inflate(LayoutInflater.from(this), root, false)
        root.addView(
            overlay.root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                val margin = dp(OVERLAY_MARGIN_DP)
                setMargins(0, 0, margin, margin)
            }
        )
        return root
    }

    private fun startSlideshow() {
        slideshowJob = serviceScope.launch {
            while (isActive) {
                showPhoto(photoIndex)
                preloadPhoto(photoIndex + 1)
                photoIndex++
                // Re-read every tick so an interval change applies without restarting the dream.
                delay(preferences.slideshowIntervalSeconds * 1_000L)
            }
        }
    }

    private fun showPhoto(index: Int) {
        val photo = PhotoRepository.at(index)
        imageLoader.enqueue(
            ImageRequest.Builder(this)
                .data(photo.remoteUrl)
                .target(imageView)
                .listener(onError = { _, _ -> imageView.setImageResource(photo.localDrawable) })
                .build()
        )
    }

    /** No target, so this only warms Coil's caches for the next transition. */
    private fun preloadPhoto(index: Int) {
        imageLoader.enqueue(
            ImageRequest.Builder(this).data(PhotoRepository.at(index).remoteUrl).build()
        )
    }

    /**
     * Ticks on the minute boundary rather than on a fixed period, so the displayed clock is never
     * stale and the countdown changes exactly when the minute does.
     */
    private fun startOverlayUpdates() {
        overlayJob = serviceScope.launch {
            while (isActive) {
                updateOverlay()
                delay(millisUntilNextMinute())
            }
        }
    }

    private suspend fun updateOverlay() {
        val now = Date()
        // Read fresh each tick so settings changes take effect while the dream is running.
        val reading = when (preferences.locationMode) {
            LocationMode.JORDAN_OFFICIAL -> jordanReading(now)
            LocationMode.AUTO -> calculated(autoLocation ?: City.DEFAULT.toLocation(), now)
            LocationMode.MANUAL -> calculated(preferences.manualCity.toLocation(), now)
        }

        overlay.timeText.text = timeFormat.format(now)
        overlay.dateText.text = dateFormat.format(now)
        overlay.locationText.text = reading.label
        overlay.nextPrayerText.text = getString(
            R.string.overlay_next_prayer,
            getString(reading.snapshot.next.labelRes).uppercase(Locale.getDefault()),
            formatCountdown(reading.snapshot.remainingMillis)
        )
    }

    /**
     * The official timetable only covers a rolling window of months, so a miss silently falls
     * back to calculating for that area's coordinates. The label stays the area name either way,
     * because the location is the same - only the source of the times differs.
     */
    private suspend fun jordanReading(now: Date): OverlayReading {
        val area = preferences.jordanArea ?: return calculated(City.DEFAULT.toLocation(), now)
        jordanRepository.snapshot(area, now)?.let { return OverlayReading(area, it) }
        val (latitude, longitude) = JordanAreas.fallbackCoordinates(area)
        return OverlayReading(area, calculate(latitude, longitude, now))
    }

    private fun calculated(location: ResolvedLocation, now: Date) = OverlayReading(
        label = getString(location.labelRes),
        snapshot = calculate(location.latitude, location.longitude, now)
    )

    private fun calculate(latitude: Double, longitude: Double, now: Date) = PrayerManager(
        latitude = latitude,
        longitude = longitude,
        method = preferences.calculationMethod
    ).snapshot(now)

    private data class OverlayReading(val label: String, val snapshot: PrayerSnapshot)

    private fun formatCountdown(remainingMillis: Long): String {
        val totalMinutes = remainingMillis / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            getString(R.string.countdown_hours_minutes, hours, minutes)
        } else {
            getString(R.string.countdown_minutes, minutes)
        }
    }

    private fun startBurnInProtection() {
        burnInJob = serviceScope.launch {
            while (isActive) {
                delay(BURN_IN_INTERVAL_MILLIS)
                // Translation rather than margins: same visible nudge, no layout pass.
                overlay.root.translationX = randomShiftPx()
                overlay.root.translationY = randomShiftPx()
            }
        }
    }

    private fun randomShiftPx(): Float {
        val magnitude = Random.nextInt(BURN_IN_MIN_SHIFT_PX, BURN_IN_MAX_SHIFT_PX + 1)
        return (if (Random.nextBoolean()) magnitude else -magnitude).toFloat()
    }

    /**
     * A dream cannot prompt for permissions, so this is best-effort only: without a grant (see
     * SettingsActivity) the overlay stays on the fallback city.
     */
    @SuppressLint("MissingPermission")
    private fun requestAutoLocation() {
        if (preferences.locationMode != LocationMode.AUTO) return
        if (!LocationPermissions.isGranted(this)) return
        locationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location == null) return@addOnSuccessListener
                autoLocation = ResolvedLocation(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    labelRes = R.string.location_current
                )
                // The callback can outlive the dream; only touch views while it is running.
                if (overlayJob?.isActive == true) serviceScope.launch { updateOverlay() }
            }
    }

    private fun millisUntilNextMinute(): Long =
        (60_000L - System.currentTimeMillis() % 60_000L).coerceAtLeast(1_000L)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val DATE_SKELETON = "EEEEdMMMM"
        const val CROSSFADE_MILLIS = 800
        const val OVERLAY_MARGIN_DP = 48
        const val BURN_IN_INTERVAL_MILLIS = 60_000L
        const val BURN_IN_MIN_SHIFT_PX = 2
        const val BURN_IN_MAX_SHIFT_PX = 5
    }
}
