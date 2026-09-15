package com.seamless.player.ui.help

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.seamless.player.R
import com.seamless.player.SeamlessApp
import com.seamless.player.databinding.ActivityHelpBinding
import com.seamless.player.databinding.ItemHelpPointBinding
import com.seamless.player.databinding.ViewHelpSectionBinding
import com.seamless.player.ui.common.ThemeManager
import com.seamless.player.util.applyBottomAndSideInsets
import com.seamless.player.util.applyTopSystemInset

/**
 * The full guide, reached from Settings: how Seamless behaves, and in passing why it feels the
 * way it does.
 *
 * Still words rather than gesture diagrams. The gestures are easy to describe and hard to draw —
 * "drag down from the top" is one clear sentence and three ambiguous arrows. What changed is the
 * shape of the words: each part used to be one paragraph with blank lines in it, which read as a
 * wall. Now each part says in a line what it is for, then gives one row per gesture or control —
 * what you do, then what happens — so the page can be scanned for the one thing someone forgot.
 *
 * The rows that name an on-screen button carry that button's own glyph, and only those: the
 * picture says "look for this", which a drawing of a finger on a gesture row would not.
 *
 * The contextual hints in [com.seamless.player.ui.common.Tips] are the first line; this is for the
 * rest, and for putting a forgotten gesture back. About says why the app exists; this says how.
 */
class HelpActivity : AppCompatActivity() {

    /** One row of the guide: what you do, what it does, and the control's glyph if it has one. */
    private class Point(
        @StringRes val lead: Int,
        @StringRes val body: Int,
        @DrawableRes val glyph: Int = 0,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyAccent(this, (application as SeamlessApp).prefs.accentColor)
        super.onCreate(savedInstanceState)
        val binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.applyTopSystemInset()
        binding.scroll.applyBottomAndSideInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }

        fill(binding.sectionPlayer, R.string.help_player_label, R.string.help_player_title,
            R.string.help_player_intro, PLAYER)
        fill(binding.sectionShorts, R.string.tab_shorts, R.string.help_shorts_title,
            R.string.help_shorts_intro, SHORTS)
        fill(binding.sectionLibrary, R.string.tab_library, R.string.help_library_title,
            R.string.help_library_intro, LIBRARY)
    }

    private fun fill(
        section: ViewHelpSectionBinding,
        @StringRes label: Int,
        @StringRes title: Int,
        @StringRes intro: Int,
        points: List<Point>,
    ) {
        section.sectionLabel.setText(label)
        section.sectionTitle.setText(title)
        section.sectionIntro.setText(intro)
        points.forEachIndexed { index, point ->
            if (index > 0) layoutInflater.inflate(R.layout.view_help_divider, section.sectionPoints, true)
            val row = ItemHelpPointBinding.inflate(layoutInflater, section.sectionPoints, true)
            row.pointLead.setText(point.lead)
            row.pointBody.setText(point.body)
            if (point.glyph != 0) {
                row.pointGlyph.setImageResource(point.glyph)
                row.pointGlyph.visibility = View.VISIBLE
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, HelpActivity::class.java)

        private val PLAYER = listOf(
            Point(R.string.help_player_tap_lead, R.string.help_player_tap_body),
            Point(R.string.help_player_levels_lead, R.string.help_player_levels_body),
            Point(R.string.help_player_scrub_lead, R.string.help_player_scrub_body),
            Point(R.string.help_player_close_lead, R.string.help_player_close_body),
            Point(R.string.help_player_jump_lead, R.string.help_player_jump_body),
            Point(R.string.help_player_hold_lead, R.string.help_player_hold_body),
            Point(R.string.help_player_cc_lead, R.string.help_player_cc_body, R.drawable.ic_closed_caption),
            Point(R.string.help_player_lock_lead, R.string.help_player_lock_body, R.drawable.ic_lock),
            Point(R.string.help_player_flow_lead, R.string.help_player_flow_body),
        )

        private val SHORTS = listOf(
            Point(R.string.help_shorts_wall_lead, R.string.help_shorts_wall_body),
            Point(R.string.help_shorts_open_lead, R.string.help_shorts_open_body),
            Point(R.string.help_shorts_swipe_lead, R.string.help_shorts_swipe_body),
            Point(R.string.help_shorts_levels_lead, R.string.help_shorts_levels_body),
            Point(R.string.help_shorts_tap_lead, R.string.help_shorts_tap_body),
            Point(R.string.help_shorts_double_lead, R.string.help_shorts_double_body, R.drawable.ic_heart_outline),
            Point(R.string.help_shorts_hold_lead, R.string.help_shorts_hold_body),
            Point(R.string.help_shorts_end_lead, R.string.help_shorts_end_body),
        )

        private val LIBRARY = listOf(
            Point(R.string.help_library_select_lead, R.string.help_library_select_body),
            Point(R.string.help_library_pin_lead, R.string.help_library_pin_body),
            Point(R.string.help_library_search_lead, R.string.help_library_search_body, R.drawable.ic_search),
            Point(R.string.help_library_order_lead, R.string.help_library_order_body, R.drawable.ic_view_options),
            Point(R.string.help_library_lock_lead, R.string.help_library_lock_body),
            Point(R.string.help_library_hide_lead, R.string.help_library_hide_body),
        )
    }
}
