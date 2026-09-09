package com.seamless.player.ui.common

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import com.seamless.player.R

/**
 * The body of a floating panel, held to a share of the screen.
 *
 * The cap belongs here and not on the list inside, and getting that wrong once is why this
 * class exists. Capping the scrolling part at a percentage says nothing about how tall the
 * *panel* ends up, because the header, two hairlines and three action rows are added on top of
 * it — so a list allowed 88% of a landscape screen produced a card that ran off the top of it
 * and covered the controls. Capping the panel and letting the weighted list inside give up
 * whatever is left over is the same rule stated where it can be enforced.
 *
 * That second half is `LinearLayout`'s own doing: a child with `wrap_content` and a weight is
 * measured naturally and then shrunk when the column overflows its bound. All this does is set
 * a smaller bound than the screen.
 *
 * The share differs by orientation. Upright there is height to spare and the panel should not
 * take it; on a phone lying down the whole screen is about as tall as a portrait panel, and a
 * strict cap there means controls that exist in one orientation and not the other — which is
 * exactly the complaint that produced this. See values/integers.xml.
 */
class CappedColumn @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        if (mode == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val available = MeasureSpec.getSize(heightMeasureSpec)
        val share = resources.getInteger(R.integer.sheet_max_height_percent) / 100f
        val cap = (resources.displayMetrics.heightPixels * share).toInt()
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(minOf(available, cap), MeasureSpec.AT_MOST),
        )
    }
}
