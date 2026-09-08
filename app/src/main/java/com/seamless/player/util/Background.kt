package com.seamless.player.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * "Do this off the main thread, then come back."
 *
 * Deliberately not coroutines: the work this app does off the main thread is small and
 * uniform, and two executors plus a handler keep it obvious.
 *
 * **Two lanes, and the separation matters.** Reading MediaStore takes milliseconds and never
 * blocks; a network request can sit for half a minute waiting for a timeout. On one shared
 * thread the second stalls the first, so a subtitle search left mid-air would hold up the
 * library scan queued behind it. They get a thread each.
 *
 * **Failure is delivered, not swallowed.** This class used to log an exception and return,
 * which meant a caller waiting on a result waited for ever — and that is exactly what it
 * cost: a subtitle search that threw left its panel saying "Searching…" until the user gave
 * up. There was nothing wrong with the panel. It was never told. So [onFailure] exists, and
 * anything that shows progress is expected to pass one.
 */
object Background {

    private val io = Executors.newSingleThreadExecutor { Thread(it, "seamless-io") }

    /**
     * Its own thread, and more than one of them.
     *
     * A request can block for the length of its timeout, and a user who taps Find subtitles,
     * changes their mind and taps it again on the next video should not be queued behind the
     * first. Two is enough for that without inviting a stampede.
     */
    private val network = Executors.newFixedThreadPool(2) { Thread(it, "seamless-net") }

    private val main = Handler(Looper.getMainLooper())

    /**
     * Runs [work] on the storage thread and delivers the result to [then] on the main thread.
     *
     * If [work] throws, [onFailure] is called on the main thread instead. Passing null keeps
     * the old behaviour — log it and stop — which is right for work whose only caller has
     * nothing to show and nothing to wait on.
     */
    fun <T> run(work: () -> T, then: (T) -> Unit, onFailure: ((Throwable) -> Unit)? = null) {
        submit(io, "io", work, then, onFailure)
    }

    /** The same, on the network lane. Every caller here should handle failure. */
    fun <T> online(work: () -> T, then: (T) -> Unit, onFailure: (Throwable) -> Unit) {
        submit(network, "net", work, then, onFailure)
    }

    private fun <T> submit(
        executor: java.util.concurrent.Executor,
        lane: String,
        work: () -> T,
        then: (T) -> Unit,
        onFailure: ((Throwable) -> Unit)?,
    ) {
        executor.execute {
            val result = try {
                work()
            } catch (error: Throwable) {
                // Throwable rather than Exception on purpose. An Error here is still a result
                // the caller is waiting for, and losing it would put the caller back in the
                // state this whole class exists to prevent.
                Log.e("Background", "$lane work failed", error)
                if (onFailure != null) main.post { onFailure(error) }
                return@execute
            }
            main.post { then(result) }
        }
    }
}
