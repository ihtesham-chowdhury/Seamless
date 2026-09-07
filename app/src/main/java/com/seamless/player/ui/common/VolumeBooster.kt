package com.seamless.player.ui.common

import android.media.audiofx.LoudnessEnhancer
import com.seamless.player.util.Log

/**
 * Amplification above the device's own maximum, for quiet recordings.
 *
 * Android's player volume is capped at unity gain — `Player.setVolume` clamps to 1.0 and
 * will not go louder — so raising a quiet video past the system maximum has to happen in
 * the audio effect chain instead. `LoudnessEnhancer` attaches to a specific audio session
 * and applies a target gain in millibels.
 *
 * Every call is guarded. Audio effects are hardware-backed and genuinely optional: the
 * constructor throws on devices that do not provide one, and a player that refuses to start
 * because a nicety is unavailable would be a poor trade. When boosting cannot be had, the
 * volume simply stops at 100%.
 *
 * Gain is applied to a session we generate ourselves rather than session 0, which is the
 * global output mix — boosting that would raise every other app's audio too.
 */
class VolumeBooster private constructor(private val effect: LoudnessEnhancer) {

    /**
     * [millibels] is hundredths of a decibel. Zero disables the effect entirely rather than
     * leaving it running at unity, so an unboosted player costs nothing.
     */
    fun setGain(millibels: Int) {
        runCatching {
            effect.setTargetGain(millibels)
            // setEnabled returns a status code rather than being a property.
            effect.setEnabled(millibels > 0)
        }.onFailure { Log.w("VolumeBooster", "could not apply gain $millibels", it) }
    }

    fun release() {
        runCatching { effect.release() }
    }

    companion object {
        /**
         * Gain at full boost. +6 dB is a doubling of amplitude, which is what players
         * conventionally present as "200%". Pushing much beyond this mostly buys clipping.
         */
        const val MAX_GAIN_MILLIBELS = 600

        /** Null when the device provides no loudness effect; the caller then caps at 100%. */
        fun create(audioSessionId: Int): VolumeBooster? = runCatching {
            VolumeBooster(LoudnessEnhancer(audioSessionId))
        }.onFailure {
            Log.w("VolumeBooster", "loudness effect unavailable on this device", it)
        }.getOrNull()
    }
}
