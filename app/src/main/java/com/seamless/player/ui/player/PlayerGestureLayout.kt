package com.seamless.player.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Touch handling for the conventional player.
 *
 *   drag down from the top      -> dismiss the player
 *   vertical drag, left half    -> brightness
 *   vertical drag, right half   -> volume
 *   horizontal drag             -> scrub, committed when you let go
 *   double tap, left / right    -> seek back / forward
 *   long press                  -> 2x speed while held
 *   single tap                  -> falls through to PlayerView, which shows its controls
 *
 * Single taps are deliberately *not* intercepted, so the controller keeps ownership of
 * show/hide. The detector is fed from onInterceptTouchEvent — which sees every event even
 * when nothing is being intercepted — and only claims the stream once a double tap or long
 * press has actually been recognised.
 */
class PlayerGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener {
        /** [delta] is movement since the last event as a fraction of height; positive is up. */
        fun onVerticalDrag(rightHalf: Boolean, delta: Float)
        fun onSeekStart()
        /** [cumulative] is total horizontal movement as a fraction of width. */
        fun onSeekDrag(cumulative: Float)
        fun onGestureEnd()
        /** The dismiss drag was carried far enough, or thrown fast enough, to commit. */
        fun onSwipeDownToClose()
        /** Double tap on one side or the other. */
        fun onDoubleTapSeek(rightHalf: Boolean)
        fun onSpeedBoostStart()
        fun onSpeedBoostEnd()

        /**
         * A touch has begun. Used to keep the controls up while something is being pressed
         * or dragged, so they cannot time out from under a finger.
         */
        fun onTouchDown()

        /**
         * A single tap was recognised. Reported, not intercepted — the tap still reaches
         * PlayerView underneath, which is what brings the controls up. This exists so the
         * chrome can *start leaving* on the release rather than after Media3 has finished
         * with it.
         */
        fun onSingleTap()

