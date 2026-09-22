package afield.four.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeferredCleanupTest {
    @Test
    fun shutdownReleasesRecentBatchesAndCurrentWorkInOrder() {
        val ran = mutableListOf<String>()
        val recent = mutableListOf(Runnable { ran += "last frame" })
        val retired = mutableListOf(Long.MAX_VALUE to recent)
        val pending = mutableListOf(Runnable { ran += "current frame" })

        assertNull(drainDeferredCleanup(pending, retired))
        assertEquals(listOf("last frame", "current frame"), ran)
        assertTrue(recent.isEmpty())
        assertTrue(retired.isEmpty())
        assertTrue(pending.isEmpty())
        assertNull(drainDeferredCleanup(pending, retired))
        assertEquals(2, ran.size, "a second drain must not release handles twice")
    }

    @Test
    fun failedReleaseDoesNotStrandOtherOrNewlyEnqueuedWork() {
        val ran = mutableListOf<String>()
        val pending = mutableListOf<Runnable>()
        val retired = mutableListOf<Pair<Long, MutableList<Runnable>>>()
        val first = IllegalArgumentException("first release")
        val second = IllegalStateException("second release")
        pending += Runnable {
            pending += Runnable { ran += "dependent" }
            retired += 7L to mutableListOf(Runnable { ran += "retired dependent" })
            throw first
        }
        pending += Runnable { ran += "survivor" }
        pending += Runnable { throw second }

        val failure = checkNotNull(drainDeferredCleanup(pending, retired))
        assertEquals(listOf("survivor", "retired dependent", "dependent"), ran)
        assertEquals(2, failure.suppressed.size)
        assertSame(first, failure.suppressed[0])
        assertSame(second, failure.suppressed[1])
        assertTrue(pending.isEmpty())
        assertTrue(retired.isEmpty())
    }
}
