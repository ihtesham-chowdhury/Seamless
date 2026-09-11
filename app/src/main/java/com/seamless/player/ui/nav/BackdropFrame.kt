package com.seamless.player.ui.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * The frame every tab lives in, which can also hand what it draws to the navigation glass.
 *
 * Frosted glass has to see what is behind it, and an Android view cannot: a view draws itself,
 * never its neighbours. So while [capturing], this frame records its children into a RenderNode of
 * its own and draws that node, and the glass draws the same node a second time, blurred. Nothing is
 * painted twice on the CPU — a RenderNode is a recorded list of drawing commands, and drawing it in
 * a second place is a reference to that list — and every child keeps its own display list, so a
 * scroll re-records only what scrolled.
 *
 * The glass learns that something changed through [onDescendantInvalidated], which every
 * invalidation beneath this frame passes through on its way up. Invalidating the glass from a
 * pre-draw listener instead would ask for another frame from inside every frame, forever.
 *
 * Only on Android 12 and later, which is where a view can be blurred. Below that, and whenever the
 * capsule in use is not glass, this is an ordinary FrameLayout.
 */
class BackdropFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** What the glass draws. Holds a display list only while [capturing]. */
    val backdrop = RenderNode("backdrop")

    /** Told whenever what this frame shows may have changed. */
    var onBackdropChanged: (() -> Unit)? = null

    var capturing = false
        set(value) {
            val wanted = value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            if (field == wanted) return
            field = wanted
            if (!wanted) backdrop.discardDisplayList()
            invalidate()
        }

    override fun dispatchDraw(canvas: Canvas) {
        if (!capturing || !canvas.isHardwareAccelerated || width == 0 || height == 0) {
            super.dispatchDraw(canvas)
            return
        }
        backdrop.setPosition(0, 0, width, height)
        val recording = backdrop.beginRecording(width, height)
        try {
            super.dispatchDraw(recording)
        } finally {
            backdrop.endRecording()
        }
        canvas.drawRenderNode(backdrop)
        onBackdropChanged?.invoke()
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        if (capturing) onBackdropChanged?.invoke()
    }
}
