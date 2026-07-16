package afield.stdlib.linalg.bulk

import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import jdk.incubator.vector.FloatVector
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Oracle for the bulk kernels is the already-tested generated heap type, called once per lane —
 * this isolates the actual new risk (does the swapped constant/scalar/binary/call rendering in
 * `renderBulk` produce the same result as the scalar heap backend for the same [Expr] tree), not
 * whether the underlying formulas are correct (that's `GeneratedLinalgTest`'s job).
 *
 * `N` is swept across the vectorized-loop/scalar-tail boundary (relative to the platform's actual
 * `SPECIES_PREFERRED` width) since that split, not the arithmetic, is this design's new risk.
 */
class BulkVectorsTest {
    private val lanes = FloatVector.SPECIES_PREFERRED.length()
    private val boundaryLengths = listOf(0, 1, lanes - 1, lanes, lanes + 1, 2 * lanes, 2 * lanes + 3).filter { it >= 0 }

    private fun randomFloats(random: Random, n: Int, from: Float = -10f, to: Float = 10f) =
        FloatArray(n) { from + random.nextFloat() * (to - from) }

    private fun assertArraysClose(expected: FloatArray, actual: FloatArray, tolerance: Float, label: String) {
        for (i in expected.indices) {
            assertTrue(
                abs(expected[i] - actual[i]) <= tolerance,
                "$label[$i]: expected=${expected[i]}, actual=${actual[i]}",
            )
        }
    }

