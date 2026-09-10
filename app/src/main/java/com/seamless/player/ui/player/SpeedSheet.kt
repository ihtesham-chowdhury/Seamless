package com.seamless.player.ui.player

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
 * The presets are laid out in two rows, slowest first. They scrolled sideways in one row at
 * first, which kept every preset but hid most of them: a row you have to scroll along puts the
 * speed you came for off its end, and scrolling to find a number is not something a speed picker
 * should ask for. Two rows show all twelve at once and still fit a phone held upright.
 *
 * In landscape the card sits in the middle of the screen rather than against the side its button
 * is on; see [FloatingSheet.Placement].
 */
class SpeedSheet(
    private val presets: List<Float>,
    initial: Float,
    private val onChanged: (Float) -> Unit,
) {

    private var current = snap(initial)

    fun show(context: Context, onDismiss: () -> Unit = {}) {
        val binding = SheetSpeedBinding.inflate(LayoutInflater.from(context))
        val dialog = FloatingSheet.create(context, binding.root, FloatingSheet.Placement.CENTRE)
        dialog.setOnDismissListener { onDismiss() }
        binding.close.setOnClickListener { dialog.dismiss() }

        val inflater = LayoutInflater.from(context)
        val rowGap = (ROW_GAP_DP * context.resources.displayMetrics.density).roundToInt()
        val perRow = ((presets.size + ROWS - 1) / ROWS).coerceAtLeast(1)

        val pills = presets.chunked(perRow).flatMapIndexed { rowIndex, speeds ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            binding.presets.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { if (rowIndex > 0) topMargin = rowGap },
            )
            // A row keeps a caption line only if one of its pills has a caption, so the pills in
            // it stay level with each other and a row with no "Normal" under it does not carry an
            // empty line for nothing.
            val captioned = speeds.any { it == 1f }
            speeds.map { speed ->
                val pill = ItemSpeedPillBinding.inflate(inflater, row, false)
                pill.value.text = format(speed)
                if (speed == 1f) pill.caption.setText(R.string.speed_reset)
                pill.caption.visibility = when {
                    speed == 1f -> View.VISIBLE
                    captioned -> View.INVISIBLE
                    else -> View.GONE
                }
                row.addView(
                    pill.root,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                speed to pill
            }
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

        /** Twelve presets, six to a row. */
        private const val ROWS = 2
        private const val ROW_GAP_DP = 8f

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
