package afield.manifold

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Booleans of temporary operands under constant garbage collection must not touch freed native objects.
 *
 * Before ManifoldNative pinned operands and locked native calls against frees, `previewPolygon(p) and ring`
 * could have its left-hand section freed by the Cleaner mid-intersection: the house corpus host died in
 * CrossSection::GetPaths (2026-10-05). A use-after-free is not reproducible on demand, so this hammers the
 * pattern from several threads while another forces collections, and checks every result.
 */
class NativeLifetimeTest {
    @Test
    fun temporaryOperandsSurviveCollectionDuringNativeCalls() {
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(4)
        val workers = (0 until 4).map { w ->
            thread(name = "lifetime-$w") {
                try {
                    val ring = Section.square(100.0) - Section.square(60.0).translated(20.0, 20.0)
                    repeat(400) {
                        // The left operand is a temporary nothing else references.
                        val area = (Section.square(100.0, 50.0) and ring).area
                        assertEquals(100.0 * 50.0 - 60.0 * 30.0, area, 1e-6)
                        val union = Section.union(List(6) { i -> Section.square(10.0).translated(20.0 * i, 0.0) })
                        assertEquals(600.0, union.area, 1e-6)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }
        }
        val collector = thread(name = "lifetime-gc") { while (done.count > 0) { System.gc(); Thread.sleep(2) } }
        workers.forEach { it.join() }
        collector.join()
        assertNull(failure.get())
    }

    /**
     * A warp runs inside a native call, on Manifold's own threads. Native objects it makes and frees there
     * must neither deadlock against frees queued by other threads nor be freed under the outer call.
     */
    @Test
    fun aCallbackMayMakeAndFreeNativeObjects() {
        val stop = AtomicBoolean(false)
        val churn = thread(name = "lifetime-churn") {
            val ring = Section.square(100.0) - Section.square(60.0).translated(20.0, 20.0)
            while (!stop.get()) { (Section.square(100.0, 50.0) and ring).area; System.gc() }
        }
        val outcome = AtomicReference<Any?>(null)
        val done = CountDownLatch(1)
        thread(name = "lifetime-warp") {
            try {
                Solid.cube(10.0).refined(8).use { cube ->
                    cube.warped { x, y, z -> Section.square(1.0).use { s -> Triple(x * s.area, y, z) } }.use { outcome.set(it.volume) }
                }
            } catch (t: Throwable) {
                outcome.set(t)
            } finally {
                done.countDown()
            }
        }
        val finished = done.await(120, TimeUnit.SECONDS)
        stop.set(true)
        churn.join()
        assertTrue(finished, "a warp that makes and frees sections did not finish")
        (outcome.get() as? Throwable)?.let { throw it }
        assertEquals(1000.0, outcome.get() as Double, 1e-6)
    }
}
