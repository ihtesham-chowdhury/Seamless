package com.seamless.player.ui.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import com.seamless.player.R
import com.seamless.player.data.NavStyle
import kotlin.math.roundToInt

/**
 * A piece of the app's glass — what a surface is made of, as distinct from what sits on it.
 *
 * Two shapes wear it. [Corners.CAPSULE] is the navigation bar: a floating pill. [Corners.BOTTOM]
 * is a panel hung from the top of a screen, square across the top and rounded along its lower
 * edge, which is what the Shorts tab's title and quick views sit on so that the wall of clips
 * can slide underneath rather than stopping dead against them.
 *
 * For the two glass styles, on Android 12 and later, it is really frosted: the page behind is
 * drawn again inside the shape through a blur and a little extra saturation, then veiled with a
 * tint and edged with a highlight. The page comes from [BackdropFrame], which records what its
 * children draw; see there for why that costs a reference rather than a repaint.
 *
 * The shape is painted opaque with the window colour before anything else. The content behind is
 * full of transparent gaps — a list row is text on nothing — and without an opaque floor the
 * real, sharp content would show through those gaps beneath its own blurred copy. Below Android
 * 12 there is no blur, and the same floor plus the tint makes a plain solid surface, which is the
 * honest fallback.
 *
 * The island style is not glass at all: as a capsule it is the dark surface from
 * glass_nav_bg.xml, and as a panel it is that surface's colours painted into this shape.
 */
class GlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Corners { CAPSULE, BOTTOM }

    var source: BackdropFrame? = null

    var corners = Corners.CAPSULE
        set(value) {
            if (field == value) return
            field = value
            invalidateOutline()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private var look = NavStyle.GLASS
    private var blurring = false
    private var blurPad = 0
    private val blurNode = RenderNode("glass")
    private val here = IntArray(2)
    private val there = IntArray(2)

    private val floor = Paint(Paint.ANTI_ALIAS_FLAG)
    private val veil = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shape = RectF()
    private val island = ContextCompat.getDrawable(context, R.drawable.glass_nav_bg)

    private var veilTop = 0
    private var veilBottom = 0
    private var rimTop = 0
    private var rimBottom = 0

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val h = view.height
                if (corners == Corners.CAPSULE) {
                    outline.setRoundRect(0, 0, view.width, h, h / 2f)
                } else {
                    // Rounded along the bottom only: the top corners are pushed off the top edge.
                    val radius = PANEL_RADIUS * density
                    outline.setRoundRect(0, -radius.roundToInt(), view.width, h, radius)
                }
            }
        }
        clipToOutline = true
    }

    fun configure(style: NavStyle, night: Boolean) {
        look = style
        blurring = style != NavStyle.ISLAND && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurNode.setRenderEffect(if (blurring) glassEffect(style) else null)
        }
        floor.color = windowColour()
        when (style) {
            // Orion's bar: a milky veil that still lets colour through, a bright top edge.
            NavStyle.GLASS -> {
                veilTop = if (night) 0xB82C2B33.toInt() else 0xC2FFFFFF.toInt()
                veilBottom = if (night) 0xB31D1C22.toInt() else 0xB3F2F3F7.toInt()
                rimTop = if (night) 0x29FFFFFF else 0xFFFFFFFF.toInt()
                rimBottom = if (night) 0x0AFFFFFF else 0x59FFFFFF
                hairline.color = if (night) 0x40000000 else 0x14000000
            }
            // Liquid glass is clearer, with a brighter, thicker specular edge.
            NavStyle.LIQUID -> {
                veilTop = if (night) 0x4D2A2A30 else 0x6BFFFFFF
                veilBottom = if (night) 0x6B111115 else 0x47FFFFFF
                rimTop = if (night) 0x59FFFFFF else 0xF2FFFFFF.toInt()
                rimBottom = if (night) 0x17FFFFFF else 0x99FFFFFF.toInt()
                hairline.color = if (night) 0x66000000 else 0x12000000
            }
            // The island's own surface, for when this is a panel rather than the capsule.
            NavStyle.ISLAND -> {
                veilTop = ContextCompat.getColor(context, R.color.nav_fill_top)
                veilBottom = ContextCompat.getColor(context, R.color.nav_fill_bottom)
                rimTop = if (night) 0x14FFFFFF else 0x0D000000
                rimBottom = 0x00FFFFFF
                hairline.color = ContextCompat.getColor(context, R.color.nav_stroke)
            }
        }
        rim.strokeWidth = density * if (style == NavStyle.LIQUID) 1.3f else 1f
        hairline.strokeWidth = density * 0.75f
        rebuildShaders()
        invalidate()
    }

    /** Blur, then a touch more saturation — glass that greys everything out reads as fog. */
    private fun glassEffect(style: NavStyle): RenderEffect {
        val radius = density * if (style == NavStyle.LIQUID) 14f else 24f
        // The recording reaches past the shape by more than the blur's reach, so the edges are
        // blurred from what really lies beyond them instead of from smeared edge pixels.
        blurPad = (radius * 1.5f).roundToInt()
        val saturate = ColorMatrix().apply { setSaturation(if (style == NavStyle.LIQUID) 1.6f else 1.3f) }
        return RenderEffect.createChainEffect(
            RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturate)),
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
        )
    }

    private fun windowColour(): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(android.R.attr.colorBackground, value, true)) {
            return Color.BLACK
        }
        return when {
            value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> value.data
            value.resourceId != 0 -> ContextCompat.getColor(context, value.resourceId)
            else -> Color.BLACK
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuildShaders()

    private fun rebuildShaders() {
        val h = height.toFloat()
        if (h <= 0f) return
        veil.shader = LinearGradient(0f, 0f, 0f, h, veilTop, veilBottom, Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(0f, 0f, 0f, h, rimTop, rimBottom, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        // The navigation capsule keeps the drawable it has always had, gradients and all.
        if (look == NavStyle.ISLAND && corners == Corners.CAPSULE) {
            island?.setBounds(0, 0, w, h)
            island?.draw(canvas)
            return
        }

        val capsule = corners == Corners.CAPSULE
        val radius = if (capsule) h / 2f else PANEL_RADIUS * density
        shape.set(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawRoundRect(shape, radius, radius, floor)

        val frame = source
        if (blurring && canvas.isHardwareAccelerated && frame != null && frame.capturing &&
            frame.backdrop.hasDisplayList()
        ) {
            getLocationInWindow(here)
            frame.getLocationInWindow(there)
            blurNode.setPosition(-blurPad, -blurPad, w + blurPad, h + blurPad)
            val recording = blurNode.beginRecording(w + 2 * blurPad, h + 2 * blurPad)
            recording.translate(
                (there[0] - here[0] + blurPad).toFloat(),
                (there[1] - here[1] + blurPad).toFloat(),
            )
            recording.drawRenderNode(frame.backdrop)
            blurNode.endRecording()
            canvas.drawRenderNode(blurNode)
        }

        canvas.drawRoundRect(shape, radius, radius, veil)

        if (capsule) {
            val hair = hairline.strokeWidth / 2f
            shape.set(hair, hair, w - hair, h - hair)
            canvas.drawRoundRect(shape, radius - hair, radius - hair, hairline)

            val edge = hairline.strokeWidth + rim.strokeWidth / 2f
            shape.set(edge, edge, w - edge, h - edge)
            canvas.drawRoundRect(shape, radius - edge, radius - edge, rim)
        } else {
            // A panel's edge is the one along the bottom; the clip has already rounded it, so a
            // line drawn inside the shape follows it without needing a second rounded path.
            val hair = hairline.strokeWidth / 2f
            canvas.drawLine(0f, h - hair, w.toFloat(), h - hair, hairline)
            val top = rim.strokeWidth / 2f
            canvas.drawLine(0f, top, w.toFloat(), top, rim)
        }
    }

    private companion object {
        /** How far a panel's lower corners are rounded. */
        const val PANEL_RADIUS = 24f
    }
}
