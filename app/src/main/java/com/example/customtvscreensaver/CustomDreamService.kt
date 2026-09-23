package com.example.customtvscreensaver

import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.request.ImageRequest
import com.example.customtvscreensaver.databinding.LayoutScreensaverOverlayBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import okhttp3.OkHttpClient

class CustomDreamService : DreamService() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private val prayerReader by lazy { PrayerReader(this) }

    // Honours locale date conventions.
    private val dateFormat by lazy { gregorianFormat(HEADER_DATE) }

    private lateinit var imageLoader: ImageLoader
    private lateinit var imageView: ImageView
    private lateinit var creditText: TextView
    private lateinit var overlay: LayoutScreensaverOverlayBinding
    private lateinit var preferences: AppPreferences

    private var slideshowJob: Job? = null
    private var overlayJob: Job? = null
    private var burnInJob: Job? = null
    private var photoIndex = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferences = AppPreferences(this)
        imageLoader = ImageLoader.Builder(this)
            .crossfade(CROSSFADE_MILLIS)
            // Wikimedia blocks generic client User-Agents, so identify the app.
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder().header("User-Agent", USER_AGENT).build()
                        )
                    }
                    .build()
            }
            .build()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        setContentView(buildContentView())
        startSlideshow()
        startOverlayUpdates()
        startBurnInProtection()
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
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            // A dream window does not pick up the wrapped configuration's direction by itself, so
            // Arabic would keep the panel and its text laid out left-to-right.
            layoutDirection = resources.configuration.layoutDirection
        }
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
        // CC BY / BY-SA require the credit to be visible with the photo.
        creditText = TextView(this).apply {
            setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
            textSize = CREDIT_TEXT_SP
            // Same translucent surface as the info panel, so it stays legible on bright photos.
            setBackgroundResource(R.drawable.credit_background)
        }
        root.addView(
            creditText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                // Start/end rather than left/right so Arabic mirrors the layout.
                marginStart = dp(OVERLAY_MARGIN_DP)
                bottomMargin = dp(OVERLAY_MARGIN_DP)
            }
        )
        overlay = LayoutScreensaverOverlayBinding.inflate(LayoutInflater.from(this), root, false)
        root.addView(
            overlay.root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                marginEnd = dp(OVERLAY_MARGIN_DP)
                bottomMargin = dp(OVERLAY_MARGIN_DP)
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

    /**
     * The online/offline choice is re-read per photo, like the interval. An online photo that
     * cannot load (no network, Commons down) is replaced by a bundled one, credit included.
     */
    private fun showPhoto(index: Int) {
        // Online chosen but no internet: go straight to the bundled photos rather than waiting on
        // a request that can only fail. The per-photo fallback below still covers a flaky link.
        val online = preferences.useOnlinePhotos && hasInternet()
        load(PhotoRepository.at(online, index)) {
            if (online) load(PhotoRepository.fallback(index))
        }
    }

    private fun load(photo: Photo, onError: () -> Unit = {}) {
        val current = imageView.drawable
        imageLoader.enqueue(
            ImageRequest.Builder(this)
                .data(photo.data)
                // Keep the current photo up while the next one loads, and if it fails, so the
                // slideshow crossfades photo to photo instead of flashing the background.
                .placeholder(current)
                .error(current)
                .target(imageView)
                .listener(
                    onSuccess = { _, _ -> showCredit(photo) },
                    onError = { _, _ -> onError() }
                )
                .build()
        )
    }

    private fun showCredit(photo: Photo) {
        creditText.text = getString(R.string.photo_credit, getString(photo.place), photo.author, photo.license)
    }

    private fun hasInternet(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?: return false
        // Validated, not merely connected: Wi-Fi without working internet counts as offline.
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** No target, so this only warms Coil's caches for the next transition. */
    private fun preloadPhoto(index: Int) {
        if (!preferences.useOnlinePhotos || !hasInternet()) return
        imageLoader.enqueue(
            ImageRequest.Builder(this).data(PhotoRepository.at(true, index).data).build()
        )
    }

    /**
     * Ticks on the minute boundary rather than on a fixed period, so the displayed clock is never
     * stale and the countdown changes exactly when the minute does.
     */
    private fun startOverlayUpdates() {
        overlayJob = serviceScope.launch {
            updateOverlay()
            if (prayerReader.prefetch(Date())) updateOverlay()
            while (isActive) {
                delay(millisUntilNextMinute())
                updateOverlay()
            }
        }
    }

    private suspend fun updateOverlay() {
        val now = Date()
        val reading = prayerReader.read(now)

        overlay.timeText.text = clockFormat(preferences.use24HourClock).format(now)
        overlay.dateText.text = dateFormat.format(now)
        overlay.hijriText.text = hijriDate(now, preferences.hijriOffsetDays)
        overlay.locationText.text = reading.label
        overlay.nextPrayerText.text = nextPrayerText(reading)
    }

    private fun startBurnInProtection() {
        burnInJob = serviceScope.launch {
            while (isActive) {
                delay(BURN_IN_INTERVAL_MILLIS)
                // Translation rather than margins: same visible nudge, no layout pass.
                overlay.root.translationX = randomShiftPx()
                overlay.root.translationY = randomShiftPx()
                creditText.translationX = randomShiftPx()
                creditText.translationY = randomShiftPx()
            }
        }
    }

    private fun randomShiftPx(): Float {
        val magnitude = Random.nextInt(BURN_IN_MIN_SHIFT_PX, BURN_IN_MAX_SHIFT_PX + 1)
        return (if (Random.nextBoolean()) magnitude else -magnitude).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val CROSSFADE_MILLIS = 800
        const val OVERLAY_MARGIN_DP = 48
        const val CREDIT_TEXT_SP = 13f
        const val USER_AGENT =
            "JordanPrayerTimesTV/${BuildConfig.VERSION_NAME} (https://github.com/mbanifawaz)"
        const val BURN_IN_INTERVAL_MILLIS = 60_000L
        const val BURN_IN_MIN_SHIFT_PX = 2
        const val BURN_IN_MAX_SHIFT_PX = 5
    }
}
