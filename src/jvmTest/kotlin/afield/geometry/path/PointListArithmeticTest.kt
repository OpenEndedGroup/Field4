package afield.geometry.path

import afield.math.linalg.Vec2
import afield.math.linalg.Vec3
import afield.math.linalg.plus
import afield.math.linalg.vec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `+` and `-` between a list of points and a point (or another list of the same type) are stdlib's:
 * append, concatenate, remove. afield.geometry.path once shadowed them with "move every point" and
 * "add point by point", which made `samples + samples.last()` shift a whole stroke and
 * `bsplineFrom(closed = true)` throw. Broadcasting against numbers and the other vector type stays.
 */
class PointListArithmeticTest {
    private fun Vec2.pair() = x to y
    private fun Vec3.triple() = Triple(x, y, z)

    @Test
    fun plusAPointAppendsIt() {
        val pts: List<Vec2> = listOf(vec(1.0, 2.0), vec(3.0, 4.0))
        val more: List<Vec2> = pts + vec(10.0, 0.0)
        assertEquals(listOf(1f to 2f, 3f to 4f, 10f to 0f), more.map { it.pair() })
        val pts3: List<Vec3> = listOf(vec(1.0, 2.0, 3.0))
        assertEquals(listOf(Triple(1f, 2f, 3f), Triple(0f, 0f, 1f)), (pts3 + vec(0.0, 0.0, 1.0)).map { it.triple() })
    }

    @Test
    fun plusAListConcatenates() {
        val a: List<Vec2> = listOf(vec(1.0, 2.0), vec(3.0, 4.0), vec(5.0, 6.0))
        val b: List<Vec2> = listOf(vec(7.0, 8.0))
        assertEquals(listOf(1f to 2f, 3f to 4f, 5f to 6f, 7f to 8f), (a + b).map { it.pair() })
        assertEquals(listOf(1f to 2f, 5f to 6f), (a - listOf(a[1])).map { it.pair() })
    }

    @Test
    fun numbersAndTheOtherVectorTypeStillBroadcast() {
        val pts: List<Vec2> = listOf(vec(1.0, 2.0), vec(3.0, 4.0))
        assertEquals(listOf(2f to 4f, 6f to 8f), (pts * 2).map { it.pair() })
        assertEquals(listOf(Triple(1f, 2f, 5f), Triple(3f, 4f, 5f)), (pts + vec(0.0, 0.0, 5.0)).map { it.triple() })
        assertEquals(listOf(11f to 12f, 13f to 14f), pts.map { it + vec(10.0, 10.0) }.map { it.pair() })
    }
}
