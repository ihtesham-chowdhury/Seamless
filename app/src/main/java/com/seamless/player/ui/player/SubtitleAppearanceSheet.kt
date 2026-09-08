package com.seamless.player.ui.player

import android.content.Context
import android.view.LayoutInflater
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.seamless.player.R
import com.seamless.player.data.Prefs
import com.seamless.player.data.SubtitleEdge
import com.seamless.player.databinding.SheetSubtitleAppearanceBinding

/**
 * Size, weight, edge, background and position — applied as they move.
 *
 * Live application is the whole design. A caption style is not something anyone can picture from
 * a label; you know whether it is right by looking at it over the actual film. So this panel
 * writes each change straight through and asks the player to restyle, and the player lifts the
 * subtitle to the middle of the picture while the panel is open so there is something to look at
 * above it. Nothing is confirmed and there is no OK button, because there is nothing to confirm.
 *
 * [onChanged] is called after every move rather than on dismissal, and is expected to be cheap:
 * it sets a style object on a view.
 */
class SubtitleAppearanceSheet(
    private val prefs: Prefs,
    private val onChanged: () -> Unit,
) {

    /**
     * [onDismiss] is how the caller puts the subtitle back where it belongs. The player raises
     * the text while this panel is open so the effect is visible above it, and something has to
     * lower it again; a timer would either fight the closing animation or lag behind it.
     */
    fun show(context: Context, onDismiss: () -> Unit = {}) {
        val binding = SheetSubtitleAppearanceBinding.inflate(LayoutInflater.from(context))
        val dialog = FloatingSheet.create(context, binding.root)
        dialog.setOnDismissListener { onDismiss() }

        // The two fractional settings ride on percentage sliders; see the layout for why.
        bindSlider(binding.size, prefs.subtitleTextScale * 100f) {
            prefs.subtitleTextScale = it / 100f
        }
        bindSlider(binding.background, prefs.subtitleBackgroundOpacity.toFloat()) {
            prefs.subtitleBackgroundOpacity = it.toInt()
        }
        bindSlider(binding.position, prefs.subtitleBottomPadding * 100f) {
            prefs.subtitleBottomPadding = it / 100f
        }

        bindWeight(binding.weight)
        bindEdge(binding.edge)

        binding.reset.setOnClickListener {
            // Back to the defaults, which are the considered answer rather than a neutral one.
            // Writing them through Prefs keeps the clamping in one place.
            prefs.subtitleTextScale = 1f
            prefs.subtitleBold = false
            prefs.subtitleBackgroundOpacity = 0
            prefs.subtitleEdge = SubtitleEdge.OUTLINE
            prefs.subtitleBottomPadding = DEFAULT_POSITION
            binding.size.value = 100f
            binding.background.value = 0f
            binding.position.value = DEFAULT_POSITION * 100f
            binding.weight.check(R.id.weight_normal)
            binding.edge.check(R.id.edge_outline)
            onChanged()
        }

        dialog.show()
    }

    /**
     * Sliders are set up value-first, then listened to.
     *
     * The other way round fires the listener with the value we just wrote, which is harmless
     * here and would not be if anything downstream counted changes.
     *
     * The value is snapped to a tick as well as clamped, and both matter: a Material Slider
     * throws outright when given a value outside its range *or* between two of its steps. A
     * setting written by an earlier version, or under a range that has since narrowed, would
     * otherwise take the panel down on open.
     */
    private fun bindSlider(slider: Slider, value: Float, write: (Float) -> Unit) {
        val step = slider.stepSize
        val snapped = if (step > 0f) {
            slider.valueFrom + Math.round((value - slider.valueFrom) / step) * step
        } else {
            value
        }
        slider.value = snapped.coerceIn(slider.valueFrom, slider.valueTo)
        slider.addOnChangeListener { _, changed, fromUser ->
            if (!fromUser) return@addOnChangeListener
            write(changed)
            onChanged()
        }
    }

    private fun bindWeight(group: MaterialButtonToggleGroup) {
        group.check(if (prefs.subtitleBold) R.id.weight_bold else R.id.weight_normal)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            // The group reports the outgoing button as unchecked before the incoming one as
            // checked, so acting on both would do the work twice with the wrong value first.
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.subtitleBold = checkedId == R.id.weight_bold
            onChanged()
        }
    }

    private fun bindEdge(group: MaterialButtonToggleGroup) {
        group.check(
            when (prefs.subtitleEdge) {
                SubtitleEdge.OUTLINE -> R.id.edge_outline
                SubtitleEdge.SHADOW -> R.id.edge_shadow
                SubtitleEdge.NONE -> R.id.edge_none
            }
        )
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.subtitleEdge = when (checkedId) {
                R.id.edge_shadow -> SubtitleEdge.SHADOW
                R.id.edge_none -> SubtitleEdge.NONE
                else -> SubtitleEdge.OUTLINE
            }
            onChanged()
        }
    }

    private companion object {
        /** Kept in step with Prefs.subtitleBottomPadding's own default. */
        const val DEFAULT_POSITION = 0.08f
    }
}
