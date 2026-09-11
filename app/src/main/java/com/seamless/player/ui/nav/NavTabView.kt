package com.seamless.player.ui.nav

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.seamless.player.R
import kotlin.math.roundToInt

/**
 * One tab: an icon and its name, and — for the frosted style — the pill behind them.
 *
 * Declared in XML with the icon and the label as its two children. [FloatingNavBar] decides where
 * the tab sits and how it looks from frame to frame; this view only arranges its own contents
 * inside whatever bounds it is given, in one of two ways:
 *
 * - stacked, icon over name, for the island and liquid styles;
 * - side by side, for the frosted style, where [expand] runs from 0 (the icon alone, the pill a
 *   circle round it) to 1 (the pill open and the name showing).
 *
 * Positions are floats applied as a whole-pixel layout plus a fractional translation, so nothing
 * steps a pixel at a time as it settles.
 */
class NavTabView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val density = resources.displayMetrics.density

    /** The frosted style's accent pill. Its own view so that it can cast its tinted glow. */
    internal val pill = View(context)

    internal lateinit var icon: ImageView
        private set
    internal lateinit var label: TextView
        private set

    internal var horizontal = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    internal var expand = 1f
    internal var pillAlpha = 0f
    internal var pillHeight = 0f
    internal var verticalGap = 3f * density

    internal var iconSize = (22f * density).roundToInt()
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    internal var iconScale = 1f
        set(value) {
            if (field == value) return
            field = value
            icon.scaleX = value
            icon.scaleY = value
        }

    private var inkKnown = false
    private var iconInk = 0
    private var labelInk = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        icon = getChildAt(0) as ImageView
        label = getChildAt(1) as TextView
        pill.background = ContextCompat.getDrawable(context, R.drawable.nav_pill_bg)
        pill.elevation = PILL_ELEVATION * density
        pill.visibility = View.GONE
        addView(pill, 0, LayoutParams(0, 0))
        // Children are drawn in order of elevation before order of declaration, so the icon and
        // the name have to sit at least as high as the pill or it would be drawn over them.
        icon.translationZ = CONTENT_Z * density
        label.translationZ = CONTENT_Z * density
        clipChildren = false
        clipToPadding = false
    }

    /** The pill's width with the whole name showing. */
    internal val expandedWidth: Float
        get() = 2 * PILL_PAD * density + iconSize + LABEL_GAP * density + label.measuredWidth

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val iconSpec = MeasureSpec.makeMeasureSpec(iconSize, MeasureSpec.EXACTLY)
        icon.measure(iconSpec, iconSpec)
        val loose = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        label.measure(loose, loose)
        setMeasuredDimension(
            resolveSize(icon.measuredWidth, widthMeasureSpec),
            resolveSize(icon.measuredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = arrange()

    /** Places the icon, the name and the pill for the current bounds and [expand]. */
    internal fun arrange() {
        val w = width.toFloat()
        val h = height.toFloat()
        val iw = icon.measuredWidth.toFloat()
        val ih = icon.measuredHeight.toFloat()
        val lw = label.measuredWidth.toFloat()
        val lh = label.measuredHeight.toFloat()

        if (horizontal) {
            val open = expand.coerceIn(0f, 1f)
            val gap = LABEL_GAP * density * open
            val group = iw + gap + lw * open
            val x = (w - group) / 2f
            icon.place(x, (h - ih) / 2f, iw, ih)
            label.place(x + iw + gap, (h - lh) / 2f, lw, lh)
            label.alpha = open * open
            val pillWidth = pillHeight + (expandedWidth - pillHeight) * expand
            pill.place((w - pillWidth) / 2f, (h - pillHeight) / 2f, pillWidth, pillHeight)
            pill.alpha = pillAlpha.coerceIn(0f, 1f)
        } else {
            val group = ih + verticalGap + lh
            val y = (h - group) / 2f
            icon.place((w - iw) / 2f, y, iw, ih)
            label.place((w - lw) / 2f, y + ih + verticalGap, lw, lh)
            label.alpha = 1f
        }
    }

    /** Colours the icon and the name. Skips the work when neither has changed. */
    internal fun ink(iconColour: Int, labelColour: Int = iconColour) {
        if (!inkKnown || iconColour != iconInk) {
            iconInk = iconColour
            icon.imageTintList = ColorStateList.valueOf(iconColour)
        }
        if (!inkKnown || labelColour != labelInk) {
            labelInk = labelColour
            label.setTextColor(labelColour)
        }
        inkKnown = true
    }

    /** Forgets the last colours, so the next [ink] applies them even if they match. */
    internal fun resetInk() {
        inkKnown = false
    }

    private companion object {
        const val PILL_PAD = 16f
        const val LABEL_GAP = 8f
        const val PILL_ELEVATION = 3f
        const val CONTENT_Z = 4f
    }
}
