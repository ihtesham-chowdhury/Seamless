package com.seamless.player.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.media3.common.C
import androidx.media3.ui.TimeBar
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.sin

/**
 * The timeline, drawn as a waveform rather than a bar.
 *
 * Media3 finds whatever View carries the id `exo_progress` and, if it implements [TimeBar],
 * drives it — so replacing the stock `DefaultTimeBar` is a matter of implementing this
 * interface rather than of patching the controller.
 *
 * What you have already watched is drawn as a moving waveform; what is left is a flat line.
 * That is the whole idea: the past has shape, the road ahead does not. It also means the
 * play position is legible at a glance from the point where the wave stops, without needing
 * a colour change to carry all the information.
 *
 * The wave is three sine components of different wavelength summed together, and that sum is
 * then drawn three times at decreasing amplitude and opacity. One sine looks like a test
 * pattern; three, with wavelengths that share no common factor, never visibly repeat across
 * the width. Drawing the result as several offset strands rather than one line is what makes
 * it read as a waveform instead of as a wandering squiggle.
 */
class WaveformTimeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), TimeBar {

    private val listeners = CopyOnWriteArraySet<TimeBar.OnScrubListener>()

    private var durationMs: Long = C.TIME_UNSET
    private var positionMs: Long = 0
    private var bufferedMs: Long = 0

    /** Where the finger is while scrubbing; [positionMs] is ignored until it lets go. */
    private var scrubbingMs: Long = 0
    private var scrubbing = false

