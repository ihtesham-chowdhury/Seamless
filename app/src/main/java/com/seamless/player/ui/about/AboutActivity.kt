package com.seamless.player.ui.about

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.seamless.player.R
import com.seamless.player.SeamlessApp
import com.seamless.player.databinding.ActivityAboutBinding
import com.seamless.player.ui.common.ThemeManager

/**
 * Why Seamless exists, in its developer's words.
 *
 * A page rather than a dialog. The story used to sit in an AlertDialog with a list of features
 * under it, which made the most personal thing in the app look like a confirmation prompt, and
 * set the story beside a sales pitch. The features moved to the guide, where they explain how
 * the app behaves; this page says why, and who.
 *
 * Nothing follows the story but its signature and one line at the foot of the page. A footer of
 * version, library and licence was tried and taken out again: it turned the end of a personal
 * note into a spec sheet, and the version is already on the Settings row that leads here.
 *
 * Its own ground rather than a settings surface: near black in dark mode, a soft paper in light.
 * One faint wash of the accent behind the heading is the only decoration, and there is one way
 * out — the close disc top left, the same object the players use.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyAccent(this, (application as SeamlessApp).prefs.accentColor)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }
        paintGlow()
        fitInsets()
        keepClosingLineAtFoot()
        // Only on arrival. Coming back from rotation, the page is simply there.
        if (savedInstanceState == null) reveal()
    }

    /**
     * The status bar's room goes to the close disc and the top of the column, the navigation
     * bar's to the bottom of the column, so nothing sits under either and the ground still runs
     * to every edge.
     */
    private fun fitInsets() {
        val close = binding.btnClose
        val closeParams = close.layoutParams as ViewGroup.MarginLayoutParams
        val closeTop = closeParams.topMargin
        val closeStart = closeParams.marginStart
        val column = binding.column
        val top = column.paddingTop
        val bottom = column.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            close.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = closeTop + bars.top
                marginStart = closeStart + bars.left
            }
            column.updatePadding(top = top + bars.top, bottom = bottom + bars.bottom)
            binding.scroll.updatePadding(left = bars.left, right = bars.right)
            insets
        }
    }

    /**
     * "Built with … in Bangladesh" belongs at the foot of the page, not wherever the story happens
     * to end. The column is given at least the height of the screen and the space above that line
     * takes what is left over, so on a tall phone the line rests on the bottom edge and on a short
     * one it follows the signature after a gap.
     *
     * Posted rather than set inside the callback: a minimum height changed during layout would ask
     * for a second layout pass in the middle of the first. The comparison stops it repeating.
     */
    private fun keepClosingLineAtFoot() {
        binding.scroll.addOnLayoutChangeListener { scroll, _, top, _, bottom, _, _, _, _ ->
            val height = bottom - top
            if (height > 0 && binding.column.minimumHeight != height) {
                scroll.post { binding.column.minimumHeight = height }
            }
        }
    }

    /**
     * The one decoration: a wash of the accent from the top corner, fading long before the story.
     * Painted from code because the accent is a runtime overlay; the strength is a colour
     * resource so dark mode can carry a little more of it than light.
     */
    private fun paintGlow() {
        binding.glow.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = GLOW_RADIUS_DP * resources.displayMetrics.density
            setGradientCenter(GLOW_CENTER_X, GLOW_CENTER_Y)
            colors = intArrayOf(ContextCompat.getColor(this@AboutActivity, R.color.page_glow), Color.TRANSPARENT)
        }
    }

    /**
     * Each block of the column settles into place a moment after the one above it: short, quick
     * and without overshoot, so it reads as a page being turned to rather than a panel launching.
     * Animator duration scale applies, so "remove animations" leaves the page simply there.
     */
    private fun reveal() {
        val rise = REVEAL_RISE_DP * resources.displayMetrics.density
        binding.column.children.forEachIndexed { index, child ->
            child.alpha = 0f
            child.translationY = rise
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(REVEAL_STAGGER_MS * index.coerceAtMost(REVEAL_STEPS))
                .setDuration(REVEAL_MS)
                .setInterpolator(DecelerateInterpolator(REVEAL_EASE))
                .start()
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, AboutActivity::class.java)

        private const val GLOW_RADIUS_DP = 420f
        private const val GLOW_CENTER_X = 0.12f
        private const val GLOW_CENTER_Y = 0.0f

        private const val REVEAL_RISE_DP = 14f
        private const val REVEAL_MS = 340L
        private const val REVEAL_STAGGER_MS = 45L
        /** The closing line arrives with the signature rather than after a pause. */
        private const val REVEAL_STEPS = 4
        private const val REVEAL_EASE = 1.8f
    }
}
