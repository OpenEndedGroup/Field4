package afield.examples.four

import afield.four.Renderer
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.defaultBuffer
import afield.four.modules.ComputeMesh
import afield.four.modules.ComputeModule
import afield.four.modules.GltfSkinning
import afield.four.modules.GltfSupport
import afield.four.modules.GpuSkinner
import afield.four.modules.GpuSkinning
import afield.four.modules.Parameters
import afield.four.modules.RiggedFixture
import afield.four.modules.StressFixture
import afield.four.modules.pointer
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.linalg.Matrix4f
import kotlin.math.abs
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.gpu-skinning.smoke
runner: jvm-main
entrypoint: afield.examples.four.GpuSkinningSmoke
tags: [smoke, four, gpu, compute, skinning, characters, instancing]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "gpu skinning tests PASSED"
*/
/**
 * That the **GPU** puts every skinned vertex exactly where [GltfSkinning.skin] says it goes.
 *
 * ### Why this is the test and a screenshot is not
 *
 * Last night landed `CpuSkinner`, eight closed-form assertions and five frames a person can look
 * at. That makes the CPU path an *oracle*: a function that already agrees with hand-computed
 * geometry, so a second implementation can be graded against it to six decimals instead of by
 * eye. A picture of a bent cylinder cannot tell you that the bend is 60° and not 59°, that the
 * normals were transformed by the inverse transpose rather than the plain 3×3, or that instance
 * 41 of a crowd got instance 41's palette. Numbers can.
 *
 * ### Why a compute shader when the point is a vertex shader
 *
 * Vulkan has no transform feedback, so there is no way to ask a vertex shader what it computed.
 * The skinning maths therefore lives in [GpuSkinning.glsl] — **one** GLSL source — and is called
 * from two places: [GpuSkinning.skinnedVertexShader], which draws, and
 * [GpuSkinning.verifyComputeShader], which writes its results to a buffer this reads back. So
 * what is graded here is the same `afSkinBlend` the renderer runs, not a parallel implementation
 * written to pass a test. Change the skinning and this test changes with it or goes red.
 *
 * ### What it asserts
 *
 * - **Bind pose is the identity.** At the rest pose the GPU must return the input, so a test
 *   that passes only because everything is near the origin cannot hide here.
 * - **Every pose of every clip agrees**, on both committed fixtures — the bending cylinder
 *   (`bend`, `curl`) and the shear-and-cap stress rig, which carries a non-uniform scale and is
 *   the case where the normal's inverse transpose stops being optional.
 * - **Normals too, not just positions.** A skinner that gets positions right and normals wrong
 *   renders a correctly-shaped mesh that is lit wrongly, which is exactly the failure a
 *   silhouette screenshot cannot show.
 * - **Per-instance palettes land on the right instances.** A crowd of 64, each at a different
 *   phase of a *blend* between two clips, compared instance by instance. An off-by-one in
 *   `gl_InstanceIndex * boneCount` would still render a plausible crowd.
 * - **The output was actually written.** The output buffer is prefilled with [SENTINEL] and any
 *   surviving sentinel is a failure, so a compute module that silently did not run — which is a
 *   real failure mode here, see the wait below — cannot report agreement.
 *
 * ### Running it
 *
 * ```
 * tools/run_field_tests.py --id four.gpu-skinning.smoke
 * ```
 *
 * Needs a GPU and a window: `afield.four` has no headless mode, so a compute-only dispatch still
 * needs a `Window` for the device to exist. It also needs an awake display.
 */
object GpuSkinningSmoke {

    /** Prefilled into the output buffer, so "never written" cannot read as "agrees". */
    private const val SENTINEL = -98765.0f

    /**
     * Positions agree to this, in the fixtures' units (a cylinder about 4 long).
     *
     * Not arbitrary: `afSkinBlend` accumulates four weighted 4×4s in fp32 and the CPU does the
     * same in Kotlin `Float`, in the same order, so the only differences available are the
     * compiler's contraction choices — a fused multiply-add on one side and not the other. That
     * is a handful of ULPs on numbers of order 1. The measured worst case is printed, so a
     * tolerance chosen to hide a real error would be visible in the output.
     */
    private const val POSITION_TOL = 1e-5f

    /** Normals are unit length and go through an inverse and a normalise, so they lose a little more. */
    private const val NORMAL_TOL = 1e-4f

