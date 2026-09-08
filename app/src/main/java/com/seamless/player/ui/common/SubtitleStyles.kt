package com.seamless.player.ui.common

import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.seamless.player.data.Prefs
import com.seamless.player.data.SubtitleEdge

/**
 * What subtitles look like.
 *
 * The defaults are the feature. Almost nobody opens a subtitle appearance panel, so the text
 * that appears the first time has to be right: white, at a size proportional to the picture,
 * separated from the frame by a thin outline rather than by an opaque black bar, and sitting
 * high enough to clear the transport controls.
 *
 * The black bar is what every other player does and it is why subtitled films look like
 * training videos. An outline costs nothing, stays readable over a white sky or a snowfield,
 * and leaves the picture visible where the text is not.
 *
 * Two things are deliberately overridden rather than respected:
 *
 * - **The system's own caption settings.** Android has a global caption style, and honouring it
 *   sounds respectful until you meet its defaults, which are the black bar. It is also invisible
 *   from inside the app — a user cannot tell why this player looks different from the last one.
 *   Media3 would apply it via `SubtitleView.setUserDefaultStyle`; this does not call that.
 * - **The subtitle file's own styling.** An ASS file can specify its own fonts, colours and
 *   sizes, most of which were chosen for a 2009 desktop player. Embedded styles are off so that
 *   what is configured here is what appears, consistently, across every file.
 */
object SubtitleStyles {

    /**
     * Applies everything from [prefs] to [view].
     *
     * [bottomPaddingOverride] lifts the text temporarily — the player raises it while the
     * controls are on screen, so a caption is never behind the timeline.
     */
    fun apply(view: SubtitleView, prefs: Prefs, bottomPaddingOverride: Float? = null) {
        view.setApplyEmbeddedStyles(false)
        view.setApplyEmbeddedFontSizes(false)
        view.setStyle(styleOf(prefs))
        view.setFractionalTextSize(
            SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * prefs.subtitleTextScale,
        )
        view.setBottomPaddingFraction(bottomPaddingOverride ?: prefs.subtitleBottomPadding)
    }

    private fun styleOf(prefs: Prefs): CaptionStyleCompat {
        val background = Color.argb(
            (prefs.subtitleBackgroundOpacity * 255 / 100).coerceIn(0, 255),
            0, 0, 0,
        )
        val edgeType = when (prefs.subtitleEdge) {
            SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
            SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            SubtitleEdge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        }
        return CaptionStyleCompat(
            /* foregroundColor = */ Color.WHITE,
            /* backgroundColor = */ background,
            // The window is the band behind a whole cue rather than behind the glyphs. Always
            // transparent: it is the single ugliest thing a subtitle renderer can draw.
            /* windowColor = */ Color.TRANSPARENT,
            /* edgeType = */ edgeType,
            /* edgeColor = */ Color.BLACK,
            /* typeface = */ if (prefs.subtitleBold) {
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            } else {
                Typeface.SANS_SERIF
            },
        )
    }
}
