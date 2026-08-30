package afield

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class InFlightOperationCoalescerTest {
    @Test
    fun `a retry joins the in-flight operation instead of executing it twice`() {
        val coalescer = InFlightOperationCoalescer<String, String>()
        val entered = CountDownLatch(1)
        val joined = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executions = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit<String> {
                coalescer.run("dust-three-launch") {
                    executions.incrementAndGet()
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    "launched"
                }
            }
            check(entered.await(5, TimeUnit.SECONDS))
            val retry = executor.submit<String> {
                coalescer.run("dust-three-launch", joined::countDown) {
                    executions.incrementAndGet()
                    "duplicate"
                }
            }

            check(joined.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals("launched", first.get(5, TimeUnit.SECONDS))
            assertEquals("launched", retry.get(5, TimeUnit.SECONDS))
            assertEquals(1, executions.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