    /** Instances in the crowd parity check. */
    private const val CROWD = 64

    /** Vertices in the synthetic rig used for the throughput measurement. */
    private const val PERF_VERTICES = 50_000

    /** Instances in the throughput measurement. */
    private const val PERF_INSTANCES = 32

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val window = Window("GPU Skinning Smoke")
        val renderer = Renderer(window)

        try {
            var failures = 0

            failures += checkFixture(renderer, window, "bending cylinder", RiggedFixture.locate())
            failures += checkFixture(renderer, window, "shear and cap", StressFixture.locate())
            failures += checkCrowd(renderer, window)
            measureThroughput(renderer, window)

            if (failures > 0) {
                println("gpu skinning tests FAILED ($failures)")
                exitProcess(1)
            }
            println("gpu skinning tests PASSED")
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        exitProcess(0)
    }

    // ------------------------------------------------------------------------------------------
    // The rig, reduced to what both the CPU oracle and the GPU need
    // ------------------------------------------------------------------------------------------

    /**
     * Exactly the inputs [GltfSkinning.skin] takes, so a synthetic rig and a loaded one are the
     * same thing to everything below.
     */
    private class Rig(
        val label: String,
        val bindPositions: FloatArray,
        val bindNormals: FloatArray,
        val influences: GltfSkinning.VertexInfluences,
        val boneCount: Int,
    ) {
        val vertexCount: Int get() = influences.vertexCount
    }

    /**
     * The bind pose out of the packed 12-float vertex buffer — the same read [GpuSkinner] and
     * [afield.four.modules.CpuSkinner] both do in their `init`.
     *
     * Read here rather than borrowed from a [GpuSkinner] because constructing one allocates three
     * GPU buffers, and this is called once per fixture for the CPU oracle's benefit.
     */
    private fun rigOf(label: String, instance: GltfSupport.MeshInstance): Rig {
        val skin = requireNotNull(instance.skin) { "$label has no skin" }
        val n = instance.buffers.vertexCount
        val positions = FloatArray(n * 3)
        val normals = FloatArray(n * 3)
        val f = instance.buffers.vertexData.asFloatBuffer()
        for (v in 0 until n) for (k in 0..2) {
            positions[v * 3 + k] = f.get(v * GpuSkinning.VERTEX_STRIDE_FLOATS + k)
            normals[v * 3 + k] = f.get(v * GpuSkinning.VERTEX_STRIDE_FLOATS + 3 + k)
        }
        return Rig(label, positions, normals, skin.influences, skin.skeleton.bones.size)
    }

    // ------------------------------------------------------------------------------------------
    // The GPU side: one compute module, reused
    // ------------------------------------------------------------------------------------------

