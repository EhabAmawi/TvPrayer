package com.example.customtvscreensaver

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ConnectException

/**
 * Makes this app the system screensaver, for TVs whose settings hide the picker (Google TV only
 * offers its own Ambient mode). The choice is the secure setting `screensaver_components`, which
 * needs `WRITE_SECURE_SETTINGS`. That is a development permission: only adb can grant it, so the
 * first time, with USB debugging on, the app connects to the TV's own adb on localhost:5555 (the
 * TV asks the user to allow it) and grants the permission to itself. After that the setting is
 * written directly and USB debugging can be turned off again.
 */
object ScreensaverSetter {
    enum class Result {
        /** This app is now the screensaver. */
        SET,
        /** adb is not listening: USB debugging is off, or this TV does not offer adb over the network. */
        NEEDS_DEBUGGING,
        /** adb answered but the connection was not allowed on the TV. */
        NOT_ALLOWED,
        FAILED
    }

    private const val KEY_COMPONENTS = "screensaver_components"
    private const val KEY_ENABLED = "screensaver_enabled"
    private const val ADB_HOST = "127.0.0.1"
    private const val ADB_PORT = 5555
    private const val CONNECT_TIMEOUT_MS = 3_000

    /** Long enough for someone to read the TV's "Allow USB debugging?" prompt and press Allow. */
    private const val AUTH_TIMEOUT_MS = 60_000

    fun component(context: Context): String =
        ComponentName(context, CustomDreamService::class.java).flattenToString()

    fun isActive(context: Context): Boolean =
        current(context)?.split(',')?.firstOrNull() == component(context)

    private fun current(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, KEY_COMPONENTS)

    private fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun set(context: Context, preferences: AppPreferences): Result =
        withContext(Dispatchers.IO) {
            if (!canWrite(context)) {
                val granted = grantViaAdb(context)
                if (granted != Result.SET) return@withContext granted
            }
            val previous = current(context)
            if (!previous.isNullOrBlank() && previous != component(context)) {
                preferences.previousScreensaver = previous
            }
            write(context, component(context))
        }

    /** How long the TV waits, idle, before the screensaver starts ("When to start" in TV settings). */
    fun startAfterMillis(context: Context): Int =
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)

    /**
     * Changes that delay. It is a system setting, so it needs WRITE_SETTINGS; installs that got
     * only WRITE_SECURE_SETTINGS from an earlier version are topped up through adb once.
     */
    suspend fun setStartAfter(context: Context, millis: Int): Result = withContext(Dispatchers.IO) {
        if (!Settings.System.canWrite(context)) {
            val granted = grantViaAdb(context)
            if (granted != Result.SET && !Settings.System.canWrite(context)) return@withContext granted
        }
        try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, millis)
            Result.SET
        } catch (_: Exception) {
            Result.FAILED
        }
    }

    /**
     * Starts the current screensaver right away, through SystemUI's Somnambulator (the component
     * the launcher's "start screensaver" shortcut uses). Returns false where it is not available.
     */
    fun startNow(context: Context): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName("com.android.systemui", "com.android.systemui.Somnambulator"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** Puts back whatever was the screensaver before this app took over (Ambient mode on Google TV). */
    suspend fun restore(context: Context, preferences: AppPreferences): Result =
        withContext(Dispatchers.IO) {
            val previous = preferences.previousScreensaver ?: return@withContext Result.FAILED
            if (!canWrite(context)) return@withContext Result.FAILED
            write(context, previous).also {
                if (it == Result.SET) preferences.previousScreensaver = null
            }
        }

    private fun write(context: Context, value: String): Result = try {
        Settings.Secure.putString(context.contentResolver, KEY_COMPONENTS, value)
        Settings.Secure.putInt(context.contentResolver, KEY_ENABLED, 1)
        Result.SET
    } catch (_: SecurityException) {
        Result.FAILED
    }

    private fun grantViaAdb(context: Context): Result {
        val keyPair = try {
            keyPair(context)
        } catch (_: Exception) {
            return Result.FAILED
        }
        return try {
            Dadb.create(ADB_HOST, ADB_PORT, keyPair, CONNECT_TIMEOUT_MS, AUTH_TIMEOUT_MS).use { adb ->
                adb.shell("pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
                // Also the "When to start" delay, which is a system (not secure) setting.
                adb.shell("appops set ${context.packageName} WRITE_SETTINGS allow")
            }
            if (canWrite(context)) Result.SET else Result.FAILED
        } catch (e: Exception) {
            when {
                e.hasCause<ConnectException>() -> Result.NEEDS_DEBUGGING
                e.hasCause<IOException>() -> Result.NOT_ALLOWED
                else -> Result.FAILED
            }
        }
    }

    /** One key pair per install, so the TV remembers the app after "Always allow". */
    private fun keyPair(context: Context): AdbKeyPair {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val private = File(dir, "adbkey")
        val public = File(dir, "adbkey.pub")
        if (!private.exists() || !public.exists()) AdbKeyPair.generate(private, public)
        return AdbKeyPair.read(private, public)
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(this) { it.cause }.any { it is T }
}
