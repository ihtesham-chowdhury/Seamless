package com.seamless.player.ui.nav

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.seamless.player.R
import com.seamless.player.data.NavStyle
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The navigation capsule: three tabs, one selection, three ways to wear it.
 *
 * [NavStyle.GLASS] is frosted glass, after Orion Store's bar. The capsule is only as wide as its
 * tabs, the selected tab is a solid pill in the accent colour holding its icon and its name, and
 * the others are icons alone. Changing tab does not slide anything: the pill that was selected
 * folds back to a circle round its icon and fades, while the new one fades in and opens to show
 * its name, and the icons between make room as it does.
 *
 * [NavStyle.ISLAND] is the dark capsule with an accent island gliding beneath the tabs.
 *
 * [NavStyle.LIQUID] is liquid glass: a clear capsule with a drop of glass under the selected tab.
 * Touch the capsule and the drop lifts and swells; drag and it follows the finger; let go and it
 * travels to the tab, stretching in proportion to its speed and thinning to match, and settles
 * with a little give. The icon under it grows as it passes, as if seen through a lens.
 *
 * All the motion is springs stepped from the frame clock ([Spring]), for two reasons, both about
 * smoothness. A tap mid-glide bends the motion rather than restarting it. And a slow frame — the
 * new tab being built is the usual one — is never made up for by jumping ahead: one frame advances
 * the springs by at most [MAX_STEP], so a stall is felt as a moment's pause instead of a skip.
 *
 * Touch is handled here rather than by the tabs, because the liquid drop follows a finger across
 * all three. A tap still ends in the tab's own performClick, so click listeners, the click sound
 * and accessibility all go through the tab as they always did.
 */
class FloatingNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** The frame the tabs draw into, which the glass blurs. */
    var backdrop: BackdropFrame? = null
        set(value) {
            field?.onBackdropChanged = null
            field = value
            glass.source = value
            value?.onBackdropChanged = { glass.invalidate() }
            value?.capturing = look != NavStyle.ISLAND
        }

    private val density = resources.displayMetrics.density
    private val glass = GlassView(context)
    private val indicator = View(context)
    private val droplet = DropletDrawable(density)
    private val tabs = ArrayList<NavTabView>(3)
    private val fades = ArrayList<Spring>(3)
    private val expands = ArrayList<Spring>(3)
    private var widths = FloatArray(0)

    private var look = NavStyle.GLASS
    private var selected = 0
    private var laidOut = false
    private var snapOnLayout = true

    /** The capsule's visible bounds inside this view. The frosted capsule is narrower, centred. */
    private val capsule = RectF()

    private val edgeLeft = Spring(GLIDE, 1f, PX_REST, PX_SPEED)
    private val edgeRight = Spring(GLIDE, 1f, PX_REST, PX_SPEED)
    private val dropCentre = Spring(DROP_RESPONSE, DROP_DAMPING, PX_REST, PX_SPEED)
    private val lift = Spring(LIFT_RESPONSE, LIFT_DAMPING, UNIT_REST, UNIT_SPEED)

    private var selectionColour = 0
    private var inkOnSelection = 0
    private var inkMuted = 0
    private var inkPlain = 0
    private var inkAccent = 0

    private val medium = Typeface.create(Typeface.DEFAULT, 500, false)
    private val semibold = Typeface.create(Typeface.DEFAULT, 600, false)

    private var pressed = false
    private var pressedTab = -1

    private var ticking = false
    private var lastFrameNanos = 0L
    private val frameCallback = Choreographer.FrameCallback { now -> onFrame(now) }

    init {
        clipChildren = false
        clipToPadding = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    capsule.left.roundToInt(), 0, capsule.right.roundToInt(), view.height,
                    view.height / 2f,
                )
                // Opaque as far as the shadow is concerned, so none is drawn under the glass.
                outline.alpha = 1f
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        for (i in 0 until childCount) (getChildAt(i) as? NavTabView)?.let { tabs += it }
        widths = FloatArray(tabs.size)
        repeat(tabs.size) {
            fades += Spring(FADE_IN, 1f, UNIT_REST, UNIT_SPEED)
            expands += Spring(GROW, 1f, UNIT_REST, UNIT_SPEED)
        }
        addView(glass, 0)
        addView(indicator, 1)
        setStyle(look)
    }

    /** Changes how the capsule looks, in place. The tabs, and which one is selected, stay. */
    fun setStyle(style: NavStyle) {
        look = style
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        resolveInk(night)
        glass.configure(style, night)
        backdrop?.capturing = style != NavStyle.ISLAND

        val labelSize = when (style) {
            NavStyle.ISLAND -> 11f
            NavStyle.GLASS -> 14f
            NavStyle.LIQUID -> 10f
        }
        tabs.forEachIndexed { i, tab ->
            tab.horizontal = style == NavStyle.GLASS
            tab.iconSize = dp(if (style == NavStyle.LIQUID) 24f else 22f).roundToInt()
            tab.verticalGap = dp(if (style == NavStyle.LIQUID) 2f else 3f)
            tab.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSize)
            tab.label.typeface = labelFace(i == selected)
            tab.iconScale = 1f
            tab.pill.visibility = if (style == NavStyle.GLASS) View.VISIBLE else View.GONE
            tab.pill.outlineSpotShadowColor = selectionColour
            tab.pill.outlineAmbientShadowColor = ColorUtils.setAlphaComponent(selectionColour, 0x40)
            // Above whatever the selection is drawn with: elevation decides drawing order first.
            tab.translationZ = dp(if (style == NavStyle.LIQUID) 12f else 4f)
            tab.resetInk()
        }

        when (style) {
            NavStyle.ISLAND -> {
                indicator.visibility = View.VISIBLE
                indicator.background = ContextCompat.getDrawable(context, R.drawable.nav_island_bg)
                indicator.elevation = dp(3f)
                indicator.translationZ = 0f
                indicator.outlineAmbientShadowColor = 0x33000000
                indicator.outlineSpotShadowColor = selectionColour
            }
            NavStyle.LIQUID -> {
                indicator.visibility = View.VISIBLE
                droplet.night = night
                indicator.background = droplet
                indicator.elevation = dp(1f)
                indicator.outlineAmbientShadowColor = if (night) 0x66000000 else 0x1F000000
                indicator.outlineSpotShadowColor = if (night) 0x99000000.toInt() else 0x33000000
            }
            NavStyle.GLASS -> indicator.visibility = View.GONE
        }

        elevation = dp(if (style == NavStyle.ISLAND) 8f else 12f)
        outlineAmbientShadowColor = when {
            style == NavStyle.ISLAND || night -> 0x66000000
            else -> 0x1A000000
        }
        outlineSpotShadowColor = when {
            style == NavStyle.ISLAND || night -> 0x99000000.toInt()
            else -> 0x38000000
        }

        snapOnLayout = true
        requestLayout()
        invalidate()
    }

    /** Moves the selection to the tab with [id]. */
    fun select(id: Int, animate: Boolean) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        selected = index
        tabs.forEachIndexed { i, tab ->
            tab.isSelected = i == index
            tab.label.typeface = labelFace(i == index)
        }
        retarget(animate)
    }

    private fun labelFace(isSelected: Boolean) =
        if (look == NavStyle.GLASS || isSelected) semibold else medium

    private fun resolveInk(night: Boolean) {
        selectionColour = ContextCompat.getColor(context, R.color.nav_island)
        inkOnSelection = ContextCompat.getColor(context, R.color.nav_on_island)
        inkMuted = ContextCompat.getColor(context, R.color.nav_ink_muted)
        inkPlain = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.nav_ink), 0xD9)
        val accent = ContextCompat.getColor(context, R.color.nav_accent)
        // Light glass is nearly white, and a light accent on it barely reads, so the selected
        // tab's ink is taken a step darker there.
        inkAccent = if (night) accent else ColorUtils.blendARGB(accent, Color.BLACK, 0.18f)
    }

    // ---- measuring and placing ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = dp(
            when (look) {
                NavStyle.ISLAND -> ISLAND_HEIGHT
                NavStyle.GLASS -> GLASS_HEIGHT
                NavStyle.LIQUID -> LIQUID_HEIGHT
            }
        ).roundToInt()
        val tabHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        val tabWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
        tabs.forEach { it.measure(tabWidth, tabHeight) }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        computeCapsule()
        val first = !laidOut
        laidOut = true
        if (changed || first || snapOnLayout) {
            snapOnLayout = false
            retarget(animate = false)
        } else {
            applyFrame()
        }
    }

    private fun computeCapsule() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (look == NavStyle.GLASS && tabs.isNotEmpty()) {
            val widest = tabs.maxOf { it.expandedWidth }
            val natural = 2 * dp(GLASS_INSET) + widest + dp(GLASS_SLOT) * (tabs.size - 1) + dp(GLASS_BREATH)
            val capsuleWidth = min(w, natural)
            capsule.set((w - capsuleWidth) / 2f, 0f, (w + capsuleWidth) / 2f, h)
        } else {
            capsule.set(0f, 0f, w, h)
        }
        invalidateOutline()
    }

    private val sidePad: Float
        get() = dp(if (look == NavStyle.LIQUID) LIQUID_PAD else ISLAND_PAD)

    private fun slotWidth() = (capsule.width() - 2 * sidePad) / max(tabs.size, 1)

    private fun slotLeft(i: Int) = capsule.left + sidePad + slotWidth() * i

    private fun slotCentre(i: Int) = slotLeft(i) + slotWidth() / 2f

    // ---- where the selection is heading ----

    private fun retarget(animate: Boolean) {
        if (!laidOut || tabs.isEmpty()) return
        val moving = animate && isAttachedToWindow
        when (look) {
            NavStyle.ISLAND -> {
                val left = slotLeft(selected) + dp(ISLAND_INSET_H)
                val right = slotLeft(selected) + slotWidth() - dp(ISLAND_INSET_H)
                edgeLeft.target = left
                edgeRight.target = right
                if (!moving) {
                    edgeLeft.snap(left)
                    edgeRight.snap(right)
                }
            }
            NavStyle.LIQUID -> {
                dropCentre.target = slotCentre(selected)
                if (!moving) {
                    dropCentre.snap(slotCentre(selected))
                    lift.snap(0f)
                }
            }
            NavStyle.GLASS -> tabs.indices.forEach { i ->
                val on = i == selected
                // Appearing is quick and opening a little slower; folding away is quick and
                // fading slower still, so the old pill is seen to close before it goes.
                fades[i].tune(if (on) FADE_IN else FADE_OUT, 1f)
                expands[i].tune(if (on) GROW else SHRINK, 1f)
                val goal = if (on) 1f else 0f
                fades[i].target = goal
                expands[i].target = goal
                if (!moving) {
                    fades[i].snap(goal)
                    expands[i].snap(goal)
                }
            }
        }
        if (moving) startTicking() else applyFrame()
    }

    // ---- the frame clock ----

    private fun startTicking() {
        if (ticking) return
        ticking = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onFrame(now: Long) {
        val dt = if (lastFrameNanos == 0L) FIRST_STEP
        else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, MAX_STEP)
        lastFrameNanos = now

        var moving = false
        when (look) {
            NavStyle.ISLAND -> {
                moving = edgeLeft.step(dt) or moving
                moving = edgeRight.step(dt) or moving
            }
            NavStyle.LIQUID -> {
                moving = dropCentre.step(dt) or moving
                moving = lift.step(dt) or moving
            }
            NavStyle.GLASS -> for (i in tabs.indices) {
                moving = fades[i].step(dt) or moving
                moving = expands[i].step(dt) or moving
            }
        }
        applyFrame()

        val liquidBusy = look == NavStyle.LIQUID && (pressed || !lift.atRest || !dropCentre.atRest)
        if (moving || liquidBusy) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            ticking = false
            lastFrameNanos = 0L
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        ticking = false
    }

    // ---- drawing a frame ----

    private fun applyFrame() {
        if (!laidOut || tabs.isEmpty()) return
        glass.layout(capsule.left.roundToInt(), 0, capsule.right.roundToInt(), height)
        when (look) {
            NavStyle.ISLAND -> frameIsland()
            NavStyle.GLASS -> frameGlass()
            NavStyle.LIQUID -> frameLiquid()
        }
    }

    private fun frameIsland() {
        val h = height.toFloat()
        val left = edgeLeft.value
        val right = edgeRight.value
        val inset = dp(ISLAND_INSET_V)
        indicator.place(left, inset, right - left, h - 2 * inset)
        indicator.invalidateOutline()
        val slot = slotWidth()
        tabs.forEachIndexed { i, tab ->
            tab.place(slotLeft(i), 0f, slot, h)
            // The island recolours each icon as it passes under it, rather than all at once.
            val cover = coverage(left, right, slotCentre(i), tab.iconSize.toFloat())
            tab.ink(ColorUtils.blendARGB(inkMuted, inkOnSelection, cover))
        }
    }

    private fun frameGlass() {
        val h = height.toFloat()
        val inset = dp(GLASS_INSET)
        val slot = dp(GLASS_SLOT)
        val pillHeight = h - 2 * inset
        var natural = 0f
        tabs.forEachIndexed { i, tab ->
            widths[i] = slot + (tab.expandedWidth - slot) * expands[i].value
            natural += widths[i]
        }
        // Whatever the tabs do not use is shared out evenly, so the icons keep balanced spacing
        // as one pill closes and another opens.
        val share = (capsule.width() - 2 * inset - natural) / tabs.size
        var x = capsule.left + inset
        tabs.forEachIndexed { i, tab ->
            val w = widths[i] + share
            tab.place(x, 0f, w, h)
            tab.pillHeight = pillHeight
            tab.expand = expands[i].value
            tab.pillAlpha = fades[i].value
            tab.arrange()
            tab.ink(ColorUtils.blendARGB(inkMuted, inkOnSelection, fades[i].value.coerceIn(0f, 1f)), inkOnSelection)
            x += w
        }
    }

    private fun frameLiquid() {
        val h = height.toFloat()
        val slot = slotWidth()
        val centre = dropCentre.value

        val travelling = abs(centre - dropCentre.target) > slot * 0.08f
        lift.target = if (pressed || travelling) 1f else 0f
        if (!lift.atRest) startTicking()

        val up = lift.value
        val speed = abs(dropCentre.velocity) / density
        val stretch = min(MAX_STRETCH, speed * STRETCH_PER_DP)
        val baseWidth = slot - 2 * dp(LIQUID_INSET_H)
        val baseHeight = h - 2 * dp(LIQUID_INSET_V)
        val width = baseWidth * max(0.85f, 1f + LIFT_WIDEN * up) * (1f + stretch)
        val height = baseHeight * max(0.85f, 1f + LIFT_HEIGHTEN * up) * (1f - SQUASH * stretch)
        indicator.place(centre - width / 2f, (h - height) / 2f, width, height)
        indicator.translationZ = dp(2f + 6f * up.coerceIn(0f, 1f))
        indicator.invalidateOutline()
        droplet.lift = up

        tabs.forEachIndexed { i, tab ->
            tab.place(slotLeft(i), 0f, slot, h)
            val near = (1f - abs(centre - slotCentre(i)) / slot).coerceIn(0f, 1f)
            tab.iconScale = 1f + (LENS_REST + LENS_LIFTED * up.coerceIn(0f, 1f)) * near
            val eased = near * near * (3f - 2f * near)
            tab.ink(ColorUtils.blendARGB(inkPlain, inkAccent, eased))
        }
    }

    /** How much of an icon of [size] centred on [centre] lies between [left] and [right], 0..1. */
    private fun coverage(left: Float, right: Float, centre: Float, size: Float): Float {
        val half = size / 2f
        val overlap = min(right, centre + half) - max(left, centre - half)
        return (overlap / size).coerceIn(0f, 1f)
    }

    // ---- touch ----

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (tabs.isEmpty() || event.x < capsule.left || event.x > capsule.right) return false
                pressed = true
                pressedTab = tabAt(event.x)
                if (look == NavStyle.LIQUID) {
                    dropCentre.target = clampToTabs(event.x)
                    startTicking()
                } else {
                    tabs.getOrNull(pressedTab)?.let(::pressIn)
                }
            }
            MotionEvent.ACTION_MOVE -> if (look == NavStyle.LIQUID) {
                dropCentre.target = clampToTabs(event.x)
                startTicking()
            } else if (pressedTab >= 0 && tabAt(event.x) != pressedTab) {
                pressOut(tabs[pressedTab])
                pressedTab = -1
            }
            MotionEvent.ACTION_UP -> {
                val under = tabAt(event.x)
                val chosen = if (look == NavStyle.LIQUID || under == pressedTab) under else -1
                endPress()
                if (chosen >= 0) tabs[chosen].performClick()
            }
            MotionEvent.ACTION_CANCEL -> endPress()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun endPress() {
        pressed = false
        if (look != NavStyle.LIQUID) tabs.getOrNull(pressedTab)?.let(::pressOut)
        pressedTab = -1
        // Home to the selected tab. If the finger chose another, selecting it retargets again.
        if (look == NavStyle.LIQUID) retarget(animate = true)
    }

    private fun tabAt(x: Float): Int {
        var nearest = -1
        var best = Float.MAX_VALUE
        tabs.forEachIndexed { i, tab ->
            val distance = abs(x - (tab.left + tab.translationX + tab.width / 2f))
            if (distance < best) {
                best = distance
                nearest = i
            }
        }
        return nearest
    }

    private fun clampToTabs(x: Float): Float = x.coerceIn(slotCentre(0), slotCentre(tabs.size - 1))

    private fun pressIn(tab: View) {
        tab.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).alpha(PRESS_ALPHA).setDuration(90).start()
    }

    private fun pressOut(tab: View) {
        tab.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
    }

    private fun dp(value: Float) = value * density

    private companion object {
        const val ISLAND_HEIGHT = 70f
        const val ISLAND_PAD = 2f
        const val ISLAND_INSET_H = 4f
        const val ISLAND_INSET_V = 6f

        const val GLASS_HEIGHT = 64f
        const val GLASS_INSET = 6f
        const val GLASS_SLOT = 60f
        const val GLASS_BREATH = 8f

        const val LIQUID_HEIGHT = 64f
        const val LIQUID_PAD = 4f
        const val LIQUID_INSET_H = 3f
        const val LIQUID_INSET_V = 5f

        /** The island's glide: most of the way in about a third of a second, settled by half. */
        const val GLIDE = 0.48f

        const val FADE_IN = 0.26f
        const val GROW = 0.46f
        const val SHRINK = 0.34f
        const val FADE_OUT = 0.55f

        const val DROP_RESPONSE = 0.52f
        const val DROP_DAMPING = 0.7f
        const val LIFT_RESPONSE = 0.36f
        const val LIFT_DAMPING = 0.55f
        const val LIFT_WIDEN = 0.2f
        const val LIFT_HEIGHTEN = 0.2f
        const val MAX_STRETCH = 0.28f
        const val STRETCH_PER_DP = 0.00032f
        const val SQUASH = 0.32f
        const val LENS_REST = 0.04f
        const val LENS_LIFTED = 0.14f

        const val PRESS_SCALE = 0.94f
        const val PRESS_ALPHA = 0.82f

        /** A frame's worth of time for the first frame, which has no previous one to measure. */
        const val FIRST_STEP = 1f / 60f

        /** The most one frame may advance the motion, however late it arrives. */
        const val MAX_STEP = 0.028f

        const val PX_REST = 0.3f
        const val PX_SPEED = 6f
        const val UNIT_REST = 0.002f
        const val UNIT_SPEED = 0.02f
    }
}

/**
 * Lays a view out at fractional coordinates: whole pixels for the layout and the remainder as a
 * translation, so motion never steps a pixel at a time as it settles.
 */
internal fun View.place(x: Float, y: Float, w: Float, h: Float) {
    val left = floor(x).toInt()
    val top = floor(y).toInt()
    layout(left, top, left + w.roundToInt(), top + h.roundToInt())
    translationX = x - left
    translationY = y - top
}
