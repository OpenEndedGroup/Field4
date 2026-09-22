package afield.examples.four

import afield.four.Renderer
import afield.four.util.GpuProbe
import afield.four.util.Options
import afield.four.util.Window
import afield.four.util.gpuProbe
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.gpu-probe.pressure-chain
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.GpuPressureProjectionProbe
tags: [smoke, four, gpu, compute, probe, fluid, pressure, chain]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "gpu pressure projection chain PASSED"
*/
/**
 * That a **chain** of compute dispatches — divergence, then Jacobi pressure, then project — leaves
 * a velocity field that is measurably divergence-free, and that it gets more so with iterations.
 *
 * ### Why a chain and not a dispatch
 *
 * Every GPU probe in this repository grades a single dispatch. Real GPU work is a pipeline: the
 * workspace document this facility was built for chains advect -> divergence -> pressure x 24 ->
 * project, and the bugs are not inside any one pass, they are *between* passes — a missing barrier
 * so pass N reads what pass N-1 has not finished writing, a ping-pong that reads the buffer it just
 * wrote, a pass silently ordered before the one it depends on. None of those are visible from
 * inside one dispatch, and a picture of a fluid cannot tell you that the divergence fell by 40x
 * rather than by 1.4x.
 *
 * So this is a probe over eighteen dispatches across three shaders, and what it asserts is a
 * theorem rather than a threshold.
 *
 * ### What it asserts
 *
 * - **The divergence pass agrees with a closed-form oracle.** The same finite-difference stencil is
 *   written twice — once in GLSL, once in Kotlin over the uploaded fixture — and compared at every
 *   one of 4,096 cells. Not a sample: an off-by-one at a boundary is exactly what a sample misses.
 * - **Helmholtz.** Projecting a divergent field against the pressure solved from its own divergence
 *   must leave a field whose divergence is near zero. The "after" number is recomputed **on the CPU
 *   from the read-back projected field**, so the GPU cannot grade its own homework; the "before"
 *   number comes from the GPU's own divergence buffer, which the oracle above has already vouched
 *   for.
 * - **The input is genuinely divergent.** Without this the whole test passes on a field of zeros,
 *   which is the single most likely way for a fluid probe to be meaningless.
 * - **The projection actually moved the field.** A `project` pass that wrote its input unchanged
 *   would also, trivially, not increase the divergence.
 * - **More iterations help, monotonically.** The chain is run for 1, 2, 4, 8 and 16 frames with the
 *   pressure re-zeroed each time, which is 16, 32, 64, 128 and 256 Jacobi sweeps. A solver whose
 *   residual does not fall with iteration count is not a solver — and a *constant* reduction across
 *   the sweep is the signature of a barrier that is not there, because the extra sweeps then all
 *   read the same stale pressure.
 * - **The Poisson residual.** `max |Lap p - div|` against `max |div|`, recomputed on the CPU, which
 *   grades the Jacobi stage directly rather than through the projection.
 * - **Nothing is NaN and nothing is still sentinel.** The output is prefilled with
 *   [GpuProbe.SENTINEL] before every sample, so a chain that silently stopped running cannot report
 *   agreement.
 *
 * ### The discretisation, and why this one
 *
 * A MAC (staggered) arrangement: `u` lives on the left x-face of each cell and `v` on the bottom
 * y-face, both stored in the `x` and `y` of one `vec4` per cell. Divergence is a forward difference
 * and the pressure gradient is a backward difference, which makes them exact adjoints, so `div .
 * grad` is precisely the 5-point Laplacian the Jacobi stage inverts. The collocated alternative —
 * central differences for both on a cell-centred grid — has a checkerboard null space that the
 * pressure solve cannot see, and a probe built on it would report a small divergence for a field
 * carrying a large invisible one. The walls are solid: face velocities on the domain boundary are
 * zero, and pressure is mirrored outside it (Neumann), which is the boundary pair that makes the
 * projection well posed.
 *
 * ### Running it
 *
 * ```
 * tools/run_field_tests.py --id four.gpu-probe.pressure-chain
 * ```
 *
 * Needs a GPU and a window: `afield.four` has no headless mode, so a compute-only dispatch still
 * needs a `Window` for the device to exist. It also needs an awake display.
 */
