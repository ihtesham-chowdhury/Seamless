package com.seamless.player.ui

import android.view.animation.PathInterpolator
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
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

        // Rotation, a resized window: the tabs change width, so the island follows them without
        // animating, since nothing was chosen. Posted, because resizing the island from inside a
        // layout pass would ask for another layout in the middle of this one.
        binding.navRow.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                binding.navRow.post { placeIsland(selectedTab, animate = false) }
            }
        }

        // A rotation rebuilds the activity, but the fragment manager restores the tab that
        // was showing, so only the capsule needs putting back where it was.
        selectedTab = savedInstanceState?.getInt(STATE_TAB) ?: R.id.nav_library
        if (savedInstanceState == null) select(selectedTab) else markSelected(selectedTab, animate = false)
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
        markSelected(id, animate = true)
        show(
            when (id) {
                R.id.nav_shorts -> ShortsTabFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> FoldersFragment()
            }
        )
    }

    /**
     * setSelected on a ViewGroup is dispatched down to its children, so one call recolours both
     * the icon and its label. The label also takes a step more weight — never more size, which
     * would nudge its icon up as the island arrived. The island moves separately, because it
     * belongs to no one tab.
     */
    private fun markSelected(id: Int, animate: Boolean) {
        for (tab in listOf(binding.navLibrary, binding.navShorts, binding.navSettings)) {
            val selected = tab.id == id
            tab.isSelected = selected
            for (i in 0 until tab.childCount) {
                (tab.getChildAt(i) as? TextView)?.typeface = if (selected) LABEL_SELECTED else LABEL
            }
        }
        placeIsland(id, animate)
    }

    /**
     * Puts the accent island under [id]'s tab.
     *
     * Width as well as position, because the tabs share the capsule by weight and nobody knows how
     * wide they are until layout has run. Before then this waits a frame and places the island
     * without animating; there is nothing to animate from when the screen has just appeared.
     *
     * The move is translation only, on the render thread, with the emphasised curve Material
     * uses for things changing place: quick to leave, slow to settle, so the island reads as
     * gliding to the tab rather than being dragged there.
     */
    private fun placeIsland(id: Int, animate: Boolean) {
        val tab = binding.navRow.findViewById<View>(id) ?: return
        if (tab.width == 0) {
            binding.navRow.post { placeIsland(id, animate = false) }
            return
        }
        val island = binding.navIsland
        // Narrower than its tab by an inset either side, so the island floats inside the capsule
        // with the same air around it as above and below, instead of filling its third edge to edge.
        val inset = resources.getDimensionPixelSize(R.dimen.nav_island_inset)
        val islandWidth = (tab.width - inset * 2).coerceAtLeast(0)
        if (island.layoutParams.width != islandWidth) {
            island.layoutParams = island.layoutParams.apply { width = islandWidth }
        }
        val target = (tab.left + inset).toFloat()
        island.animate().cancel()
        if (!animate || island.visibility != View.VISIBLE) {
            island.translationX = target
            island.visibility = View.VISIBLE
            return
        }
        island.animate()
            .translationX(target)
            .setDuration(ISLAND_MOVE_MS)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
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

        /** Long enough to be seen travelling, short enough not to be waited for. */
        const val ISLAND_MOVE_MS = 280L

        /**
         * Tab labels: medium at rest, one step heavier under the island. 600 is a true semibold
         * where the system font is variable (Android 12 and later) and the nearest weight before.
         */
        private val LABEL: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        private val LABEL_SELECTED: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)

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
