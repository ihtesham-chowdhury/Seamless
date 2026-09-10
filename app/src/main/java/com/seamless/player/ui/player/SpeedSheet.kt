package com.seamless.player.ui.player

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import com.seamless.player.R
import com.seamless.player.databinding.ItemSpeedPillBinding
import com.seamless.player.databinding.SheetSpeedBinding
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Playback speed, in the same floating card as the subtitle panel.
 *
 * It was an AlertDialog with twelve numbers in a list, which is the one shape nothing else in
 * this player is. It is now a card with three ways to arrive at a speed, arranged by how
 * precisely someone knows what they want: presets for the speeds people use, a slider for "a
 * bit faster", and a step either side of the slider for "exactly 1.1".
 *
 * Everything applies as it moves, like the appearance panel and for the same reason — a speed is
 * judged by listening to it, not by reading it — and nothing is confirmed. [onChanged] is called
 * on every change and is expected to be cheap.
 *
 * The presets scroll sideways rather than being trimmed to what fits. Each one is in the list
 * because it is a speed someone actually uses, and a picker that quietly drops 0.85 on a narrow
 * phone is making a decision it has no business making.
 */
class SpeedSheet(
    private val presets: List<Float>,
    initial: Float,
    private val onChanged: (Float) -> Unit,
) {

    private var current = snap(initial)

    fun show(context: Context, onDismiss: () -> Unit = {}) {
        val binding = SheetSpeedBinding.inflate(LayoutInflater.from(context))
        val dialog = FloatingSheet.create(context, binding.root)
        dialog.setOnDismissListener { onDismiss() }
        binding.close.setOnClickListener { dialog.dismiss() }

        val inflater = LayoutInflater.from(context)
        val pills = presets.map { speed ->
            val pill = ItemSpeedPillBinding.inflate(inflater, binding.presets, false)
            pill.value.text = format(speed)
            if (speed == 1f) {
                pill.caption.setText(R.string.speed_reset)
                pill.caption.visibility = View.VISIBLE
            }
            binding.presets.addView(pill.root)
            speed to pill
        }

        fun render(fromSlider: Boolean) {
            binding.readout.text = readout(current)
            // Not written back while the finger is on it: setting a slider's value from inside its
            // own change listener fights the drag and makes the thumb stutter.
            if (!fromSlider) binding.slider.value = hundredths(current).toFloat()
            pills.forEach { (speed, pill) -> pill.value.isSelected = abs(speed - current) < EPSILON }
            binding.slower.isEnabled = current > MIN + EPSILON
            binding.faster.isEnabled = current < MAX - EPSILON
            binding.slower.alpha = if (binding.slower.isEnabled) 1f else DISABLED_ALPHA
            binding.faster.alpha = if (binding.faster.isEnabled) 1f else DISABLED_ALPHA
        }

        fun choose(speed: Float, fromSlider: Boolean = false) {
            val next = snap(speed)
            val changed = abs(next - current) >= EPSILON
            current = next
            render(fromSlider)
            if (changed) onChanged(current)
        }

        // Value first, listeners second: the other way round fires the listener with the value
        // just written, which would report a change nobody made.
        render(fromSlider = false)
        binding.slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) choose(value / 100f, fromSlider = true)
        }
        binding.slower.setOnClickListener { choose(current - STEP) }
        binding.faster.setOnClickListener { choose(current + STEP) }
        pills.forEach { (speed, pill) -> pill.value.setOnClickListener { choose(speed) } }

        // Once there are widths to work with, bring the selected preset into view rather than
        // leaving 2.5x off the end of a row the user has to go looking along.
        binding.presetsScroll.post {
            val selected = pills.firstOrNull { (speed, _) -> abs(speed - current) < EPSILON }
                ?.second ?: return@post
            val offset = selected.root.left - (binding.presetsScroll.width - selected.root.width) / 2
            binding.presetsScroll.scrollTo(offset.coerceAtLeast(0), 0)
        }

        dialog.show()
    }

    companion object {
        /** "1", "1.5", "0.85": the number people say, without the zeros they do not. */
        fun format(speed: Float): String =
            String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')

        private const val MIN = 0.25f
        private const val MAX = 3f

        /** Fine enough for "a little faster", coarse enough that the slider has a feel to it. */
        private const val STEP = 0.05f

        private const val EPSILON = 0.001f
        private const val DISABLED_ALPHA = 0.35f

        /**
         * Rounded to the nearest step and held in range.
         *
         * Through twentieths rather than by multiplying the step, because 23 * 0.05f is not quite
         * 1.15f in floating point, and a speed stored as 1.1500001 is a speed no preset will ever
         * recognise as its own.
         */
        private fun snap(speed: Float): Float =
            ((speed * 20f).roundToInt() / 20f).coerceIn(MIN, MAX)

        private fun hundredths(speed: Float): Int = (speed * 100f).roundToInt()

        /** The number at full size and the "×" at a little over half, the way it is written. */
        private fun readout(speed: Float): CharSequence {
            val text = SpannableString(format(speed) + "×")
            text.setSpan(
                RelativeSizeSpan(0.55f),
                text.length - 1,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            return text
        }
    }
}