    private var keyTimeIncrementMs: Long = C.TIME_UNSET
    private var keyCountIncrement: Int = 0

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** Advances while playing, which is what makes the waveform drift rather than sit still. */
    private var phase = 0f

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = PLAYED_COLOR
        strokeWidth = 2f * density
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = UNPLAYED_COLOR
        strokeWidth = 2f * density
    }

    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = BUFFERED_COLOR
        strokeWidth = 2f * density
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PLAYED_COLOR
    }

    private val wavePath = Path()

    /**
     * Redraws the wave while it is on screen and moving.
     *
     * Bounded by design: the controls hide themselves after a few seconds, and `isShown`
     * goes false with them, so this is never a background animation burning frames behind a
     * video.
     */
    private val drift = object : Runnable {
        override fun run() {
            if (!isShown) return
            phase += PHASE_STEP
            invalidate()
            postOnAnimationDelayed(this, FRAME_MS)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        removeCallbacks(drift)
        if (isVisible) postOnAnimation(drift)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(drift)
        super.onDetachedFromWindow()
    }

    // ---- TimeBar ----

    override fun addListener(listener: TimeBar.OnScrubListener) {
        listeners += listener
    }

    override fun removeListener(listener: TimeBar.OnScrubListener) {
        listeners -= listener
    }

    override fun setKeyTimeIncrement(time: Long) {
        keyTimeIncrementMs = time
    }

    override fun setKeyCountIncrement(count: Int) {
        keyCountIncrement = count
    }

    override fun setPosition(position: Long) {
        if (positionMs == position) return
        positionMs = position
        invalidate()
    }

    override fun setBufferedPosition(bufferedPosition: Long) {
        if (bufferedMs == bufferedPosition) return
        bufferedMs = bufferedPosition
        invalidate()
    }

    override fun setDuration(duration: Long) {
        if (durationMs == duration) return
        durationMs = duration
        invalidate()
    }

    /**
     * How often the controller should push a new position. 40ms keeps the wave's leading
     * edge moving smoothly instead of stepping once a second.
     */
    override fun getPreferredUpdateDelay(): Long = UPDATE_DELAY_MS

    /** No ad playback in a local video player; there is nothing to mark. */
    override fun setAdGroupTimesMs(
        adGroupTimesMs: LongArray?,
        playedAdGroups: BooleanArray?,
        adGroupCount: Int,
    ) = Unit

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        invalidate()
    }

    // ---- measurement ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST ->
                minOf(MeasureSpec.getSize(heightMeasureSpec), (DEFAULT_HEIGHT_DP * density).toInt())
            else -> (DEFAULT_HEIGHT_DP * density).toInt()
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    // ---- drawing ----

    private val trackLeft: Float get() = paddingLeft + THUMB_RADIUS_DP * density
    private val trackRight: Float get() = width - paddingRight - THUMB_RADIUS_DP * density
    private val trackWidth: Float get() = (trackRight - trackLeft).coerceAtLeast(1f)

    /** 0..1 through the video, taking the finger's position over the player's while scrubbing. */
    private val progressFraction: Float
        get() {
            val duration = durationMs
            if (duration <= 0) return 0f
            val shown = if (scrubbing) scrubbingMs else positionMs
            return (shown.toFloat() / duration).coerceIn(0f, 1f)
        }

    override fun onDraw(canvas: Canvas) {
        val centreY = height / 2f
        val played = trackLeft + trackWidth * progressFraction

        // The road ahead: a flat line, dim.
        canvas.drawLine(played, centreY, trackRight, centreY, linePaint)

        // Buffering only shows where it is genuinely behind, which on local files is never.
        val duration = durationMs
        if (duration > 0 && bufferedMs > 0) {
            val buffered = trackLeft + trackWidth * (bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
            if (buffered > played + 1f) {
                canvas.drawLine(played, centreY, buffered, centreY, bufferedPaint)
            }
        }

        // What has been watched: the wave. Built only as far as the play position, so the
        // cost of the path is proportional to what is actually drawn.
        if (played > trackLeft + 0.5f) {
            for (strand in STRANDS.indices) {
                val (amplitudeScale, phaseOffset, alpha) = STRANDS[strand]
                buildWave(centreY, played, amplitudeScale, phaseOffset)
                wavePaint.alpha = (alpha * 255).toInt()
                wavePaint.strokeWidth = (2f - strand * 0.3f) * density
                canvas.drawPath(wavePath, wavePaint)
            }
            wavePaint.alpha = 255
        }

        val radius = (if (scrubbing) THUMB_RADIUS_DP + 2f else THUMB_RADIUS_DP) * density
        canvas.drawCircle(played, centreY, radius, thumbPaint)
    }

    /**
     * Samples the summed sines into [wavePath] from the start of the track to [end].
     *
     * The amplitude is faded in over the first few points and out over the last few, so the
     * wave grows out of the left edge and settles into the thumb rather than being chopped
     * off at both ends.
     */
    private fun buildWave(
        centreY: Float,
        end: Float,
        amplitudeScale: Float,
        phaseOffset: Float,
    ) {
        wavePath.rewind()
        val amplitude = (height / 2f - 2f * density) * AMPLITUDE_FRACTION * amplitudeScale
        val step = STEP_DP * density
        val span = end - trackLeft
        val fade = (span * 0.12f).coerceAtMost(FADE_DP * density).coerceAtLeast(1f)

        var x = trackLeft
        var first = true
        while (x <= end) {
            val t = x - trackLeft
            // Taper at both ends: zero amplitude at the edges, full in the middle.
            val taper = minOf(t / fade, (end - x) / fade, 1f).coerceAtLeast(0f)
            val y = centreY + amplitude * taper * waveAt(t, phaseOffset)
            if (first) {
                wavePath.moveTo(x, y)
                first = false
            } else {
                wavePath.lineTo(x, y)
            }
            x += step
        }
        // Always finish exactly under the thumb, whatever the sampling step left over.
        wavePath.lineTo(end, centreY)
    }

    /**
     * Three sines, summed and normalised to roughly -1..1.
     *
     * The divisors set how many cycles fit across the bar. They are deliberately high and
     * mutually prime-ish: at a handful of cycles this looked like a wandering line rather
     * than a waveform, and at round multiples of each other the sum visibly repeats.
     */
    private fun waveAt(x: Float, phaseOffset: Float): Float {
        val base = trackWidth
        val p = phase + phaseOffset
        val a = sin(TAU * x / (base / 13.1f) + p)
        val b = sin(TAU * x / (base / 7.3f) - p * 0.6f)
        val c = sin(TAU * x / (base / 23.7f) + p * 1.7f)
        return 0.52f * a + 0.32f * b + 0.16f * c
    }

    // ---- scrubbing ----

    private var downX = 0f
    private var dragging = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || durationMs <= 0) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                dragging = false
                // Claim the gesture immediately. The player's own swipe-to-scrub lives in an
                // ancestor, and without this the first movement would be taken as a
                // full-screen seek gesture instead of a drag on this bar.
                parent?.requestDisallowInterceptTouchEvent(true)
                startScrub(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.x - downX) > touchSlop) dragging = true
                updateScrub(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                stopScrub(canceled = false)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                stopScrub(canceled = true)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun positionFor(x: Float): Long {
        val fraction = ((x - trackLeft) / trackWidth).coerceIn(0f, 1f)
        return (durationMs * fraction).toLong()
    }

    private fun startScrub(x: Float) {
        scrubbing = true
        scrubbingMs = positionFor(x)
        isPressed = true
        listeners.forEach { it.onScrubStart(this, scrubbingMs) }
        invalidate()
    }

    private fun updateScrub(x: Float) {
        if (!scrubbing) return
        scrubbingMs = positionFor(x)
        listeners.forEach { it.onScrubMove(this, scrubbingMs) }
        invalidate()
    }

    private fun stopScrub(canceled: Boolean) {
        if (!scrubbing) return
        scrubbing = false
        isPressed = false
        listeners.forEach { it.onScrubStop(this, scrubbingMs, canceled) }
        invalidate()
    }

    private companion object {
        const val TAU = (2.0 * Math.PI).toFloat()
        const val PLAYED_COLOR = 0xFFFFFFFF.toInt()
        const val UNPLAYED_COLOR = 0x59FFFFFF
        const val BUFFERED_COLOR = 0x8CFFFFFF.toInt()
        const val DEFAULT_HEIGHT_DP = 28f
        const val THUMB_RADIUS_DP = 5f
        /** How much of the half-height the tallest strand may use. */
        const val AMPLITUDE_FRACTION = 0.78f

        /**
         * The strands, as (amplitude scale, phase offset, opacity).
         *
         * Offsetting the phase rather than the position is what braids them: each strand
         * peaks somewhere the others do not, so they cross rather than run parallel.
         */
        val STRANDS = listOf(
            Triple(1f, 0f, 1f),
            Triple(0.70f, 2.1f, 0.5f),
            Triple(0.44f, 4.2f, 0.3f),
        )
        /** Sampling interval. Small enough to look smooth, large enough to stay cheap. */
        const val STEP_DP = 1.5f
        /** Distance over which the wave grows from flat to full height at each end. */
        const val FADE_DP = 20f
        const val PHASE_STEP = 0.055f
        const val FRAME_MS = 33L
        const val UPDATE_DELAY_MS = 40L
    }
}
