package com.seamless.player.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.media3.common.C
import androidx.media3.ui.TimeBar
import com.seamless.player.data.SeekBarStyle
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.sin

/**
 * The timeline, in one of four hands.
 *
 * Media3 finds whatever View carries the id `exo_progress` and, if it implements [TimeBar],
 * drives it — so replacing the stock `DefaultTimeBar` is a matter of implementing this
 * interface rather than of patching the controller. Everything about position, buffering,
 * scrubbing and accessibility is shared; only [onDraw] asks which [style] is wanted.
 *
 * [SeekBarStyle.WAVE] is the original and the default. What you have already watched is drawn
 * as a moving waveform; what is left is a flat line. That is the whole idea: the past has
 * shape, the road ahead does not, and the play position is legible from the point where the
 * wave stops without a colour change having to carry it. The wave is three sine components of
 * different wavelength summed together, drawn three times at decreasing amplitude and opacity.
 * One sine looks like a test pattern; three, at wavelengths sharing no common factor, never
 * visibly repeat across the width.
 *
 * [SeekBarStyle.THREAD] is a hairline that lifts into a smooth hill under the play position,
 * with a bead at its crest — the line reacts to where you are rather than changing colour.
 *
 * [SeekBarStyle.BOLD] is a thick rounded bar with a standing pill for a thumb, which grows
 * under the finger. The one to choose if the timeline should be easy to hit.
 *
 * [SeekBarStyle.SOFT] is pressed into the surface: a recessed track with light along its lower
 * inside edge, a soft fill, and a glowing pill.
 *
 * Only the wave animates. The others are redrawn when the position moves and at no other time,
 * which is why the drifting phase is only stepped for that one.
 */
class TimelineBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), TimeBar {

    var style: SeekBarStyle = SeekBarStyle.WAVE
        set(value) {
            if (field == value) return
            field = value
            removeCallbacks(drift)
            if (value == SeekBarStyle.WAVE && isShown) postOnAnimation(drift)
            invalidate()
        }

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

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val wavePath = Path()
    private val threadPath = Path()
    private val bar = RectF()

