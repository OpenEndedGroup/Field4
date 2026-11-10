package afield.io.resolve

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.test.*

class ResolvePlayheadLinkTest {
    private fun at(seconds: Double): ResolvePlayhead {
        val frame = (seconds * 24).roundToInt()
        return ResolvePlayhead("Edit", "", 86400 + frame, frame, frame / 24.0, 24.0)
    }

    @Test fun offersCoalesceAndFailuresRetryOnlyTheNewestPosition() {
        val clock = AtomicLong()
        val writes = mutableListOf<Double>()
        var fail = true
        val link = ResolvePlayheadLink(read = { at(0.0) }, write = {
            writes.add(it)
            if (fail) { fail = false; throw ResolveUnavailable("offline") }
            at(it)
        }, clock = clock::get, automatic = false)
        try {
            link.enabled = true
            (1..100).forEach { link.offer(it.toDouble()) }
            assertTrue(writes.isEmpty())
            link.step()
            assertEquals(listOf(100.0), writes)
            assertEquals("offline", link.status.error)
            (101..200).forEach { link.offer(it.toDouble()) }
            link.step()
            assertEquals(1, writes.size, "Backoff must not queue or retry old positions")
            clock.set(1_000_000_001)
            link.step()
            assertEquals(listOf(100.0, 200.0), writes)
            assertNull(link.status.error)
            assertTrue(link.status.settled)
        } finally { link.close() }
    }

    @Test fun pausedPositionIsVerifiedAndCorrectedWithoutAStreamOfRedundantWrites() {
        val clock = AtomicLong()
        var remote = at(0.0)
        val link = ResolvePlayheadLink(read = { remote }, write = { at(it).also { p -> remote = p } },
            clock = clock::get, automatic = false)
        try {
            link.enabled = true
            link.offer(2.0)
            link.step()
            repeat(10) { link.step() }
            assertEquals(1, link.status.writes)
            remote = at(3.0)
            clock.set(1_000_000_001)
            link.step()
            assertEquals(2.0, remote.seconds)
            assertEquals(2, link.status.writes)
            assertEquals(1, link.status.reads)
            link.offer(null)
            clock.set(5_000_000_000)
            link.step()
            assertEquals(2, link.status.writes, "An unloaded movie must clear its pending target")
        } finally { link.close() }
    }

    @Test fun getDisablesFollowingAndTakesPriorityOverPendingWrites() {
        val link = ResolvePlayheadLink(read = { at(7.0) }, write = { error("Get must not send a seek") }, automatic = false)
        try {
            link.enabled = true
            link.offer(10.0)
            val old = link.getPlayhead()
            val latest = link.getPlayhead()
            assertTrue(old.isCancelled)
            assertFalse(link.enabled)
            assertFalse(latest.isDone)
            link.step()
            assertEquals(7.0, latest.get(1, TimeUnit.SECONDS).seconds)
            assertEquals(0, link.status.writes)
            link.step()
        } finally { link.close() }
    }

    @Test fun slowIoCannotBlockOffersOrCloseAndItsLateReplyIsDiscarded() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val link = ResolvePlayheadLink(read = { at(0.0) }, write = {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            at(it)
        }, intervalSeconds = .05)
        try {
            link.enabled = true
            link.offer(1.0)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            CompletableFuture.runAsync {
                repeat(1000) { link.offer(it.toDouble()) }
                link.close()
            }.get(1, TimeUnit.SECONDS)
            assertNull(link.status.targetSeconds)
            release.countDown()
            assertNull(link.status.observed, "A closed link must not publish a late reply")
        } finally { release.countDown(); link.close() }
    }

    @Test fun closedLinksCancelPendingGetsAndRejectNewWork() {
        val link = ResolvePlayheadLink(read = { at(0.0) }, write = { at(it) }, automatic = false)
        val get = link.getPlayhead()
        link.close()
        assertFailsWith<CancellationException> { get.join() }
        assertFailsWith<IllegalStateException> { link.getPlayhead() }
        assertFailsWith<IllegalArgumentException> { link.offer(Double.NaN) }
    }
}
