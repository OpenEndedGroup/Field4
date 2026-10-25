package afield.lang.python

import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [NumpyShared]'s contract, with Kotlin standing in for numpy: it writes the same direct buffers
 * that `fieldcv.field` hands to Python as arrays (`DirectNDArray.getData()`), so no JEP is needed.
 */
class NumpySharedTest {

    private fun unique(prefix: String) = "$prefix-${System.nanoTime()}"

    @Test
    fun anImageIsAttachedByNameAndReplacedOnlyByAnotherSize() {
        val name = unique("img")
        val a = NumpyShared.image(name, 4, 6)
        assertSame(a, NumpyShared.image(name))
        assertSame(a, NumpyShared.image(name, 4, 6))
        val b = NumpyShared.image(name, 8, 6)
        assertNotSame(a, b)
        assertSame(b, NumpyShared.image(name))
        assertFailsWith<IllegalStateException> { NumpyShared.image(unique("missing")) }
    }

    @Test
    fun frontOnlyChangesInConsume() {
        val img = NumpyShared.image(unique("img"), 2, 3)
        val back = img.array().data as ByteBuffer
        assertEquals(2 * 3 * 4, back.capacity())

        assertFalse(img.consume(), "nothing published yet")
        back.put(0, 7)
        assertEquals(0, img.front.get(0).toInt(), "an unpublished write is invisible")
        img.publish()
        back.put(0, 9) // the writer moves on before the reader looks
        assertEquals(0, img.front.get(0).toInt(), "publish alone does not touch front")
        assertTrue(img.consume())
        assertEquals(7, img.front.get(0).toInt(), "front holds what was published, not what came after")
        assertFalse(img.consume(), "a second consume with nothing new is a no-op")
    }

    @Test
    fun aFrameIsNeverTornBetweenTwoPublishes() {
        val img = NumpyShared.image(unique("img"), 64, 64)
        val back = img.array().data as ByteBuffer
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val writer = thread {
            var v: Byte = 0
            while (!stop.get()) {
                v++
                for (i in 0 until back.capacity()) back.put(i, v)
                img.publish()
            }
        }
        var checked = 0
        val deadline = System.nanoTime() + 300_000_000L
        while (System.nanoTime() < deadline) {
            if (img.consume()) {
                val first = img.front.get(0)
                for (i in 0 until img.front.capacity()) assertEquals(first, img.front.get(i), "torn at byte $i")
                checked++
            }
        }
        stop.set(true)
        writer.join()
        assertTrue(checked > 3, "only $checked frames consumed")
    }

    @Test
    fun polylinesArePackedPointsPlusCountsInNormalizedCoordinates() {
        val l = NumpyShared.lines(unique("lines"))
        val points = l.points().data as FloatBuffer
        val counts = l.counts().data as IntBuffer
        floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0.5f, 0.5f, 0.25f, 0.75f).forEachIndexed { i, v -> points.put(i, v) }
        counts.put(0, 3)
        counts.put(1, 2)
        l.publish(5, 2)

        val unit = l.lines()
        assertEquals(listOf(3, 2), unit.map { it.nodes.size })
        val scaled = l.lines(360.0, 240.0)
        assertEquals(360.0, scaled[0].nodes[1].to.x.toDouble(), 1e-4)
        assertEquals(180.0, scaled[1].nodes[1].to.y.toDouble(), 1e-4)
        assertSame(scaled, l.lines(360.0, 240.0), "rebuilt only when something new arrives")

        points.put(0, 0.9f) // refilling the back arrays does not disturb the snapshot
        assertEquals(0.0, l.lines()[0].nodes[0].to.x.toDouble(), 1e-6)
    }

    @Test
    fun reserveGrowsTheBackArraysAndPublishChecksCapacity() {
        val l = NumpyShared.lines(unique("lines"))
        assertFailsWith<IllegalArgumentException> { l.publish(l.pointCapacity + 1, 1) }
        l.reserve(5000, 700)
        assertTrue(l.pointCapacity >= 5000 && l.lineCapacity >= 700)
        assertEquals(l.pointCapacity * 2, (l.points().data as FloatBuffer).capacity())
        l.publish(0, 0)
        assertEquals(emptyList(), l.lines())
    }
}
