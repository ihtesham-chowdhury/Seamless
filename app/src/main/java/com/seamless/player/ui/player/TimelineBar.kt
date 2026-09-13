package com.seamless.player.ui.player

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.media3.common.C
import androidx.media3.ui.TimeBar
import com.seamless.player.data.SeekBarStyle
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.sin

/**
 * The timeline, in one of six hands.
 *
 * Media3 finds whatever View carries the id `exo_progress` and, if it implements [TimeBar],
 * drives it — so replacing the stock `DefaultTimeBar` is a matter of implementing this
 * interface rather than of patching the controller. Everything about position, buffering,
 * scrubbing and accessibility is shared; only [onDraw] asks which [style] is wanted, and
 * [ControlSkin] dresses the transport buttons to match.
 *
 * [SeekBarStyle.WAVE] is the original and the default. What you have already watched is drawn
 * as a moving waveform; what is left is a flat line. That is the whole idea: the past has
 * shape, the road ahead does not, and the play position is legible from the point where the
 * wave stops without a colour change having to carry it.
 *
 * [SeekBarStyle.THREAD] is a hairline that lifts into a hill under the play position with a
 * bead at its crest. Touch it and the hill rises, widens and takes a halo: the line reacts to
 * the finger rather than changing colour.
 *
 * [SeekBarStyle.SOFT] is pressed into the surface — a recessed track with light along its
 * lower inside edge, a blue fill and a pale knob — and is the one style that follows the app's
 * theme, because a pale panel is what it is drawn as and a dark room is where it usually sits.
 *
 * [SeekBarStyle.MINIMAL] is a slim line with the elapsed and total times at either end of it.
 * [SeekBarStyle.HIGHLIGHT] is a thick amber bar carrying those times inside itself. Both draw
 * their own times, which is why [drawsOwnTimes] exists: the labels under the bar are hidden for
 * them rather than saying everything twice.
 *
 * [SeekBarStyle.FILM] is a strip of film — sprocket holes, frame lines, a warm fill for what
 * has been watched — with a turned knob for a thumb.
 *
 * Only the wave animates. The others are redrawn when the position moves and at no other time,
 * which is why the drifting phase is stepped for that one alone.
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

    /** Whether this style says the time itself, so the labels below it can stand down. */
    val drawsOwnTimes: Boolean
        get() = style == SeekBarStyle.MINIMAL || style == SeekBarStyle.HIGHLIGHT

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

    private val night: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

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
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 12f * density
    }

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

    /** Where the track begins and ends. The styles that write their own times narrow it. */
    private var trackLeft = 0f
    private var trackRight = 0f

    private val trackWidth: Float get() = (trackRight - trackLeft).coerceAtLeast(1f)

    private fun resetTrack() {
        trackLeft = paddingLeft + THUMB_RADIUS_DP * density
        trackRight = width - paddingRight - THUMB_RADIUS_DP * density
    }

    /** 0..1 through the video, taking the finger's position over the player's while scrubbing. */
    private val progressFraction: Float
        get() {
            val duration = durationMs
            if (duration <= 0) return 0f
            val shown = if (scrubbing) scrubbingMs else positionMs
            return (shown.toFloat() / duration).coerceIn(0f, 1f)
        }

    private val shownMs: Long get() = if (scrubbing) scrubbingMs else positionMs

    /** Where buffering has reached, or the play position where there is nothing to show. */
    private fun bufferedEdge(played: Float): Float {
        val duration = durationMs
        if (duration <= 0 || bufferedMs <= 0) return played
        val at = trackLeft + trackWidth * (bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
        return if (at > played + 1f) at else played
    }

    override fun onDraw(canvas: Canvas) {
        val centreY = height / 2f
        resetTrack()
        // These two write the times themselves, so the track has to make room for them first.
        if (style == SeekBarStyle.MINIMAL) makeRoomForTimes(canvas, centreY)
        val played = trackLeft + trackWidth * progressFraction
        when (style) {
            SeekBarStyle.WAVE -> drawWave(canvas, centreY, played)
            SeekBarStyle.THREAD -> drawThread(canvas, centreY, played)
            SeekBarStyle.SOFT -> drawSoft(canvas, centreY, played)
            SeekBarStyle.MINIMAL -> drawMinimal(canvas, centreY, played)
            SeekBarStyle.HIGHLIGHT -> drawHighlight(canvas, centreY, played)
            SeekBarStyle.FILM -> drawFilm(canvas, centreY, played)
        }
    }

    private fun drawWave(canvas: Canvas, centreY: Float, played: Float) {
        // The road ahead: a flat line, dim.
        linePaint.color = UNPLAYED_COLOR
        linePaint.strokeWidth = 2f * density
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
                wavePaint.color = PLAYED_COLOR
                wavePaint.alpha = (alpha * 255).toInt()
                wavePaint.strokeWidth = (2f - strand * 0.3f) * density
                canvas.drawPath(wavePath, wavePaint)
            }
            wavePaint.alpha = 255
        }

        val radius = (if (scrubbing) THUMB_RADIUS_DP + 2f else THUMB_RADIUS_DP) * density
        thumbPaint.color = PLAYED_COLOR
        canvas.drawCircle(played, centreY, radius, thumbPaint)
    }

    /**
     * A hairline that rises into a hill under the play position.
     *
     * The whole thread is drawn dim, then the same path again clipped to what has been played,
     * which is steadier than splitting the curve at the crest: one path, two passes, and the
     * bead sits exactly where the two meet. Under a finger the hill rises half as far again,
     * widens, thickens, and takes a halo — the reaction is the point of this one.
     */
    private fun drawThread(canvas: Canvas, centreY: Float, played: Float) {
        val touched = if (scrubbing) 1f else 0f
        val bead = (THREAD_BEAD_DP + THREAD_BEAD_TOUCHED_DP * touched) * density
        val halo = if (scrubbing) bead * THREAD_HALO else bead
        // The hill may rise into the room the controls bar leaves above the timeline, and no
        // further. It used to rise past it, and the bead's halo was cut flat along the top of
        // the bar the moment a finger touched it - the bar is only 30dp tall.
        val ceiling = -THREAD_OVERFLOW_DP * density
        val lift = ((THREAD_LIFT_DP + THREAD_LIFT_TOUCHED_DP * touched) * density)
            .coerceAtMost(centreY - halo - ceiling)
        val span = (THREAD_SPAN_DP + THREAD_SPAN_TOUCHED_DP * touched) * density
        val weight = (THREAD_WIDTH_DP + 0.8f * touched) * density
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

        linePaint.color = UNPLAYED_COLOR
        linePaint.strokeWidth = weight
        canvas.drawPath(threadPath, linePaint)

        wavePaint.color = PLAYED_COLOR
        wavePaint.strokeWidth = weight
        canvas.save()
        canvas.clipRect(0f, 0f, played, height.toFloat())
        canvas.drawPath(threadPath, wavePaint)
        canvas.restore()

        if (scrubbing) {
            fillPaint.color = PLAYED_COLOR
            fillPaint.alpha = 0x33
            canvas.drawCircle(played, centreY - lift, halo, fillPaint)
            fillPaint.alpha = 255
        }
        thumbPaint.color = PLAYED_COLOR
        canvas.drawCircle(played, centreY - lift, bead, thumbPaint)
    }

    /**
     * Pressed into the surface: a recessed track, a blue fill, a pale knob.
     *
     * The one style that reads the theme. It is drawn as a panel with light falling on it, and
     * a panel that is pale in a light app and dark in a dark one is the same object in two
     * rooms; a single tone would be a hole in one of them.
     */
    private fun drawSoft(canvas: Canvas, centreY: Float, played: Float) {
        val dark = night
        val thickness = SOFT_HEIGHT_DP * density
        val radius = thickness / 2f

        fillPaint.color = if (dark) SOFT_WELL_DARK else SOFT_WELL_LIGHT
        bar.set(trackLeft, centreY - radius, trackRight, centreY + radius)
        canvas.drawRoundRect(bar, radius, radius, fillPaint)

        // The lip: shade along the top inside edge, light along the bottom one, which is what
        // makes the track look sunk rather than drawn on.
        strokePaint.strokeWidth = 1f * density
        strokePaint.color = if (dark) SOFT_SHADE_DARK else SOFT_SHADE_LIGHT
        canvas.drawLine(
            trackLeft + radius, centreY - radius + 0.5f * density,
            trackRight - radius, centreY - radius + 0.5f * density, strokePaint,
        )
        strokePaint.color = if (dark) SOFT_LIGHT_DARK else SOFT_LIGHT_LIGHT
        canvas.drawLine(
            trackLeft + radius, centreY + radius - 0.5f * density,
            trackRight - radius, centreY + radius - 0.5f * density, strokePaint,
        )

        fillPaint.color = if (dark) SOFT_FILL_DARK else SOFT_FILL_LIGHT
        val inset = 1.5f * density
        bar.set(
            trackLeft + inset, centreY - radius + inset,
            played.coerceAtLeast(trackLeft + thickness), centreY + radius - inset,
        )
        canvas.drawRoundRect(bar, radius, radius, fillPaint)

        val knob = (if (scrubbing) SOFT_KNOB_DP + 1.5f else SOFT_KNOB_DP) * density
        // A soft shadow under the knob, three rings of it, because a hardware canvas will not
        // draw a blur mask and three fills read as light all the same.
        for (ring in SOFT_GLOW_RINGS downTo 1) {
            fillPaint.color = if (dark) 0xFF000000.toInt() else 0xFF8A8FA0.toInt()
            fillPaint.alpha = SOFT_GLOW_ALPHA / ring
            canvas.drawCircle(played, centreY + ring * 0.6f * density, knob + ring * 1.6f * density, fillPaint)
        }
        fillPaint.alpha = 255
        thumbPaint.color = if (dark) SOFT_KNOB_DARK else SOFT_KNOB_LIGHT
        canvas.drawCircle(played, centreY, knob, thumbPaint)
    }

    /** Narrows the track to leave room for the times at either end, and draws them. */
    private fun makeRoomForTimes(canvas: Canvas, centreY: Float) {
        val elapsed = formatTime(shownMs)
        val total = formatTime(if (durationMs > 0) durationMs else 0)
        timePaint.color = TIME_COLOR
        timePaint.textAlign = Paint.Align.LEFT
        val baseline = centreY + timePaint.textSize * 0.35f
        canvas.drawText(elapsed, paddingLeft.toFloat(), baseline, timePaint)
        val elapsedWidth = timePaint.measureText(elapsed)
        timePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(total, (width - paddingRight).toFloat(), baseline, timePaint)
        val totalWidth = timePaint.measureText(total)
        val gap = 10f * density
        trackLeft = paddingLeft + elapsedWidth + gap
        trackRight = width - paddingRight - totalWidth - gap
    }

    /** A slim line: red for what has been watched, white for what is left. */
    private fun drawMinimal(canvas: Canvas, centreY: Float, played: Float) {
        val thickness = (if (scrubbing) MINIMAL_HEIGHT_DP + 2f else MINIMAL_HEIGHT_DP) * density
        val radius = thickness / 2f

        fillPaint.color = MINIMAL_TRACK_COLOR
        bar.set(trackLeft, centreY - radius, trackRight, centreY + radius)
        canvas.drawRoundRect(bar, radius, radius, fillPaint)

        fillPaint.color = MINIMAL_PLAYED_COLOR
        bar.set(trackLeft, centreY - radius, played.coerceAtLeast(trackLeft + thickness), centreY + radius)
        canvas.drawRoundRect(bar, radius, radius, fillPaint)

        if (scrubbing) {
            thumbPaint.color = MINIMAL_PLAYED_COLOR
            canvas.drawCircle(played, centreY, radius + 3f * density, thumbPaint)
        }
    }

    /**
     * A thick amber bar carrying the times inside it.
     *
     * The times go inside the fill while there is room for them and just past its end when
     * there is not, which is what keeps them readable in the first seconds of a video rather
     * than squeezed into a sliver of amber.
     */
    private fun drawHighlight(canvas: Canvas, centreY: Float, played: Float) {
        val thickness = (if (scrubbing) HIGHLIGHT_HEIGHT_DP + 2f else HIGHLIGHT_HEIGHT_DP) * density
        val radius = thickness / 2f

        fillPaint.color = HIGHLIGHT_TRACK_COLOR
        bar.set(trackLeft, centreY - radius, trackRight, centreY + radius)
        canvas.drawRoundRect(bar, radius, radius, fillPaint)

        val edge = played.coerceAtLeast(trackLeft + thickness)
        fillPaint.color = HIGHLIGHT_FILL_COLOR
        bar.set(trackLeft, centreY - radius, edge, centreY + radius)
        canvas.drawRoundRect(bar, radius, radius, fillPaint)

        val elapsed = formatTime(shownMs)
        val rest = " / " + formatTime(if (durationMs > 0) durationMs else 0)
        timePaint.textAlign = Paint.Align.LEFT
        val elapsedWidth = timePaint.measureText(elapsed)
        val restWidth = timePaint.measureText(rest)
        val padding = 12f * density
        val inside = edge - trackLeft > elapsedWidth + restWidth + padding * 2
        val startX = if (inside) edge - restWidth - elapsedWidth - padding else edge + padding
        val baseline = centreY + timePaint.textSize * 0.35f
        timePaint.color = if (inside) HIGHLIGHT_INK_COLOR else PLAYED_COLOR
        canvas.drawText(elapsed, startX, baseline, timePaint)
        timePaint.color = if (inside) HIGHLIGHT_INK_FADED else UNPLAYED_COLOR
        canvas.drawText(rest, startX + elapsedWidth, baseline, timePaint)
    }

    /** A strip of film: sprocket holes, frame lines, a warm fill, a turned knob. */
    private fun drawFilm(canvas: Canvas, centreY: Float, played: Float) {
        val half = FILM_HEIGHT_DP * density / 2f
        val corner = 3f * density

        fillPaint.color = FILM_BODY_COLOR
        bar.set(trackLeft, centreY - half, trackRight, centreY + half)
        canvas.drawRoundRect(bar, corner, corner, fillPaint)

        // The frames themselves, between the two bands of sprocket holes.
        val band = FILM_BAND_DP * density
        val frameTop = centreY - half + band
        val frameBottom = centreY + half - band
        fillPaint.color = FILM_EMPTY_COLOR
        bar.set(trackLeft + corner, frameTop, trackRight - corner, frameBottom)
        canvas.drawRect(bar, fillPaint)

        val edge = played.coerceAtMost(trackRight - corner)
        if (edge > trackLeft + corner) {
            fillPaint.shader = LinearGradient(
                trackLeft, 0f, edge, 0f,
                FILM_WARM_START, FILM_WARM_END, Shader.TileMode.CLAMP,
            )
            bar.set(trackLeft + corner, frameTop, edge, frameBottom)
            canvas.drawRect(bar, fillPaint)
            fillPaint.shader = null
        }

        // Frame lines and sprocket holes, spaced by the same pitch so they read as one strip.
        val pitch = FILM_PITCH_DP * density
        val holeWidth = FILM_HOLE_W_DP * density
        val holeHeight = FILM_HOLE_H_DP * density
        var x = trackLeft + pitch
        strokePaint.color = FILM_LINE_COLOR
        strokePaint.strokeWidth = 1f * density
        while (x < trackRight - corner) {
            canvas.drawLine(x, frameTop, x, frameBottom, strokePaint)
            x += pitch
        }
        fillPaint.color = FILM_HOLE_COLOR
        x = trackLeft + pitch / 2f
        while (x < trackRight - holeWidth) {
            bar.set(x, centreY - half + (band - holeHeight) / 2f, x + holeWidth, centreY - half + (band + holeHeight) / 2f)
            canvas.drawRoundRect(bar, holeHeight / 2f, holeHeight / 2f, fillPaint)
            bar.set(x, centreY + half - (band + holeHeight) / 2f, x + holeWidth, centreY + half - (band - holeHeight) / 2f)
            canvas.drawRoundRect(bar, holeHeight / 2f, holeHeight / 2f, fillPaint)
            x += pitch
        }

        val knob = (if (scrubbing) FILM_KNOB_DP + 1.5f else FILM_KNOB_DP) * density
        fillPaint.color = 0x59000000
        canvas.drawCircle(played, centreY + 1.5f * density, knob, fillPaint)
        thumbPaint.color = FILM_KNOB_COLOR
        canvas.drawCircle(played, centreY, knob, thumbPaint)
        strokePaint.color = FILM_KNOB_RIM
        strokePaint.strokeWidth = 1.2f * density
        canvas.drawCircle(played, centreY, knob - 0.6f * density, strokePaint)
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val seconds = total % 60
        val minutes = (total / 60) % 60
        val hours = total / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
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
        const val TIME_COLOR = 0xD9FFFFFF.toInt()
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
        const val THREAD_LIFT_TOUCHED_DP = 5f
        const val THREAD_SPAN_DP = 34f
        const val THREAD_SPAN_TOUCHED_DP = 22f
        const val THREAD_BEAD_DP = 4.5f
        const val THREAD_BEAD_TOUCHED_DP = 2.5f
        /** The halo's radius under a finger, as a multiple of the bead's. */
        const val THREAD_HALO = 1.9f
        /** How far above its own top edge the thread may draw, into the bar's padding. */
        const val THREAD_OVERFLOW_DP = 10f

        const val SOFT_HEIGHT_DP = 12f
        const val SOFT_KNOB_DP = 9f
        const val SOFT_WELL_LIGHT = 0xFFE4E6EC.toInt()
        const val SOFT_WELL_DARK = 0xFF1E1E24.toInt()
        const val SOFT_SHADE_LIGHT = 0x1A000000
        const val SOFT_SHADE_DARK = 0x66000000
        const val SOFT_LIGHT_LIGHT = 0xCCFFFFFF.toInt()
        const val SOFT_LIGHT_DARK = 0x1AFFFFFF
        const val SOFT_FILL_LIGHT = 0xFF2F80ED.toInt()
        const val SOFT_FILL_DARK = 0xFF4A90E2.toInt()
        const val SOFT_KNOB_LIGHT = 0xFFFFFFFF.toInt()
        const val SOFT_KNOB_DARK = 0xFFF2F3F7.toInt()
        const val SOFT_GLOW_ALPHA = 60
        const val SOFT_GLOW_RINGS = 3

        const val MINIMAL_HEIGHT_DP = 4f
        const val MINIMAL_TRACK_COLOR = 0xF2FFFFFF.toInt()
        const val MINIMAL_PLAYED_COLOR = 0xFFE02020.toInt()

        const val HIGHLIGHT_HEIGHT_DP = 22f
        const val HIGHLIGHT_TRACK_COLOR = 0x59202024
        const val HIGHLIGHT_FILL_COLOR = 0xFFFFD400.toInt()
        const val HIGHLIGHT_INK_COLOR = 0xFF16161A.toInt()
        const val HIGHLIGHT_INK_FADED = 0x8C16161A.toInt()

        const val FILM_HEIGHT_DP = 24f
        const val FILM_BAND_DP = 6f
        const val FILM_PITCH_DP = 14f
        const val FILM_HOLE_W_DP = 6f
        const val FILM_HOLE_H_DP = 3f
        const val FILM_KNOB_DP = 9f
        const val FILM_BODY_COLOR = 0xFF2B2B30.toInt()
        const val FILM_EMPTY_COLOR = 0xFF3A3A42.toInt()
        const val FILM_WARM_START = 0xFFF2B06A.toInt()
        const val FILM_WARM_END = 0xFFE2703A.toInt()
        const val FILM_HOLE_COLOR = 0xFFD8D8DE.toInt()
        const val FILM_LINE_COLOR = 0x4D000000
        const val FILM_KNOB_COLOR = 0xFFC79A62.toInt()
        const val FILM_KNOB_RIM = 0x66000000
    }
}
