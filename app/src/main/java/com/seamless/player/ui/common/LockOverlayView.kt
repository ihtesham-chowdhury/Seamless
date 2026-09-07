package com.seamless.player.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import com.seamless.player.R
import com.seamless.player.data.UnlockMethod

/**
 * Screen lock for the players.
 *
 * While engaged this swallows every touch, so a phone in a pocket cannot seek, change
 * volume or swipe to the next clip.
 *
 * It deliberately shows nothing at all. The video stays completely unobstructed: no scrim,
 * no standing instruction, and not even the slide bar until the screen is touched. A hint
 * appears at the bottom only once someone has clearly tried and failed a few times, and
 * fades again. Touching the screen in slider mode summons the bar, which retreats if unused.
 */
class LockOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var onUnlocked: (() -> Unit)? = null

    private val hint: TextView
    private val slideGroup: View
    private val slider: SeekBar

    private var method: UnlockMethod = UnlockMethod.SLIDER

    /** How far through the corner sequence the user is. */
    private var cornerProgress = 0

    /** Fruitless taps since the last hint, used to decide when a nudge is warranted. */
    private var strayTaps = 0

    private val hideHint = Runnable {
        hint.animate().alpha(0f).setDuration(250).withEndAction {
            hint.visibility = GONE
            hint.alpha = 1f
        }
    }

    private val hideSlider = Runnable {
        // Not while it is being dragged, or the bar would vanish under the finger.
        if (slider.isPressed) return@Runnable
        slideGroup.animate().alpha(0f).setDuration(250).withEndAction {
            slideGroup.visibility = GONE
            slideGroup.alpha = 1f
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_lock_overlay, this, true)
        hint = findViewById(R.id.lock_hint)
        slideGroup = findViewById(R.id.slide_group)
        slider = findViewById(R.id.lock_slider)
        isClickable = true
        isFocusable = true
        visibility = GONE

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) {
                // Only a near-complete slide counts; anything less springs back.
                if (bar.progress >= UNLOCK_THRESHOLD) {
                    unlock()
                } else {
                    bar.progress = 0
                    // A half-hearted drag is itself a signal that help would be welcome.
                    noteFailedAttempt()
                }
            }
        })
    }

    val isLocked: Boolean get() = visibility == VISIBLE

    /**
     * Caps how wide the slide bar is allowed to get.
     *
     * Left at match_parent it stretched the full width of a landscape screen, which made a
     * short deliberate gesture into a long haul and looked wrong besides. On a portrait
     * phone the cap is above the available width, so nothing changes there; in landscape the
     * bar settles at a comfortable size in the middle.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val available = w - (2 * SLIDE_MARGIN_DP * resources.displayMetrics.density).toInt()
        val cap = (MAX_SLIDE_WIDTH_DP * resources.displayMetrics.density).toInt()
        val params = slideGroup.layoutParams as LayoutParams
        params.width = minOf(available, cap).coerceAtLeast(0)
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        slideGroup.layoutParams = params
    }

    fun lock(method: UnlockMethod) {
        this.method = method
        cornerProgress = 0
        strayTaps = 0
        slider.progress = 0
        // Even the slide bar starts hidden: locking should leave the picture completely
        // clear. A touch brings it up, and it fades again if unused.
        slideGroup.visibility = GONE
        hint.visibility = GONE
        removeCallbacks(hideHint)
        removeCallbacks(hideSlider)
        visibility = VISIBLE
        bringToFront()
    }

    /** Brings the slide bar up on contact, and starts its retreat. */
    private fun revealSlider() {
        if (method != UnlockMethod.SLIDER) return
        slideGroup.animate().cancel()
        slideGroup.alpha = 1f
        slideGroup.visibility = VISIBLE
        removeCallbacks(hideSlider)
        postDelayed(hideSlider, SLIDER_LINGER_MS)
    }

    private fun unlock() {
        removeCallbacks(hideHint)
        removeCallbacks(hideSlider)
        visibility = GONE
        hint.visibility = GONE
        cornerProgress = 0
        strayTaps = 0
        slider.progress = 0
        onUnlocked?.invoke()
    }

    /**
     * Swallows everything that is not the unlock interaction. The slider is a child view, so
     * it still receives its own touches through normal dispatch.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (method == UnlockMethod.CORNERS) {
                handleCornerTap(event.x, event.y)
            } else {
                // In slider mode a touch is a request for the bar, not a failed attempt.
                if (slideGroup.visibility == VISIBLE) noteFailedAttempt()
                revealSlider()
            }
        }
        return true
    }

    /** Corners must be tapped clockwise from the top left. A wrong tap starts over. */
    private fun handleCornerTap(x: Float, y: Float) {
        val zoneW = width * CORNER_ZONE
        val zoneH = height * CORNER_ZONE
        val left = x <= zoneW
        val right = x >= width - zoneW
        val top = y <= zoneH
        val bottom = y >= height - zoneH

        val corner = when {
            left && top -> 0
            right && top -> 1
            right && bottom -> 2
            left && bottom -> 3
            else -> -1
        }

        if (corner != -1 && corner == cornerProgress) {
            cornerProgress++
            if (cornerProgress >= 4) {
                unlock()
            } else {
                // Mid-sequence feedback, so a correct tap feels acknowledged.
                showHint(context.getString(R.string.lock_hint_corners_progress, cornerProgress))
            }
            return
        }

        // Either a miss or an out-of-order corner: reset, and count it towards a nudge.
        cornerProgress = 0
        noteFailedAttempt()
    }

    /**
     * Shows guidance only once someone is visibly struggling, rather than standing on the
     * video from the moment the lock is engaged.
     */
    private fun noteFailedAttempt() {
        strayTaps++
        if (strayTaps < ATTEMPTS_BEFORE_HINT) return
        strayTaps = 0
        showHint(
            context.getString(
                if (method == UnlockMethod.SLIDER) R.string.lock_hint_slider
                else R.string.lock_hint_corners
            )
        )
    }

    private fun showHint(text: String) {
        hint.text = text
        hint.alpha = 1f
        hint.visibility = VISIBLE
        removeCallbacks(hideHint)
        postDelayed(hideHint, HINT_LINGER_MS)
    }

    private companion object {
        const val UNLOCK_THRESHOLD = 90
        /** Corner hit area, as a fraction of each edge. */
        const val CORNER_ZONE = 0.22f
        /** Wrong taps tolerated before offering help. */
        const val ATTEMPTS_BEFORE_HINT = 3
        const val HINT_LINGER_MS = 2_600L
        /** How long the slide bar stays up after being summoned. */
        const val SLIDER_LINGER_MS = 4_000L
        /** Widest the slide bar may be drawn, whatever the screen. */
        const val MAX_SLIDE_WIDTH_DP = 340f
        /** Kept in step with the margins on slide_group in view_lock_overlay.xml. */
        const val SLIDE_MARGIN_DP = 24f
    }
}
