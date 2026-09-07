package com.seamless.player.ui.help

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.seamless.player.R
import com.seamless.player.SeamlessApp
import com.seamless.player.databinding.ActivityHelpBinding
import com.seamless.player.databinding.ViewHelpSectionBinding
import com.seamless.player.ui.common.ThemeManager
import com.seamless.player.util.applyBottomAndSideInsets
import com.seamless.player.util.applyTopSystemInset

/**
 * The full guide, reached from Settings.
 *
 * Deliberately plain prose rather than a grid of gesture diagrams. The gestures are easy to
 * describe and hard to draw — "drag down from the top edge" is one clear sentence and three
 * ambiguous arrows — and anyone who has opened this screen has already decided to read.
 *
 * The contextual hints in [com.seamless.player.ui.common.Tips] are the first line; this is
 * for the rest, and for putting a forgotten gesture back.
 */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyAccent(this, (application as SeamlessApp).prefs.accentColor)
        super.onCreate(savedInstanceState)
        val binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.applyTopSystemInset()
        binding.scroll.applyBottomAndSideInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }

        fill(binding.sectionPlayer, R.string.help_player_title, R.string.help_player_body)
        fill(binding.sectionShorts, R.string.help_shorts_title, R.string.help_shorts_body)
        fill(binding.sectionLibrary, R.string.help_library_title, R.string.help_library_body)
    }

    private fun fill(section: ViewHelpSectionBinding, title: Int, body: Int) {
        section.sectionTitle.setText(title)
        section.sectionBody.setText(body)
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, HelpActivity::class.java)
    }
}
