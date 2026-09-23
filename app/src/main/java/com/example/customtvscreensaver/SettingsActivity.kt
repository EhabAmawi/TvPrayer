package com.example.customtvscreensaver

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import com.example.customtvscreensaver.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Date

/** Opened from PrayerTimesActivity and as the dream's settingsActivity (res/xml/sample_dream.xml). */
class SettingsActivity : LocalizedActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferences: AppPreferences

    private val prayerReader by lazy { PrayerReader(this) }
    private val jordanRepository by lazy { JordanPrayerRepository(this) }
    private val uiScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = AppPreferences(this)

        binding.backButton.setOnClickListener { finish() }
        setupJordanArea()
        setupLanguage()
        setupClockFormat()
        setupHijriOffset()
        setupPhotoSource()
        setupInterval()
        binding.aboutVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        binding.jordanAreaSpinner.post { binding.jordanAreaSpinner.requestFocus() }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    /**
     * Only the feed's own list is offered. Until cities.json has loaded once the spinner stays
     * empty and the status line says so.
     */
    private fun setupJordanArea() {
        binding.jordanAreaSpinner.enableDpadActivation()
        uiScope.launch {
            val areas = prayerReader.areas()
            binding.jordanAreaSpinner.bindStrings(areas, prayerReader.area()) { area ->
                preferences.jordanArea = area
                refreshJordanStatus()
            }
            refreshJordanStatus()
        }
    }

    /** Reports whether the chosen area actually has a published timetable right now. */
    private fun refreshJordanStatus() {
        if (!jordanRepository.isConfigured) {
            binding.locationStatusText.setText(R.string.jordan_status_not_configured)
            return
        }
        binding.locationStatusText.setText(R.string.jordan_status_loading)
        uiScope.launch {
            val area = prayerReader.area() ?: run {
                binding.locationStatusText.setText(R.string.jordan_areas_unavailable)
                return@launch
            }
            val available = jordanRepository.snapshot(area, Date()) != null
            binding.locationStatusText.text = getString(
                if (available) R.string.jordan_status_ready else R.string.jordan_status_unavailable,
                area
            )
        }
    }

    /** Each language is named in itself, so it can be found whatever the current language. */
    private fun setupLanguage() {
        val options = linkedMapOf(
            AppLanguage.SYSTEM to getString(R.string.language_system),
            AppLanguage.ENGLISH to getString(R.string.language_english),
            AppLanguage.ARABIC to getString(R.string.language_arabic)
        )
        binding.languageSpinner.enableDpadActivation()
        binding.languageSpinner.bindStrings(
            entries = options.values.toList(),
            selected = options[preferences.appLanguage]
        ) { label ->
            val tag = options.entries.first { it.value == label }.key
            if (tag != preferences.appLanguage) {
                preferences.appLanguage = tag
                recreate()
            }
        }
    }

    private fun setupHijriOffset() {
        val offsets = AppPreferences.HIJRI_OFFSETS.toList()
        val labels = offsets.map { days ->
            when {
                days == 0 -> getString(R.string.hijri_offset_none)
                days > 0 -> resources.getQuantityString(R.plurals.hijri_offset_plus, days, days)
                else -> resources.getQuantityString(R.plurals.hijri_offset_minus, -days, -days)
            }
        }
        binding.hijriOffsetSpinner.enableDpadActivation()
        binding.hijriOffsetSpinner.bindStrings(
            entries = labels,
            selected = labels[offsets.indexOf(preferences.hijriOffsetDays)]
        ) { label ->
            preferences.hijriOffsetDays = offsets[labels.indexOf(label)]
        }
    }

    /** Labels include an example so the difference is obvious on a TV from across the room. */
    private fun setupClockFormat() {
        val twelve = getString(R.string.clock_12_hour)
        val twentyFour = getString(R.string.clock_24_hour)
        binding.clockFormatSpinner.enableDpadActivation()
        binding.clockFormatSpinner.bindStrings(
            entries = listOf(twelve, twentyFour),
            selected = if (preferences.use24HourClock) twentyFour else twelve
        ) { label ->
            preferences.use24HourClock = label == twentyFour
        }
    }

    private fun setupPhotoSource() {
        val online = getString(R.string.photos_online)
        val offline = getString(R.string.photos_offline)
        binding.photoSourceSpinner.enableDpadActivation()
        binding.photoSourceSpinner.bindStrings(
            entries = listOf(online, offline),
            selected = if (preferences.useOnlinePhotos) online else offline
        ) { label ->
            preferences.useOnlinePhotos = label == online
        }
    }

    private fun setupInterval() {
        // Bounds come from AppPreferences so the clamp and the control cannot disagree.
        with(binding.intervalSeekbar) {
            min = AppPreferences.MIN_INTERVAL_SECONDS
            max = AppPreferences.MAX_INTERVAL_SECONDS
            progress = preferences.slideshowIntervalSeconds
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    preferences.slideshowIntervalSeconds = progress
                    renderIntervalLabel(progress)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        renderIntervalLabel(preferences.slideshowIntervalSeconds)
    }

    private fun renderIntervalLabel(seconds: Int) {
        binding.intervalValue.text =
            resources.getQuantityString(R.plurals.slideshow_interval_format, seconds, seconds)
    }
}

private fun Spinner.bindStrings(
    entries: List<String>,
    selected: String?,
    onSelected: (String) -> Unit
) {
    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, entries)
    setSelection(entries.indexOf(selected).coerceAtLeast(0))
    onItemSelectedListener = SimpleSelectionListener { position -> onSelected(entries[position]) }
}

/**
 * Plain AppCompat widgets (rather than Leanback ones) do not open on the remote's centre key,
 * so translate it - and the equivalent gamepad keys - into a click.
 */
private fun View.enableDpadActivation() {
    setOnKeyListener { target, keyCode, event ->
        val activation = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A
        if (event.action == KeyEvent.ACTION_DOWN && activation) {
            target.performClick()
            true
        } else {
            false
        }
    }
}
