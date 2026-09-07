package com.seamless.player.util

import com.seamless.player.BuildConfig

/** Thin logging wrapper so debug chatter disappears from release builds. */
object Log {
    private const val TAG_PREFIX = "Seamless/"
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG_PREFIX + tag, msg)
    }
    fun w(tag: String, msg: String, t: Throwable? = null) =
        android.util.Log.w(TAG_PREFIX + tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        android.util.Log.e(TAG_PREFIX + tag, msg, t)
}