    /**
     * The compute dispatch of [GpuSkinning.verifyComputeShader], sized for a worst case and then
     * re-pointed at each rig in turn.
     *
     * One module for the whole run, because a `ComputeModule`'s shader compiles asynchronously and
     * a module whose shader has not finished compiling **dispatches as a silent no-op** — the
     * "not valid" branch has its `println` commented out. Building one and waiting once is both
     * faster and less likely to read an unwritten buffer than building six.
     */
    private class Dispatcher(
        val renderer: Renderer,
        val window: Window,
        maxVertices: Int,
        maxInstances: Int,
    ) {
        /** Palette slots reserved per instance. Both committed fixtures are well inside this. */
        val maxBones: Int = 16

        var vertexCount: Int = 0
        var instanceCount: Int = 0
        var boneCount: Int = 0

        val jointBuffer: FrameBufferGroup = floats("gpuSkinParityJoints", maxVertices * 4)
        val weightBuffer: FrameBufferGroup = floats("gpuSkinParityWeights", maxVertices * 4)
        val bindBuffer: FrameBufferGroup = floats("gpuSkinParityBind", maxVertices * 8)
        val paletteBuffer: FrameBufferGroup = floats("gpuSkinParityPalette", maxInstances * maxBones * 16)
        val outBuffer: FrameBufferGroup = floats("gpuSkinParityOut", maxVertices * maxInstances * 8)

        private val module = ComputeModule(
            Parameters(
                renderer = renderer,
                compute = GpuSkinning.verifyComputeShader,
                name = "gpu-skinning-parity",
            )
        ).pointer()

        /**
         * [MAX_DISPATCHES] identical dispatches, of which [dispatches] are switched on.
         *
         * This exists for the throughput measurement and nothing else. The presented frame is
         * locked to the display at 8.3 ms here, and skinning 1.6 M vertices costs so much less
         * than that it vanishes: the first attempt measured 8.35 ms against an 8.30 ms baseline
         * and reported 28,455 M vertices/s, which is noise with a unit on it. Repeating the
         * *same* dispatch N times scales the GPU work without scaling the output buffer, until
         * the work is large enough to push the frame off the vsync floor and be measurable.
         *
         * They are deliberately not separated by barriers. Throughput, not latency, is the
         * question, and letting the scheduler overlap them measures what a real workload would
         * get.
         */
        private val meshes: List<ComputeMesh> = (0 until MAX_DISPATCHES).map { i ->
            ComputeMesh(renderer, module).apply {
                x = 1; y = 1; z = 1
                push.bind("jointBuffer") { this@Dispatcher.jointBuffer }
                push.bind("weightBuffer") { this@Dispatcher.weightBuffer }
                push.bind("paletteBuffer") { this@Dispatcher.paletteBuffer }
                push.bind("bindBuffer") { this@Dispatcher.bindBuffer }
                push.bind("outBuffer") { this@Dispatcher.outBuffer }
                push.bind("boneCount") { this@Dispatcher.boneCount }
                push.bind("vertexCount") { this@Dispatcher.vertexCount }
                push.bind("instanceCount") { this@Dispatcher.instanceCount }
            }
        }

        /**
         * How many of [meshes] run per frame. 1 for every correctness check.
         *
         * Applied by rebuilding the module's mesh map rather than by setting the unused
         * dispatches to zero groups. `ComputeMesh` logs `"ComputeMesh has no work to do"` every
         * frame for a zero-group dispatch, and with 127 of them switched off that was 42,000 log
         * lines during a three-second warm-up — enough string formatting to bend the very frame
         * times this is trying to measure, and in the direction that flatters the GPU.
         */
        var dispatches: Int = 1
            set(value) {
                field = value
                module.current.meshes.clear()
                for (i in 0 until value) module.current.meshes["dispatch$i"] = meshes[i]
            }

        init {
            dispatches = 1
            renderer += "gpuSkinningParity" to module
        }

        private fun floats(name: String, count: Int): FrameBufferGroup =
            defaultBuffer(name = name) {
                size = count.floats
                // Host-cached: a buffer the CPU reads back must ask for it, or it lands in
                // write-combined memory where reads are pathological.
                streamReads()
            }.toBuffer(renderer)

        /** Upload the static half of a rig: joints, weights, and the bind pose. */
        fun loadRig(rig: Rig) {
            vertexCount = rig.vertexCount
            boneCount = rig.boneCount
            require(boneCount <= maxBones) { "${rig.label} has $boneCount bones, sized for $maxBones" }

            val infl = rig.influences
            jointBuffer.get(write = true).mapAndWrite_ints {
                for (v in 0 until rig.vertexCount) for (k in 0 until GpuSkinning.MAX_INFLUENCES) {
                    put(infl.jointIndex[v * GpuSkinning.MAX_INFLUENCES + k])
                }
            }
            weightBuffer.get(write = true).mapAndWrite {
                for (v in 0 until rig.vertexCount) for (k in 0 until GpuSkinning.MAX_INFLUENCES) {
                    put(infl.weight[v * GpuSkinning.MAX_INFLUENCES + k])
                }
            }
            bindBuffer.get(write = true).mapAndWrite {
                for (v in 0 until rig.vertexCount) {
                    put(rig.bindPositions[v * 3]); put(rig.bindPositions[v * 3 + 1])
                    put(rig.bindPositions[v * 3 + 2]); put(1f)
                    put(rig.bindNormals[v * 3]); put(rig.bindNormals[v * 3 + 1])
                    put(rig.bindNormals[v * 3 + 2]); put(0f)
                }
            }
        }

        fun uploadPalettes(palettes: List<List<Matrix4f>>) {
            instanceCount = palettes.size
            paletteBuffer.get(write = true).mapAndWrite {
                for (p in palettes) for (m in p) for (n in 0 until 16) put(m[n])
            }
        }

        /** Prefill the output with [SENTINEL], dispatch, wait, and read back. */
        fun run(readBack: Boolean = true): FloatArray {
            val outFloats = vertexCount * instanceCount * 8
            if (readBack) {
                outBuffer.get(write = true).mapAndWrite { repeat(outFloats) { put(SENTINEL) } }
            }
            val groups = ((vertexCount.toLong() * instanceCount + GpuSkinning.LOCAL_SIZE - 1) /
                GpuSkinning.LOCAL_SIZE).toInt().coerceAtLeast(1)
            for (m in meshes) m.x = groups

            frame()
            renderer.windowContext().computeFences.forEach { it.fenceWait() }

            if (!readBack) return FloatArray(0)
            val out = FloatArray(outFloats)
            outBuffer.get().mapAndRead { get(out, 0, outFloats) }
            return out
        }

        fun frame() {
            window.pollEvents()
            renderer.render()
        }

        /**
         * Wait for the shader to actually compile.
         *
         * Frames here are about a millisecond, so a frame count is not a wait: this needs wall
         * clock. Without it the first dispatch is a no-op and the output comes back all sentinel,
         * which reads as "the GPU computed nothing" rather than "the shader was not ready yet".
         */
        fun warmUp(seconds: Double = 3.0) {
            val deadline = System.nanoTime() + (seconds * 1e9).toLong()
            var frames = 0
            while (System.nanoTime() < deadline) {
                frame(); frames++
                Thread.sleep(2)
            }
            println("  warm-up: $frames frames over ${"%.1f".format(seconds)}s")
        }
    }