    @Test
    fun vec3DestinationOperationsMatchHeapPerLaneAcrossBoundaryLengths() {
        val random = Random(0x8ec1de)
        for (n in boundaryLengths) {
            val selfX = randomFloats(random, n); val selfY = randomFloats(random, n); val selfZ = randomFloats(random, n)
            val otherX = randomFloats(random, n); val otherY = randomFloats(random, n); val otherZ = randomFloats(random, n)
            val scalar = random.nextFloat() * 4f - 2f
            val t = random.nextFloat()

            fun expected(op: (Vector3f, Vector3f) -> Vector3f): Triple<FloatArray, FloatArray, FloatArray> {
                val ex = FloatArray(n); val ey = FloatArray(n); val ez = FloatArray(n)
                for (i in 0 until n) {
                    val r = op(Vector3f(selfX[i], selfY[i], selfZ[i]), Vector3f(otherX[i], otherY[i], otherZ[i]))
                    ex[i] = r.x; ey[i] = r.y; ez[i] = r.z
                }
                return Triple(ex, ey, ez)
            }

            fun check(
                label: String,
                expected: Triple<FloatArray, FloatArray, FloatArray>,
                bulk: (FloatArray, FloatArray, FloatArray) -> Unit,
                tolerance: Float = 1e-5f,
            ) {
                val dx = FloatArray(n); val dy = FloatArray(n); val dz = FloatArray(n)
                bulk(dx, dy, dz)
                assertArraysClose(expected.first, dx, tolerance, "$label.x")
                assertArraysClose(expected.second, dy, tolerance, "$label.y")
                assertArraysClose(expected.third, dz, tolerance, "$label.z")
            }

            check("add", expected { a, b -> Vector3f(a).add(b) }, { dx, dy, dz -> addRange(selfX, selfY, selfZ, otherX, otherY, otherZ, dx, dy, dz, 0, n) })
            check("sub", expected { a, b -> Vector3f(a).sub(b) }, { dx, dy, dz -> subRange(selfX, selfY, selfZ, otherX, otherY, otherZ, dx, dy, dz, 0, n) })
            check("mul", expected { a, b -> Vector3f(a).mul(b) }, { dx, dy, dz -> mulRange(selfX, selfY, selfZ, otherX, otherY, otherZ, dx, dy, dz, 0, n) })
            check("min", expected { a, b -> Vector3f(a).min(b) }, { dx, dy, dz -> minRange(selfX, selfY, selfZ, otherX, otherY, otherZ, dx, dy, dz, 0, n) })
            check("max", expected { a, b -> Vector3f(a).max(b) }, { dx, dy, dz -> maxRange(selfX, selfY, selfZ, otherX, otherY, otherZ, dx, dy, dz, 0, n) })
            check("cross", expected { a, b -> Vector3f(a).cross(b) }, { dx, dy, dz -> crossRange(selfX, selfY, selfZ, otherX, otherY, otherZ, dx, dy, dz, 0, n) })
            check("lerp", expected { a, b -> Vector3f(a).lerp(b, t) }, { dx, dy, dz -> lerpRange(selfX, selfY, selfZ, otherX, otherY, otherZ, t, dx, dy, dz, 0, n) })
            check("fma", expected { a, b -> Vector3f(a).fma(scalar, b) }, { dx, dy, dz -> fmaRange(selfX, selfY, selfZ, scalar, otherX, otherY, otherZ, dx, dy, dz, 0, n) })
            check("div", expected { a, b -> Vector3f(a).div(Vector3f(b).add(Vector3f(5f))) },
                { dx, dy, dz ->
                    val denomX = FloatArray(n) { otherX[it] + 5f }; val denomY = FloatArray(n) { otherY[it] + 5f }; val denomZ = FloatArray(n) { otherZ[it] + 5f }
                    divRange(selfX, selfY, selfZ, denomX, denomY, denomZ, dx, dy, dz, 0, n)
                })

            // scalar-parameter overloads
            check("addScalar", expected { a, _ -> Vector3f(a).add(scalar) }, { dx, dy, dz -> addRange(selfX, selfY, selfZ, scalar, dx, dy, dz, 0, n) })
            check("subScalar", expected { a, _ -> Vector3f(a).sub(scalar) }, { dx, dy, dz -> subRange(selfX, selfY, selfZ, scalar, dx, dy, dz, 0, n) })

            val mulScalarExpected = FloatArray(n) { selfX[it] * scalar } to FloatArray(n) { selfY[it] * scalar } to FloatArray(n) { selfZ[it] * scalar }
            val dsx = FloatArray(n); val dsy = FloatArray(n); val dsz = FloatArray(n)
            mulRange(selfX, selfY, selfZ, scalar, dsx, dsy, dsz, 0, n)
            assertArraysClose(mulScalarExpected.first.first, dsx, 1e-5f, "mulScalar.x")
            assertArraysClose(mulScalarExpected.first.second, dsy, 1e-5f, "mulScalar.y")
            assertArraysClose(mulScalarExpected.second, dsz, 1e-5f, "mulScalar.z")

            // negate/absolute (no vector parameter)
            val negExpected = expected { a, _ -> Vector3f(a).negate() }
            check("negate", negExpected, { dx, dy, dz -> negateRange(selfX, selfY, selfZ, dx, dy, dz, 0, n) })
            val absExpected = expected { a, _ -> Vector3f(abs(a.x), abs(a.y), abs(a.z)) }
            check("absolute", absExpected, { dx, dy, dz -> absoluteRange(selfX, selfY, selfZ, dx, dy, dz, 0, n) })

            // normalize: same CSE-hoisted subexpression path in the vector loop and the tail
            val normalizeExpected = expected { a, _ -> Vector3f(a).normalize() }
            val nx = FloatArray(n); val ny = FloatArray(n); val nz = FloatArray(n)
            normalizeRange(selfX, selfY, selfZ, nx, ny, nz, 0, n)
            assertArraysClose(normalizeExpected.first, nx, 2e-6f, "normalize.x")
            assertArraysClose(normalizeExpected.second, ny, 2e-6f, "normalize.y")
            assertArraysClose(normalizeExpected.third, nz, 2e-6f, "normalize.z")
        }
    }

