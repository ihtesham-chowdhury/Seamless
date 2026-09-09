package com.seamless.player.ui.common

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A scrolling list that refuses to grow past a share of the screen.
 *
 * The floating panels in the player are meant to sit *over* the video with the film still
 * visible around them. A plain `ScrollView` measured at `wrap_content` will happily take the
 * whole height when a file turns out to have nine subtitle tracks, and the panel stops being a
 * panel — it becomes a settings screen with a video hidden behind it.
 *
 * Only an at-most measurement is capped. A parent that has already decided the height — a
 * weighted `LinearLayout` working out what to shrink, which is exactly what happens when the
 * rest of the panel needs room — is obeyed, because by then the decision has been made
 * somewhere with more information than this view has.
 */
class CappedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        if (mode == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val available = MeasureSpec.getSize(heightMeasureSpec)
        val cap = (resources.displayMetrics.heightPixels * MAX_SHARE_OF_SCREEN).toInt()
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(minOf(available, cap), MeasureSpec.AT_MOST),
        )
    }

    private companion object {
        /**
         * Enough for six or seven rows on a phone held upright, which is more tracks than
         * almost any file has, and still leaves half the picture showing.
         */
        const val MAX_SHARE_OF_SCREEN = 0.46f
    }
}
