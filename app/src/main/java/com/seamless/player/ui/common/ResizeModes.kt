package com.seamless.player.ui.common

import android.widget.ImageView
import androidx.annotation.StringRes
import androidx.media3.ui.AspectRatioFrameLayout
import com.seamless.player.R
import com.seamless.player.data.ResizeMode

/**
 * Bridges the stored [ResizeMode] to Media3's constants and to display labels, so the data
 * layer never has to know about Media3 or resources.
 */
object ResizeModes {

    fun toMedia3(mode: ResizeMode): Int = when (mode) {
        ResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        ResizeMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        ResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    /**
     * The ImageView equivalent, so a still standing in for a video — the feed's poster
     * frame — is framed the same way the video will be when it arrives, and swapping one
     * for the other does not make the picture jump.
     */
    fun toImageScale(mode: ResizeMode): ImageView.ScaleType = when (mode) {
        ResizeMode.FIT -> ImageView.ScaleType.FIT_CENTER
        ResizeMode.CROP -> ImageView.ScaleType.CENTER_CROP
        ResizeMode.STRETCH -> ImageView.ScaleType.FIT_XY
    }

    @StringRes
    fun label(mode: ResizeMode): Int = when (mode) {
        ResizeMode.FIT -> R.string.resize_fit
        ResizeMode.CROP -> R.string.resize_crop
        ResizeMode.STRETCH -> R.string.resize_stretch
    }

    /** Fit -> Crop -> Stretch -> Fit. */
    fun next(mode: ResizeMode): ResizeMode = when (mode) {
        ResizeMode.FIT -> ResizeMode.CROP
        ResizeMode.CROP -> ResizeMode.STRETCH
        ResizeMode.STRETCH -> ResizeMode.FIT
    }
}
