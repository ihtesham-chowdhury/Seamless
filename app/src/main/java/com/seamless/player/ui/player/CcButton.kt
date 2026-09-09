package com.seamless.player.ui.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.seamless.player.data.AccentColor
import com.seamless.player.ui.common.AccentColors

/**
 * The CC control's three states, drawn rather than declared.
 *
 * The control has to say two different things without saying either of them in words:
 * *subtitles are on*, and *this control is open*. Those are not the same fact — one is about the
 * film, the other is about the screen — and collapsing them into one appearance leaves the user
 * unable to tell, on closing the panel, whether anything happened.
 *
 * No dot and no badge. A badge on a control means "something has arrived here for you", which is
 * not what an active caption track is; it is a setting, and a setting shows in the control's own
 * surface. So the three states are the same disc with progressively more of the accent in it:
 * plain, ringed, and filled.
 *
 * Built in code rather than as three drawables and a selector, because the accent is a runtime
 * value. The player deliberately runs without the accent theme overlay — a purple slider over
 * someone's film would be a strange thing to insist on — so there is no `?attr/colorPrimary`
 * here to point a selector at, and the colour has to be fetched and mixed anyway.
 */
object CcButton {

    enum class State {
        /** Nothing switched on. Indistinguishable from every other control in the row. */
        IDLE,

        /** A subtitle track is playing. */
        ACTIVE,

        /** The subtitle panel is open on top of the video. */
        OPEN,
    }

    /**
     * The accent, made fit to sit on top of a film.
     *
     * Accents are chosen against the browsing UI, which is a page; here they land on an
     * arbitrary frame of video, which may be anything from a snow field to a night interior.
     * Mixing a third of white in guarantees the ring separates from the disc it is drawn on and
     * from whatever is behind it, and costs the hue nothing anyone would notice. Indigo at full
     * strength on a dark scene is a black ring on black.
     */
    fun accentFor(context: Context, accent: AccentColor): Int {
        val base = ContextCompat.getColor(context, AccentColors.previewColorRes(accent))
        return ColorUtils.blendARGB(base, Color.WHITE, 0.34f)
    }

    /**
     * Paints [button] for [state].
     *
     * Cheap enough to call on every tracks change and every panel open: three shapes and a
     * tint, no inflation and no measure pass.
     */
    fun apply(button: ImageView, state: State, accent: Int) {
        val context = button.context
        val ring = when (state) {
            State.IDLE -> null
            State.ACTIVE -> Ring(dp(context, 1.5f), ColorUtils.setAlphaComponent(accent, 0xA6))
            State.OPEN -> Ring(dp(context, 2f), accent)
        }
        val fill = when (state) {
            State.IDLE -> RESTING_FILL
            // Brighter, not coloured: the ring carries the accent and a tinted disc as well
            // would make an ordinary playing state look like a warning.
            State.ACTIVE -> ACTIVE_FILL
            // Open is the one state that may take colour, because it is momentary.
            State.OPEN -> ColorUtils.compositeColors(
                ColorUtils.setAlphaComponent(accent, 0x3D),
                ACTIVE_FILL,
            )
        }

        button.background = ripple(disc(fill, ring))
        button.imageTintList = ColorStateList.valueOf(
            if (state == State.OPEN) accent else Color.WHITE,
        )
    }

    private data class Ring(val width: Int, val colour: Int)

    private fun disc(fill: Int, ring: Ring?): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        ring?.let { setStroke(it.width, it.colour) }
    }

    /**
     * The same touch feedback as every other floating control, clipped to the disc.
     *
     * The mask is not optional: without one the ripple fills the view's square bounds, and a
     * round button flashing a square is the single most obvious way to look unfinished.
     */
    private fun ripple(content: Drawable): Drawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(RIPPLE), LayerDrawable(arrayOf(content)), mask)
    }

    private fun dp(context: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics,
    ).toInt().coerceAtLeast(1)

    /** Matched to circle_control_bg, which the rest of the row uses. */
    private const val RESTING_FILL = 0x66000000

    private const val ACTIVE_FILL = 0x8C000000.toInt()

    private const val RIPPLE = 0x33FFFFFF
}
