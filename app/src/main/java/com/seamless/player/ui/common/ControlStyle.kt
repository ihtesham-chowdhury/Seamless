package com.seamless.player.ui.common

/**
 * How a floating control says "off".
 *
 * One value for every toggle in a control row — shuffle, speed at 1x, captions — because they sit
 * side by side, and three different dims in one row read as three different states rather than
 * one. It was 0.4 for two of them and 0.55 for the third, which was too faint next to controls at
 * full strength: a control that dim looks less like "off" than like "unavailable". 0.65 still reads
 * as off at a glance and still looks like it belongs to the row.
 */
object ControlStyle {
    const val INACTIVE_ALPHA = 0.65f
}