        /**
         * Whether [x], [y] lands on a control rather than on the picture.
         *
         * The detector is fed from onInterceptTouchEvent, so it sees every touch in the
         * player including ones that belong to a button — and a long press on the speed
         * button was starting the speed *boost* on top of whatever the button itself does.
         * Only the activity knows where its controls are, so it answers this and the
         * gestures below keep out of those areas entirely.
         */
        fun isOverControl(x: Float, y: Float): Boolean
    }

    var listener: Listener? = null

    /** Cleared while the screen lock is engaged, so no drag reaches the player. */
    var gesturesEnabled: Boolean = true

    /** Mirrors the setting; when off, the top band behaves like anywhere else. */
    var swipeDownToCloseEnabled: Boolean = true

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var mode = Mode.NONE
    private var rightHalf = false
    private var boosting = false

    /** Set when the detector recognised something, so the rest of the stream is ours. */
    private var claimedByDetector = false

    private var velocity: VelocityTracker? = null

    /**
     * The card's corners are painted, not clipped, and that is a fix rather than a preference.
     *
     * Rounding them by giving this view an outline and switching on clipToOutline made the
     * picture vanish the instant a drag began. Everything drawn the ordinary way - the subtitle,
     * the controls - kept rendering, while the TextureView the video arrives on went blank and
     * stayed blank; a recording of the gesture is a black screen with the subtitle still floating
     * on it. A TextureView is composited from a hardware layer of its own, and an ancestor clip
     * against a rounded outline is not something every driver applies to that layer.
     *
     * Painting four corner slivers over the children after they have drawn gives the same
     * picture and clips nothing, so how the video layer is composited is never in question.
     *
     * Four separate slivers, not one rounded rectangle subtracted from the bounds. The
     * subtraction is the same picture and costs a great deal more: a path's rasterisation is
     * charged over its bounding box, and that one's box is the whole screen, so every frame of
     * a drag paid full-screen coverage to tint four corners. It did not show on a flick, which
     * draws a handful of frames; it showed on a slow drag, which draws hundreds.
     */
    private val cornerMask = Path()
    private val corner = RectF()
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    /** Grows as the dismiss drag progresses; drives the painted corners. */
    private var cornerRadius = 0f
        set(value) {
            // Half a pixel is not a corner anyone can see, and a drag delivers a value per
            // frame; redrawing for changes below that is work with nothing to show for it.
            if (abs(field - value) < CORNER_EPSILON && value != 0f) return
            field = value
            rebuildCornerMask()
            invalidate()
        }

    private enum class Mode { NONE, VERTICAL, SEEK, CLOSE }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildCornerMask()
    }

    /** The corners themselves: four slivers, each the size of one corner and no larger. */
    private fun rebuildCornerMask() {
        cornerMask.reset()
        if (cornerRadius <= 0f || width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val r = cornerRadius
        val d = r * 2f

        // Top left.
        corner.set(0f, 0f, d, d)
        cornerMask.moveTo(0f, 0f)
        cornerMask.lineTo(0f, r)
        cornerMask.arcTo(corner, 180f, 90f)
        cornerMask.close()

        // Top right.
        corner.set(w - d, 0f, w, d)
        cornerMask.moveTo(w, 0f)
        cornerMask.lineTo(w - r, 0f)
        cornerMask.arcTo(corner, 270f, 90f)
        cornerMask.close()

        // Bottom right.
        corner.set(w - d, h - d, w, h)
        cornerMask.moveTo(w, h)
        cornerMask.lineTo(w, h - r)
        cornerMask.arcTo(corner, 0f, 90f)
        cornerMask.close()

        // Bottom left.
        corner.set(0f, h - d, d, h)
        cornerMask.moveTo(0f, h)
        cornerMask.lineTo(r, h)
        cornerMask.arcTo(corner, 90f, 90f)
        cornerMask.close()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (cornerRadius > 0f) canvas.drawPath(cornerMask, cornerPaint)
    }

    /**
     * False, and this is a performance fix rather than a rendering choice.
     *
     * Setting alpha on a ViewGroup that reports overlapping content makes the framework
     * render the whole group into an offscreen buffer every frame, so the layers can be
     * flattened before that alpha is applied. Full screen, sixty times a second, on top of a
     * video that is already being composited - that was one of two things making the dismiss
     * gesture stutter. The other was an explicit LAYER_TYPE_HARDWARE that an earlier attempt
     * at fixing the stutter had added, which forced a *second* full-screen offscreen copy for
     * the same reason. Both are gone.
     *
     * The cost of saying false is that during a fade, overlapping children blend against each
     * other rather than being flattened first. Here that means the controls could show faintly
     * through the video for the fifth of a second the exit animation lasts, on a view already
     * on its way off the screen. That is a good trade.
     */
    override fun hasOverlappingRendering(): Boolean = false

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        /**
         * onSingleTapUp rather than onSingleTapConfirmed: confirmed waits out the
         * double-tap window, which would put a visible delay on summoning the controls.
         * The cost is that the first tap of a double tap reports too — handled by
         * onDoubleTapSeek putting the controls away again.
         */
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!gesturesEnabled || overControl(e)) return false
            listener?.onSingleTap()
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!gesturesEnabled || overControl(e)) return false
            claimedByDetector = true
            listener?.onDoubleTapSeek(e.x > width / 2f)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // Only a stationary press counts; a drag that happens to dwell is not a hold.
            // A press on a button is that button's business, not a gesture.
            if (!gesturesEnabled || mode != Mode.NONE || overControl(e)) return
            claimedByDetector = true
            boosting = true
            listener?.onSpeedBoostStart()
        }
    })

    private fun overControl(e: MotionEvent): Boolean =
        listener?.isOverControl(e.x, e.y) == true

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!gesturesEnabled) return false

        // Fed here rather than in onTouchEvent so double tap and long press are recognised
        // even while the controller still owns single taps.
        detector.onTouchEvent(ev)

        // The release has to be handled *here*, before the claim short-circuits below.
        //
        // Interception starts on the event after the one that returns true, so returning
        // true for ACTION_UP means this view's onTouchEvent never sees the release at all —
        // and a long press that ended there would leave the speed boost switched on for the
        // rest of the session. Ending it on both paths is the fix; endBoost() is idempotent.
        if (ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            val claimed = claimedByDetector
            endBoost()
            claimedByDetector = false
            // Swallow the release of a gesture that was ours.
            //
            // A press held still until it became the speed boost has no events between its
            // down and its up, so this up is the first chance there is to claim it. Clearing the
            // claim and then letting the up through handed PlayerView a down and an up it reads
            // as a tap — and a tap on a hidden controller raises it, the moment the finger
            // lifted off a hold. Intercepting here sends PlayerView a cancel instead, so a hold
            // shows the 2x badge while held and nothing at all when released.
            if (claimed) return true
        }

        if (claimedByDetector) return true

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                mode = Mode.NONE
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(ev) }
                // Reported for every touch, including ones that land on a button or on the
                // timeline, so the controls' linger restarts while they are being used.
                listener?.onTouchDown()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (mode == Mode.NONE && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    mode = when {
                        isDismissDrag(dx, dy) -> Mode.CLOSE
                        abs(dx) > abs(dy) -> Mode.SEEK
                        else -> Mode.VERTICAL
                    }
                    rightHalf = downX > width / 2f
                    if (mode == Mode.SEEK) listener?.onSeekStart()
                    return true
                }
            }
        }
        // Taps are left alone so PlayerView can toggle its own controls.
        return false
    }

    /** A downward, mostly-vertical drag that began in the band along the top edge. */
    private fun isDismissDrag(dx: Float, dy: Float): Boolean =
        swipeDownToCloseEnabled &&
            downY <= height * TOP_BAND &&
            dy > 0 &&
            abs(dy) > abs(dx)

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!gesturesEnabled) return true
        detector.onTouchEvent(ev)
        velocity?.addMovement(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.VERTICAL -> {
                    // Screen coordinates grow downwards; invert so dragging up increases.
                    val delta = (lastY - ev.y) / height.coerceAtLeast(1)
                    lastY = ev.y
                    listener?.onVerticalDrag(rightHalf, delta)
                }
                Mode.SEEK -> listener?.onSeekDrag((ev.x - downX) / width.coerceAtLeast(1))
                Mode.CLOSE -> applyDismissProgress((ev.y - downY).coerceAtLeast(0f))
                Mode.NONE -> Unit
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.CLOSE) finishDismiss(ev)
                else if (mode != Mode.NONE) listener?.onGestureEnd()

                endBoost()
                mode = Mode.NONE
                claimedByDetector = false
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    /**
     * Commits on distance *or* speed. Requiring distance alone made the gesture feel heavy:
     * a quick flick reads as "dismiss" even though it has barely travelled.
     */
    private fun finishDismiss(ev: MotionEvent) {
        val travelled = ev.y - downY
        val speed = velocity?.let {
            it.computeCurrentVelocity(1000)
            it.yVelocity
        } ?: 0f

        val farEnough = travelled > height * CLOSE_DISTANCE
        val fastEnough = speed > FLING_VELOCITY && travelled > touchSlop * 2

        if (farEnough || fastEnough) animateOut(speed) else springBack()
    }

    /**
     * Moves the picture with the finger: down, and slightly smaller, with the corners
     * rounding off as it goes. The result reads as a card being put away rather than as a
     * screen sliding off, which is the whole point of the gesture.
     *
     * Every property touched here - translation, scale, the outline radius - is a RenderNode
     * property, so a change costs a matrix update on the render thread and nothing else. That
     * is deliberate, and it is what the previous version got wrong: see the note on
     * [hasOverlappingRendering].
     */
    private fun applyDismissProgress(travelled: Float) {
        val progress = (travelled / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        translationY = travelled
        val scale = 1f - progress * DISMISS_SCALE
        scaleX = scale
        scaleY = scale
        // Reaches full rounding a quarter of the way down, then holds; the corners should be
        // established early rather than still growing as the card leaves.
        cornerRadius = (progress * 4f).coerceAtMost(1f) *
            MAX_CORNER_DP * resources.displayMetrics.density
    }

    /**
     * Carries the card the rest of the way out, then hands over to the activity to finish.
     *
     * How long that takes comes from how far is left and how fast the card is already moving,
     * between a floor and a ceiling. A fixed duration was the other half of this gesture's
     * trouble: a flick commits after a tenth of the screen, so the remaining nine tenths had to
     * be covered in the same fifth of a second however gently it was thrown, which stops reading
     * as movement at all - the card is simply gone. Speed decides *whether* to let the card go;
     * it does not get to turn its leaving into a jump.
     */
    private fun animateOut(speed: Float) {
        animate().cancel()
        val remaining = (height - translationY).coerceAtLeast(1f)
        val fromThrow = if (speed > 1f) remaining / speed * 1000f else EXIT_MAX_MS.toFloat()
        animate()
            .translationY(height.toFloat())
            .scaleX(1f - DISMISS_SCALE)
            .scaleY(1f - DISMISS_SCALE)
            .setDuration(fromThrow.coerceIn(EXIT_MIN_MS.toFloat(), EXIT_MAX_MS.toFloat()).toLong())
            // Carries on at the speed it was thrown and eases as it leaves, rather than starting
            // from nothing: the first frames are where the movement has to be visible.
            .setInterpolator(DecelerateInterpolator(1.25f))
            .withEndAction { listener?.onSwipeDownToClose() }
            .start()
    }

    /** Safe to call repeatedly; only the first call after a boost does anything. */
    private fun endBoost() {
        if (!boosting) return
        boosting = false
        listener?.onSpeedBoostEnd()
    }

    private fun springBack() {
        animate().cancel()
        animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SPRING_MS)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .setUpdateListener {
                // Unround in step with the spring, so the corners do not snap square at the
                // end of an otherwise smooth movement.
                cornerRadius *= 0.82f
            }
            .withEndAction { cornerRadius = 0f }
            .start()
    }

    /** Puts the view back after a dismissal was abandoned, or the activity was re-entered. */
    fun resetTransform() {
        animate().cancel()
        translationY = 0f
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
        cornerRadius = 0f
    }

    private companion object {
        /** How far down the screen the dismiss gesture may start. */
        const val TOP_BAND = 0.40f
        /** How far it must travel to commit, as a fraction of the screen. */
        const val CLOSE_DISTANCE = 0.10f
        /** Downward pixels per second that count as a deliberate flick. */
        const val FLING_VELOCITY = 1_400f
        /** How far the picture shrinks over a full-height drag. Subtle on purpose. */
        const val DISMISS_SCALE = 0.14f
        /** Corner rounding once the shrink is fully established. */
        const val MAX_CORNER_DP = 22f
        /** The floor and ceiling on how long the card takes to leave once committed. */
        const val EXIT_MIN_MS = 260L
        const val EXIT_MAX_MS = 430L
        const val SPRING_MS = 190L
        /** Below this, a change in the corner radius is not worth a redraw. */
        const val CORNER_EPSILON = 0.5f
    }
}
