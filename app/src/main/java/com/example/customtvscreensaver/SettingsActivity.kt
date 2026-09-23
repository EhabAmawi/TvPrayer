package com.example.customtvscreensaver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.customtvscreensaver.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Doubles as the launcher entry point and as the dream's settingsActivity (see
 * res/xml/sample_dream.xml), which is also why the runtime location prompt lives here: a
 * DreamService has no way to ask for one.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferences: AppPreferences

    /** True once a denial came back with no rationale left to show, i.e. "don't ask again". */
    private var permissionBlocked = false

    private val jordanRepository by lazy { JordanPrayerRepository(this) }
    private val uiScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var jordanJob: Job? = null
    private var jordanAreas = emptyList<String>()

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.any { it }
        permissionBlocked = !granted &&
            LocationPermissions.REQUIRED.none { shouldShowRequestPermissionRationale(it) }
        renderLocationState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = AppPreferences(this)

        setupLocationMode()
        setupManualCity()
        setupJordanArea()
        setupCalculationMethod()
        setupInterval()
        binding.locationPermissionButton.setOnClickListener {
            if (permissionBlocked) openAppSettings() else requestLocationPermission()
        }
        binding.locationModeSpinner.post { binding.locationModeSpinner.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        // The grant may have changed in system settings while we were away.
        renderLocationState()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun setupLocationMode() {
        binding.locationModeSpinner.bindOptions(
            entries = LocationMode.entries,
            selected = preferences.locationMode
        ) { mode ->
            val changed = mode != preferences.locationMode
            preferences.locationMode = mode
            renderLocationState()
            if (changed && mode == LocationMode.AUTO && !LocationPermissions.isGranted(this)) {
                requestLocationPermission()
            }
        }
    }

    /**
     * The area list comes from the feed rather than a resource, so the spinner is populated
     * asynchronously and stays empty (with an explanatory status) when it cannot be loaded.
     */
    private fun setupJordanArea() {
        binding.jordanAreaSpinner.enableDpadActivation()
        loadJordanAreas()
    }

    private fun loadJordanAreas() {
        if (jordanAreas.isNotEmpty() || !jordanRepository.isConfigured) return
        jordanJob?.cancel()
        jordanJob = uiScope.launch {
            val areas = jordanRepository.areas()
            jordanAreas = areas
            if (areas.isNotEmpty()) {
                val selected = preferences.jordanArea?.takeIf { it in areas } ?: areas.first()
                preferences.jordanArea = selected
                binding.jordanAreaSpinner.bindStrings(areas, selected) { area ->
                    preferences.jordanArea = area
                    renderLocationState()
                    refreshJordanStatus()
                }
            }
            renderLocationState()
            refreshJordanStatus()
        }
    }

    /** Reports whether the chosen area actually has a published timetable right now. */
    private fun refreshJordanStatus() {
        val area = preferences.jordanArea ?: return
        uiScope.launch {
            val available = jordanRepository.snapshot(area, Date()) != null
            if (preferences.locationMode != LocationMode.JORDAN_OFFICIAL) return@launch
            binding.locationStatusText.text = getString(
                if (available) R.string.jordan_status_ready else R.string.jordan_status_unavailable,
                area
            )
        }
    }

    private fun setupManualCity() {
        binding.manualCitySpinner.bindOptions(
            entries = City.entries,
            selected = preferences.manualCity
        ) { city ->
            preferences.manualCity = city
            renderLocationState()
        }
    }

    private fun setupCalculationMethod() {
        binding.calculationMethodSpinner.bindOptions(
            entries = CalculationMethodOption.entries,
            selected = preferences.calculationMethod
        ) { method ->
            preferences.calculationMethod = method
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

    private fun renderLocationState() {
        val mode = preferences.locationMode
        val granted = LocationPermissions.isGranted(this)

        binding.manualCityTitle.isVisible = mode == LocationMode.MANUAL
        binding.manualCitySpinner.isVisible = mode == LocationMode.MANUAL
        val showJordanArea = mode == LocationMode.JORDAN_OFFICIAL && jordanAreas.isNotEmpty()
        binding.jordanAreaTitle.isVisible = showJordanArea
        binding.jordanAreaSpinner.isVisible = showJordanArea
        binding.locationPermissionButton.isVisible = mode == LocationMode.AUTO && !granted
        binding.locationPermissionButton.setText(
            if (permissionBlocked) R.string.open_system_settings else R.string.grant_location_permission
        )
        binding.locationStatusText.text = when (mode) {
            LocationMode.MANUAL -> getString(
                R.string.location_status_manual,
                getString(preferences.manualCity.labelRes)
            )
            LocationMode.JORDAN_OFFICIAL -> when {
                !jordanRepository.isConfigured -> getString(R.string.jordan_status_not_configured)
                jordanAreas.isEmpty() -> getString(R.string.jordan_status_no_areas)
                else -> getString(R.string.jordan_status_loading)
            }
            LocationMode.AUTO ->
                if (granted) getString(R.string.location_status_auto)
                else getString(
                    R.string.location_status_permission_missing,
                    getString(City.DEFAULT.labelRes)
                )
        }
        if (mode == LocationMode.JORDAN_OFFICIAL) loadJordanAreas()
    }

    private fun requestLocationPermission() =
        locationPermissionRequest.launch(LocationPermissions.REQUIRED)

    private fun openAppSettings() = startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
    )
}

/**
 * Builds the spinner straight from the enum, so entry order and labels can never drift apart.
 * The listener is attached after setSelection so restoring the saved value is not reported as a
 * user change.
 */
private fun <T : LabelledOption> Spinner.bindOptions(
    entries: List<T>,
    selected: T,
    onSelected: (T) -> Unit
) {
    adapter = ArrayAdapter(
        context,
        android.R.layout.simple_spinner_dropdown_item,
        entries.map { context.getString(it.labelRes) }
    )
    setSelection(entries.indexOf(selected).coerceAtLeast(0))
    onItemSelectedListener = SimpleSelectionListener { position -> onSelected(entries[position]) }
    enableDpadActivation()
}

/** Same as [bindOptions] but for feed-supplied strings, which have no resource id. */
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
