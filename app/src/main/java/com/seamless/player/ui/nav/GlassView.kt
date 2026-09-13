package com.seamless.player.ui.nav

import android.content.Context
import android.graphics.BlendMode
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
import androidx.core.graphics.ColorUtils
import com.seamless.player.R
import com.seamless.player.data.NavStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A piece of the app's glass — what a surface is made of, as distinct from what sits on it.
 *
 * Two shapes wear it.
 *
 * [Corners.CAPSULE] is the navigation bar: a floating pill, for the two glass styles really
 * frosted on Android 12 and later — the page behind drawn again inside the shape through a blur
 * and a little extra saturation, then veiled and edged with a highlight. The island style's
 * capsule is not glass at all; it keeps the dark surface from glass_nav_bg.xml.
 *
 * [Corners.CANOPY] is what the Shorts tab's title and quick views hang in: full width, no edge
 * anywhere, and a lower part that dissolves into the wall of clips rather than ending in a line.
 * The blurred copy, the opaque floor under it and the veil over it all fade together, over the
 * same curve, so what the eye gets is the wall softening as it passes under the title rather
 * than a panel with a gradient on it. Each navigation style gives the canopy a material of its
 * own, and the three are meant to read as three expressions of one header:
 *
 * - frosted glass blurs the most and veils calmly: soft glass over the collection;
 * - the accent island blurs lightly, lets more of the wall through, and puts its colour in one
 *   faint wash near the top, leaving the chosen quick view to carry the accent properly;
 * - liquid glass barely blurs, is the clearest of the three, catches a band of light along the
 *   top, and dissolves over the longest distance: clear glass over the videos.
 *
 * The blurred page comes from [BackdropFrame], which records what its children draw; see there
 * for why that costs a reference rather than a repaint. The canopy's fade is part of the blur's
 * own render effect — the blurred copy is masked by a gradient on the GPU — so a scroll costs
 * the same as it did before the canopy dissolved.
 *
 * Where there is blur, the shape is painted opaque with the window colour first. The content
 * behind is full of transparent gaps, and without that floor the real, sharp content would show
 * through them beneath its own blurred copy. Below Android 12 there is no blur for a floor to
 * protect, so a canopy there is only its veil, a little stronger, over the sharp wall.
 */
class GlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Corners { CAPSULE, CANOPY }

    var source: BackdropFrame? = null

    var corners = Corners.CAPSULE
        set(value) {
            if (field == value) return
            field = value
            invalidateOutline()
            rebuildShaders()
            applyEffect()
            invalidate()
        }

    /**
     * How present a canopy's veil is, 0 to 1: lighter while the wall rests at the top, full
     * once clips have scrolled under it. A paint alpha on a view that the scroll is already
     * redrawing, so it costs nothing a scroll did not already cost.
     */
    var strength = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (abs(field - clamped) < STRENGTH_STEP && clamped != 0f && clamped != 1f) return
            if (field == clamped) return
            field = clamped
            if (corners == Corners.CANOPY) invalidate()
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
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shape = RectF()
    private val island = ContextCompat.getDrawable(context, R.drawable.glass_nav_bg)

    private var windowColour = Color.BLACK
    private var veilTop = 0
    private var veilBottom = 0
    private var rimTop = 0
    private var rimBottom = 0

    // The canopy's material, set per style in configure().
    private var canopyBlurDp = 22f
    private var canopySaturation = 1.25f
    private var canopyVeil = 0
    private var canopyWash = 0
    private var fadeStart = 0.58f
    private var restStrength = 0.75f

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val h = view.height
                if (corners == Corners.CAPSULE) {
                    outline.setRoundRect(0, 0, view.width, h, h / 2f)
                } else {
                    outline.setRect(0, 0, view.width, h)
                }
            }
        }
        clipToOutline = true
    }

    fun configure(style: NavStyle, night: Boolean) {
        look = style
        windowColour = resolveWindowColour()
        floor.color = windowColour
        val accent = ContextCompat.getColor(context, R.color.nav_island)
        when (style) {
            NavStyle.GLASS -> {
                // Orion's bar: a milky veil that still lets colour through, a bright top edge.
                veilTop = if (night) 0xB82C2B33.toInt() else 0xC2FFFFFF.toInt()
                veilBottom = if (night) 0xB31D1C22.toInt() else 0xB3F2F3F7.toInt()
                rimTop = if (night) 0x29FFFFFF else 0xFFFFFFFF.toInt()
                rimBottom = if (night) 0x0AFFFFFF else 0x59FFFFFF
                hairline.color = if (night) 0x40000000 else 0x14000000
                // Soft glass: the most blur, a calm veil, a whisper of light along the top.
                canopyBlurDp = 22f
                canopySaturation = 1.25f
                canopyVeil = if (night) 0xA61A1920.toInt() else 0xB3F7F7FA.toInt()
                canopyWash = if (night) 0x0FFFFFFF else 0x59FFFFFF
                fadeStart = 0.58f
                restStrength = 0.75f
            }
            NavStyle.LIQUID -> {
                // Liquid glass is clearer, with a brighter, thicker specular edge.
                veilTop = if (night) 0x4D2A2A30 else 0x6BFFFFFF
                veilBottom = if (night) 0x6B111115 else 0x47FFFFFF
                rimTop = if (night) 0x59FFFFFF else 0xF2FFFFFF.toInt()
                rimBottom = if (night) 0x17FFFFFF else 0x99FFFFFF.toInt()
                hairline.color = if (night) 0x66000000 else 0x12000000
                // Clear glass: barely blurred, the lightest veil, a band of light, the longest fade.
                canopyBlurDp = 3f
                canopySaturation = 1.5f
                canopyVeil = if (night) 0x520C0C10 else 0x5CFFFFFF
                canopyWash = if (night) 0x17FFFFFF else 0x8CFFFFFF.toInt()
                fadeStart = 0.38f
                restStrength = 0.6f
            }
            NavStyle.ISLAND -> {
                // The island's capsule keeps its drawable; these are for when it is a canopy.
                veilTop = ContextCompat.getColor(context, R.color.nav_fill_top)
                veilBottom = ContextCompat.getColor(context, R.color.nav_fill_bottom)
                rimTop = if (night) 0x14FFFFFF else 0x0D000000
                rimBottom = 0x00FFFFFF
                hairline.color = ContextCompat.getColor(context, R.color.nav_stroke)
                // Focused: light blur, a thinner dark veil, and the accent as one faint wash.
                canopyBlurDp = 8f
                canopySaturation = 1.1f
                canopyVeil = if (night) 0x9410101A.toInt() else 0x94FFFFFF.toInt()
                canopyWash = ColorUtils.setAlphaComponent(accent, if (night) 0x2B else 0x1F)
                fadeStart = 0.5f
                restStrength = 0.8f
            }
        }
        rim.strokeWidth = density * if (style == NavStyle.LIQUID) 1.3f else 1f
        hairline.strokeWidth = density * 0.75f
        applyEffect()
        rebuildShaders()
        invalidate()
    }

    /** Chooses and installs the render effect for the current shape and style. */
    private fun applyEffect() {
        val canopy = corners == Corners.CANOPY
        blurring = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (canopy || look != NavStyle.ISLAND)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!blurring) {
            blurNode.setRenderEffect(null)
            return
        }
        // A canopy's fade is measured against its height, which layout has not given it yet.
        if (canopy && height == 0) return
        blurNode.setRenderEffect(if (canopy) canopyEffect() else capsuleEffect(look))
    }

    /** Blur, then a touch more saturation — glass that greys everything out reads as fog. */
    private fun capsuleEffect(style: NavStyle): RenderEffect {
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

    /**
     * The canopy's blur, masked so that it dissolves.
     *
     * DST_IN keeps the blurred copy only as far as the gradient's alpha allows, so the copy
     * fades over exactly the curve the floor and the veil fade over. The node spans the view and
     * the pad around it, so the view's own top edge sits [blurPad] down in the node's space.
     */
    private fun canopyEffect(): RenderEffect {
        val radius = (density * canopyBlurDp).coerceAtLeast(1f)
        blurPad = (radius * 1.5f).roundToInt()
        val saturate = ColorMatrix().apply { setSaturation(canopySaturation) }
        val blur = RenderEffect.createChainEffect(
            RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturate)),
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
        )
        val top = blurPad.toFloat()
        val mask = fadeGradient(top, top + height, Color.BLACK)
        return RenderEffect.createBlendModeEffect(blur, RenderEffect.createShaderEffect(mask), BlendMode.DST_IN)
    }

    /**
     * Solid to [fadeStart], then easing away to nothing at [bottom]: quickly at first and slowly
     * at the end, so the edge is felt rather than seen.
     */
    private fun fadeGradient(top: Float, bottom: Float, colour: Int): LinearGradient {
        val span = 1f - fadeStart
        return LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(colour, colour, faded(colour, 0.55f), faded(colour, 0.18f), faded(colour, 0f)),
            floatArrayOf(0f, fadeStart, fadeStart + span * 0.45f, fadeStart + span * 0.8f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun faded(colour: Int, factor: Float): Int =
        ColorUtils.setAlphaComponent(colour, (Color.alpha(colour) * factor).roundToInt())

    private fun resolveWindowColour(): Int {
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuildShaders()
        if (corners == Corners.CANOPY) applyEffect()
    }

    private fun rebuildShaders() {
        val h = height.toFloat()
        if (h <= 0f) return
        if (corners == Corners.CANOPY) {
            floor.shader = fadeGradient(0f, h, windowColour)
            // With no blur underneath, the veil is all there is to keep the title readable over
            // a sharp wall, so it is allowed to be half as present again.
            val veilColour = if (blurring) canopyVeil else strengthened(canopyVeil)
            veil.shader = fadeGradient(0f, h, veilColour)
            wash.shader = LinearGradient(
                0f, 0f, 0f, h * WASH_REACH, canopyWash, faded(canopyWash, 0f), Shader.TileMode.CLAMP,
            )
        } else {
            floor.shader = null
            veil.shader = LinearGradient(0f, 0f, 0f, h, veilTop, veilBottom, Shader.TileMode.CLAMP)
            rim.shader = LinearGradient(0f, 0f, 0f, h, rimTop, rimBottom, Shader.TileMode.CLAMP)
        }
    }

    private fun strengthened(colour: Int): Int =
        ColorUtils.setAlphaComponent(colour, (Color.alpha(colour) * 1.5f).roundToInt().coerceAtMost(0xE6))

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        if (corners == Corners.CANOPY) {
            drawCanopy(canvas, w, h)
            return
        }

        // The navigation capsule keeps the drawable it has always had, gradients and all.
        if (look == NavStyle.ISLAND) {
            island?.setBounds(0, 0, w, h)
            island?.draw(canvas)
            return
        }

        val radius = h / 2f
        shape.set(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawRoundRect(shape, radius, radius, floor)
        drawBackdrop(canvas, w, h)
        canvas.drawRoundRect(shape, radius, radius, veil)

        val hair = hairline.strokeWidth / 2f
        shape.set(hair, hair, w - hair, h - hair)
        canvas.drawRoundRect(shape, radius - hair, radius - hair, hairline)

        val edge = hairline.strokeWidth + rim.strokeWidth / 2f
        shape.set(edge, edge, w - edge, h - edge)
        canvas.drawRoundRect(shape, radius - edge, radius - edge, rim)
    }

    private fun drawCanopy(canvas: Canvas, w: Int, h: Int) {
        val right = w.toFloat()
        val bottom = h.toFloat()
        if (blurring) canvas.drawRect(0f, 0f, right, bottom, floor)
        drawBackdrop(canvas, w, h)
        veil.alpha = (255 * (restStrength + (1f - restStrength) * strength)).roundToInt()
        canvas.drawRect(0f, 0f, right, bottom, veil)
        if (Color.alpha(canopyWash) > 0) canvas.drawRect(0f, 0f, right, bottom * WASH_REACH, wash)
    }

    private fun drawBackdrop(canvas: Canvas, w: Int, h: Int) {
        val frame = source ?: return
        if (!blurring || !canvas.isHardwareAccelerated || !frame.capturing ||
            !frame.backdrop.hasDisplayList()
        ) {
            return
        }
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

    private companion object {
        /** How far down a canopy its wash or band of light reaches, as a share of its height. */
        const val WASH_REACH = 0.45f

        /** The smallest change in [strength] worth a redraw. */
        const val STRENGTH_STEP = 0.02f
    }
}
