package afield.graphics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FLineWiggleTest {

    private fun wave(cycles: Int = 1, steps: Int = 200): FLine {
        val l = FLine().moveTo(0, 0)
        for (i in 1..steps) {
            val x = i.toDouble() / steps
            l.lineTo(x, sin(2 * PI * cycles * x), 0)
        }
        return l
    }

    private fun points(l: FLine): List<DoubleArray> =
        l.nodes.map { doubleArrayOf(it.to.x.toDouble(), it.to.y.toDouble(), it.to.z.toDouble()) }

    @Test
    fun aWaveOnAStraightLineIsTheWaveScaledToTheLine() {
        val b = FLine().moveTo(0, 0).lineTo(10, 0)
        val out = points(b.wiggledBy(wave(), amplitude = 0.5))
        for (p in out) assertEquals(0.5 * sin(2 * PI * p[0] / 10), p[1], 2e-2)
        assertEquals(0.0, out.first()[0], 1e-4)
        assertEquals(10.0, out.last()[0], 1e-3)
        assertEquals(0.0, out.last()[1], 1e-3)
    }

    @Test
    fun subtractingTheChordRemovesASlopeAndRepeatsJoin() {
        // A sine riding up a slope: with the chord removed it is the same wave.
        val sloped = FLine().moveTo(0, 0)
        for (i in 1..200) { val x = i / 200.0; sloped.lineTo(x * 3, x * 3 + 0.3 * sin(2 * PI * x), 0) }
        val b = FLine().moveTo(0, 0).lineTo(12, 0)
        val out = points(b.wiggledBy(sloped, repeats = 4))
        // Every quarter of B returns to the line.
        for (k in 0..4) {
            val p = out.minByOrNull { abs(it[0] - 3.0 * k) }!!
            assertEquals(0.0, p[1], 2e-2, "copy boundary $k")
        }
        // The offset is the wave's perpendicular distance from its chord, scaled by nothing.
        val peak = out.maxOf { it[1] }
        assertEquals(0.3 / sqrt(2.0), peak, 2e-2)
    }

    @Test
    fun withoutSubtractingTheChordDriftAccumulates() {
        val diag = FLine().moveTo(0, 0).lineTo(1, 1)
        val b = FLine().moveTo(0, 0).lineTo(10, 0)
        val out = points(b.wiggledBy(diag, subtractChord = false))
        // x places the point, y is kept: the end lands 1 above the end of B.
        assertEquals(10.0, out.last()[0], 1e-3)
        assertEquals(1.0, out.last()[1], 1e-3)
    }

    private fun helixish(): FLine {
        val l = FLine().moveTo(1, 0, 0)
        for (i in 1..300) {
            val t = i / 300.0 * 3 * PI
            l.lineTo(cos(t) * (1 + 0.2 * t), sin(t), 0.4 * t + 0.1 * t * t)
        }
        return l
    }

    private fun rotation(axis: DoubleArray, angle: Double): Array<DoubleArray> {
        val l = sqrt(axis.sumOf { it * it })
        val (x, y, z) = axis.map { it / l }
        val c = cos(angle); val s = sin(angle); val t = 1 - c
        return arrayOf(
            doubleArrayOf(t * x * x + c, t * x * y - s * z, t * x * z + s * y),
            doubleArrayOf(t * x * y + s * z, t * y * y + c, t * y * z - s * x),
            doubleArrayOf(t * x * z - s * y, t * y * z + s * x, t * z * z + c),
        )
    }

    private fun apply(r: Array<DoubleArray>, v: DoubleArray) =
        DoubleArray(3) { r[it][0] * v[0] + r[it][1] * v[1] + r[it][2] * v[2] }

    private fun rotated(l: FLine, r: Array<DoubleArray>): FLine {
        val out = FLine()
        points(l).forEachIndexed { i, p ->
            val q = apply(r, p)
            if (i == 0) out.moveTo(q[0], q[1], q[2]) else out.lineTo(q[0], q[1], q[2])
        }
        return out
    }

    private fun dist(a: DoubleArray, b: DoubleArray) = sqrt((0..2).sumOf { (a[it] - b[it]) * (a[it] - b[it]) })

    @Test
    fun framesTurnWithTheLineInEveryOrientation() {
        val b = helixish()
        val base = assertNotNull(b.sweptFrames(up = null))
        assertTrue(base.strength > 0.2, "the test curve should choose its own frame, strength ${base.strength}")
        val rnd = java.util.Random(4)
        repeat(40) {
            val r = rotation(doubleArrayOf(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()), rnd.nextDouble() * 2 * PI)
            val f = assertNotNull(rotated(b, r).sweptFrames(up = null))
            for (s in listOf(0.0, 0.3, 0.7, 1.0)) {
                val expected = apply(r, base.frameAt(s).normal)
                assertTrue(dist(expected, f.frameAt(s).normal) < 2e-2, "normal at s=$s after rotation $it")
            }
        }
    }

    @Test
    fun theStartingNormalMovesSmoothlyAsTheLineTumbles() {
        // Tumble through a path that visits every tangent direction; with the default `up` the
        // starting normal must never jump.
        val b = helixish()
        var previous: DoubleArray? = null
        var previousRotation: Array<DoubleArray>? = null
        val steps = 720
        for (i in 0..steps) {
            val a = 2 * PI * i / steps
            val r = rotation(doubleArrayOf(cos(3 * a), sin(2 * a), 0.5), 4 * a)
            val n = assertNotNull(rotated(b, r).sweptFrames()).frameAt(0.0).normal
            if (previous != null) {
                // Compare against the previous normal carried by the small rotation between steps.
                val step = (0..2).maxOf { row -> (0..2).maxOf { col -> abs(r[row][col] - previousRotation!![row][col]) } }
                assertTrue(dist(n, previous) < 4 * step + 2e-2, "jump at step $i: ${dist(n, previous)}")
            }
            previous = n; previousRotation = r
        }
    }

    @Test
    fun aStraightLineLeansOnUp() {
        val f = assertNotNull(FLine().moveTo(0, 0, 0).lineTo(1, 1, 0.2).sweptFrames())
        assertTrue(f.strength < 1e-3, "strength ${f.strength}")
        val n = f.frameAt(0.5).normal
        val t = f.frameAt(0.5).tangent
        assertEquals(0.0, n[0] * t[0] + n[1] * t[1] + n[2] * t[2], 1e-9)
        assertTrue(n[2] > 0.9, "normal should lean to +z, was ${n.toList()}")
    }

    @Test
    fun aClosedLinesFramesMeet() {
        val knot = FLine().moveTo(0, -1, 0)
        for (i in 1..400) {
            val t = 2 * PI * i / 400
            knot.lineTo(sin(t) + 2 * sin(2 * t), cos(t) - 2 * cos(2 * t), -sin(3 * t))
        }
        val f = assertNotNull(knot.sweptFrames(samples = 800))
        assertTrue(dist(f.frameAt(0.0).normal, f.frameAt(1.0).normal) < 3e-2)
    }

    @Test
    fun aFlatLineUsesTheTwoDimensionalFrame() {
        val f = assertNotNull(FLine().moveTo(0, 0).lineTo(1, 0).sweptFrames())
        assertTrue(f.planar)
        assertEquals(1.0, f.frameAt(0.5).normal[1], 1e-9)
        val up3 = FLine().moveTo(0, 0).lineTo(10, 0).wiggledBy(FLine().moveTo(0, 0).lineTo(0.5, 0, 1).lineTo(1, 0, 0))
        assertEquals(1.0, points(up3).maxOf { it[2] }, 1e-2)
    }

    @Test
    fun withoutTheChordOffsetsComeFromTheOrigin() {
        // A circle round y = z = 0, starting off the axis: it should be centred on B.
        val ring = FLine().moveTo(0, 1, 0)
        for (i in 1..120) { val a = i / 120.0 * 2 * PI; ring.lineTo(i / 120.0, cos(a), sin(a)) }
        val b = FLine().moveTo(0, 0, 0).lineTo(0, 0, 10)
        val out = points(b.wiggledBy(ring, subtractChord = false, amplitude = 2))
        for (p in out) assertEquals(2.0, sqrt(p[0] * p[0] + p[1] * p[1]), 1e-2)
        assertEquals(10.0, out.last()[2], 1e-3)
    }
}
