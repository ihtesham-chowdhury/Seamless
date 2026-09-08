package com.seamless.player.ui.player

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * A panel that floats over the video instead of replacing it.
 *
 * `BottomSheetDialog` gets used for the drag, the scrim and the dismissal, all of which are
 * worth having, and then three of its defaults are undone:
 *
 * - **The opaque surface.** The sheet's own container is painted transparent so the layout's
 *   inset, rounded, translucent panel is what you see — video visible all the way round it,
 *   rather than a card wedged against the bottom of the screen.
 * - **The collapsed state.** A sheet that opens half-way and waits to be dragged is right for a
 *   long scrolling list and wrong for a short one; these open fully and stay there.
 * - **The system bars.** This is the one that is not cosmetic. Showing any dialog over an
 *   immersive activity hands focus to a new window, and a focused window that has not asked to
 *   be immersive brings the status and navigation bars back — over a film, mid-sentence. The
 *   fix is the documented one: create the window unfocusable so the bars stay put, show it, hide
 *   the bars on the dialog's own window as well, then restore focus so the panel can be touched.
 */
internal object FloatingSheet {

    fun create(context: Context, content: View): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(content)

        dialog.window?.let { window ->
            // Keep the scrim light: the point of a floating panel is that the film carries on
            // behind it, and a standard dim would put it behind frosted grey.
            window.setDimAmount(0.18f)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }

        // The sheet's own container, which Material paints with colorSurface. Set now rather
        // than posted: setContentView has already attached it, and a posted call would show one
        // frame of opaque card before the panel appeared.
        (content.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        dialog.behavior.skipCollapsed = true

        dialog.setOnShowListener {
            // Expanded here rather than before showing, because the behavior settles its own
            // state as the sheet is laid out and would overwrite an earlier assignment.
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            val window = dialog.window ?: return@setOnShowListener
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        return dialog
    }
}