object GpuPressureProjectionProbe {

    /** Cells per side. 64 x 64 = 4,096 cells, one workgroup of 64 per 64 cells. */
    private const val N = 64

    private const val CELLS = N * N

    /** Floats in a cell-per-`vec4` field. */
    private const val FIELD_FLOATS = CELLS * 4

    /** Jacobi sweeps per frame. One stage, this many dispatches, each followed by a barrier. */
    private const val JACOBI = 16

    /** Frame counts sampled, so the effective sweep is this times [JACOBI]. */
    private val FRAME_SWEEP = listOf(1, 2, 4, 8, 16)

    /** Workgroups needed to cover the grid at `local_size_x = 64`. */
    private const val GROUPS = CELLS / 64

    // ------------------------------------------------------------------------------------------
    // The three shaders. One `Field` type, reused, because every field here is a vec4 per cell.
    // ------------------------------------------------------------------------------------------

    /**
     * `#version` has to be the first thing in the source, so this literal starts on the opening
     * line rather than after a newline. A shader whose `#version` is not first reports a wall of
     * errors about everything else, which masks the real one.
     */
    private const val PREAMBLE = """#version 450
#extension GL_EXT_buffer_reference : require
layout(std430, buffer_reference) buffer Field { vec4 v[]; };
layout(local_size_x = 64) in;
"""

    /**
     * `div[i,j] = (u[i+1,j] - u[i,j]) + (v[i,j+1] - v[i,j])`, with solid walls.
     *
     * Forward differences over face velocities. A face on the domain boundary reads zero rather
     * than wrapping or clamping: a wall is a wall, and clamping would make the outermost ring
     * report the divergence of a field that flows through it.
     */
    private val DIVERGENCE_GLSL = PREAMBLE + """
layout(push_constant) uniform Push {
    Field velocity;
    Field divergence;
    uint  side;
} pc;

void main() {
    uint id = gl_GlobalInvocationID.x;
    uint n = pc.side;
    if (id >= n * n) return;
    uint i = id % n;
    uint j = id / n;

    float uL = (i == 0u)          ? 0.0 : pc.velocity.v[id].x;
    float uR = (i + 1u >= n)      ? 0.0 : pc.velocity.v[id + 1u].x;
    float vB = (j == 0u)          ? 0.0 : pc.velocity.v[id].y;
    float vT = (j + 1u >= n)      ? 0.0 : pc.velocity.v[id + n].y;

    pc.divergence.v[id] = vec4((uR - uL) + (vT - vB), 0.0, 0.0, 0.0);
}
"""

    /**
     * One Jacobi sweep of `Lap p = div`, mirrored at the walls.
     *
     * `p = (pL + pR + pB + pT - div) / 4`. Mirroring a missing neighbour onto the centre cell is
     * the discrete Neumann condition `dp/dn = 0`, which is the one that pairs with zero face
     * velocity on the boundary.
     */
    private val JACOBI_GLSL = PREAMBLE + """
layout(push_constant) uniform Push {
    Field pressureIn;
    Field pressureOut;
    Field divergence;
    uint  side;
} pc;

void main() {
    uint id = gl_GlobalInvocationID.x;
    uint n = pc.side;
    if (id >= n * n) return;
    uint i = id % n;
    uint j = id / n;

    float pL = pc.pressureIn.v[(i == 0u)     ? id : id - 1u].x;
    float pR = pc.pressureIn.v[(i + 1u >= n) ? id : id + 1u].x;
    float pB = pc.pressureIn.v[(j == 0u)     ? id : id - n ].x;
    float pT = pc.pressureIn.v[(j + 1u >= n) ? id : id + n ].x;

    pc.pressureOut.v[id] =
        vec4((pL + pR + pB + pT - pc.divergence.v[id].x) * 0.25, 0.0, 0.0, 0.0);
}
"""

