package com.seamless.player.util

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.seamless.player.R

/**
 * Window inset helpers.
 *
 * Since targetSdk 35 Android draws apps edge to edge by default, so content sits underneath
 * the status and navigation bars unless it says otherwise. Left unhandled it looks like a
 * layout bug — a title jammed against the clock — which is exactly what it was.
 *
 * Padding is added to the view's *original* padding rather than replacing it, because these
 * listeners can run more than once (rotation, keyboard, gesture-nav changes) and a naive
 * implementation accumulates padding on every pass.
 */

/** Pads the top by the status bar and any display cutout. */
fun View.applyTopSystemInset() {
    val initial = paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val bars = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updatePadding(top = initial + bars.top)
        windowInsets
    }
    requestApplyInsetsWhenAttached()
}

/** Pads bottom and both sides — for scrolling content behind a gesture bar. */
fun View.applyBottomAndSideInsets() {
    val left = paddingLeft
    val right = paddingRight
    val bottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val bars = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updatePadding(
            left = left + bars.left,
            right = right + bars.right,
            bottom = bottom + bars.bottom,
        )
        windowInsets
    }
    requestApplyInsetsWhenAttached()
}

/**
 * Keeps the floating navigation capsule clear of the gesture bar.
 *
 * A margin rather than padding: the pill's background is the thing being positioned, and
 * padding would stretch the glass down behind the system bar instead of moving it up.
 */
fun View.applyBottomMarginInset() {
    val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    val initial = params.bottomMargin
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        val current = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (current != null && current.bottomMargin != initial + bars.bottom) {
            current.bottomMargin = initial + bars.bottom
            view.layoutParams = current
        }
        windowInsets
    }
    requestApplyInsetsWhenAttached()
}

/**
 * Room at the bottom of a scrolling list for the floating navigation capsule.
 *
 * The list runs the full height of the screen so content passes behind the glass, which only
 * works if the last row can still be scrolled out from under it. Pair with
 * clipToPadding="false", or the padding becomes a dead band instead of extra scroll.
 */
fun View.applyFloatingNavInset() {
    val initial = paddingBottom + resources.getDimensionPixelSize(R.dimen.nav_pill_reserve)
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(bottom = initial + bars.bottom)
        windowInsets
    }
    requestApplyInsetsWhenAttached()
}

/**
 * Insets are only delivered to attached views. Requesting immediately works for a view that
 * is already attached and would silently do nothing otherwise, so defer when it is not.
 */
private fun View.requestApplyInsetsWhenAttached() {
    if (isAttachedToWindow) {
        ViewCompat.requestApplyInsets(this)
        return
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            view.removeOnAttachStateChangeListener(this)
            ViewCompat.requestApplyInsets(view)
        }

        override fun onViewDetachedFromWindow(view: View) = Unit
    })
}
