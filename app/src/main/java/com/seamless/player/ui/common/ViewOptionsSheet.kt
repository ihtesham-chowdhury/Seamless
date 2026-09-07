package com.seamless.player.ui.common

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.seamless.player.R
import com.seamless.player.data.Prefs
import com.seamless.player.data.SortKey
import com.seamless.player.databinding.SheetViewOptionsBinding

/**
 * One presentation a screen offers.
 *
 * [id] is whatever the caller stores — an enum constant's name, in practice. The sheet never
 * interprets it, which is what lets the library offer two layouts and the shorts tab three
 * without either of them knowing about the other's enum.
 */
data class ViewChoice(
    val id: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
)

/**
 * Layout and order, in one sheet, behind one button.
 *
 * These were two toolbar buttons: one that toggled grid and list, and one that opened a
 * radio dialog for the sort key with a neutral button that flipped the direction without
 * ever saying which way it had flipped to. Both are questions about how this list is
 * presented, and neither is asked often enough to deserve its own permanent icon.
 *
 * Everything applies as it is tapped, with the sheet left open. Sorting is something you
 * arrive at by trying: name, no — largest first, yes. A sheet that closed on the first tap
 * would make each attempt cost two more.
 *
 * The order is remembered per [scope], not globally. See [Prefs.sortFor].
 */
class ViewOptionsSheet(
    private val prefs: Prefs,
    private val scope: String,
    private val keys: List<SortKey>,
    private val views: List<ViewChoice>,
    private val getView: () -> String,
    private val setView: (String) -> Unit,
    /** The layout changed: swap the layout manager, then re-submit. */
    private val onViewChanged: () -> Unit,
    /** The order changed: re-sort what is already loaded. */
    private val onSortChanged: () -> Unit,
) {

    fun show(context: Context) {
        val binding = SheetViewOptionsBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(binding.root)

        bindViewMode(context, binding.viewGroup)
        val order = binding.orderGroup
        val again = binding.shuffleAgain
        bindSortKeys(context, binding.sortGroup, order, again)
        bindOrder(order)

        again.setOnClickListener {
            // Writing the key again is what draws a new seed; see Prefs.setSortKey.
            prefs.setSortKey(scope, SortKey.RANDOM)
            onSortChanged()
        }

        dialog.show()
    }

    /**
     * One button per layout the caller offers.
     *
     * Built here rather than declared in the sheet's XML because the number of layouts
     * differs by screen — two in the library, three on the shorts tab — and a fixed pair of
     * buttons would have meant a second sheet layout that differed only in how many.
     */
    private fun bindViewMode(context: Context, group: MaterialButtonToggleGroup) {
        val inflater = LayoutInflater.from(context)
        val byId = HashMap<Int, String>(views.size)
        val current = getView()

        views.forEach { choice ->
            val button = inflater.inflate(R.layout.item_view_choice, group, false)
                as MaterialButton
            button.id = View.generateViewId()
            button.setText(choice.label)
            button.setIconResource(choice.icon)
            byId[button.id] = choice.id
            group.addView(button)
            if (choice.id == current) group.check(button.id)
        }

        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val wanted = byId[checkedId] ?: return@addOnButtonCheckedListener
            if (wanted == getView()) return@addOnButtonCheckedListener
            setView(wanted)
            onViewChanged()
        }
    }

    /**
     * One radio per applicable key, plus Random.
     *
     * Ids are generated rather than declared: a RadioGroup identifies its selection by view
     * id, and the set of keys is decided by the caller.
     */
    private fun bindSortKeys(
        context: Context,
        group: RadioGroup,
        order: MaterialButtonToggleGroup,
        shuffleAgain: View,
    ) {
        val current = prefs.sortFor(scope).key
        val byId = HashMap<Int, SortKey>(keys.size)
        val inflater = LayoutInflater.from(context)

        keys.forEach { key ->
            val button = inflater.inflate(R.layout.item_sort_choice, group, false) as RadioButton
            button.id = View.generateViewId()
            button.setText(SortLabels.label(key))
            byId[button.id] = key
            group.addView(button)
            if (key == current) group.check(button.id)
        }

        applyOrderControls(current, order, shuffleAgain)

        group.setOnCheckedChangeListener { _, checkedId ->
            val key = byId[checkedId] ?: return@setOnCheckedChangeListener
            if (key == prefs.sortFor(scope).key) return@setOnCheckedChangeListener
            prefs.setSortKey(scope, key)
            applyOrderControls(key, order, shuffleAgain)
            onSortChanged()
        }
    }

    /**
     * Random has no direction, so the control that would claim otherwise gives way to the
     * one that makes sense: deal again.
     */
    private fun applyOrderControls(
        key: SortKey,
        order: MaterialButtonToggleGroup,
        shuffleAgain: View,
    ) {
        val random = key == SortKey.RANDOM
        order.visibility = if (random) View.GONE else View.VISIBLE
        shuffleAgain.visibility = if (random) View.VISIBLE else View.GONE
    }

    private fun bindOrder(group: MaterialButtonToggleGroup) {
        group.check(
            if (prefs.sortFor(scope).ascending) R.id.order_ascending else R.id.order_descending
        )
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val ascending = checkedId == R.id.order_ascending
            if (ascending == prefs.sortFor(scope).ascending) return@addOnButtonCheckedListener
            prefs.setSortAscending(scope, ascending)
            onSortChanged()
        }
    }
}