    private var dispatcher: Dispatcher? = null

    private fun dispatcherFor(renderer: Renderer, window: Window): Dispatcher =
        dispatcher ?: Dispatcher(
            renderer, window,
            maxVertices = PERF_VERTICES,
            maxInstances = PERF_INSTANCES,
        ).also {
            dispatcher = it
            it.warmUp()
        }

    // ------------------------------------------------------------------------------------------
    // Comparison
    // ------------------------------------------------------------------------------------------

    private class Worst(var position: Float = 0f, var normal: Float = 0f, var sentinels: Int = 0)

    /**
     * Compare one instance's slice of the GPU output against the CPU oracle.
     *
     * Reads the *whole* of both, rather than a sample: there are only tens of thousands of
     * vertices and a spot check is exactly how an off-by-one at a buffer boundary survives.
     */
    private fun compare(
        gpu: FloatArray, instance: Int, rig: Rig, cpu: GltfSkinning.SkinnedVertices, into: Worst,
    ) {
        val base = instance * rig.vertexCount * 8
        for (v in 0 until rig.vertexCount) {
            val o = base + v * 8
            for (k in 0..2) {
                val g = gpu[o + k]
                if (g == SENTINEL) { into.sentinels++; continue }
                into.position = maxOf(into.position, abs(g - cpu.position[v * 3 + k]))
            }
            val cn = cpu.normal ?: continue
            for (k in 0..2) {
                val g = gpu[o + 4 + k]
                if (g == SENTINEL) { into.sentinels++; continue }
                into.normal = maxOf(into.normal, abs(g - cn[v * 3 + k]))
            }
        }
    }

    private fun report(label: String, worst: Worst): Int {
        var failures = 0
        println(
            "  %-34s worst |dpos| %.3e   worst |dnormal| %.3e".format(
                label, worst.position, worst.normal
            )
        )
        if (worst.sentinels > 0) {
            println("  FAIL: $label left ${worst.sentinels} output slots unwritten"); failures++
        }
        if (!(worst.position <= POSITION_TOL)) {
            println("  FAIL: $label position error ${worst.position} exceeds $POSITION_TOL"); failures++
        }
        if (!(worst.normal <= NORMAL_TOL)) {
            println("  FAIL: $label normal error ${worst.normal} exceeds $NORMAL_TOL"); failures++
        }
        return failures
    }

    // ------------------------------------------------------------------------------------------
    // 1 & 2: both fixtures, every clip, several poses
    // ------------------------------------------------------------------------------------------

