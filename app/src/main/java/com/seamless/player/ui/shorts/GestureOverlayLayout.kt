package com.seamless.player.ui.shorts

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * The touch layer for one page of the shorts feed.
 *
 * The tricky part is coexisting with ViewPager2: vertical drags must reach the pager so it
 * can move to the next clip, while horizontal drags must be stolen for brightness and
 * volume. That is why the horizontal decision lives in onInterceptTouchEvent — anything we
 * do not claim there simply falls through to the pager.
 *
 * Gesture map (as agreed):
 *   vertical drag        -> handled by ViewPager2, next / previous clip
 *   horizontal, left     -> brightness
 *   horizontal, right    -> volume
 *   double tap left/right-> seek -5s / +5s
 *   long press           -> 2x speed while held
 *   single tap           -> play / pause
 * There is deliberately no swipe-to-seek.
 */
class GestureOverlayLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * Where a double tap landed.
     *
     * Double tap already meant "jump five seconds", and it still does — that gesture is
     * older than this one and people rely on it. Rather than take it away, the screen is
     * split in three: the edges keep the seek, and the middle, which nobody was aiming at
     * when they wanted to skip, becomes the favourite. Seeking is an edge gesture by nature —
     * you tap the side you want to go towards — so the middle was the part of the screen
     * that meant nothing.
     */
    enum class TapZone { LEFT, MIDDLE, RIGHT }

    interface Listener {
        fun onDragStart(rightHalf: Boolean)
        /** [delta] is the movement since the last event as a fraction of the view width. */
        fun onDrag(rightHalf: Boolean, delta: Float)
        fun onDragEnd()
        fun onSingleTap()
        fun onDoubleTap(zone: TapZone)
        fun onSpeedBoostStart()
        fun onSpeedBoostEnd()
    }

    var listener: Listener? = null

    /** Cleared while the screen lock is engaged, so no gesture reaches the feed. */
    var gesturesEnabled: Boolean = true

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** Even thirds. Wide enough in the middle to hit without aiming, narrow enough that the
     *  seek zones are still the obvious targets they were. */
    private fun zoneOf(x: Float): TapZone = when {
        width <= 0 -> TapZone.MIDDLE
        x < width / 3f -> TapZone.LEFT
        x > width * 2f / 3f -> TapZone.RIGHT
        else -> TapZone.MIDDLE
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var rightHalf = false
    private var boosting = false

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            listener?.onSingleTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            listener?.onDoubleTap(zoneOf(e.x))
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // Only boost if this is a stationary press, not the start of a drag.
            if (!dragging) {
                boosting = true
                listener?.onSpeedBoostStart()
            }
        }
    })

    /**
     * Claims horizontal drags only. Returning false for everything else leaves vertical
     * movement to ViewPager2, which is what drives next / previous.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!gesturesEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (!dragging && isHorizontal(ev)) {
                beginDrag()
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && isHorizontal(ev)) beginDrag()
                if (dragging) {
                    val delta = (ev.x - lastX) / width.coerceAtLeast(1)
                    lastX = ev.x
                    listener?.onDrag(rightHalf, delta)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    listener?.onDragEnd()
                }
                if (boosting) {
                    boosting = false
                    listener?.onSpeedBoostEnd()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        // Consume everything we were given so taps and long presses stay ours.
        return true
    }

    /** A drag counts as horizontal only if it clearly out-runs the vertical component. */
    private fun isHorizontal(ev: MotionEvent): Boolean {
        val dx = abs(ev.x - downX)
        val dy = abs(ev.y - downY)
        return dx > touchSlop && dx > dy * 1.5f
    }

    private fun beginDrag() {
        dragging = true
        rightHalf = downX > width / 2f
        // Stop the pager stealing the gesture back once we have committed to it.
        parent?.requestDisallowInterceptTouchEvent(true)
        listener?.onDragStart(rightHalf)
    }
}
