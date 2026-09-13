package com.seamless.player.ui.player

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.seamless.player.R
import com.seamless.player.data.SeekBarStyle

/**
 * Dresses the player's bottom row to match the timeline it sits above.
 *
 * A timeline is not only the bar. Pressing a waveform into a row of glass discs is one thing;
 * pressing a strip of film into the same discs is a costume half worn. So each style names what
 * previous, play and next are made of, and what colour their glyphs take — and the two styles
 * that write the times into the bar itself send the labels underneath away rather than have the
 * same numbers said twice.
 *
 * Applied by PlayerActivity once, after Media3 has inflated the controller.
 */
object ControlSkin {

    fun apply(playerView: View, style: SeekBarStyle) {
        val bar = playerView.findViewById<TimelineBar>(R.id.exo_progress) ?: return
        bar.style = style

        val night = (playerView.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val disc = when (style) {
            // Bare glyphs: both of these are drawn as quiet lines, and a disc under each button
            // would be the loudest thing in the row.
            SeekBarStyle.THREAD, SeekBarStyle.MINIMAL, SeekBarStyle.HIGHLIGHT ->
                R.drawable.transport_plain_bg
            SeekBarStyle.SOFT -> R.drawable.transport_soft_bg
            SeekBarStyle.FILM -> R.drawable.transport_ring_bg
            SeekBarStyle.WAVE -> R.drawable.transport_disc_bg
        }
        val playDisc = when (style) {
            SeekBarStyle.THREAD, SeekBarStyle.MINIMAL, SeekBarStyle.HIGHLIGHT ->
                R.drawable.transport_plain_bg
            SeekBarStyle.SOFT -> R.drawable.transport_soft_bg
            SeekBarStyle.FILM -> R.drawable.transport_ring_bg
            SeekBarStyle.WAVE -> R.drawable.transport_play_disc_bg
        }

        val glyph = when (style) {
            SeekBarStyle.SOFT -> if (night) SOFT_GLYPH_DARK else SOFT_GLYPH_LIGHT
            SeekBarStyle.FILM -> FILM_GLYPH
            SeekBarStyle.MINIMAL -> MINIMAL_GLYPH
            else -> WHITE
        }
        val playGlyph = when (style) {
            SeekBarStyle.SOFT -> if (night) SOFT_PLAY_DARK else SOFT_PLAY_LIGHT
            SeekBarStyle.FILM -> FILM_PLAY
            else -> WHITE
        }

        playerView.findViewById<ImageButton>(R.id.btn_prev)?.dress(disc, glyph)
        playerView.findViewById<ImageButton>(R.id.btn_next)?.dress(disc, glyph)
        playerView.findViewById<ImageButton>(R.id.exo_play_pause)?.dress(playDisc, playGlyph)

        val ownTimes = bar.drawsOwnTimes
        playerView.findViewById<TextView>(R.id.exo_position)?.visibility =
            if (ownTimes) View.GONE else View.VISIBLE
        playerView.findViewById<TextView>(R.id.time_remaining)?.visibility =
            if (ownTimes) View.GONE else View.VISIBLE
    }

    private fun ImageButton.dress(background: Int, glyph: Int) {
        setBackgroundResource(background)
        imageTintList = ColorStateList.valueOf(glyph)
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val SOFT_GLYPH_LIGHT = 0xFF3C4150.toInt()
    private const val SOFT_GLYPH_DARK = 0xFFE6E8EF.toInt()
    private const val SOFT_PLAY_LIGHT = 0xFF2F80ED.toInt()
    private const val SOFT_PLAY_DARK = 0xFF6BA8F0.toInt()
    private const val FILM_GLYPH = 0xFFD9C3A3.toInt()
    private const val FILM_PLAY = 0xFFF0C489.toInt()
    private const val MINIMAL_GLYPH = 0xF2FFFFFF.toInt()
}
