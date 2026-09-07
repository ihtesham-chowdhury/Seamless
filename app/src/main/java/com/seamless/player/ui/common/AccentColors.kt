package com.seamless.player.ui.common

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.seamless.player.R
import com.seamless.player.data.AccentColor

/** Maps [AccentColor] to the resources that describe it: an overlay, a swatch, a label. */
object AccentColors {

    /** Null for DEFAULT — no overlay is applied, leaving Material's own baseline colour. */
    @StyleRes
    fun overlayStyleRes(accent: AccentColor): Int? = when (accent) {
        AccentColor.DEFAULT -> null
        AccentColor.RED -> R.style.ThemeOverlay_Seamless_Red
        AccentColor.ORANGE -> R.style.ThemeOverlay_Seamless_Orange
        AccentColor.AMBER -> R.style.ThemeOverlay_Seamless_Amber
        AccentColor.GREEN -> R.style.ThemeOverlay_Seamless_Green
        AccentColor.TEAL -> R.style.ThemeOverlay_Seamless_Teal
        AccentColor.CYAN -> R.style.ThemeOverlay_Seamless_Cyan
        AccentColor.BLUE -> R.style.ThemeOverlay_Seamless_Blue
        AccentColor.INDIGO -> R.style.ThemeOverlay_Seamless_Indigo
        AccentColor.PURPLE -> R.style.ThemeOverlay_Seamless_Purple
        AccentColor.PINK -> R.style.ThemeOverlay_Seamless_Pink
    }

    /** The colour shown for this accent in the picker grid. Resolves per light/dark on its own. */
    @ColorRes
    fun previewColorRes(accent: AccentColor): Int = when (accent) {
        AccentColor.DEFAULT -> R.color.accent_default_preview
        AccentColor.RED -> R.color.accent_red_primary
        AccentColor.ORANGE -> R.color.accent_orange_primary
        AccentColor.AMBER -> R.color.accent_amber_primary
        AccentColor.GREEN -> R.color.accent_green_primary
        AccentColor.TEAL -> R.color.accent_teal_primary
        AccentColor.CYAN -> R.color.accent_cyan_primary
        AccentColor.BLUE -> R.color.accent_blue_primary
        AccentColor.INDIGO -> R.color.accent_indigo_primary
        AccentColor.PURPLE -> R.color.accent_purple_primary
        AccentColor.PINK -> R.color.accent_pink_primary
    }

    @StringRes
    fun label(accent: AccentColor): Int = when (accent) {
        AccentColor.DEFAULT -> R.string.accent_default
        AccentColor.RED -> R.string.accent_red
        AccentColor.ORANGE -> R.string.accent_orange
        AccentColor.AMBER -> R.string.accent_amber
        AccentColor.GREEN -> R.string.accent_green
        AccentColor.TEAL -> R.string.accent_teal
        AccentColor.CYAN -> R.string.accent_cyan
        AccentColor.BLUE -> R.string.accent_blue
        AccentColor.INDIGO -> R.string.accent_indigo
        AccentColor.PURPLE -> R.string.accent_purple
        AccentColor.PINK -> R.string.accent_pink
    }
}
