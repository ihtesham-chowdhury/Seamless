package com.seamless.player

import android.app.Application
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
        // Must happen before any Activity is created, so the very first frame is already
        // in the right mode instead of flashing light-then-dark.
        ThemeManager.applyNightMode(prefs.themeMode)
    }
}
