package com.seamless.player.ui.common

import android.app.Activity
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.StringRes
import com.seamless.player.R
import com.seamless.player.data.Prefs
import com.seamless.player.databinding.ViewTipBinding

/**
 * One-time hints, shown where the thing they describe actually is.
 *
 * The alternative — a carousel of screens on first launch — is worse for two reasons. It
 * arrives before any of it means anything, so none of it is remembered; and it has to cover
 * everything at once, which is why nobody reads them. A sentence about long-pressing folders,
 * shown the first time you are looking at a list of folders, is read because it is about what
 * is on the screen.
 *
 * Each tip is shown once ever. The full guide in Settings is for anyone who wants the rest,
 * and can put these back.
 */
object Tips {

    /** What has to be left clear below a tip, which differs by the kind of screen. */
    enum class Room {
        /** Fullscreen and immersive. There is nothing at the bottom to avoid. */
        NONE,

        /** An ordinary screen: sit above the system navigation bar. */
        SYSTEM_BAR,

        /** A tab in the main window: sit above the floating navigation capsule as well. */
        NAV_PILL,
    }

    /** Keys. Stored rather than derived, so renaming a screen cannot re-show an old tip. */
    const val LIBRARY = "library"
    const val FOLDER = "folder"
    const val SHORTS = "shorts"
    const val PLAYER = "player"

    /**
     * Shows [message] once, floating above whatever is already on screen.
     *
     * Added to the activity's content view rather than to a container in each layout, so
     * adding a tip to a screen never means editing that screen's XML.
     */
    fun showOnce(
        activity: Activity,
        prefs: Prefs,
        key: String,
        @StringRes message: Int,
        room: Room = Room.SYSTEM_BAR,
    ) {
        if (prefs.hasSeenTip(key)) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        // Marked as seen when shown, not when dismissed. A tip the user swiped past, or that
        // was on screen when they left, has done its job; showing it again would be nagging.
        prefs.markTipSeen(key)

        val binding = ViewTipBinding.inflate(LayoutInflater.from(activity), root, false)
        binding.message.setText(message)
        binding.root.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
            val margin = (MARGIN_DP * activity.resources.displayMetrics.density).toInt()
            setMargins(margin, margin, margin, margin + clearance(root, room))
        }

        binding.got.setOnClickListener { dismiss(root, binding.root) }
        root.addView(binding.root)

        binding.root.alpha = 0f
        binding.root.translationY = 24f * activity.resources.displayMetrics.density
        binding.root.animate().alpha(1f).translationY(0f).setDuration(220).start()

        binding.root.postDelayed({ dismiss(root, binding.root) }, LINGER_MS)
    }

    /** How much to leave below the tip on this kind of screen. */
    private fun clearance(root: View, room: Room): Int = when (room) {
        Room.NONE -> 0
        Room.SYSTEM_BAR -> systemBar(root)
        Room.NAV_PILL ->
            systemBar(root) + root.resources.getDimensionPixelSize(R.dimen.nav_pill_reserve)
    }

    private fun systemBar(root: View): Int =
        root.rootWindowInsets?.systemWindowInsetBottom ?: 0

    private fun dismiss(root: ViewGroup, tip: View) {
        if (tip.parent == null) return
        tip.animate().cancel()
        tip.animate()
            .alpha(0f)
            .translationY(tip.height / 2f)
            .setDuration(180)
            .withEndAction { root.removeView(tip) }
            .start()
    }

    private const val MARGIN_DP = 16f
    private const val LINGER_MS = 7_000L
}
