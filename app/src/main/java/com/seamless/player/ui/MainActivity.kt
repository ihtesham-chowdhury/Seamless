package com.seamless.player.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.seamless.player.R
import com.seamless.player.SeamlessApp
import com.seamless.player.databinding.ActivityMainBinding
import com.seamless.player.ui.common.AppLock
import com.seamless.player.ui.common.ThemeManager
import com.seamless.player.util.applyBottomMarginInset
import com.seamless.player.util.applyTopSystemInset
import com.seamless.player.ui.library.FoldersFragment
import com.seamless.player.ui.settings.SettingsFragment
import com.seamless.player.ui.shorts.ShortsTabFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must happen before super.onCreate() / setContentView(), while the theme is still
        // mutable — this is what makes the navigation capsule and buttons pick up the accent.
        ThemeManager.applyAccent(this, (application as SeamlessApp).prefs.accentColor)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android draws edge to edge from targetSdk 35 onwards. Handling the insets in the
        // one container every tab lives in means no fragment has to think about it.
        binding.content.applyTopSystemInset()
        binding.navPill.applyBottomMarginInset()

        binding.navLibrary.setOnClickListener { select(R.id.nav_library) }
        binding.navShorts.setOnClickListener { select(R.id.nav_shorts) }
        binding.navSettings.setOnClickListener { select(R.id.nav_settings) }

        // A rotation rebuilds the activity, but the fragment manager restores the tab that
        // was showing, so only the capsule needs putting back where it was.
        selectedTab = savedInstanceState?.getInt(STATE_TAB) ?: R.id.nav_library
        if (savedInstanceState == null) select(selectedTab) else markSelected(selectedTab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, selectedTab)
    }

    /** Which tab the capsule is sitting on. */
    private var selectedTab: Int = R.id.nav_library

    private fun select(id: Int) {
        // Tapping the tab you are already on used to rebuild it. Now it does nothing, which
        // is what the gesture means everywhere else.
        if (id == selectedTab && supportFragmentManager.findFragmentById(R.id.content) != null) {
            return
        }
        selectedTab = id
        markSelected(id)
        show(
            when (id) {
                R.id.nav_shorts -> ShortsTabFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> FoldersFragment()
            }
        )
    }

    /**
     * setSelected on a ViewGroup is dispatched down to its children, so one call moves the
     * capsule and recolours both the icon and its label.
     */
    private fun markSelected(id: Int) {
        binding.navLibrary.isSelected = id == R.id.nav_library
        binding.navShorts.isSelected = id == R.id.nav_shorts
        binding.navSettings.isSelected = id == R.id.nav_settings
    }

    override fun onStart() {
        super.onStart()
        requireUnlock()
    }

    /**
     * Gates the whole app. The content is hidden rather than merely covered, so nothing
     * private is on screen behind the prompt or in the recents thumbnail.
     */
    private fun requireUnlock() {
        val app = application as SeamlessApp
        if (!app.prefs.appLockEnabled || app.unlockedThisSession) {
            binding.content.visibility = View.VISIBLE
            return
        }
        binding.content.visibility = View.INVISIBLE
        AppLock.authenticate(
            activity = this,
            titleRes = R.string.lock_app_prompt_title,
            onSuccess = {
                app.unlockedThisSession = true
                binding.content.visibility = View.VISIBLE
            },
            // Refusing entry means leaving; there is nothing to show.
            onFailure = { finish() },
        )
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, fragment)
            .commit()
    }

    companion object {
        private const val STATE_TAB = "selected_tab"

        /** The permission that lets us read the video library, which differs by OS version. */
        val videoPermission: String
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_VIDEO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        fun hasVideoPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, videoPermission) ==
                PackageManager.PERMISSION_GRANTED
    }
}
