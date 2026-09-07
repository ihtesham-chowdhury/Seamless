package com.seamless.player.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.content.res.AppCompatResources
import com.seamless.player.R

/**
 * The volume and brightness readout: a vertical level, an icon and a percentage.
 *
 * It replaced a line of text. Text told you the number and nothing else — you could not see
 * how much headroom was left, or how far a drag had moved you, without reading and comparing
 * two numbers. A filled column answers both at a glance, which is the whole reason the
 * gesture exists.
 *
 * Drawn rather than composed from child views. Everything here is a rounded rectangle, a
 * glyph and one line of text, and a custom onDraw is both smaller and cheaper than the
 * layout it would otherwise take.
 */
class LevelHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Which quantity is being adjusted. Decides the glyph and which edge it sits against. */
    enum class Kind { VOLUME, BRIGHTNESS }

    private val density = resources.displayMetrics.density

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BACKGROUND_COLOR
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK_COLOR }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILL_COLOR }
    private val boostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BOOST_COLOR }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FILL_COLOR
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
        isFakeBoldText = true
    }

    private val rect = RectF()

    private var kind: Kind = Kind.VOLUME

    /** 0..1 of the ordinary range. */
    private var level = 0f

    /** 0..1 again, for whatever sits *above* the ordinary maximum. Volume boost only. */
    private var overflow = 0f

    private var label: String = ""
    private var glyph: Drawable? = null

    /**
     * Sets everything the view shows in one call.
     *
     * @param level 0..1 within the normal range.
     * @param overflow 0..1 of the amplified range beyond it, or 0 when there is none.
     * @param automatic brightness only: the screen has been handed back to the system.
     */
    fun show(
        kind: Kind,
        level: Float,
        label: String,
        overflow: Float = 0f,
        automatic: Boolean = false,
    ) {
        this.kind = kind
        this.level = level.coerceIn(0f, 1f)
        this.overflow = overflow.coerceIn(0f, 1f)
        this.label = label
        glyph = AppCompatResources.getDrawable(context, glyphFor(kind, this.level, automatic))
        alignToGesture(kind)
        invalidate()
    }

    private fun glyphFor(kind: Kind, level: Float, automatic: Boolean): Int = when {
        kind == Kind.BRIGHTNESS && automatic -> R.drawable.ic_brightness_auto
        kind == Kind.BRIGHTNESS -> R.drawable.ic_brightness
        level <= 0f -> R.drawable.ic_volume_off
        level < 0.5f -> R.drawable.ic_volume_low
        else -> R.drawable.ic_volume_up
    }

    /**
     * Puts the readout on the same side of the screen as the gesture that summoned it —
     * brightness left, volume right — so it appears under the thumb rather than across the
     * middle of the picture.
     */
    private fun alignToGesture(kind: Kind) {
        val params = layoutParams as? FrameLayout.LayoutParams ?: return
        val side = if (kind == Kind.BRIGHTNESS) Gravity.START else Gravity.END
        val wanted = side or Gravity.CENTER_VERTICAL
        if (params.gravity != wanted) {
            params.gravity = wanted
            layoutParams = params
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((WIDTH_DP * density).toInt(), widthMeasureSpec),
            resolveSize((HEIGHT_DP * density).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, w / 2f, w / 2f, backgroundPaint)

        val pad = PADDING_DP * density
        val glyphSize = GLYPH_DP * density
        val textHeight = textPaint.textSize

        // Column runs between the number at the top and the glyph at the bottom.
        val top = pad + textHeight + pad * 0.6f
        val bottom = h - pad - glyphSize - pad * 0.6f
        val trackWidth = TRACK_DP * density
        val left = (w - trackWidth) / 2f

        rect.set(left, top, left + trackWidth, bottom)
        canvas.drawRoundRect(rect, trackWidth / 2f, trackWidth / 2f, trackPaint)

        val span = bottom - top
        // Amplified volume takes the top slice of the column, so the normal range keeps a
        // fixed size and 100% stays in the same place whether or not boost is available.
        val normalSpan = if (overflow > 0f) span * (1f - BOOST_SHARE) else span
        val filled = normalSpan * level
        if (filled > 0f) {
            rect.set(left, bottom - filled, left + trackWidth, bottom)
            canvas.drawRoundRect(rect, trackWidth / 2f, trackWidth / 2f, fillPaint)
        }
        if (overflow > 0f) {
            val boostTop = bottom - normalSpan - (span * BOOST_SHARE * overflow)
            rect.set(left, boostTop, left + trackWidth, bottom - normalSpan)
            canvas.drawRoundRect(rect, trackWidth / 2f, trackWidth / 2f, boostPaint)
        }

        canvas.drawText(label, w / 2f, pad + textHeight, textPaint)

        glyph?.let {
            val glyphLeft = ((w - glyphSize) / 2f).toInt()
            val glyphTop = (h - pad - glyphSize).toInt()
            it.setBounds(
                glyphLeft, glyphTop,
                glyphLeft + glyphSize.toInt(), glyphTop + glyphSize.toInt(),
            )
            it.draw(canvas)
        }
    }

    private companion object {
        const val WIDTH_DP = 54f
        const val HEIGHT_DP = 172f
        const val PADDING_DP = 12f
        const val TRACK_DP = 6f
        const val GLYPH_DP = 22f
        const val BACKGROUND_COLOR = 0xB3000000.toInt()
        const val TRACK_COLOR = 0x40FFFFFF
        const val FILL_COLOR = 0xFFFFFFFF.toInt()
        /** Amplified volume, marked out in the accent rather than in plain white. */
        const val BOOST_COLOR = 0xFFF2C230.toInt()
        /** How much of the column the amplified range occupies when it is in play. */
        const val BOOST_SHARE = 0.33f
    }
}