    private fun checkFixture(renderer: Renderer, window: Window, label: String, path: String): Int {
        println("--- $label: $path")

        val model = GltfSupport.loadGltfBasic(path, GltfSupport.GltfLoadConfig(loadTextures = false))
        val instance = model.meshes.first { it.skin != null }
        val rig = rigOf(label, instance)
        val skeleton = instance.skin!!.skeleton
        println(
            "  ${rig.vertexCount} vertices, ${rig.boneCount} bones, " +
                "clips ${skeleton.clips.map { it.name }}"
        )

        val d = dispatcherFor(renderer, window)
        d.loadRig(rig)

        var failures = 0
        val options = GltfSkinning.SkinningOptions()

        // The rest pose first: the GPU must return its input unchanged.
        run {
            val palette = skeleton.palette(null, 0f)
            d.uploadPalettes(listOf(palette))
            val gpu = d.run()
            val cpu = GltfSkinning.skin(rig.bindPositions, rig.bindNormals, rig.influences, palette, options)
            val worst = Worst()
            compare(gpu, 0, rig, cpu, worst)
            failures += report("rest pose", worst)
        }

        for (clip in skeleton.clips) {
            for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val seconds = t * clip.durationSeconds
                val palette = skeleton.palette(clip, seconds)
                d.uploadPalettes(listOf(palette))
                val gpu = d.run()
                val cpu = GltfSkinning.skin(rig.bindPositions, rig.bindNormals, rig.influences, palette, options)
                val worst = Worst()
                compare(gpu, 0, rig, cpu, worst)
                failures += report("clip '${clip.name}' t=%.2f".format(t), worst)
            }
        }
        return failures
    }

    // ------------------------------------------------------------------------------------------
    // 3: the crowd — per-instance blended palettes, each with its own placement
    // ------------------------------------------------------------------------------------------

    /**
     * $CROWD instances, every one at a *different* mix of two clips and in a different place.
     *
     * Deliberately all different: a crowd where every instance shares a palette would pass with
     * `paletteBase` hard-wired to 0, which is the single most likely way to get instancing wrong.
     */
    private fun checkCrowd(renderer: Renderer, window: Window): Int {
        println("--- crowd: $CROWD instances, each a different blend and a different place")
        val path = RiggedFixture.locate()
        val model = GltfSupport.loadGltfBasic(path, GltfSupport.GltfLoadConfig(loadTextures = false))
        val instance = model.meshes.first { it.skin != null }
        val rig = rigOf("crowd", instance)
        val skinner = GpuSkinner(instance, instances = CROWD, renderer = renderer, name = "crowdParity")

        val clips = skinner.clips
        require(clips.size >= 2) { "the crowd check needs two clips, this rig has $clips" }
        val a = clips[0]
        val b = clips[1]

        val palettes = (0 until CROWD).map { i ->
            skinner.blendedPalette(
                placement(i),
                a, (i * 0.031f) % 1f,
                b, (i * 0.047f) % 1f,
                mix = i / (CROWD - 1).toFloat(),
            )
        }

        val d = dispatcherFor(renderer, window)
        d.loadRig(rig)
        d.uploadPalettes(palettes)
        val gpu = d.run()

        val options = GltfSkinning.SkinningOptions()
        val worst = Worst()
        for (i in 0 until CROWD) {
            val cpu = GltfSkinning.skin(rig.bindPositions, rig.bindNormals, rig.influences, palettes[i], options)
            compare(gpu, i, rig, cpu, worst)
        }
        var failures = report("all $CROWD instances", worst)

        // And that the instances are not all the same mesh: if paletteBase were ignored, every
        // instance would agree with instance 0 and the check above would still pass.
        var maxSpread = 0f
        for (i in 1 until CROWD) {
            for (v in 0 until rig.vertexCount * 8) {
                maxSpread = maxOf(maxSpread, abs(gpu[i * rig.vertexCount * 8 + v] - gpu[v]))
            }
        }
        println("  largest difference between instance 0 and any other: %.4f".format(maxSpread))
        if (maxSpread < 1f) {
            println("  FAIL: every instance came back near-identical, so paletteBase is being ignored")
            failures++
        }
        return failures
    }

    /** Instance [i] placed on a grid, so a rendered crowd is a crowd and not one character. */
    fun placement(i: Int): Matrix4f {
        val columns = 8
        val x = (i % columns - (columns - 1) / 2f) * 2.2f
        val z = (i / columns - (columns - 1) / 2f) * 2.2f
        return Matrix4f().translate(afield.stdlib.linalg.Vector3f(x, 0f, z))
    }

    // ------------------------------------------------------------------------------------------
    // 4: throughput, CPU against GPU, at a stated population
    // ------------------------------------------------------------------------------------------

    /**
     * A synthetic rig with [PERF_VERTICES] vertices on the bending cylinder's two-bone skeleton.
     *
     * Synthetic and not a loaded file because there is no 50,000-vertex rigged asset in the
     * repository and generating one is cheaper than committing one. Every vertex has two
     * influences with weights that sum to 1, which is the ordinary case and the one worth
     * quoting; a rig with four influences everywhere would roughly double the CPU's inner loop
     * and change the GPU's not at all, so this is the *conservative* comparison.
     */
    private fun syntheticRig(boneCount: Int): Rig {
        val n = PERF_VERTICES
        val positions = FloatArray(n * 3)
        val normals = FloatArray(n * 3)
        val jointIndex = IntArray(n * 4)
        val weight = FloatArray(n * 4)
        val r = java.util.Random(4)
        for (v in 0 until n) {
            val y = 4f * v / n
            val theta = 2.0 * Math.PI * (v % 97) / 97.0
            positions[v * 3] = 0.5f * Math.cos(theta).toFloat()
            positions[v * 3 + 1] = y
            positions[v * 3 + 2] = 0.5f * Math.sin(theta).toFloat()
            normals[v * 3] = Math.cos(theta).toFloat()
            normals[v * 3 + 2] = Math.sin(theta).toFloat()
            val w = r.nextFloat()
            jointIndex[v * 4] = 0
            jointIndex[v * 4 + 1] = (1 % boneCount)
            weight[v * 4] = w
            weight[v * 4 + 1] = 1f - w
        }
        val infl = GltfSkinning.VertexInfluences(n, jointIndex, weight, 0, 0f)
        return Rig("synthetic ${n}v", positions, normals, infl, boneCount)
    }

    private fun measureThroughput(renderer: Renderer, window: Window) {
        println("--- throughput (population: $PERF_VERTICES vertices x $PERF_INSTANCES instances)")
        val path = RiggedFixture.locate()
        val model = GltfSupport.loadGltfBasic(path, GltfSupport.GltfLoadConfig(loadTextures = false))
        val skeleton = model.meshes.first { it.skin != null }.skin!!.skeleton
        val rig = syntheticRig(skeleton.bones.size)
        val palettes = (0 until PERF_INSTANCES).map { i ->
            skeleton.palette(skeleton.clips.first(), i * 0.01f).map { Matrix4f(placement(i)).mul(it) }
        }
        val options = GltfSkinning.SkinningOptions()
        val total = PERF_VERTICES.toLong() * PERF_INSTANCES

        // ---- CPU: the work the GPU is taking over. Warm the JIT first or the first call is the
        // interpreter and the number is meaningless.
        //
        // Best of three, not one run. Two consecutive runs of this smoke measured 55.8 ms and
        // 104.8 ms for the identical work — a 1.9x spread from whatever else the machine was
        // doing. A single sample would have quoted either as "the" CPU rate. The minimum is the
        // least contaminated sample available, and all three are printed so the spread is
        // visible rather than summarised away.
        repeat(2) { for (p in palettes) GltfSkinning.skin(rig.bindPositions, rig.bindNormals, rig.influences, p, options) }
        val cpuRuns = (0 until 3).map {
            val start = System.nanoTime()
            for (p in palettes) GltfSkinning.skin(rig.bindPositions, rig.bindNormals, rig.influences, p, options)
            System.nanoTime() - start
        }
        val cpuNanos = cpuRuns.min()
        println("  CPU runs (ms): ${cpuRuns.joinToString(", ") { "%.1f".format(it / 1e6) }} -- best taken")

        // ---- GPU: the same vertices through the same GLSL the vertex shader runs.
        //
        // Measured as a sweep, not as one subtraction. The presented frame is locked to the
        // display, so any GPU work smaller than one refresh is simply invisible: the first
        // version of this measured 8.35 ms against an 8.30 ms baseline and divided, which
        // produced "28,455 M vertices/s" -- noise with a unit on it. Scaling the number of
        // identical dispatches until the frame comes off the vsync floor, and taking the slope,
        // measures something real. The sweep is printed so the linearity can be checked rather
        // than assumed.
        val d = dispatcherFor(renderer, window)
        d.loadRig(rig)
        d.uploadPalettes(palettes)
        d.dispatches = 1
        repeat(3) { d.run(readBack = false) }

        val sweep = LinkedHashMap<Int, Long>()
        for (n in DISPATCH_SWEEP) {
            d.dispatches = n
            repeat(3) { d.run(readBack = false) }   // let the queue settle at this depth
            val start = System.nanoTime()
            repeat(FRAMES) { d.run(readBack = false) }
            sweep[n] = (System.nanoTime() - start) / FRAMES
        }
        d.dispatches = 1

        // The marginal cost between consecutive sweep points, and the median of those.
        //
        // A least-squares line over the whole sweep is the wrong summary here: the low end is
        // pinned to the vsync floor and the top end saturates, so both ends are flat for
        // different reasons and a single fit splits the difference between them. The interval
        // slopes make the linear stretch visible, and their median ignores both ends without
        // anyone having to choose a cut-off.
        println("  frame time against dispatch count (each dispatch is $total vertices):")
        val points = sweep.toList()
        val intervals = mutableListOf<Double>()
        for (i in points.indices) {
            val (n, nanos) = points[i]
            val marginal = if (i == 0) null else
                (nanos - points[i - 1].second).toDouble() / (n - points[i - 1].first)
            if (marginal != null) intervals += marginal
            println(
                "    %3d dispatches = %,12d vertices : %8.2f ms%s".format(
                    n, n * total, nanos / 1e6,
                    if (marginal == null) "" else "   marginal %6.3f ms/dispatch".format(marginal / 1e6)
                )
            )
        }
        // The quoted slope runs from the first point clearly off the vsync floor to the last.
        //
        // Not the median of the intervals and not a fit over everything. Below the floor the
        // marginal cost reads as zero or negative because the frame is waiting on the display,
        // and the interval marginals above it drift upward (0.52, 0.68, 0.78 ms in one run) as
        // the GPU saturates and clocks settle. An endpoint slope over the region that is
        // unambiguously doing work is the conservative reading of that: it includes the
        // saturation rather than quoting the best stretch.
        val floor = sweep.values.min()
        val firstReal = points.indexOfFirst { it.second > floor * 3 / 2 }
        require(firstReal in 0 until points.size - 1) {
            "no sweep point rose clearly above the ${floor / 1e6} ms frame floor -- widen DISPATCH_SWEEP"
        }
        val (n0, t0) = points[firstReal]
        val (n1, t1) = points.last()
        val nanosPerDispatch = (t1 - t0).toDouble() / (n1 - n0)
        println(
            "  slope taken from $n0 to $n1 dispatches (the region above the %.2f ms frame floor); "
                .format(floor / 1e6) +
                "interval marginals span %.3f..%.3f ms".format(intervals.min() / 1e6, intervals.max() / 1e6)
        )

        val paletteStart = System.nanoTime()
        repeat(FRAMES) { d.uploadPalettes(palettes) }
        val paletteNanos = (System.nanoTime() - paletteStart) / FRAMES

        println()
        println("  CPU GltfSkinning.skin        : %8.2f ms   %7.1f M vertices/s".format(cpuNanos / 1e6, total * 1e3 / cpuNanos))
        println("  GPU marginal cost per dispatch: %7.3f ms   %7.1f M vertices/s".format(nanosPerDispatch / 1e6, total * 1e3 / nanosPerDispatch))
        println("  speed-up, GPU over CPU       : %.0fx".format(cpuNanos / nanosPerDispatch))
        println("  CPU palette upload, per frame: %8.3f ms  ($PERF_INSTANCES x ${rig.boneCount} matrices)".format(paletteNanos / 1e6))
        println("  NOTE: the GPU figure is a compute dispatch of the same GLSL the vertex shader")
        println("        runs, obtained as the slope of frame time against repeated dispatches of")
        println("        the same work. It is throughput, not the latency of one dispatch, and it")
        println("        is not a draw-call measurement.")
    }

    private const val FRAMES = 20

    /** Repeat counts for the throughput sweep. Chosen to straddle the vsync floor. */
    private val DISPATCH_SWEEP = listOf(1, 8, 16, 32, 64, 96, 128)

    /** Upper bound on [DISPATCH_SWEEP], and therefore on the compute meshes built up front. */
    private const val MAX_DISPATCHES = 128
}
