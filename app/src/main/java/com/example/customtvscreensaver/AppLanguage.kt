package com.example.customtvscreensaver

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * The app's own language choice (AppPreferences.appLanguage), applied by wrapping each
 * activity's and the dream's base context. A plain per-context override rather than
 * AppCompatDelegate.setApplicationLocales, because below API 33 that only reaches AppCompat
 * activities, and the dream is a Service.
 *
 * Formatters take their locale from [appLocale] (the wrapped configuration), never from
 * Locale.getDefault(): Android resets the process default on configuration changes, which made the
 * dream fall back to English dates. Arabic is "ar-u-nu-arab" so every formatter uses Eastern Arabic
 * digits, as the phone app does; plain "ar" formats with Latin digits on Android.
 */
object AppLanguage {
    const val SYSTEM = ""
    const val ENGLISH = "en"
    const val ARABIC = "ar"
    private const val ARABIC_WITH_DIGITS = "ar-u-nu-arab"

    fun wrap(base: Context): Context {
        val tag = AppPreferences(base).appLanguage
        val locale = if (tag == SYSTEM) {
            Resources.getSystem().configuration.locales[0]
        } else {
            Locale.forLanguageTag(if (tag == ARABIC) ARABIC_WITH_DIGITS else tag)
        }
        val config = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(locale))
        }
        return base.createConfigurationContext(config)
    }
}

/** The language every screen formats in: the one [AppLanguage.wrap] put in this configuration. */
val Context.appLocale: Locale get() = resources.configuration.locales[0]

/** Applies [AppLanguage], and recreates on resume if the language was changed meanwhile. */
abstract class LocalizedActivity : AppCompatActivity() {
    private var appliedLanguage: String? = null

    override fun attachBaseContext(newBase: Context) {
        appliedLanguage = AppPreferences(newBase).appLanguage
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        if (appliedLanguage != AppPreferences(this).appLanguage) recreate()
    }
}
