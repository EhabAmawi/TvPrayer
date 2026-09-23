package com.example.customtvscreensaver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * A DreamService cannot prompt for permissions, so the request lives in SettingsActivity and
 * the dream only ever checks. Coarse accuracy is enough for prayer calculations, so either
 * grant counts.
 */
object LocationPermissions {
    val REQUIRED = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun isGranted(context: Context): Boolean = REQUIRED.any { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
