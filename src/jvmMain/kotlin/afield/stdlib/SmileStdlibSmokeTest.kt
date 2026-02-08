package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Focused smoke tests for Smile-backed stdlib helpers.
 *
 * This follows the repo's lightweight "runnable object" style instead of JUnit.
 */
object SmileStdlibSmokeTest {

    fun runTests(): Boolean {
        return try {
            println("Running Smile stdlib smoke tests...")
            testCubicSplineHitsKnots()
            testGaussianProcessFinite()
            testVec3ExactAgainstBruteforce()
            testVec2ApproxShapeAndFinite()
            testRadiusSearchRespectsThreshold()
            testDuplicatePolicy()
            println("✅ Smile stdlib smoke tests passed")
            true
        } catch (t: Throwable) {
            println("❌ Smile stdlib smoke tests failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "Smile stdlib smoke tests failed" }
    }

    private fun testCubicSplineHitsKnots() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0)
        val ys = doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.0)
        val spline = smileCubicSpline(xs, ys)
        for (i in xs.indices) {
            assertClose("spline knot $i", spline.predict(xs[i]), ys[i], 1e-9)
        }
    }

    private fun testGaussianProcessFinite() {
        val xs = DoubleArray(24) { i -> i.toDouble() / 23.0 }
        val ys = DoubleArray(24) { i ->
            val x = xs[i]
            sin(2.0 * PI * x) + 0.05 * cos(6.0 * PI * x)
        }
        val gp = smileGaussianProcess(
            xs,
            ys,
            SmileGpOptions(
                lengthScale = 0.2,
                noise = 1e-5,
                normalize = true,
                tol = 1e-4,
                maxIter = 300
            )
        )
        val queries = DoubleArray(80) { i -> i.toDouble() / 79.0 }
        val predicted = gp.predict(queries)
        require(predicted.all { it.isFinite() }) { "GP predictions contained non-finite values" }
    }

    private fun testVec3ExactAgainstBruteforce() {
        val rnd = Random(42)
        val points = List(256) {
            vec(
                rnd.nextDouble(-10.0, 10.0),
                rnd.nextDouble(-10.0, 10.0),
                rnd.nextDouble(-10.0, 10.0)
            )
        }
        val index = vec3NN(points, mode = VecNNMode.EXACT)

        repeat(20) {
            val q = vec(
                rnd.nextDouble(-10.0, 10.0),
                rnd.nextDouble(-10.0, 10.0),
                rnd.nextDouble(-10.0, 10.0)
            )
            val brute = points.indices.minByOrNull { i -> sqDist(points[i], q) } ?: error("No brute-force result")
            val bruteDist = sqrt(sqDist(points[brute], q))
            val nn = index.nearest(q)
            assertClose("Vec3 exact NN distance", nn.distance, bruteDist, 1e-9)
        }
    }

    private fun testVec2ApproxShapeAndFinite() {
        val rnd = Random(7)
        val points = List(300) {
            vec(
                rnd.nextDouble(-20.0, 20.0),
                rnd.nextDouble(-20.0, 20.0)
            )
        }
        val index = vec2NN(
            points,
            mode = VecNNMode.APPROXIMATE,
            options = VecNNBuildOptions(
                lshBucketWidth = 1.0,
                lshProjectionCount = 10,
                lshUniversalHashSize = 20011
            )
        )

        val q = vec(1.25, -2.5)
        val k = 12
        val neighbors = index.kNearest(q, k)
        require(neighbors.size == k) { "Expected $k neighbors, got ${neighbors.size}" }
        require(neighbors.map { it.index }.toSet().size == neighbors.size) { "kNearest returned duplicate indices" }
        require(neighbors.all { it.distance.isFinite() && it.distance >= 0.0 }) {
            "kNearest returned invalid distances"
        }
    }

    private fun testRadiusSearchRespectsThreshold() {
        val grid = buildList {
            for (y in -5..5) {
                for (x in -5..5) {
                    add(vec(x, y))
                }
            }
        }
        val index = vec2NN(grid, mode = VecNNMode.EXACT)
        val radius = 2.1
        val neighbors = index.withinRadius(vec(0, 0), radius)
        require(neighbors.isNotEmpty()) { "withinRadius unexpectedly returned no neighbors" }
        require(neighbors.all { it.distance <= radius + 1e-12 }) {
            "withinRadius returned a point outside the requested radius"
        }
    }

    private fun testDuplicatePolicy() {
        val points = listOf(vec(0, 0), vec(1, 1), vec(0, 0))
        var rejected = false
        try {
            vec2NN(points, options = VecNNBuildOptions(allowDuplicatePoints = false))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        require(rejected) { "Expected duplicate-policy rejection when allowDuplicatePoints=false" }

        val accepted = vec2NN(points, options = VecNNBuildOptions(allowDuplicatePoints = true))
        require(accepted.size == 3) { "Duplicate-allowed index should keep all entries" }
    }

    private fun sqDist(a: Vec2, b: Vec2): Double {
        val dx = a.x.toDouble() - b.x.toDouble()
        val dy = a.y.toDouble() - b.y.toDouble()
        return dx * dx + dy * dy
    }

    private fun sqDist(a: Vec3, b: Vec3): Double {
        val dx = a.x.toDouble() - b.x.toDouble()
        val dy = a.y.toDouble() - b.y.toDouble()
        val dz = a.z.toDouble() - b.z.toDouble()
        return dx * dx + dy * dy + dz * dz
    }

    private fun assertClose(label: String, got: Double, expected: Double, eps: Double) {
        require(abs(got - expected) <= eps) {
            "$label mismatch: got=$got expected=$expected eps=$eps"
        }
    }
}
