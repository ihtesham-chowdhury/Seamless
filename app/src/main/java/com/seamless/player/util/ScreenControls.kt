package com.seamless.player.util

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import com.seamless.player.ui.common.VolumeBooster

/**
 * Per-window brightness and media volume, used by the drag gestures.
 *
 * Brightness is applied to the activity window only, so it never changes the system
 * setting and is forgotten when you leave the player.
 */
class ScreenControls(private val activity: Activity) {

    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val maxVolume: Int = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    var volume: Int
        get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        set(value) {
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                value.coerceIn(0, maxVolume),
                0, // no system UI; we draw our own indicator
            )
        }

    // ---- volume, continued past the system maximum ----

    private var booster: VolumeBooster? = null

    /** How far into the boost range we are, in the same step units as the system volume. */
    private var boostStep = 0

    /**
     * The full range, system volume plus boost. Doubling the system range means the top of
     * the scale reads as 200%, which is the convention other players use.
     */
    val volumeSteps: Int get() = if (booster != null) maxVolume * 2 else maxVolume

    /**
     * One continuous scale across both halves. Below the system maximum this is ordinary
     * stream volume; above it the stream stays at maximum and the surplus becomes gain.
     */
    var volumeLevel: Int
        get() = if (boostStep > 0) maxVolume + boostStep else volume
        set(value) {
            val target = value.coerceIn(0, volumeSteps)
            if (target <= maxVolume) {
                boostStep = 0
                booster?.setGain(0)
                volume = target
            } else {
                volume = maxVolume
                boostStep = target - maxVolume
                booster?.setGain(gainForStep(boostStep))
            }
        }

    val volumePercent: Int
        get() = if (maxVolume == 0) 0 else volumeLevel * 100 / maxVolume

    val isVolumeBoosted: Boolean get() = boostStep > 0

    private fun gainForStep(step: Int): Int =
        if (maxVolume == 0) 0 else step * VolumeBooster.MAX_GAIN_MILLIBELS / maxVolume

    /**
     * Binds boosting to a player's audio session. Safe to call again when the session
     * changes; any boost already dialled in is carried over to the new one.
     */
    fun attachAudioSession(audioSessionId: Int) {
        booster?.release()
        booster = VolumeBooster.create(audioSessionId)
        if (boostStep > 0) booster?.setGain(gainForStep(boostStep))
    }

    /** Audio effects hold real hardware; let them go with the player. */
    fun releaseAudioSession() {
        booster?.release()
        booster = null
        boostStep = 0
    }

    /**
     * 0f..1f, or [FOLLOW_SYSTEM] below the bottom of the range.
     *
     * Dragging all the way down hands control back to the system rather than pinning the
     * screen at its dimmest — which is what you actually want in the dark, since automatic
     * brightness can go lower than the manual floor and will adapt when the lights come on.
     */
    var brightness: Float
        get() {
            val current = activity.window.attributes.screenBrightness
            // While automatic, report the bottom of the manual range so that dragging back
            // up continues from where the finger left off instead of jumping to mid-scale.
            return if (current < 0f) 0f else current
        }
        set(value) {
            activity.window.attributes = activity.window.attributes.apply {
                screenBrightness =
                    if (value < AUTO_THRESHOLD) FOLLOW_SYSTEM else value.coerceAtMost(1f)
            }
        }

    /** True when brightness has been handed back to the system. */
    val isBrightnessAutomatic: Boolean
        get() = activity.window.attributes.screenBrightness < 0f

    /** Applies a previously saved brightness, if there is one. */
    fun restoreBrightness(saved: Float) {
        if (saved in 0f..1f) brightness = saved
    }

    companion object {
        /** WindowManager's "use the system setting" value. */
        const val FOLLOW_SYSTEM = -1f

        /** Below this, the drag is treated as a request for automatic brightness. */
        const val AUTO_THRESHOLD = 0.02f
    }

    fun keepScreenOn(on: Boolean) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
