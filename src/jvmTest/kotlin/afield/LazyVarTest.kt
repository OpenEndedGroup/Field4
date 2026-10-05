package afield

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [LazyVar.discard] and the setter never wait for an initializer running on another thread, and a
 * value nobody caches goes to `onDiscard` exactly once.
 *
 * `Box.ki` is the user: its initializer builds a K2 engine under the lazy's lock, and a retire used
 * to skip a box whose engine was still being built, because `isInitialized` was false. The engine was
 * then stored, outlived its box, and kept a compiler project alive for the life of the JVM. Each
 * blocking call below runs on a pool thread with a timeout, so a version that waits for the lock
 * fails here instead of deadlocking.
 */
class LazyVarTest {

    private val pool = Executors.newCachedThreadPool()

    @AfterTest
    fun shutdown() {
        pool.shutdownNow()
    }

    private class Holder(lazy: LazyVar<Any>) {
        var value: Any by lazy
    }

    /** An initializer that signals when it has started and returns only once [finish] opens. */
    private class HeldInitializer {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val built = Any()
        fun run(): Any {
            started.countDown()
            check(finish.await(10, TimeUnit.SECONDS)) { "the test never let the initializer finish" }
            return built
        }
    }

    private fun <T> onPool(block: () -> T): T = pool.submit<T> { block() }.get(5, TimeUnit.SECONDS)

    @Test
    fun discardDuringTheInitializerDoesNotWaitAndReleasesWhatItBuilds() {
        val released = CopyOnWriteArrayList<Any>()
        val held = HeldInitializer()
        var firstBuild = true
        val lazy = lazyVar<Any>(onDiscard = { released += it }) {
            if (firstBuild) { firstBuild = false; held.run() } else Any()
        }
        val holder = Holder(lazy)

        val reader = pool.submit<Any> { holder.value }
        assertTrue(held.started.await(5, TimeUnit.SECONDS))
        val before = lazy.generation

        assertFalse(onPool { lazy.discard() }, "nothing was cached yet, and the discard returned without waiting for the build")
        assertEquals(before + 1, lazy.generation, "an initializer that noted the generation can tell it was overtaken")
        assertTrue(released.isEmpty(), "the build has not returned yet")

        held.finish.countDown()
        assertSame(held.built, reader.get(5, TimeUnit.SECONDS), "the caller that asked first still gets what was built")
        assertEquals(listOf(held.built), released.toList(), "and it is released exactly once, by the building thread")
        assertFalse(lazy.isInitialized, "but not cached")
        assertNull(lazy.valueOrNull())

        val fresh = holder.value
        assertNotSame(held.built, fresh, "the next read builds afresh")
        assertSame(fresh, lazy.valueOrNull())
        assertEquals(1, released.size)
    }

    @Test
    fun theSetterDuringTheInitializerWinsAndTheBuiltValueIsReleased() {
        val released = CopyOnWriteArrayList<Any>()
        val held = HeldInitializer()
        val lazy = lazyVar<Any>(onDiscard = { released += it }) { held.run() }
        val holder = Holder(lazy)
        val replacement = Any()

        val reader = pool.submit<Any> { holder.value }
        assertTrue(held.started.await(5, TimeUnit.SECONDS))
        onPool { holder.value = replacement }

        held.finish.countDown()
        assertSame(replacement, reader.get(5, TimeUnit.SECONDS), "the reader gets the value that was set")
        assertSame(replacement, holder.value)
        assertEquals(listOf(held.built), released.toList(), "what the initializer built is released, not leaked")
    }

    @Test
    fun discardReleasesACachedValueOnceOnTheCallingThread() {
        val released = CopyOnWriteArrayList<Pair<Any, Thread>>()
        val lazy = lazyVar<Any>(onDiscard = { released += it to Thread.currentThread() }) { Any() }
        val holder = Holder(lazy)
        val first = holder.value

        assertTrue(lazy.discard())
        assertEquals(listOf(first to Thread.currentThread()), released.toList())
        assertFalse(lazy.discard(), "a second discard has nothing to release")
        assertEquals(1, released.size)
        assertNotSame(first, holder.value, "and the initializer runs again")
    }

    /**
     * The handshake under contention: whenever the discard lands relative to the build and its
     * publication, what was built is either cached (the discard came first) or released exactly once,
     * never both and never neither.
     */
    @Test
    fun aRacingDiscardLeavesEachBuiltValueCachedOrReleasedExactlyOnce() {
        repeat(2_000) { round ->
            val released = CopyOnWriteArrayList<Any>()
            val spin = round % 50
            val lazy = lazyVar<Any>(onDiscard = { released += it }) {
                repeat(spin) { Thread.onSpinWait() }
                Any()
            }
            val holder = Holder(lazy)
            val start = CyclicBarrier(2)
            val reader = pool.submit<Any> { start.await(); holder.value }
            val discarder = pool.submit<Boolean> { start.await(); repeat(spin / 2) { Thread.onSpinWait() }; lazy.discard() }
            val built = reader.get(5, TimeUnit.SECONDS)
            discarder.get(5, TimeUnit.SECONDS)

            val cached = lazy.valueOrNull() === built
            val releasedCount = released.count { it === built }
            assertTrue(
                (cached && releasedCount == 0) || (!cached && releasedCount == 1),
                "round $round: cached=$cached released=$releasedCount"
            )
            assertEquals(releasedCount, released.size, "round $round: nothing else was released")
        }
    }
}
