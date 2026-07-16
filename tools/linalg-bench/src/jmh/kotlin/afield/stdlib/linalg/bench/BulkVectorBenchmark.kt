package afield.stdlib.linalg.bench

import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.bulk.addRange
import afield.stdlib.linalg.bulk.crossRange
import afield.stdlib.linalg.bulk.dotRange
import afield.stdlib.linalg.bulk.normalizeRange
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The same "add, cross, normalize, dot" kernel [VectorBenchmark] runs per-instance, run here over
 * N Vec3 elements three ways: the generated bulk/SIMD kernels, a hand-written scalar loop (the
 * actual competitor -- code not yet using SIMD), and a loop calling the existing generated heap
 * `Vector3f` API per element. This is the "is this worth adopting" comparison, not just "is SIMD
 * faster than nothing": if bulk doesn't clearly beat the scalar loop, that's a legitimate v1
 * finding, not a benchmark bug.
 *
 * Run alongside the rest of the suite with `./gradlew :tools:linalg-bench:jmh`, or add
 * `includes.set(listOf("BulkVectorBenchmark"))` to the `jmh {}` block in this module's
 * build.gradle.kts to run just this class.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
open class BulkVectorBenchmark {
    @Param("256", "4096", "65536")
    var n: Int = 0

    private lateinit var ax: FloatArray
    private lateinit var ay: FloatArray
    private lateinit var az: FloatArray
    private lateinit var bx: FloatArray
    private lateinit var by: FloatArray
    private lateinit var bz: FloatArray
    private lateinit var cx: FloatArray
    private lateinit var cy: FloatArray
    private lateinit var cz: FloatArray
    private lateinit var dx: FloatArray
    private lateinit var dy: FloatArray
    private lateinit var dz: FloatArray
    private lateinit var dotOut: FloatArray

    @Setup(Level.Trial)
    fun setup() {
        val random = Random(0x7ec7)
        ax = FloatArray(n) { random.nextFloat() * 20f - 10f }
        ay = FloatArray(n) { random.nextFloat() * 20f - 10f }
        az = FloatArray(n) { random.nextFloat() * 20f - 10f }
        bx = FloatArray(n) { random.nextFloat() * 20f - 10f }
        by = FloatArray(n) { random.nextFloat() * 20f - 10f }
        bz = FloatArray(n) { random.nextFloat() * 20f - 10f }
        cx = FloatArray(n); cy = FloatArray(n); cz = FloatArray(n)
        dx = FloatArray(n); dy = FloatArray(n); dz = FloatArray(n)
        dotOut = FloatArray(n)
    }

    @Benchmark
    fun bulkGeneratedKernel(bh: Blackhole) {
        addRange(ax, ay, az, bx, by, bz, cx, cy, cz, 0, n)
        crossRange(ax, ay, az, bx, by, bz, dx, dy, dz, 0, n)
        normalizeRange(cx, cy, cz, cx, cy, cz, 0, n)
        dotRange(cx, cy, cz, bx, by, bz, dotOut, 0, n)
        bh.consume(dotOut[n - 1]); bh.consume(dx[n - 1])
    }

    @Benchmark
    fun handWrittenScalarLoop(bh: Blackhole) {
        for (i in 0 until n) {
            cx[i] = ax[i] + bx[i]; cy[i] = ay[i] + by[i]; cz[i] = az[i] + bz[i]
            dx[i] = ay[i] * bz[i] - az[i] * by[i]
            dy[i] = az[i] * bx[i] - ax[i] * bz[i]
            dz[i] = ax[i] * by[i] - ay[i] * bx[i]
            val inv = 1f / sqrt(cx[i] * cx[i] + cy[i] * cy[i] + cz[i] * cz[i])
            cx[i] *= inv; cy[i] *= inv; cz[i] *= inv
            dotOut[i] = cx[i] * bx[i] + cy[i] * by[i] + cz[i] * bz[i]
        }
        bh.consume(dotOut[n - 1]); bh.consume(dx[n - 1])
    }

    @Benchmark
    fun heapVector3fPerElementLoop(bh: Blackhole) {
        val c = Vector3f(); val d = Vector3f()
        for (i in 0 until n) {
            val a = Vector3f(ax[i], ay[i], az[i])
            val b = Vector3f(bx[i], by[i], bz[i])
            a.add(b, c)
            a.cross(b, d)
            c.normalize(c)
            dotOut[i] = c.dot(b)
            cx[i] = c.x; cy[i] = c.y; cz[i] = c.z
            dx[i] = d.x; dy[i] = d.y; dz[i] = d.z
        }
        bh.consume(dotOut[n - 1]); bh.consume(dx[n - 1])
    }
}
