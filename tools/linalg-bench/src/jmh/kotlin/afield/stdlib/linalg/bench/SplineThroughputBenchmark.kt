package afield.stdlib.linalg.bench

import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.arena.FrameArena
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * The benchmark that actually exercises what the arena is *for*: producing a million short-lived
 * Vector3 per frame, frame after frame, where the high-water mark never grows.
 *
 * One "op" is one frame: evaluate a cubic Bezier at [POINTS_PER_FRAME] parameter values. The three
 * strategies differ only in where those vectors live:
 *
 *  - heapFreshPerFrame  — a new Vector3f per sample, stored into a persistent array so escape
 *                         analysis cannot elide it. This is the ergonomic, obvious code, and it
 *                         hands the GC a million dead objects every frame.
 *  - heapPooledPerFrame — a persistent array of Vector3f allocated once and mutated in place. GC
 *                         free, but you manage the pool by hand and the objects live forever.
 *  - arenaResetPerFrame — a million arena vectors per frame inside one scope; the scope reset just
 *                         rewinds the cursor. GC free, no manual pool, native memory reused.
 *
 * Run with the allocation profiler — the point is `gc.alloc.rate.norm` (bytes/op), not just time:
 *   java -jar build/libs/linalg-bench-jmh.jar Spline -prof gc
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
open class SplineThroughputBenchmark {
    // Control points, pre-extracted to scalars so the hot loop is pure arithmetic + storage.
    private val p0x = 0.0f; private val p0y = 0.0f; private val p0z = 0.0f
    private val p1x = 1.0f; private val p1y = 2.0f; private val p1z = 0.5f
    private val p2x = 2.0f; private val p2y = -1.0f; private val p2z = 1.5f
    private val p3x = 3.0f; private val p3y = 0.0f; private val p3z = 0.0f
    private val inv = 1.0f / (POINTS_PER_FRAME - 1)

    private lateinit var arena: FrameArena
    private lateinit var freshOutput: Array<Vector3f?>
    private lateinit var pooledOutput: Array<Vector3f>

    @Setup(Level.Trial)
    fun setup() {
        // Pre-sized so the arena never grows during the run: the whole point is a flat water mark.
        arena = FrameArena(POINTS_PER_FRAME * 16 + 64)
        freshOutput = arrayOfNulls(POINTS_PER_FRAME)
        pooledOutput = Array(POINTS_PER_FRAME) { Vector3f() }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        arena.close()
    }

    @Benchmark
    fun heapFreshPerFrame(bh: Blackhole) {
        val out = freshOutput
        for (i in 0 until POINTS_PER_FRAME) {
            val t = i * inv
            val mt = 1.0f - t
            val w0 = mt * mt * mt
            val w1 = 3.0f * mt * mt * t
            val w2 = 3.0f * mt * t * t
            val w3 = t * t * t
            out[i] = Vector3f(
                p0x * w0 + p1x * w1 + p2x * w2 + p3x * w3,
                p0y * w0 + p1y * w1 + p2y * w2 + p3y * w3,
                p0z * w0 + p1z * w1 + p2z * w2 + p3z * w3,
            )
        }
        bh.consume(out)
    }

    @Benchmark
    fun heapPooledPerFrame(bh: Blackhole) {
        val out = pooledOutput
        for (i in 0 until POINTS_PER_FRAME) {
            val t = i * inv
            val mt = 1.0f - t
            val w0 = mt * mt * mt
            val w1 = 3.0f * mt * mt * t
            val w2 = 3.0f * mt * t * t
            val w3 = t * t * t
            out[i].set(
                p0x * w0 + p1x * w1 + p2x * w2 + p3x * w3,
                p0y * w0 + p1y * w1 + p2y * w2 + p3y * w3,
                p0z * w0 + p1z * w1 + p2z * w2 + p3z * w3,
            )
        }
        bh.consume(out)
    }

    @Benchmark
    fun arenaResetPerFrame(bh: Blackhole) {
        var ax = 0.0f
        var ay = 0.0f
        var az = 0.0f
        arena.scoped {
            for (i in 0 until POINTS_PER_FRAME) {
                val t = i * inv
                val mt = 1.0f - t
                val w0 = mt * mt * mt
                val w1 = 3.0f * mt * mt * t
                val w2 = 3.0f * mt * t * t
                val w3 = t * t * t
                val p = vec3(
                    p0x * w0 + p1x * w1 + p2x * w2 + p3x * w3,
                    p0y * w0 + p1y * w1 + p2y * w2 + p3y * w3,
                    p0z * w0 + p1z * w1 + p2z * w2 + p3z * w3,
                )
                ax += p.x; ay += p.y; az += p.z
            }
        } // scope reset: cursor rewinds to zero, native memory retained, nothing to collect
        bh.consume(ax); bh.consume(ay); bh.consume(az)
    }

    private companion object {
        const val POINTS_PER_FRAME = 1_000_000
    }
}
