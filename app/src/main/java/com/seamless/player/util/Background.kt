package com.seamless.player.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Minimal "do this off the main thread, then come back" helper.
 *
 * Deliberately not coroutines: the app has exactly one kind of background work (querying
 * MediaStore), and a single executor keeps the code obvious.
 */
object Background {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "seamless-io")
    }
    private val main = Handler(Looper.getMainLooper())

    /** Runs [work] on a background thread and delivers the result to [then] on the main thread. */
    fun <T> run(work: () -> T, then: (T) -> Unit) {
        executor.execute {
            val result = try {
                work()
            } catch (t: Throwable) {
                Log.e("Background", "background work failed", t)
                return@execute
            }
            main.post { then(result) }
        }
    }
}