    @Test
    fun vec3ScalarResultOperationsMatchHeapPerLaneAcrossBoundaryLengths() {
        val random = Random(0x5ca1ab1e)
        for (n in boundaryLengths) {
            val selfX = randomFloats(random, n); val selfY = randomFloats(random, n); val selfZ = randomFloats(random, n)
            val otherX = randomFloats(random, n); val otherY = randomFloats(random, n); val otherZ = randomFloats(random, n)

            val dotExpected = FloatArray(n) { Vector3f(selfX[it], selfY[it], selfZ[it]).dot(Vector3f(otherX[it], otherY[it], otherZ[it])) }
            val dotOut = FloatArray(n)
            dotRange(selfX, selfY, selfZ, otherX, otherY, otherZ, dotOut, 0, n)
            assertArraysClose(dotExpected, dotOut, 2e-4f, "dot")

            val lengthSquaredExpected = FloatArray(n) { Vector3f(selfX[it], selfY[it], selfZ[it]).lengthSquared() }
            val lengthSquaredOut = FloatArray(n)
            lengthSquaredRange(selfX, selfY, selfZ, lengthSquaredOut, 0, n)
            assertArraysClose(lengthSquaredExpected, lengthSquaredOut, 1e-4f, "lengthSquared")

            val distanceSquaredExpected = FloatArray(n) { Vector3f(selfX[it], selfY[it], selfZ[it]).distanceSquared(Vector3f(otherX[it], otherY[it], otherZ[it])) }
            val distanceSquaredOut = FloatArray(n)
            distanceSquaredRange(selfX, selfY, selfZ, otherX, otherY, otherZ, distanceSquaredOut, 0, n)
            assertArraysClose(distanceSquaredExpected, distanceSquaredOut, 2e-4f, "distanceSquared")
        }
    }

    @Test
    fun destinationAliasedWithSelfArraysIsSafe() {
        val n = 2 * lanes + 1
        val random = Random(0x0a11a5ed)
        val x = randomFloats(random, n); val y = randomFloats(random, n); val z = randomFloats(random, n)
        val ox = randomFloats(random, n); val oy = randomFloats(random, n); val oz = randomFloats(random, n)
        val expectedX = FloatArray(n) { x[it] + ox[it] }
        val expectedY = FloatArray(n) { y[it] + oy[it] }
        val expectedZ = FloatArray(n) { z[it] + oz[it] }

        addRange(x, y, z, ox, oy, oz, x, y, z, 0, n) // dest === self

        assertArraysClose(expectedX, x, 1e-5f, "aliased.x")
        assertArraysClose(expectedY, y, 1e-5f, "aliased.y")
        assertArraysClose(expectedZ, z, 1e-5f, "aliased.z")
    }

    @Test
    fun vec2AndVec4OperationsMatchHeapPerLane() {
        val random = Random(0xface)
        for (n in boundaryLengths) {
            val ax = randomFloats(random, n); val ay = randomFloats(random, n)
            val bx = randomFloats(random, n); val by = randomFloats(random, n)
            val dotExpected2 = FloatArray(n) { Vector2f(ax[it], ay[it]).dot(Vector2f(bx[it], by[it])) }
            val dotOut2 = FloatArray(n)
            dotRange(ax, ay, bx, by, dotOut2, 0, n)
            assertArraysClose(dotExpected2, dotOut2, 2e-4f, "vec2.dot")

            val addExpectedX = FloatArray(n) { ax[it] + bx[it] }
            val addExpectedY = FloatArray(n) { ay[it] + by[it] }
            val dx2 = FloatArray(n); val dy2 = FloatArray(n)
            addRange(ax, ay, bx, by, dx2, dy2, 0, n)
            assertArraysClose(addExpectedX, dx2, 1e-5f, "vec2.add.x")
            assertArraysClose(addExpectedY, dy2, 1e-5f, "vec2.add.y")

            val w1 = randomFloats(random, n)
            val z1 = randomFloats(random, n)
            val normalizeExpectedX = FloatArray(n); val normalizeExpectedY = FloatArray(n)
            val normalizeExpectedZ = FloatArray(n); val normalizeExpectedW = FloatArray(n)
            for (i in 0 until n) {
                val v = Vector4f(ax[i], ay[i], z1[i], w1[i]).normalize()
                normalizeExpectedX[i] = v.x; normalizeExpectedY[i] = v.y; normalizeExpectedZ[i] = v.z; normalizeExpectedW[i] = v.w
            }
            val nx4 = FloatArray(n); val ny4 = FloatArray(n); val nz4 = FloatArray(n); val nw4 = FloatArray(n)
            normalizeRange(ax, ay, z1, w1, nx4, ny4, nz4, nw4, 0, n)
            assertArraysClose(normalizeExpectedX, nx4, 2e-6f, "vec4.normalize.x")
            assertArraysClose(normalizeExpectedY, ny4, 2e-6f, "vec4.normalize.y")
            assertArraysClose(normalizeExpectedZ, nz4, 2e-6f, "vec4.normalize.z")
            assertArraysClose(normalizeExpectedW, nw4, 2e-6f, "vec4.normalize.w")
        }
    }
}