    /**
     * Redraws the wave while it is on screen and moving.
     *
     * Bounded by design: the controls hide themselves after a few seconds, and `isShown`
     * goes false with them, so this is never a background animation burning frames behind a
     * video. The other styles never post it at all.
     */
    private val drift = object : Runnable {
        override fun run() {
            if (!isShown || style != SeekBarStyle.WAVE) return
            phase += PHASE_STEP
            invalidate()
            postOnAnimationDelayed(this, FRAME_MS)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        removeCallbacks(drift)
        if (isVisible && style == SeekBarStyle.WAVE) postOnAnimation(drift)
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
     * How often the controller should push a new position. 40ms keeps the leading edge moving
     * smoothly instead of stepping once a second.
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

    /** Where buffering has reached, or the play position where there is nothing to show. */
    private fun bufferedEdge(played: Float): Float {
        val duration = durationMs
        if (duration <= 0 || bufferedMs <= 0) return played
        val at = trackLeft + trackWidth * (bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
        return if (at > played + 1f) at else played
    }

    override fun onDraw(canvas: Canvas) {
        val centreY = height / 2f
        val played = trackLeft + trackWidth * progressFraction
        when (style) {
            SeekBarStyle.WAVE -> drawWave(canvas, centreY, played)
            SeekBarStyle.THREAD -> drawThread(canvas, centreY, played)
            SeekBarStyle.BOLD -> drawBold(canvas, centreY, played)
            SeekBarStyle.SOFT -> drawSoft(canvas, centreY, played)
        }
    }

    private fun drawWave(canvas: Canvas, centreY: Float, played: Float) {
        // The road ahead: a flat line, dim.
        canvas.drawLine(played, centreY, trackRight, centreY, linePaint)

        // Buffering only shows where it is genuinely behind, which on local files is never.
        val buffered = bufferedEdge(played)
        if (buffered > played) canvas.drawLine(played, centreY, buffered, centreY, bufferedPaint)

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
     * A hairline that rises into a hill under the play position.
     *
     * The whole thread is drawn dim, then the same path again clipped to what has been played,
     * which is cheaper and steadier than splitting the curve at the crest: one path, two
     * passes, and the bead sits exactly where the two meet.
     */
    private fun drawThread(canvas: Canvas, centreY: Float, played: Float) {
        val lift = (if (scrubbing) THREAD_LIFT_DP + 3f else THREAD_LIFT_DP) * density
        val span = (if (scrubbing) THREAD_SPAN_DP + 12f else THREAD_SPAN_DP) * density
        val start = (played - span / 2f).coerceAtLeast(trackLeft)
        val end = (played + span / 2f).coerceAtMost(trackRight)

        threadPath.rewind()
        threadPath.moveTo(trackLeft, centreY)
        threadPath.lineTo(start, centreY)
        threadPath.cubicTo(
            start + span * 0.28f, centreY,
            played - span * 0.20f, centreY - lift,
            played, centreY - lift,
        )
        threadPath.cubicTo(
            played + span * 0.20f, centreY - lift,
            end - span * 0.28f, centreY,
            end, centreY,
        )
        threadPath.lineTo(trackRight, centreY)

        linePaint.strokeWidth = THREAD_WIDTH_DP * density
        canvas.drawPath(threadPath, linePaint)

        wavePaint.strokeWidth = THREAD_WIDTH_DP * density
        canvas.save()
        canvas.clipRect(0f, 0f, played, height.toFloat())
        canvas.drawPath(threadPath, wavePaint)
        canvas.restore()

        val bead = (if (scrubbing) THREAD_BEAD_DP + 1.5f else THREAD_BEAD_DP) * density
        canvas.drawCircle(played, centreY - lift, bead, thumbPaint)
    }

    /** A thick rounded bar with a standing pill, both of which grow under the finger. */
    private fun drawBold(canvas: Canvas, centreY: Float, played: Float) {
        val thickness = (if (scrubbing) BOLD_HEIGHT_DP + 3f else BOLD_HEIGHT_DP) * density
        val radius = thickness / 2f

        fillPaint.color = TRACK_COLOR
        bar.set(trackLeft, centreY - radius, trackRight, centreY + radius)
        canvas.drawRoundRect(bar, radius, radius, fillPaint)

        val buffered = bufferedEdge(played)
        if (buffered > played) {
            fillPaint.color = BUFFERED_COLOR
            bar.set(trackLeft, centreY - radius, buffered, centreY + radius)
            canvas.drawRoundRect(bar, radius, radius, fillPaint)
        }

        fillPaint.color = PLAYED_COLOR
        bar.set(trackLeft, centreY - radius, played.coerceAtLeast(trackLeft + thickness), centreY + radius)
        canvas.drawRoundRect(bar, radius, radius, fillPaint)

        val pillWidth = BOLD_PILL_W_DP * density
        val pillHeight = (if (scrubbing) BOLD_PILL_H_DP + 5f else BOLD_PILL_H_DP) * density
        bar.set(
            played - pillWidth / 2f, centreY - pillHeight / 2f,
            played + pillWidth / 2f, centreY + pillHeight / 2f,
        )
        canvas.drawRoundRect(bar, pillWidth / 2f, pillWidth / 2f, thumbPaint)
    }

    /**
     * Pressed into the surface: a recessed track, a soft fill, a glowing pill.
     *
     * The glow is three rounded rectangles at falling opacity rather than a blur mask, which
     * hardware canvases do not draw. Three is enough to read as light and costs three fills.
     */
    private fun drawSoft(canvas: Canvas, centreY: Float, played: Float) {
        val thickness = SOFT_HEIGHT_DP * density
        val radius = thickness / 2f

        fillPaint.color = SOFT_WELL_COLOR
        bar.set(trackLeft, centreY - radius, trackRight, centreY + radius)
        canvas.drawRoundRect(bar, radius, radius, fillPaint)

        // The lip: dark along the top inside edge, light along the bottom one, which is what
        // makes the track look sunk rather than drawn on.
        linePaint.strokeWidth = 1f * density
        linePaint.color = SOFT_SHADE_COLOR
        canvas.drawLine(trackLeft + radius, centreY - radius + 0.5f * density, trackRight - radius, centreY - radius + 0.5f * density, linePaint)
        linePaint.color = SOFT_LIGHT_COLOR
        canvas.drawLine(trackLeft + radius, centreY + radius - 0.5f * density, trackRight - radius, centreY + radius - 0.5f * density, linePaint)
        linePaint.color = UNPLAYED_COLOR

        fillPaint.color = SOFT_FILL_COLOR
        val inset = 1.5f * density
        bar.set(
            trackLeft + inset, centreY - radius + inset,
            played.coerceAtLeast(trackLeft + thickness), centreY + radius - inset,
        )
        canvas.drawRoundRect(bar, radius, radius, fillPaint)

        val pillWidth = (if (scrubbing) SOFT_PILL_W_DP + 3f else SOFT_PILL_W_DP) * density
        val pillHeight = SOFT_PILL_H_DP * density
        for (ring in 0 until SOFT_GLOW_RINGS) {
            val spread = ring * 3f * density
            fillPaint.color = SOFT_GLOW_COLOR
            fillPaint.alpha = (SOFT_GLOW_ALPHA / (ring + 1)).toInt()
            bar.set(
                played - pillWidth / 2f - spread, centreY - pillHeight / 2f - spread,
                played + pillWidth / 2f + spread, centreY + pillHeight / 2f + spread,
            )
            canvas.drawRoundRect(bar, (pillWidth + spread) / 2f, (pillWidth + spread) / 2f, fillPaint)
        }
        fillPaint.alpha = 255

        bar.set(
            played - pillWidth / 2f, centreY - pillHeight / 2f,
            played + pillWidth / 2f, centreY + pillHeight / 2f,
        )
        canvas.drawRoundRect(bar, pillWidth / 2f, pillWidth / 2f, thumbPaint)
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
        const val TRACK_COLOR = 0x33FFFFFF
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

        const val THREAD_WIDTH_DP = 1.6f
        const val THREAD_LIFT_DP = 6f
        const val THREAD_SPAN_DP = 34f
        const val THREAD_BEAD_DP = 4.5f

        const val BOLD_HEIGHT_DP = 6f
        const val BOLD_PILL_W_DP = 4f
        const val BOLD_PILL_H_DP = 16f

        const val SOFT_HEIGHT_DP = 10f
        const val SOFT_WELL_COLOR = 0x4D000000
        const val SOFT_SHADE_COLOR = 0x66000000
        const val SOFT_LIGHT_COLOR = 0x26FFFFFF
        const val SOFT_FILL_COLOR = 0xCCFFFFFF.toInt()
        const val SOFT_PILL_W_DP = 11f
        const val SOFT_PILL_H_DP = 18f
        const val SOFT_GLOW_COLOR = 0xFFFFFFFF.toInt()
        const val SOFT_GLOW_ALPHA = 54f
        const val SOFT_GLOW_RINGS = 3
    }
}
