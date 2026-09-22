package afield.four.util

import afield.four.Renderer
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.Structure
import afield.four.buffers.VulkanBuffer
import afield.four.buffers.defaultBuffer
import afield.four.modules.ComputeMesh
import afield.four.modules.ComputeModule
import afield.four.modules.ComputeModulePointer
import afield.four.modules.Parameters
import afield.four.modules.computeMemoryBarrier
import afield.four.modules.computeTask
import afield.four.modules.pointer
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * A GPU compute shader, put on a bench: fixture buffers, a dispatch chain, evidence that it ran,
 * and numbers printed next to the bounds they were compared against.
 *
 * ### Why this exists
 *
 * The same forty lines are written out longhand every time anyone wants to know whether a compute
 * shader is right. Allocate some buffers. Upload a `FloatArray`. Build a `ComputeModule` from a
 * GLSL string, hang a `ComputeMesh` off it with named slots bound, append a barrier, register it on
 * the renderer. Pump frames — some number of them, chosen by feel. Wait the compute fences. Read a
 * buffer back. Compare against hand-tuned thresholds. `println` a tag. Remember to remove the
 * module in a `finally`.
 *
 * That paragraph is written out six times in this repository
 * ([afield.examples.four.GpuSkinningSmoke], [afield.examples.four.GpuNeighbourGridSmoke],
 * `NeuralFieldParitySmoke`, `FieldStatsSmokeTest`, `ShaderFieldAutoControlSmokeTest`,
 * `ShaderFieldQuantileWarpSmokeTest`) and about thirty-four times, as throwaway boxes named
 * `Temporary … checks`, in one live workspace document. Two of those authors independently invented
 * the same two workarounds, which is the signal that they belong in a facility rather than in a
 * box: a sentinel prefill to tell "the GPU agrees" apart from "the GPU never ran", and a
 * hand-written `finally` to stop a probe leaking a compute module into a live renderer.
 *
 * ### What this is not
 *
 * Not [FieldProbe]. That is an in-shader *sample tap*: a GLSL include and a ring buffer a running
 * shader writes values into, read back asynchronously and drawn as a histogram, for looking at a
 * scene that already exists. This is the other half — it *builds* the scene, one dispatch wide,
 * from a fixture the caller states, and grades the answer. The two compose: a stage's GLSL can
 * include `fieldProbe` and this will not mind.
 *
 * Not headless, either. `afield.four` has no headless mode, so a compute-only dispatch still needs
 * a [Window] for the device to exist, and it needs an awake display.
 *
 * ### The three things it knows that a hand-rolled probe forgets
 *
 * 1. **A buffer the host reads back must ask for it.** [fixture] always applies `streamReads()`,
 *    which is `HOST_VISIBLE | HOST_CACHED`. A plain `hosted()` buffer lands in write-combined
 *    memory where host reads are not wrong so much as pathological, and a `zeroInit()` one is
 *    `DEVICE_LOCAL`. This is not offered as an option because there is no version of a probe that
 *    wants to get it wrong.
 * 2. **A frame count is not a wait.** A `ComputeModule`'s shader compiles asynchronously and a
 *    module whose shader has not finished compiling **dispatches as a silent no-op** — the "not
 *    valid" branch in [ComputeModulePointer.render] has its `println` commented out. So neither
 *    `repeat(4) { frame() }` nor three seconds of wall clock is a wait; both are a hope. [arm] and
 *    [stillWaiting] make it a *condition*: prefill every output with [SENTINEL], then pump until
 *    every witnessed slot has actually been overwritten, and fail with a deadline — naming the
 *    buffer and the first unwritten index — if it never is.
 * 3. **A slot name is not checked.** `Structure.bind` accepts any string at all, so a misspelled
 *    push-constant name binds nothing and the shader reads a null device pointer or whatever was
 *    in that memory before. [Bindings.bind] validates against the shader's introspected push
 *    constants and lists the slots it does have.
 *
 * ### A chain, not a dispatch
 *
 * Real GPU work is a chain — advect, divergence, pressure, project — and a probe over one dispatch
 * cannot see the seams between passes, which is where the bugs are. So the unit here is a
 * [Stage]: one GLSL source, one shader compile, and as many [Stage.dispatch] calls as you like,
 * each with its own bindings and each followed by a barrier. Stages run in the order you create
 * them (`ComputeTarget.modules` is a `LinkedHashMap` and is executed in iteration order) and
 * dispatches run in the order you add them (a `ComputeModule`'s `meshes` likewise). Twenty-four
 * Jacobi iterations are therefore *one* stage and twenty-four dispatches, not twenty-four shader
 * compiles.
 *
 * ### On readback and barriers
 *
 * There is deliberately no host-read barrier here, and that is a measured decision rather than an
 * omission: [afield.four.buffers.VulkanBuffer.mapAndRead] already calls `vmaInvalidateAllocation`
 * over the range before mapping, and [landed] waits this window's compute fences, which is the
 * execution dependency. Adding `hostReadBarrier` on top would need the buffer to be known while
 * the compute command buffer is being recorded, which a probe reading *after* the fact does not
 * have. If you are writing a probe that reads mid-frame, that is the barrier you want and this is
 * not the facility for it.
 *
 * ### Usage, from `jvmMain`
 *
 * ```
 * Options.enableVR = false
 * val window = Window("my probe")
 * val renderer = Renderer(window)
 * gpuProbe(renderer, "my-probe", pump = { window.pollEvents(); renderer.render() }) { probe ->
 *     val input = probe.fixture("input", 1024)
 *     val output = probe.fixture("output", 1024)
 *     probe.upload(input, myFloats)
 *
 *     probe.stage("doubling", glsl)
 *         .dispatch(groups = 16) { bind("input") { input }; bind("output") { output } }
 *
 *     probe.settle(output)                       // arm, pump until written, fence
 *     val got = probe.read(output)
 *     probe.atMost("worst |error|", worstError(got), 1e-5f, buffer = "output")
 *     exitProcess(if (probe.verdict("doubling") == 0) 0 else 1)
 * }
 * ```
 *
 * ### Usage, from a box
 *
 * A box drives its own frames, so the pump stays with the box, inside `frames { }` where `frame()`
 * is available. Everything else is identical:
 *
 * ```
 * val probe = GpuProbe(renderer, "my-probe")
 * val out = probe.fixture("out", 256)
 * probe.stage("check", glsl).dispatch(groups = 4) { bind("out") { out } }
 * frames {
 *     try {
 *         probe.arm(out)
 *         while (probe.stillWaiting()) frame()
 *         println(probe.read(out).max())
 *         probe.verdict("check")
 *     } finally { probe.close() }
 * }
 * ```
 *
 * @see FieldProbe for the in-shader sample tap.
 * @see FieldStats for GPU-side reductions, which a stage may use.
 */
class GpuProbe(
    val renderer: Renderer,
    val name: String = "gpuProbe",
    /**
     * One rendered frame. `{ window.pollEvents(); renderer.render() }` from `jvmMain`; from a box,
     * leave it null and drive [stillWaiting] from inside `frames { }` with the box's own `frame()`.
     */
    val pump: (() -> Unit)? = null,
) : AutoCloseable {

    companion object {
        /**
         * Prefilled into every armed output, so "never written" cannot read as "agrees".
         *
         * The exact value is [afield.examples.four.GpuSkinningSmoke]'s, kept deliberately: it is
         * far outside any plausible field value, it is exactly representable in fp32, and it is
         * recognisable on sight in a hex dump or a debugger.
         */
        const val SENTINEL = -98765.0f

        /** How long [stillWaiting] will pump before it gives up, in seconds. */
        const val DEFAULT_LANDING_SECONDS = 12.0
    }

    // ----------------------------------------------------------------------------------------
    // Fixtures
    // ----------------------------------------------------------------------------------------

    private val fixtures = LinkedHashMap<FrameBufferGroup, String>()
    private val sizes = LinkedHashMap<FrameBufferGroup, Int>()

    /**
     * A buffer of [floats] floats, host-writable and host-*readable*, owned by this probe.
     *
     * `streamReads()` is applied unconditionally — see the class note. The name is carried so a
     * failed assertion can say which buffer it was about.
     */
    fun fixture(name: String, floats: Int): FrameBufferGroup {
        require(floats > 0) { "fixture '$name' needs a positive float count, not $floats" }
        val b = defaultBuffer(name = "${this.name}.$name") {
            size = floats.floats
            streamReads()
        }.toBuffer(renderer)
        fixtures[b] = name
        sizes[b] = floats
        return b
    }

    /** The name [fixture] gave this buffer, for messages. */
    fun nameOf(buffer: FrameBufferGroup): String = fixtures[buffer] ?: "<not a probe fixture>"

    /** How many floats [fixture] allocated, or 0 if this is not one of ours. */
    fun sizeOf(buffer: FrameBufferGroup): Int = sizes[buffer] ?: 0

    /** Upload [from] to the front of [buffer]. Fails loudly rather than truncating. */
    fun upload(buffer: FrameBufferGroup, from: FloatArray) {
        val capacity = sizes[buffer]
        require(capacity == null || from.size <= capacity) {
            "cannot upload ${from.size} floats into fixture '${nameOf(buffer)}', which holds $capacity"
        }
        buffer.mapAndWrite { put(from) }
    }

    /** Upload [from] to the front of [buffer], from its position to its limit. */
    fun upload(buffer: FrameBufferGroup, from: FloatBuffer) {
        buffer.mapAndWrite { put(from) }
    }

    /** Write [value] into the first [floats] floats of [buffer]. */
    fun fill(buffer: FrameBufferGroup, value: Float, floats: Int = sizeOf(buffer)) {
        require(floats > 0) { "fill('${nameOf(buffer)}') was given $floats floats" }
        buffer.mapAndWrite { repeat(floats) { put(value) } }
    }

    // ----------------------------------------------------------------------------------------
    // Stages and dispatches
    // ----------------------------------------------------------------------------------------

    /**
     * One compute shader, registered on the renderer, with one or more dispatches behind it.
     *
     * Created by [GpuProbe.stage]. Every [dispatch] appends a `ComputeMesh` *and* a barrier, so
     * iteration `n + 1` reads what iteration `n` wrote.
     */
    inner class Stage internal constructor(
        val stageName: String,
        internal val module: ComputeModulePointer,
        internal val key: String,
    ) {
        private var dispatches = 0

        /** The push-constant slots this shader actually has, for validation and for messages. */
        val slots: List<String>
            get() = module.current.ci.pushConstants.flatMap { pc -> pc.members.map { it.name } }

        /** Whether the shader compiled. An invalid stage dispatches as a silent no-op. */
        val valid: Boolean get() = module.current.valid

        /** How many dispatches have been added. */
        val dispatchCount: Int get() = dispatches

        /**
         * One dispatch of [groups] x [groupsY] x [groupsZ] workgroups, followed by a barrier.
         *
         * [bindings] runs against a [Bindings] receiver whose `bind` validates the slot name
         * against the shader's introspected push constants.
         */
        fun dispatch(
            groups: Int,
            groupsY: Int = 1,
            groupsZ: Int = 1,
            label: String = "dispatch$dispatches",
            bindings: Bindings.() -> Unit,
        ): Stage {
            require(groups > 0 && groupsY > 0 && groupsZ > 0) {
                "stage '$stageName' dispatch '$label' has no work to do: " +
                    "$groups x $groupsY x $groupsZ workgroups"
            }
            val mesh = ComputeMesh(renderer, module).apply {
                x = groups; y = groupsY; z = groupsZ
            }
            Bindings(this, mesh.push, label).bindings()
            module += label to (mesh as afield.four.modules.ComputeTask)
            module += "$label.barrier" to computeTask { _, cmd, _ ->
                computeMemoryBarrier(cmd, null as VulkanBuffer?)
            }
            dispatches++
            return this
        }

        override fun toString() = "Stage($stageName, $dispatches dispatches, valid=$valid)"
    }

    /** The receiver of [Stage.dispatch]'s binding block. */
    inner class Bindings internal constructor(
        private val stage: Stage,
        private val push: Structure,
        private val label: String,
    ) {
        /**
         * Bind push-constant slot [slot] to a live supplier.
         *
         * Throws — naming the slots the shader does have — rather than binding a name the shader
         * has never heard of.
         */
        fun bind(slot: String, value: () -> Any?) {
            val known = stage.slots
            require(slot in known) {
                "stage '${stage.stageName}' dispatch '$label': the compute shader has no push " +
                    "constant named '$slot'. It has " +
                    if (known.isEmpty()) "none at all — did it compile?"
                    else known.joinToString(", ")
            }
            push.bind(slot, value)
        }

        /** Bind every entry of [map], in iteration order. */
        fun bindAll(map: Map<String, () -> Any?>) = map.forEach { (k, v) -> bind(k, v) }
    }

    private val stages = mutableListOf<Stage>()

    /** The stages created so far, in the order they run. */
    val stageList: List<Stage> get() = stages

    /**
     * Compile [glsl] as a compute shader and register it, after every stage created before it.
     *
     * A shader that fails to compile reports through [Parameters.errorHandler] into this probe's
     * failure list rather than into the log, so [verdict] cannot come back clean on a probe whose
     * shader never built.
     */
    fun stage(stageName: String, glsl: String): Stage {
        val key = "$name.$stageName.${stages.size}"
        val module = ComputeModule(
            Parameters(
                renderer = renderer,
                compute = glsl,
                name = key,
                computeSourceName = "$stageName.comp",
                errorHandler = { desc, t ->
                    fail("stage '$stageName' shader: $desc${t?.let { " (${it.message})" } ?: ""}")
                },
            )
        ).pointer()
        val stage = Stage(stageName, module, key)
        renderer += key to module
        stages += stage
        return stage
    }

    // ----------------------------------------------------------------------------------------
    // "Did the GPU actually write this?"
    // ----------------------------------------------------------------------------------------

    private class Witness(val buffer: FrameBufferGroup, val floats: Int)

    private val witnesses = mutableListOf<Witness>()
    private var landingDeadline = 0L
    private var pumpedFrames = 0
    private var landedAfterFrames = -1

    /**
     * Prefill every [outputs] buffer with [SENTINEL] and start the clock.
     *
     * After this, [landed] answers "has the GPU overwritten all of it yet" by *looking*, rather
     * than by counting frames. Call it again to re-arm for another dispatch.
     */
    fun arm(vararg outputs: FrameBufferGroup, seconds: Double = DEFAULT_LANDING_SECONDS) {
        witnesses.clear()
        for (b in outputs) {
            val floats = sizes[b] ?: error(
                "arm() needs a probe fixture so it knows the size to prefill; " +
                    "'${nameOf(b)}' was not allocated by fixture()"
            )
            fill(b, SENTINEL, floats)
            witnesses += Witness(b, floats)
        }
        landingDeadline = System.nanoTime() + (seconds * 1e9).toLong()
        pumpedFrames = 0
        landedAfterFrames = -1
    }

    /**
     * Whether every armed output has been completely overwritten by the GPU.
     *
     * Waits this window's compute fences first, so `true` means the work is *finished* and not
     * merely submitted. A partial result — some slots written, some still sentinel — reads as not
     * landed, because a half-written buffer is the shape a too-small dispatch makes and it should
     * not be gradeable.
     */
    fun landed(): Boolean {
        renderer.windowContext().computeFences.forEach { it.fenceWait() }
        for (w in witnesses) {
            var survivors = 0
            w.buffer.mapAndRead {
                for (i in 0 until w.floats) if (get(i) == SENTINEL) survivors++
            }
            if (survivors > 0) return false
        }
        if (landedAfterFrames < 0) landedAfterFrames = pumpedFrames
        return true
    }

    /**
     * `!landed()`, plus a deadline and a frame count — the loop condition.
     *
     * `probe.arm(out); while (probe.stillWaiting()) frame()` is the whole idiom, in `jvmMain` and
     * in a box alike. Throws [GpuProbeNeverLanded] past the deadline, naming which buffers still
     * hold sentinel and from which index, because "it timed out" and "the third of four outputs
     * was never bound" are different bugs and the second is the common one.
     */
    fun stillWaiting(): Boolean {
        if (witnesses.isEmpty()) return false
        if (landed()) return false
        if (System.nanoTime() > landingDeadline) throw GpuProbeNeverLanded(landingReport())
        pumpedFrames++
        return true
    }

    /** How many frames the caller pumped before everything landed, or -1 if it never did. */
    val framesToLand: Int get() = landedAfterFrames

    /**
     * Wait this window's compute fences — "the GPU has nothing of mine left in flight".
     *
     * Needed before a host write to a buffer a dispatch might still be reading, which is what
     * re-seeding a fixture between runs is. [landed] does this already; this is the same wait
     * without the sentinel scan.
     */
    fun idle() {
        renderer.windowContext().computeFences.forEach { it.fenceWait() }
    }

    private fun landingReport(): String {
        val lines = mutableListOf<String>()
        lines += "probe '$name': the GPU never finished writing, after $pumpedFrames pumped frames."
        for (w in witnesses) {
            var survivors = 0
            var firstSurvivor = -1
            w.buffer.mapAndRead {
                for (i in 0 until w.floats) if (get(i) == SENTINEL) {
                    survivors++
                    if (firstSurvivor < 0) firstSurvivor = i
                }
            }
            lines += "  fixture '${nameOf(w.buffer)}': $survivors of ${w.floats} slots still hold " +
                "the sentinel" + (if (firstSurvivor >= 0) ", from index $firstSurvivor" else "")
        }
        for (s in stages) {
            if (!s.valid) lines += "  stage '${s.stageName}' is NOT valid — its shader did not " +
                "compile, and an invalid module dispatches as a silent no-op"
        }
        lines += "  Causes, in the order they actually happen: a slot bound to the wrong buffer; " +
            "too few workgroups to cover the output; an early return in main() that skips part " +
            "of the range; a shader that did not compile."
        return lines.joinToString("\n")
    }

    /**
     * [arm], then pump until [landed] — the whole wait, when this probe owns a [pump].
     *
     * Needs [pump], which means `jvmMain`. From a box, use [arm] and [stillWaiting] with the box's
     * own `frame()`.
     */
    fun settle(vararg outputs: FrameBufferGroup, seconds: Double = DEFAULT_LANDING_SECONDS) {
        val p = requirePump()
        arm(*outputs, seconds = seconds)
        while (stillWaiting()) p()
    }

    /** Render [n] frames. Needs [pump], which means `jvmMain`. */
    fun pumpFrames(n: Int) {
        val p = requirePump()
        repeat(n) { p() }
    }

    private fun requirePump(): () -> Unit = pump ?: error(
        "probe '$name' has no pump, so it cannot drive frames itself. Either construct it with " +
            "pump = { window.pollEvents(); renderer.render() }, or drive it from a box with " +
            "`arm(...); while (stillWaiting()) frame()`."
    )

    // ----------------------------------------------------------------------------------------
    // Readback
    // ----------------------------------------------------------------------------------------

    /**
     * Read the first [floats] floats of [buffer] back to the host.
     *
     * `mapAndRead` invalidates the mapped range first, and [landed] has already waited the compute
     * fences, so this is the whole of the readback — see the class note on barriers.
     */
    fun read(buffer: FrameBufferGroup, floats: Int = sizeOf(buffer)): FloatArray {
        require(floats > 0) { "read('${nameOf(buffer)}') was asked for $floats floats" }
        val out = FloatArray(floats)
        buffer.mapAndRead { get(out, 0, floats) }
        return out
    }

    /** Read the first [ints] ints of [buffer] back to the host. */
    fun readInts(buffer: FrameBufferGroup, ints: Int = sizeOf(buffer)): IntArray {
        require(ints > 0) { "readInts('${nameOf(buffer)}') was asked for $ints ints" }
        val out = IntArray(ints)
        buffer.mapAndRead_ints { get(out, 0, ints) }
        return out
    }

    // ----------------------------------------------------------------------------------------
    // Claims
    // ----------------------------------------------------------------------------------------

    private class Claim(
        val label: String,
        val measured: Double,
        val comparator: String,
        val bound: Double,
        val buffer: String?,
        val index: Int?,
    ) {
        val passed: Boolean
            get() = when (comparator) {
                ">=" -> measured >= bound
                "<=" -> measured <= bound
                "==" -> measured == bound
                else -> false
            }

        fun where(): String = when {
            buffer != null && index != null -> "   [$buffer index $index]"
            buffer != null -> "   [$buffer]"
            index != null -> "   [index $index]"
            else -> ""
        }
    }

    private val claims = mutableListOf<Claim>()
    private val failures = mutableListOf<String>()

    /** Record an unconditional failure — a shader that did not compile, an exception, a gap. */
    fun fail(message: String) {
        failures += message
    }

    /**
     * [measured] must be at least [floor]. Both numbers go in the verdict table.
     *
     * This is the assertion this project keeps needing and keeps getting wrong in one particular
     * direction: a floor so far below the measurement that it cannot fail. Printing them side by
     * side is the whole point — `0.94 >= 0.001` is visibly not a test.
     */
    fun atLeast(label: String, measured: Number, floor: Number, buffer: String? = null, index: Int? = null) {
        claims += Claim(label, measured.toDouble(), ">=", floor.toDouble(), buffer, index)
    }

    /** [measured] must be at most [ceiling]. */
    fun atMost(label: String, measured: Number, ceiling: Number, buffer: String? = null, index: Int? = null) {
        claims += Claim(label, measured.toDouble(), "<=", ceiling.toDouble(), buffer, index)
    }

    /** [measured] must equal [expected] exactly — counts, indices, set sizes. */
    fun exactly(label: String, measured: Number, expected: Number, buffer: String? = null, index: Int? = null) {
        claims += Claim(label, measured.toDouble(), "==", expected.toDouble(), buffer, index)
    }

    /**
     * The spread of [values] must be at least [floor] — the guard against a probe that passes
     * because the GPU wrote a constant, or nothing but zeros.
     *
     * Two of the six hand-rolled probes grew this by hand after being fooled once:
     * `GpuSkinningSmoke` checks that instance 41 is not identical to instance 0, and
     * `GpuNeighbourGridSmoke` checks that somebody had a neighbour at all. A trivially-satisfied
     * assertion is the failure mode of every GPU test, so it is a first-class claim here.
     */
    fun varies(label: String, values: FloatArray, floor: Number, buffer: String? = null) {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in values) if (v.isFinite()) { lo = minOf(lo, v); hi = maxOf(hi, v) }
        val spread = if (hi < lo) 0.0 else (hi - lo).toDouble()
        claims += Claim("$label (spread)", spread, ">=", floor.toDouble(), buffer, null)
    }

    /**
     * Every one of [values] must be finite, and none may still be [SENTINEL].
     *
     * Reports the *first* offending index, because with 65,536 floats the index is the diagnosis:
     * index 0 is a binding, a boundary index is a stencil, an isolated index is a race.
     */
    fun allFinite(label: String, values: FloatArray, buffer: String? = null) {
        var bad = 0
        var firstBad = -1
        var sentinels = 0
        var firstSentinel = -1
        for (i in values.indices) {
            if (!values[i].isFinite()) {
                bad++; if (firstBad < 0) firstBad = i
            } else if (values[i] == SENTINEL) {
                sentinels++; if (firstSentinel < 0) firstSentinel = i
            }
        }
        claims += Claim("$label: non-finite", bad.toDouble(), "==", 0.0, buffer, firstBad.takeIf { it >= 0 })
        claims += Claim(
            "$label: surviving sentinels", sentinels.toDouble(), "==", 0.0,
            buffer, firstSentinel.takeIf { it >= 0 }
        )
    }

    /**
     * Print every claim with its measured value beside its bound, and return the failure count.
     *
     * The table is the artefact. A reader should be able to see, without reading the source, both
     * what was measured and how much room there was — which is the only way a floor chosen to hide
     * a real error becomes visible.
     */
    fun verdict(title: String = name): Int {
        val width = (claims.maxOfOrNull { it.label.length } ?: 1).coerceIn(1, 48)
        val dispatchTotal = stages.sumOf { it.dispatchCount }
        println(
            "--- $title: ${claims.size} claims over ${stages.size} stage(s), " +
                "$dispatchTotal dispatch(es)" +
                (if (landedAfterFrames >= 0) ", landed after $landedAfterFrames pumped frame(s)" else "") +
                " ---"
        )
        var failed = 0
        for (c in claims) {
            val ok = c.passed
            if (!ok) failed++
            println(
                "  %s %-${width}s  %14s %-2s %-14s%s".format(
                    if (ok) " " else "!", c.label.take(width),
                    fmt(c.measured), c.comparator, fmt(c.bound), c.where()
                )
            )
        }
        for (f in failures) println("  ! $f")
        val held = claims.size - failed
        println(
            "  $held of ${claims.size} claims hold" +
                if (failures.isNotEmpty()) ", and ${failures.size} unconditional failure(s)" else ""
        )
        return failed + failures.size
    }

    private fun fmt(v: Double): String = when {
        v == 0.0 -> "0"
        !v.isFinite() -> v.toString()
        v == Math.rint(v) && abs(v) < 1e9 -> "%,.0f".format(v)
        abs(v) >= 1e-3 && abs(v) < 1e6 -> "%.6f".format(v)
        else -> "%.4e".format(v)
    }

    // ----------------------------------------------------------------------------------------
    // Teardown
    // ----------------------------------------------------------------------------------------

    /**
     * Remove every module this probe put on the renderer.
     *
     * Not optional and not the caller's job: a probe that leaks a module into a live renderer keeps
     * dispatching into buffers nobody is looking at, forever. Every hand-rolled probe writes this
     * out in a `finally`, and four of the six in this repository simply do not, getting away with
     * it only because they `exitProcess` afterwards.
     *
     * The fixtures are deliberately *not* freed — the caller usually wants to read one after the
     * probe is closed, and `FrameBufferGroup` allocations go with the renderer.
     */
    override fun close() {
        for (s in stages) renderer.computeList.modules.remove(s.key)
        witnesses.clear()
    }
}

/** Thrown by [GpuProbe.stillWaiting] when the armed outputs are never completely written. */
class GpuProbeNeverLanded(message: String) : RuntimeException(message)

/**
 * Run [block] against a [GpuProbe] and close it however [block] ends.
 *
 * The `use`-shaped entry point, so that the `finally { renderer.computeList.modules.remove(key) }`
 * every hand-rolled probe writes out is structural instead.
 */
inline fun <R> gpuProbe(
    renderer: Renderer,
    name: String = "gpuProbe",
    noinline pump: (() -> Unit)? = null,
    block: (GpuProbe) -> R,
): R = GpuProbe(renderer, name, pump).use(block)
