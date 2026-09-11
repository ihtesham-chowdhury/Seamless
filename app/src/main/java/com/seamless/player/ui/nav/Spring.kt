package com.seamless.player.ui.nav

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A spring, stepped with its exact solution rather than integrated.
 *
 * Exact means an 8ms frame and a 28ms frame land on the same curve, so the motion is the same at
 * 60Hz, at 120Hz and on a frame that arrived late. Retargeting keeps the current position and
 * velocity, so tapping a second tab mid-glide bends the motion instead of restarting it — the small
 * jolt a cancelled-and-restarted animator gives is one of the things this replaces.
 *
 * `response` is roughly how long the spring takes to get most of the way there, in seconds.
 * `damping` of 1 settles without overshooting; below 1 it overshoots a little and comes back.
 * The two rest thresholds are in whatever unit the value is in — pixels, or a 0..1 fraction.
 */
internal class Spring(
    response: Float,
    damping: Float,
    private val restDistance: Float,
    private val restSpeed: Float,
) {
    private var omega = 0.0
    private var zeta = 1.0

    init {
        tune(response, damping)
    }

    var value = 0f
        private set
    var velocity = 0f
        private set
    var target = 0f

    val atRest: Boolean get() = value == target && velocity == 0f

    /** Changes how the spring moves from here on, keeping where it is and how fast it is going. */
    fun tune(response: Float, damping: Float) {
        omega = 2.0 * PI / response
        zeta = damping.toDouble()
    }

    /** Jumps straight to [to] and stops there. */
    fun snap(to: Float) {
        value = to
        target = to
        velocity = 0f
    }

    /** Advances by [seconds]. Returns whether it is still moving afterwards. */
    fun step(seconds: Float): Boolean {
        if (atRest) return false
        if (seconds > 0f) {
            val t = seconds.toDouble()
            val d = (value - target).toDouble()
            val v0 = velocity.toDouble()
            val x: Double
            val v: Double
            if (zeta >= 1.0) {
                val e = exp(-omega * t)
                val c = v0 + omega * d
                x = (d + c * t) * e
                v = (v0 - omega * c * t) * e
            } else {
                val wd = omega * sqrt(1 - zeta * zeta)
                val e = exp(-zeta * omega * t)
                val b = (v0 + zeta * omega * d) / wd
                val cs = cos(wd * t)
                val sn = sin(wd * t)
                x = e * (d * cs + b * sn)
                v = e * ((b * wd - zeta * omega * d) * cs - (d * wd + zeta * omega * b) * sn)
            }
            value = (target + x).toFloat()
            velocity = v.toFloat()
        }
        if (abs(value - target) < restDistance && abs(velocity) < restSpeed) {
            value = target
            velocity = 0f
            return false
        }
        return true
    }
}
