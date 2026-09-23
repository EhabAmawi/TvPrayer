package com.example.customtvscreensaver

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.customtvscreensaver.databinding.ActivityPrayerCalendarBinding
import com.example.customtvscreensaver.databinding.ItemCalendarRowBinding
import com.example.customtvscreensaver.databinding.ItemPrayerTimeBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every stored day for the chosen area, as the phone app's Prayer Calendar: opens on today, steps
 * a day at a time through the days that actually exist, and toggles to a list of all of them.
 * Reads only what [PrayerReader.prefetch] has stored, so it works offline.
 */
class PrayerCalendarActivity : LocalizedActivity() {
    private lateinit var binding: ActivityPrayerCalendarBinding
    private lateinit var preferences: AppPreferences
    private val prayerReader by lazy { PrayerReader(this) }
    private val uiScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var days: List<CalendarDay> = emptyList()
    private var selectedIndex = 0
    private var showAllDays = false
    private val dayCards = mutableMapOf<PrayerName, ItemPrayerTimeBinding>()

    private val dayFormat by lazy { gregorianFormat(DAY_DATE) }
    private val shortFormat by lazy { gregorianFormat(SHORT_DATE) }
    private val rangeFormat by lazy { gregorianFormat(RANGE_DATE) }
    /** Compares days in Jordan's zone, independent of the display language. */
    private val keyFormat by lazy {
        SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = JordanTimetable.ZONE }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrayerCalendarBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = AppPreferences(this)

        PrayerName.entries.forEach { name ->
            val card = ItemPrayerTimeBinding.inflate(layoutInflater, binding.dayRow, true)
            card.prayerName.setText(name.labelRes)
            dayCards[name] = card
        }
        binding.previousDayButton.setOnClickListener { step(-1) }
        binding.nextDayButton.setOnClickListener { step(1) }
        binding.modeButton.setOnClickListener {
            showAllDays = !showAllDays
            render()
        }

        uiScope.launch {
            days = prayerReader.calendar()
            selectedIndex = todayIndex()
            render()
            binding.nextDayButton.requestFocus()
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    /** Today, or the nearest stored day when today is outside what the feed has published. */
    private fun todayIndex(): Int {
        if (days.isEmpty()) return 0
        val today = keyFormat.format(Date())
        val index = days.indexOfFirst { keyFormat.format(it.date) >= today }
        return if (index < 0) days.lastIndex else index
    }

    private fun step(offset: Int) {
        val target = selectedIndex + offset
        if (target !in days.indices) return
        selectedIndex = target
        render()
    }

    private fun render() {
        val empty = days.isEmpty()
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        listOf(binding.previousDayButton, binding.nextDayButton, binding.modeButton)
            .forEach { it.visibility = if (empty) View.INVISIBLE else View.VISIBLE }
        if (empty) {
            binding.dayRow.visibility = View.GONE
            binding.allDaysScroll.visibility = View.GONE
            return
        }

        binding.modeButton.setText(
            if (showAllDays) R.string.calendar_single_day else R.string.calendar_all_days
        )
        // Stepping only means something for a single day, so the list hides the arrows.
        val stepVisibility = if (showAllDays) View.INVISIBLE else View.VISIBLE
        binding.previousDayButton.visibility = stepVisibility
        binding.nextDayButton.visibility = stepVisibility
        binding.previousDayButton.isEnabled = selectedIndex > 0
        binding.nextDayButton.isEnabled = selectedIndex < days.lastIndex
        binding.dayRow.visibility = if (showAllDays) View.GONE else View.VISIBLE
        binding.allDaysScroll.visibility = if (showAllDays) View.VISIBLE else View.GONE

        val day = days[selectedIndex]
        if (showAllDays) {
            binding.dayTitle.setText(R.string.calendar_all_days)
            binding.dayHijri.text = getString(
                R.string.calendar_range,
                rangeFormat.format(days.first().date),
                rangeFormat.format(days.last().date)
            )
            renderAllDays()
        } else {
            binding.dayTitle.text = dayTitle(day)
            binding.dayHijri.text = hijriDate(day.date, preferences.hijriOffsetDays)
            val timeFormat = clockFormat(preferences.use24HourClock)
            val times = day.prayers.associate { it.name to it.time }
            dayCards.forEach { (name, card) ->
                card.prayerTime.text =
                    times[name]?.let(timeFormat::format) ?: getString(R.string.time_unknown)
            }
        }
    }

    /** Built on each switch to the list, so a clock-format change is always reflected. */
    private fun renderAllDays() {
        val list = binding.allDaysList
        list.removeAllViews()
        list.addView(headerRow())

        val timeFormat = clockFormat(preferences.use24HourClock)
        val today = keyFormat.format(Date())
        var todayRow: View? = null
        days.forEach { day ->
            val row = ItemCalendarRowBinding.inflate(layoutInflater, list, false)
            val isToday = keyFormat.format(day.date) == today
            row.rowDate.text = dayTitle(day, shortFormat)
            row.rowHijri.text = hijriDate(day.date, preferences.hijriOffsetDays)
            row.root.isSelected = isToday
            if (isToday) {
                row.rowDate.setTextColor(ContextCompat.getColor(this, R.color.accent_teal))
                todayRow = row.root
            }
            val times = day.prayers.associate { it.name to it.time }
            PrayerName.entries.forEach { name ->
                row.rowTimes.addView(
                    column(times[name]?.let(timeFormat::format) ?: getString(R.string.time_unknown))
                )
            }
            list.addView(row.root)
        }

        // Land on today (or the day being viewed) rather than at the top of every month.
        val target = todayRow ?: list.getChildAt(selectedIndex + 1)
        target?.post {
            binding.allDaysScroll.scrollTo(0, (target.top - target.height).coerceAtLeast(0))
            target.requestFocus()
        }
    }

    /** Prayer names above the time columns, aligned with them by the same weights. */
    private fun headerRow(): View {
        val row = ItemCalendarRowBinding.inflate(layoutInflater, binding.allDaysList, false)
        row.root.isFocusable = false
        row.root.background = null
        PrayerName.entries.forEach { name ->
            row.rowTimes.addView(column(getString(name.labelRes), secondary = true))
        }
        return row.root
    }

    private fun column(text: String, secondary: Boolean = false) = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        gravity = Gravity.CENTER
        textSize = if (secondary) 14f else 18f
        setTextColor(
            ContextCompat.getColor(
                this@PrayerCalendarActivity,
                if (secondary) R.color.overlay_secondary else R.color.overlay_text
            )
        )
        this.text = text
    }

    private fun dayTitle(day: CalendarDay, format: SimpleDateFormat = dayFormat): String {
        val date = format.format(day.date)
        return if (keyFormat.format(day.date) == keyFormat.format(Date())) {
            getString(R.string.calendar_today_title, getString(R.string.calendar_today), date)
        } else {
            date
        }
    }


    private companion object {
        const val RANGE_DATE = "MMM d, y"
    }
}
