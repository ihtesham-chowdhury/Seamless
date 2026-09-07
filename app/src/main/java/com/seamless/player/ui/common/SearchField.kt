package com.seamless.player.ui.common

import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import com.seamless.player.databinding.ViewSearchFieldBinding

/**
 * Drives the search row that lives under a toolbar, shared by both library screens.
 *
 * Search used to be a bar standing permanently below the toolbar. It cost a row of every
 * screen for something used occasionally, and it read as part of the header rather than as
 * an action. Now the toolbar has a button and this appears when asked.
 *
 * Deliberately not an ActionView inside the toolbar: a collapsing action view takes the
 * title's place, and both screens use the title to say where you are — which is exactly
 * the context you want while typing a query.
 */
class SearchField(
    private val binding: ViewSearchFieldBinding,
    private val onQueryChanged: (String) -> Unit,
) {

    val isOpen: Boolean get() = binding.root.visibility == View.VISIBLE

    init {
        binding.root.visibility = View.GONE
        binding.search.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            binding.clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            onQueryChanged(query)
        }
        binding.clear.setOnClickListener {
            binding.search.setText("")
            binding.search.requestFocus()
        }
    }

    /** @return true if search is now open. */
    fun toggle(): Boolean {
        if (isOpen) close() else open()
        return isOpen
    }

    fun open() {
        if (isOpen) return
        val root = binding.root
        root.alpha = 0f
        root.visibility = View.VISIBLE
        root.animate().alpha(1f).setDuration(REVEAL_MS).start()
        binding.search.requestFocus()
        // Post rather than call directly: the field cannot take focus until it has been
        // laid out, and asking for the keyboard before that quietly does nothing.
        binding.search.post {
            binding.search.context.getSystemService<InputMethodManager>()
                ?.showSoftInput(binding.search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Closing clears the query too — a hidden filter still applied would be a trap. */
    fun close() {
        if (!isOpen) return
        hideKeyboard()
        binding.search.setText("")
        val root = binding.root
        root.animate().alpha(0f).setDuration(REVEAL_MS).withEndAction {
            root.visibility = View.GONE
            root.alpha = 1f
        }.start()
    }

    private fun hideKeyboard() {
        binding.search.context.getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(binding.search.windowToken, 0)
    }

    private companion object {
        const val REVEAL_MS = 160L
    }
}
