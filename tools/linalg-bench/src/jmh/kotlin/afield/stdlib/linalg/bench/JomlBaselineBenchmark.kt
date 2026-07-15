package afield.stdlib.linalg.bench

import org.joml.Vector3f as JomlVector3f
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Baseline: the identical heap kernel on stock JOML `Vector3f`. This exists only to check the
 * assumption the whole ditch-JOML decision rests on — that Field's generated heap vector performs
 * like JOML's. Compare `jomlReusedDestinations` against
 * `VectorBenchmark.heapFloatReusedDestinations`, and the alloc-per-op pair, in the same run.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
open class JomlBaselineBenchmark {
    private val a = JomlVector3f(1f, 2f, 3f)
    private val b = JomlVector3f(4f, 5f, 6f)
    private val c = JomlVector3f()
    private val d = JomlVector3f()
    private var tick = 0

    private fun nextSeed(): Float = 1f + (tick++ and 1023) * 1e-3f

    @Benchmark
    fun jomlReusedDestinations(bh: Blackhole) {
        a.x = nextSeed()
        a.add(b, c); c.mul(SCALE, c)
        a.cross(b, d)
        c.normalize(c)
        bh.consume(c.dot(b)); bh.consume(d.x)
    }

    @Benchmark
    fun jomlAllocatePerOp(bh: Blackhole) {
        a.x = nextSeed()
        val c = a.add(b, JomlVector3f()).mul(SCALE, JomlVector3f())
        val d = a.cross(b, JomlVector3f())
        val cn = c.normalize(JomlVector3f())
        bh.consume(cn.dot(b)); bh.consume(d.x)
    }

    private companion object {
        const val SCALE = 1.0001f
    }
}
