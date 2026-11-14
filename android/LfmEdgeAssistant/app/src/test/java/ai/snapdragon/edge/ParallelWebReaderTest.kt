package ai.snapdragon.edge

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ParallelWebReaderTest {
    private abstract class ReaderWeb : WebAccess {
        override fun search(query: String, request: AssistantClient.Request) = emptyList<WebHit>()
    }

    private val urls = (1..4).map { "https://example.com/$it" }

    @Test
    fun allFourReadsStartBeforeAnyOneCompletesAndKeepSourceOrder() {
        val ready = CountDownLatch(4)
        val web =
            object : ReaderWeb() {
                override fun read(
                    url: String,
                    request: AssistantClient.Request,
                    focus: String,
                ): ReadPage {
                    ready.countDown()
                    check(ready.await(2, TimeUnit.SECONDS)) { "Reads were serialized" }
                    return ReadPage("Page", url, "Evidence", false)
                }
            }
        val results = ParallelWebReader().read(urls, "question", web, AssistantClient.Request())
        assertEquals(urls, results.map { it.page?.url })
        assertTrue(results.all { it.error == null })
    }

    @Test
    fun stopCancelsEveryParallelSocket() {
        val attached = CountDownLatch(4)
        val released = CountDownLatch(4)
        val cancelled = AtomicInteger()
        val parent = AssistantClient.Request()
        val web =
            object : ReaderWeb() {
                override fun read(
                    url: String,
                    request: AssistantClient.Request,
                    focus: String,
                ): ReadPage {
                    request.attachCancellation {
                        cancelled.incrementAndGet()
                        released.countDown()
                    }
                    attached.countDown()
                    released.await(2, TimeUnit.SECONDS)
                    request.check()
                    return ReadPage("Page", url, "Evidence", false)
                }
            }
        val caller = Executors.newSingleThreadExecutor()
        try {
            val result =
                caller.submit<Boolean> {
                    try {
                        ParallelWebReader().read(urls, "question", web, parent)
                        false
                    } catch (expected: CancellationException) {
                        true
                    }
                }
            assertTrue(attached.await(2, TimeUnit.SECONDS))
            parent.cancel()
            assertTrue(result.get(3, TimeUnit.SECONDS))
            assertTrue(cancelled.get() >= 4)
        } finally {
            parent.cancel()
            caller.shutdownNow()
        }
    }

    @Test
    fun slowPageDoesNotDiscardCompletedEvidence() {
        val web =
            object : ReaderWeb() {
                override fun read(
                    url: String,
                    request: AssistantClient.Request,
                    focus: String,
                ): ReadPage {
                    if (url.endsWith("1")) Thread.sleep(2000)
                    return ReadPage("Page", url, "Evidence", false)
                }
            }
        val results = ParallelWebReader(150).read(urls, "question", web, AssistantClient.Request())
        assertNotNull(results[0].error)
        assertNull(results[0].page)
        assertTrue(results.drop(1).all { it.page != null })
    }
}
