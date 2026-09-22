package afield.four.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoDisposerTest {
    @Test
    fun collectedResourcesWaitForTheirOwnDevice() {
        val ownerA = Any()
        val ownerB = Any()
        val ran = mutableListOf<String>()
        val a = AutoDisposer.register(Any(), "a", ownerA) { ran += "a" }
        val b = AutoDisposer.register(Any(), "b", ownerB) { ran += "b" }
        // Deterministic reference-queue delivery, without relying on GC timing.
        a.ref.enqueue()
        b.ref.enqueue()
        AutoDisposer.drain(ownerA) { it.run() }
        assertEquals(listOf("a"), ran)
        assertEquals(1, AutoDisposer.ownedCount(ownerB))
        AutoDisposer.drain(ownerB) { it.run() }
        assertEquals(listOf("a", "b"), ran)
    }

    @Test
    fun shutdownIncludesReachableAndAlreadyQueuedResourcesExactlyOnce() {
        val owner = Any()
        val ran = mutableListOf<String>()
        val collected = AutoDisposer.register(Any(), "collected", owner) { ran += "collected" }
        val reachableTarget = Any()
        val reachable = AutoDisposer.register(reachableTarget, "reachable", owner) { ran += "reachable" }
        collected.ref.enqueue()
        val delayed = mutableListOf<Runnable>()
        AutoDisposer.drain(owner) { delayed += it }
        assertTrue(ran.isEmpty())
        AutoDisposer.releaseOwner(owner) { it.run() }
        assertEquals(listOf("reachable", "collected"), ran)
        delayed.forEach { it.run() }
        AutoDisposer.releaseOwner(owner) { it.run() }
        assertEquals(2, ran.size)
        assertEquals(0, AutoDisposer.ownedCount(owner))
        assertFalse(reachable.disable(), "explicit cleanup after shutdown must not free twice")
        java.lang.ref.Reference.reachabilityFence(reachableTarget)
    }

    @Test
    fun explicitCleanupCancelsQueuedActionAndReplacementUpdatesItsHandles() {
        val owner = Any()
        val ran = mutableListOf<String>()
        val token = AutoDisposer.register(Any(), "buffer", owner) { ran += "stale" }
        token.updateCleanup { ran += "replacement" }
        token.ref.enqueue()
        val delayed = mutableListOf<Runnable>()
        AutoDisposer.drain(owner) { delayed += it }
        assertTrue(token.disable())
        delayed.forEach { it.run() }
        assertTrue(ran.isEmpty())
        assertEquals(0, AutoDisposer.ownedCount(owner))

        val replacement = AutoDisposer.register(Any(), "replacement", owner) { ran += "old" }
        replacement.updateCleanup { ran += "new" }
        AutoDisposer.releaseOwner(owner) { it.run() }
        assertEquals(listOf("new"), ran)
    }
}
