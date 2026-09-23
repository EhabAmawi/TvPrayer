package com.example.customtvscreensaver

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.customtvscreensaver.databinding.ActivityPrayerTimesBinding
import com.example.customtvscreensaver.databinding.ItemPrayerTimeBinding
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

/**
 * Launcher entry point: today's full timetable with the next prayer highlighted. The screensaver
 * shows the same data through the same [PrayerReader]; settings are one button away.
 */
class PrayerTimesActivity : LocalizedActivity() {
    private lateinit var binding: ActivityPrayerTimesBinding
    private lateinit var preferences: AppPreferences
    private val prayerReader by lazy { PrayerReader(this) }

    private val dateFormat by lazy { gregorianFormat(HEADER_DATE) }

    private val uiScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var refreshJob: Job? = null
    private val cards = mutableMapOf<PrayerName, ItemPrayerTimeBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityPrayerTimesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = AppPreferences(this)

        PrayerName.entries.forEach { name ->
            val card = ItemPrayerTimeBinding.inflate(layoutInflater, binding.prayerRow, true)
            card.prayerName.setText(name.labelRes)
            cards[name] = card
        }
        binding.cityButton.setOnClickListener { chooseCity() }
        binding.calendarButton.setOnClickListener {
            startActivity(Intent(this, PrayerCalendarActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.cityButton.requestFocus()
    }

    /** Settings may have changed while we were away, so each resume restarts from fresh prefs. */
    override fun onResume() {
        super.onResume()
        refreshJob = uiScope.launch {
            render()
            // Fills the offline store in the background of the first frame, then shows its data.
            if (prayerReader.prefetch(Date())) render()
            while (isActive) {
                delay(millisUntilNextMinute())
                render()
            }
        }
    }

    override fun onPause() {
        refreshJob?.cancel()
        refreshJob = null
        super.onPause()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private suspend fun render() {
        val now = Date()
        val reading = prayerReader.read(now)
        val snapshot = reading.snapshot
        val timeFormat = clockFormat(preferences.use24HourClock)

        binding.timeText.text = timeFormat.format(now)
        binding.dateText.text = dateFormat.format(now)
        binding.hijriText.text = hijriDate(now, preferences.hijriOffsetDays)
        binding.cityButton.text = reading.label
        binding.nextPrayerText.text = nextPrayerText(reading)
        val times = snapshot?.today.orEmpty().associate { it.name to it.time }
        cards.forEach { (name, card) ->
            card.prayerTime.text = times[name]?.let(timeFormat::format) ?: getString(R.string.time_unknown)
            card.root.isSelected = name == snapshot?.next
        }
    }

    /** A plain list dialog: it already handles the remote's D-pad and Back. */
    private fun chooseCity() {
        uiScope.launch {
            val areas = prayerReader.areas()
            if (areas.isEmpty()) {
                Toast.makeText(
                    this@PrayerTimesActivity,
                    R.string.jordan_areas_unavailable,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val selected = prayerReader.area()
            AlertDialog.Builder(this@PrayerTimesActivity)
                .setTitle(R.string.jordan_area_title)
                .setSingleChoiceItems(
                    areas.toTypedArray(),
                    areas.indexOf(selected)
                ) { dialog, which ->
                    preferences.jordanArea = areas[which]
                    dialog.dismiss()
                    uiScope.launch {
                        render()
                        if (prayerReader.prefetch(Date())) render()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private companion object {
    }
}
