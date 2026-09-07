package com.seamless.player.ui.common

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import com.seamless.player.data.AccentColor
import com.seamless.player.data.ThemeMode

/**
 * Applies the two appearance settings.
 *
 * These are two different mechanisms on purpose. Night mode is a framework-level concept —
 * AppCompatDelegate owns it, applies it process-wide, and recreates every live
 * AppCompatActivity on its own. An accent colour is not: it is an ordinary theme overlay,
 * which only takes effect if applied before an activity's views inflate, so changing it
 * requires the caller to recreate the activity itself.
 */
object ThemeManager {

    fun applyNightMode(mode: ThemeMode) {
        val target = when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }

    /** Call before super.onCreate(), before any view is inflated. */
    fun applyAccent(activity: Activity, accent: AccentColor) {
        AccentColors.overlayStyleRes(accent)?.let { activity.theme.applyStyle(it, true) }
    }
}