    /**
     * `u -= dp/dx`, `v -= dp/dy` — backward differences, the adjoint of the divergence stencil.
     *
     * Writes to a *separate* output field rather than in place, so the input fixture stays the
     * thing the CPU oracle compares against and a sample can be repeated without re-uploading.
     */
    private val PROJECT_GLSL = PREAMBLE + """
layout(push_constant) uniform Push {
    Field velocity;
    Field pressure;
    Field velocityOut;
    uint  side;
} pc;

void main() {
    uint id = gl_GlobalInvocationID.x;
    uint n = pc.side;
    if (id >= n * n) return;
    uint i = id % n;
    uint j = id / n;

    float pC = pc.pressure.v[id].x;
    float pL = pc.pressure.v[(i == 0u) ? id : id - 1u].x;
    float pB = pc.pressure.v[(j == 0u) ? id : id - n ].x;

    float u = (i == 0u) ? 0.0 : pc.velocity.v[id].x - (pC - pL);
    float v = (j == 0u) ? 0.0 : pc.velocity.v[id].y - (pC - pB);

    pc.velocityOut.v[id] = vec4(u, v, 0.0, 0.0);
}
"""

    // ------------------------------------------------------------------------------------------
    // Fixture and oracle, in Kotlin
    // ------------------------------------------------------------------------------------------

    /**
     * A multi-scale divergent field: two low modes plus a high one on each component.
     *
     * Multi-scale on purpose. Jacobi kills high-frequency error in a handful of sweeps and low
     * frequency in hundreds, so a field with only one scale in it would report either a
     * spectacular reduction or a negligible one, and neither would say anything about the chain. A
     * superposition makes the sweep in [FRAME_SWEEP] show the shape of the convergence rather than
     * one point on it.
     */
    private fun seedField(): FloatArray {
        val out = FloatArray(FIELD_FLOATS)
        for (j in 0 until N) for (i in 0 until N) {
            val x = (i + 0.5) / N
            val y = (j + 0.5) / N
            val u = sin(2 * PI * x * 3) * cos(2 * PI * y * 2) + 0.5 * sin(2 * PI * x * 9)
            val v = cos(2 * PI * x * 2) * sin(2 * PI * y * 3) + 0.5 * sin(2 * PI * y * 7)
            val o = (j * N + i) * 4
            out[o] = u.toFloat()
            out[o + 1] = v.toFloat()
        }
        return out
    }

    /** [DIVERGENCE_GLSL], in Kotlin, over a `vec4`-per-cell field. The oracle. */
    private fun divergenceOf(field: FloatArray): FloatArray {
        val out = FloatArray(CELLS)
        for (j in 0 until N) for (i in 0 until N) {
            val id = j * N + i
            val uL = if (i == 0) 0f else field[id * 4]
            val uR = if (i + 1 >= N) 0f else field[(id + 1) * 4]
            val vB = if (j == 0) 0f else field[id * 4 + 1]
            val vT = if (j + 1 >= N) 0f else field[(id + N) * 4 + 1]
            out[id] = (uR - uL) + (vT - vB)
        }
        return out
    }

    /** `max |Lap p - div|`, with the same mirrored walls the Jacobi stage uses. */
    private fun poissonResidual(pressure: FloatArray, divergence: FloatArray): Float {
        var worst = 0f
        for (j in 0 until N) for (i in 0 until N) {
            val id = j * N + i
            val pC = pressure[id * 4]
            val pL = pressure[(if (i == 0) id else id - 1) * 4]
            val pR = pressure[(if (i + 1 >= N) id else id + 1) * 4]
            val pB = pressure[(if (j == 0) id else id - N) * 4]
            val pT = pressure[(if (j + 1 >= N) id else id + N) * 4]
            worst = maxOf(worst, abs((pL + pR + pB + pT - 4 * pC) - divergence[id]))
        }
        return worst
    }

