package com.seamless.player.ui.player

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.seamless.player.R

/**
 * A panel that floats over the video instead of being pulled out of the bottom of it.
 *
 * This was a `BottomSheetDialog` and is not any more, and the difference is the whole point.
 * A bottom sheet is bottom-anchored by construction: it can be given a margin, but it still
 * belongs to the edge of the screen, still stretches the full width, and still reads as a
 * drawer attached to the frame. What it is meant to be is a card resting on top of the
 * picture, clear of every edge, with the film continuing around it. Those are different
 * objects and no amount of margin turns one into the other.
 *
 * Three decisions are worth stating:
 *
 * - **Where it sits.** Against the end edge and the bottom edge, inset from both. The inset is
 *   the entire difference between a card resting on the picture and a drawer attached to the
 *   frame, and it is what was missing. On a phone held upright the width cap is wider than the
 *   screen, so the horizontal inset centres it; on a wide screen it hugs the side the CC button
 *   is on and leaves the picture — and the transport controls — visible beside it. One rule,
 *   right in both. Bottom-anchored rather than centred so the caption can still be lifted clear
 *   of it while the appearance panel is open, which is the only way to see what a caption style
 *   actually looks like.
 * - **How big it may get.** Capped in width, and its scrolling part capped as a share of the
 *   screen. A panel allowed to grow is a settings page with a video behind it.
 * - **The system bars.** This is the one that is not cosmetic. Showing any dialog over an
 *   immersive activity hands focus to a new window, and a focused window that has not asked to
 *   be immersive brings the status and navigation bars back — over a film, mid-sentence. The
 *   fix is the documented one: create the window unfocusable so the bars stay put, show it,
 *   hide the bars on the dialog's own window as well, then restore focus so it can be touched.
 */
internal object FloatingSheet {

    /**
     * Where a panel sits.
     *
     * [END] is the subtitle panel's place: against the side its control is on, where a tall list
     * leaves the picture visible beside it. [CENTRE] is for a compact panel with nothing to sit
     * beside — the speed card — which is a question about the whole video and belongs in the
     * middle of it. The two only differ in landscape: held upright the width cap is wider than
     * the screen, so both come out centred along the bottom.
     */
    enum class Placement { END, CENTRE }

    fun create(context: Context, content: View, placement: Placement = Placement.END): Dialog {
        val landscape =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val centred = placement == Placement.CENTRE && landscape
        val dialog = FloatingDialog(context, content, centred)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)

        val insetPx = context.resources.getDimensionPixelSize(R.dimen.sheet_side_inset)
        val bottomPx = context.resources.getDimensionPixelSize(R.dimen.sheet_bottom_inset)
        val maxWidth = context.resources.getDimensionPixelSize(R.dimen.sheet_max_width)
        val screenWidth = screenSize(context).x

        dialog.window?.let { window ->
            // Keep the scrim light: the point of a floating panel is that the film carries on
            // behind it, and a standard dim would put it behind frosted grey.
            window.setDimAmount(0.18f)
            window.setLayout(
                minOf(screenWidth - insetPx * 2, maxWidth),
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            window.setGravity(if (centred) Gravity.CENTER else Gravity.END or Gravity.BOTTOM)
            // x and y are offsets from the gravity edges on a floating window, which is how the
            // card gets air underneath it rather than sitting on the bottom of the screen. A
            // centred window has no edge to be offset from.
            window.attributes = window.attributes.apply {
                x = if (centred) 0 else insetPx
                y = if (centred) 0 else bottomPx
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }

        dialog.setOnShowListener {
            dialog.animateIn()
            val window = dialog.window ?: return@setOnShowListener
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        return dialog
    }

    @Suppress("DEPRECATION")
    private fun screenSize(context: Context): Point {
        val manager = context.getSystemService(WindowManager::class.java)
        return Point().also { manager?.defaultDisplay?.getSize(it) }
    }

    /**
     * A dialog that animates itself out of the way rather than vanishing.
     *
     * `dismiss` is overridden rather than the animation being run at each call site because
     * there are five ways out of this panel — the close button, a tap outside, the back
     * gesture, choosing a track, and the player being torn down — and four of them are the
     * system calling `dismiss` directly. Anything less than an override leaves some of them
     * cutting to black while the others glide.
     *
     * The motion is the one Apple uses for a popover and iOS uses for a share sheet: scale and
     * opacity together, weighted so the panel appears to come *from* the control rather than
     * to fade in over it. The pivot is the top end corner, which is where the control row is.
     * Both properties are hardware-accelerated, which matters more here than usual — this runs
     * over decoding video.
     */
    private class FloatingDialog(
        context: Context,
        private val content: View,
        /** Grows from its own middle, rather than from the corner of a control it is not beside. */
        private val centred: Boolean,
    ) : Dialog(context, R.style.Theme_Seamless_FloatingSheet) {

        private var leaving = false

        fun animateIn() {
            content.pivotX = if (centred) content.width / 2f else content.width.toFloat()
            content.pivotY = if (centred) content.height / 2f else 0f
            content.alpha = 0f
            content.scaleX = OPENING_SCALE
            content.scaleY = OPENING_SCALE
            content.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(OPEN_MS)
                // Heavily weighted towards the end of the curve. A panel that decelerates
                // sharply reads as settling into place; a linear one reads as being dragged.
                .setInterpolator(DecelerateInterpolator(2.2f))
                .start()
        }

        override fun dismiss() {
            if (leaving || !isShowing) {
                finish()
                return
            }
            leaving = true
            content.animate()
                .alpha(0f)
                .scaleX(CLOSING_SCALE)
                .scaleY(CLOSING_SCALE)
                .setDuration(CLOSE_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { finish() }
                .start()
        }

        /** `super.dismiss()` cannot be called from inside a lambda, so it is called from here. */
        private fun finish() {
            // The window can be gone already if the activity was destroyed while this was
            // animating, and dismissing a dialog whose host has left throws.
            runCatching { super.dismiss() }
        }

        private companion object {
            /** Close enough to 1 to read as arriving rather than as zooming. */
            const val OPENING_SCALE = 0.93f

            /** Shrinks further on the way out than it grew on the way in, which reads as away. */
            const val CLOSING_SCALE = 0.95f

            const val OPEN_MS = 260L
            const val CLOSE_MS = 180L
        }
    }
}
