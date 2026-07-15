package afield.stdlib.linalg.bench

import afield.stdlib.linalg.Vector3d
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.arena.FrameArena
import afield.stdlib.linalg.storage.SegmentVec3Buffer
import afield.stdlib.linalg.storage.SegmentVec3f
import afield.stdlib.linalg.storage.Vec3StorageLayout
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
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * One vector kernel — add, scalar-multiply, cross, dot, normalize — lowered onto every storage
 * backend, so the numbers are comparable. Run twice to compare arena validation profiles:
 *
 *   ./gradlew :tools:linalg-bench:jmh
 *   ./gradlew :tools:linalg-bench:jmh -PlinalgArenaChecks=false
 *
 * `arenaChecksCompiledIn` reports which profile a given run measured.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
open class VectorBenchmark {
    private val b = Vector3f(4f, 5f, 6f)
    private val a = Vector3f(1f, 2f, 3f)
    private val cf = Vector3f()
    private val df = Vector3f()

    private val bd = Vector3d(4.0, 5.0, 6.0)
    private val ad = Vector3d(1.0, 2.0, 3.0)
    private val cd = Vector3d()
    private val dd = Vector3d()

    private lateinit var arena: FrameArena

    private lateinit var packed: SegmentVec3Buffer
    private lateinit var aligned: SegmentVec3Buffer

    private var tick = 0

    /** A tiny per-invocation perturbation so nothing folds to a loop-invariant constant. */
    private fun nextSeedF(): Float = 1f + (tick++ and 1023) * 1e-3f
    private fun nextSeedD(): Double = 1.0 + (tick++ and 1023) * 1e-3

    @Setup(Level.Trial)
    fun setup() {
        arena = FrameArena(256)
        packed = SegmentVec3Buffer.of(ByteBuffer.allocateDirect(4 * 16), 4, Vec3StorageLayout.PACKED_12)
        aligned = SegmentVec3Buffer.of(ByteBuffer.allocateDirect(4 * 16), 4, Vec3StorageLayout.ALIGNED_16)
        for (buffer in listOf(packed, aligned)) with(buffer) {
            buffer[0].set(1f, 2f, 3f)
            buffer[1].set(4f, 5f, 6f)
        }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        arena.close()
    }

    /** Reports whether this run's classes have arena validation compiled in. */
    @Benchmark
    fun arenaChecksCompiledIn(): Boolean = FrameArena.checksCompiledIn

    @Benchmark
    fun heapFloatReusedDestinations(bh: Blackhole) {
        a.x = nextSeedF()
        a.add(b, cf); cf.mul(SCALE, cf)
        a.cross(b, df)
        cf.normalize(cf)
        bh.consume(cf.dot(b)); bh.consume(df.x)
    }

    @Benchmark
    fun heapFloatAllocatePerOp(bh: Blackhole) {
        a.x = nextSeedF()
        val c = a.add(b, Vector3f()).mul(SCALE, Vector3f())
        val d = a.cross(b, Vector3f())
        val cn = c.normalize(Vector3f())
        bh.consume(cn.dot(b)); bh.consume(d.x)
    }

    @Benchmark
    fun heapDoubleReusedDestinations(bh: Blackhole) {
        ad.x = nextSeedD()
        ad.add(bd, cd); cd.mul(SCALE_D, cd)
        ad.cross(bd, dd)
        cd.normalize(cd)
        bh.consume(cd.dot(bd)); bh.consume(dd.x)
    }

    @Benchmark
    fun arenaScratchPerInvocation(bh: Blackhole) {
        val seed = nextSeedF()
        arena.scoped {
            val a = vec3(seed, 2f, 3f)
            val b = vec3(4f, 5f, 6f)
            val c = vec3()
            val d = vec3()
            a.add(b, c); c.mul(SCALE, c)
            a.cross(b, d)
            c.normalize(c)
            bh.consume(c.dot(b)); bh.consume(d.x)
        }
    }

    /** Diagnostic: scope + four allocations + two initializing sets, no vector ops. Subtract from
     *  arenaScratchPerInvocation to attribute cost between allocation/scope and the arithmetic. */
    @Benchmark
    fun arenaAllocateOnly(bh: Blackhole) {
        val seed = nextSeedF()
        arena.scoped {
            val a = vec3(seed, 2f, 3f)
            val b = vec3(4f, 5f, 6f)
            val c = vec3()
            val d = vec3()
            bh.consume(a.x); bh.consume(b.x); bh.consume(c.x); bh.consume(d.x)
        }
    }

    @Benchmark
    fun segmentPacked12(bh: Blackhole) = segmentKernel(packed, bh)

    @Benchmark
    fun segmentAligned16(bh: Blackhole) = segmentKernel(aligned, bh)

    private fun segmentKernel(buffer: SegmentVec3Buffer, bh: Blackhole) {
        val seed = nextSeedF()
        with(buffer) {
            val a: SegmentVec3f = buffer[0]
            val b: SegmentVec3f = buffer[1]
            val c: SegmentVec3f = buffer[2]
            val d: SegmentVec3f = buffer[3]
            a.x = seed
            a.add(b, c); c.mul(SCALE, c)
            a.cross(b, d)
            c.normalize(c)
            bh.consume(c.dot(b)); bh.consume(d.x)
        }
    }

    @Benchmark
    fun constructRawConstructor(bh: Blackhole) {
        val i = tick++
        bh.consume(Vector3f(i.toFloat(), (i + 1).toFloat(), (i + 2).toFloat()))
    }

    @Benchmark
    fun constructCollectionLiteral(bh: Blackhole) {
        val i = tick++
        val v: Vector3f = [i, i + 1, i + 2]
        bh.consume(v)
    }

    private companion object {
        const val SCALE = 1.0001f
        const val SCALE_D = 1.0001
    }
}
