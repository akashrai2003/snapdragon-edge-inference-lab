package ai.snapdragon.edge

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Read the small returned result set concurrently, with one deadline and cancellation for all. */
class ParallelWebReader(private val deadlineMs: Long = 20000) {
    data class Attempt(val page: ReadPage?, val error: String?, val elapsedMs: Long)

    fun read(
        urls: List<String>,
        focus: String,
        web: WebAccess,
        parent: AssistantClient.Request,
    ): List<Attempt> {
        require(urls.size in 1..4)
        parent.check()
        val children = urls.map { AssistantClient.Request() }
        val pool =
            Executors.newFixedThreadPool(urls.size) { work ->
                Thread(work, "CueWebPage").apply { isDaemon = true }
            }
        try {
            // One parent Stop owns every socket, not just the last parallel request attached.
            parent.attachCancellation {
                children.forEach { it.cancel() }
                pool.shutdownNow()
            }
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs)
            val futures =
                urls.mapIndexed { index, url ->
                    pool.submit(
                        Callable {
                            val started = System.nanoTime()
                            try {
                                val page = web.read(url, children[index], focus)
                                children[index].check()
                                Attempt(page, null, (System.nanoTime() - started) / 1_000_000)
                            } catch (problem: Exception) {
                                Attempt(
                                    null,
                                    if (problem is WebResearchException) problem.userMessage
                                    else "That page could not be read just now.",
                                    (System.nanoTime() - started) / 1_000_000,
                                )
                            }
                        }
                    )
                }
            return futures.mapIndexed { index, future ->
                parent.check()
                val attempt =
                    try {
                        if (future.isDone) future.get()
                        else
                            future.get(
                                (until - System.nanoTime()).coerceAtLeast(1),
                                TimeUnit.NANOSECONDS,
                            )
                    } catch (problem: Exception) {
                        children[index].cancel()
                        future.cancel(true)
                        Attempt(
                            null,
                            "That page did not finish within the reading limit.",
                            deadlineMs,
                        )
                    }
                parent.check()
                attempt
            }
        } finally {
            children.forEach { it.cancel() }
            pool.shutdownNow()
        }
    }
}
