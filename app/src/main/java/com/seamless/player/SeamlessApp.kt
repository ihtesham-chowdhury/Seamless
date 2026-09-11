package com.seamless.player

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import com.seamless.player.data.Prefs
import com.seamless.player.ui.common.ThemeManager

class SeamlessApp : Application() {
    val prefs: Prefs by lazy { Prefs(this) }

    /**
     * Cleared when the process dies, which is exactly the lifetime wanted: unlock once per
     * launch, not once per screen rotation, and never persisted.
     */
    var unlockedThisSession: Boolean = false

    override fun onCreate() {
        super.onCreate()
        // A new install's starting point has to exist before anything reads a setting, the
        // theme just below included.
        prefs.settleDefaults(::neverUpdated)
        // Must happen before any Activity is created, so the very first frame is already
        // in the right mode instead of flashing light-then-dark.
        ThemeManager.applyNightMode(prefs.themeMode)
    }

    /**
     * Installed and never updated since. Updating from an earlier version moves the last-update
     * time on, which is what tells someone who already had the app apart from someone new to it.
     */
    private fun neverUpdated(): Boolean = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        info.firstInstallTime == info.lastUpdateTime
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