    private fun maxAbs(a: FloatArray): Float {
        var m = 0f
        for (v in a) if (v.isFinite()) m = maxOf(m, abs(v))
        return m
    }

    // ------------------------------------------------------------------------------------------

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val window = Window("GPU Pressure Projection Probe")
        val renderer = Renderer(window)
        var failures = 0

        try {
            failures = gpuProbe(
                renderer,
                name = "pressure-chain",
                pump = { window.pollEvents(); renderer.render() },
            ) { probe ->
                run(probe)
            }
        } finally {
            renderer.cleanup()
            window.cleanup()
        }

        if (failures > 0) {
            println("gpu pressure projection chain FAILED ($failures)")
            exitProcess(1)
        }
        println("gpu pressure projection chain PASSED")
        exitProcess(0)
    }

    private fun run(probe: GpuProbe): Int {
        // --- fixtures. Every one of these is host-readable because `fixture` insists on it.
        val velocity = probe.fixture("velocity", FIELD_FLOATS)
        val velocityOut = probe.fixture("velocityOut", FIELD_FLOATS)
        val divergence = probe.fixture("divergence", FIELD_FLOATS)
        val pressureA = probe.fixture("pressureA", FIELD_FLOATS)
        val pressureB = probe.fixture("pressureB", FIELD_FLOATS)

        val seeded = seedField()
        probe.upload(velocity, seeded)
        probe.fill(pressureA, 0f)
        probe.fill(pressureB, 0f)

        // --- the chain. Three stages, three shader compiles, 1 + JACOBI + 1 dispatches.
        //
        // Stage order is registration order and dispatch order is insertion order, so this reads
        // as the pipeline it is. The ping-pong is explicit and visible: iteration `k` reads the
        // buffer iteration `k - 1` wrote, and because JACOBI is even the answer ends up back in
        // pressureA, which is what `project` binds.
        probe.stage("divergence", DIVERGENCE_GLSL).dispatch(groups = GROUPS) {
            bind("velocity") { velocity }
            bind("divergence") { divergence }
            bind("side") { N }
        }

        val jacobi = probe.stage("jacobi", JACOBI_GLSL)
        require(JACOBI % 2 == 0) { "JACOBI must be even so the result lands back in pressureA" }
        for (k in 0 until JACOBI) {
            val from = if (k % 2 == 0) pressureA else pressureB
            val to = if (k % 2 == 0) pressureB else pressureA
            jacobi.dispatch(groups = GROUPS, label = "sweep$k") {
                bind("pressureIn") { from }
                bind("pressureOut") { to }
                bind("divergence") { divergence }
                bind("side") { N }
            }
        }

        probe.stage("project", PROJECT_GLSL).dispatch(groups = GROUPS) {
            bind("velocity") { velocity }
            bind("pressure") { pressureA }
            bind("velocityOut") { velocityOut }
            bind("side") { N }
        }

        println(
            "--- chain: ${probe.stageList.size} stages, " +
                "${probe.stageList.sumOf { it.dispatchCount }} dispatches over $CELLS cells " +
                "(${N}x$N), $JACOBI Jacobi sweeps per frame"
        )

        // --- wait for the chain to exist at all. This is the wait that matters: a ComputeModule
        // whose shader has not finished compiling dispatches as a silent no-op, so the question is
        // "has the output been written", not "have four frames gone by".
        // The two graded outputs, and deliberately NOT the pressure pair.
        //
        // Arming `pressureA`/`pressureB` looks strictly better and is wrong, measured: it hung
        // for 1434 frames and then reported `9 of 16384 slots still hold the sentinel, from index
        // 908`. The Jacobi stencil is an average, and a constant is a fixed point of an average:
        // `(4 x -98765 - 0) / 4` is exactly `-98765`, so any interior cell whose four neighbours
        // are all still sentinel and whose divergence is exactly zero reproduces the sentinel
        // forever. A sentinel must not be a fixed point of the shader that is supposed to
        // overwrite it — witness the END of a chain, not an intermediate that is fed back into
        // itself.
        //
        // The Jacobi stage's execution is not left unwitnessed by this. A stage that never ran
        // leaves the pressure at zero, the projection returns its input, and three of the claims
        // below — the reduction, `extra sweeps helped` and `projection changed the field` — fail
        // together rather than quietly.
        probe.settle(velocityOut, divergence)
        val warmFrames = probe.framesToLand
        println("  cold start: landed after $warmFrames pumped frames (three shader compiles)")
        for (s in probe.stageList) {
            if (!s.valid) probe.fail("stage '${s.stageName}' never became valid")
        }

        // --- claim 1: the divergence pass, against the closed-form oracle, at every cell.
        val gpuDiv = probe.read(divergence)
        val cpuDiv = divergenceOf(seeded)
        var worstDivError = 0f
        var worstDivIndex = -1
        for (id in 0 until CELLS) {
            val e = abs(gpuDiv[id * 4] - cpuDiv[id])
            if (e > worstDivError) { worstDivError = e; worstDivIndex = id }
        }
        val maxDivBefore = maxAbs(cpuDiv)
        println(
            ("  divergence pass vs closed form: worst |error| %.3e at cell %d of %d; " +
                "field carries max |div| %.4f")
                .format(worstDivError, worstDivIndex, CELLS, maxDivBefore)
        )
        // Measured 1.192e-07 at cell 96, which is one ULP of a number of order 1 — the GLSL and
        // the Kotlin sum the same four terms in the same order, so the only difference available
        // is the compiler's contraction choice. 1e-6 is eight times the measurement, which leaves
        // room for a fused multiply-add on one side and not the other, and nothing else.
        probe.atMost(
            "divergence pass vs closed form", worstDivError, 1e-6f,
            buffer = "divergence", index = worstDivIndex
        )
        probe.allFinite("divergence pass", gpuDiv, buffer = "divergence")
        // The trivial-pass guard: a field of zeros would satisfy everything below. The fixture is
        // a fixed superposition computed on the CPU, so this is exactly reproducible — measured
        // 1.7649, and a floor of 1.5 fails the moment somebody edits `seedField` into something
        // flat rather than passing quietly on it.
        probe.atLeast("input field max |div|", maxDivBefore, 1.5f, buffer = "velocity")

        // --- claim 2: the sweep. Pressure re-zeroed each sample, so `frames x JACOBI` is the
        // whole iteration count and the curve is comparable point to point.
        println("  projected divergence against Jacobi sweeps (population: $CELLS cells each):")
        data class Sample(val frames: Int, val sweeps: Int, val after: Float, val ratio: Float)

        val samples = mutableListOf<Sample>()
        var lastProjected = FloatArray(0)
        for (frames in FRAME_SWEEP) {
            probe.idle()
            probe.fill(pressureA, 0f)
            probe.fill(pressureB, 0f)
            probe.arm(velocityOut, divergence)
            while (probe.stillWaiting()) probe.pumpFrames(1)
            val landed = probe.framesToLand
            if (frames > landed) probe.pumpFrames(frames - landed)
            val pumped = maxOf(frames, landed)
            probe.idle()

            val projected = probe.read(velocityOut)
            lastProjected = projected
            val after = maxAbs(divergenceOf(projected))
            val sweeps = pumped * JACOBI
            samples += Sample(pumped, sweeps, after, maxDivBefore / after)
            println(
                "    %2d frames = %4d sweeps : max |div| after %.5f   reduction %6.1fx".format(
                    pumped, sweeps, after, maxDivBefore / after
                )
            )
        }

        // Monotone, as a count rather than as a boolean, so a single non-monotone point is
        // distinguishable from a flat curve.
        var regressions = 0
        for (k in 1 until samples.size) {
            // 1.001 of slack: two adjacent points can agree to fp32 once converged.
            if (samples[k].after > samples[k - 1].after * 1.001f) regressions++
        }
        probe.exactly("sweep is monotone (non-monotone steps)", regressions, 0)
        val best = samples.last()
        val worst = samples.first()
        // Measured 2250.8x at 256 sweeps. Jacobi has no atomics and no race, the fixture is
        // fixed, and the frame count at this end of the sweep is exactly 16, so this number is
        // reproducible on a given GPU rather than approximately so. 1500 is a third below it; a
        // chain that lost a stage or a barrier reports single digits, not 1400.
        probe.atLeast(
            "reduction at ${best.sweeps} sweeps", best.ratio, 1500.0f, buffer = "velocityOut"
        )
        // And that the *extra* sweeps did something. A chain missing a barrier between Jacobi
        // iterations reads a stale pressure every time, so every sample comes back identical --
        // which passes a reduction floor and a monotonicity check but not this.
        //
        // Measured 533x. The floor is 100 rather than 400 because the *first* sample's population
        // is `max(1, framesToLand)`: on a contended GPU that can be 2 frames, which makes the
        // first point 32 sweeps instead of 16 and legitimately shrinks this ratio to about 286.
        // The sweep table prints the frame count for each point, so the population is visible.
        probe.atLeast(
            "extra sweeps helped (${worst.sweeps} -> ${best.sweeps})",
            worst.after / best.after, 100.0f
        )

        // --- claim 3: the projection moved the field, and left it finite.
        var worstMove = 0f
        for (i in 0 until FIELD_FLOATS) worstMove = maxOf(worstMove, abs(lastProjected[i] - seeded[i]))
        // Measured 1.434 against a field whose own max |div| is 1.765, i.e. the correction is the
        // same order as the thing being corrected. A floor of 1.0 is a real constraint; 0.1 would
        // have passed on a projection that barely touched the field.
        probe.atLeast("projection changed the field", worstMove, 1.0f, buffer = "velocityOut")
        probe.allFinite("projected field", lastProjected, buffer = "velocityOut")
        probe.varies("projected field", lastProjected, 1.2f, buffer = "velocityOut")

        // --- claim 4: the Jacobi stage directly, as a Poisson residual.
        val pressure = probe.read(pressureA)
        val residual = poissonResidual(pressure, cpuDiv)
        println(
            "  Poisson residual max |Lap p - div| = %.5f against max |div| %.4f (%.1f%%)".format(
                residual, maxDivBefore, 100f * residual / maxDivBefore
            )
        )
        // Measured 4.45e-04 of max |div| after 256 sweeps. 5e-3 is an order of magnitude above it
        // and still an order of magnitude below the 4.5e-2 that 64 sweeps would give, so this
        // claim can tell the sweep lengths apart rather than accepting any of them.
        probe.atMost("Poisson residual / max |div|", residual / maxDivBefore, 5e-3f, buffer = "pressureA")
        probe.allFinite("pressure", pressure, buffer = "pressureA")
        // Measured 11.91. The pressure field is the solution of a Poisson problem with an O(1)
        // right-hand side over 64 cells, so it is an order of magnitude larger than the velocity;
        // a floor of 8 catches a solve that collapsed to near-constant, which 0.05 would not.
        probe.varies("pressure", pressure, 8.0f, buffer = "pressureA")

        // --- and that the whole chain re-lands promptly once warm, which is the claim that the
        // sentinel mechanism is measuring something rather than being always true.
        //
        // Measured 1. It is structurally 1: `landed()` waits *every* compute fence for this
        // window, so one pumped frame is one completed chain no matter how many frames the
        // renderer keeps in flight. The bound is 2 rather than 1 only so a scheduling hiccup is
        // not a red test; a 3 here would mean the sentinel is surviving a completed frame, which
        // is a real finding and should fail.
        probe.atMost("frames to land once warm", probe.framesToLand, 2)

        return probe.verdict("gpu pressure projection chain")
    }
}
