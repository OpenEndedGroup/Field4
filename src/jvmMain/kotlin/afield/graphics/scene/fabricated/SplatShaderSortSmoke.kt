package afield.graphics.scene.fabricated

import afield.document.graph.Box
import afield.graphics.render.ComputeModule
import afield.graphics.render.pointer
import afield.graphics.render.parameters
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Camera
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.Matrix4f
import afield.math.linalg.Vector4f
import kotlin.random.Random
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.splat-shader.smoke
runner: jvm-main
entrypoint: afield.graphics.scene.fabricated.SplatShaderSortSmoke
requiresDisplay: true
timeoutSec: 300
tags: [smoke, four, splat, gpu, sort]
expect:
  exitCode: 0
  stdoutContains:
    - "splat shader sort smoke PASSED"
  stdoutNotContains: ["VUID-"]
  stderrNotContains: ["VUID-"]
*/
/**
 * Does the splat depth sort see where a [SplatShader] insert *put* the splats? Measured on the GPU.
 *
 * Every case draws the same 4 096 splats, scattered through a cube, and reads back what the sort
 * produced (`sortedIndices`) and where the splats are (the cloud's own buffer), then counts the
 * adjacent pairs drawn in the wrong order: a nearer splat drawn before a farther one, which
 * `Blend.Over` composites wrongly. A correct back-to-front order has none.
 *
 * 1. **The vertex-shader seam** (what editing `gaussianSplatModuleHere`'s vertex shader gives): a
 *    plain [GaussianSplatRenderer.Cloud] over the splats, with the insert `pos.z = -pos.z` applied
 *    only where it is drawn. The sort sees the unmirrored splats, so about half of all pairs are
 *    out of order — the order is reversed relative to what is drawn.
 * 2. **The [SplatShader] seam**, same insert: the sort reads the inserted splats. None out of order.
 * 3. **Moving splats outside the uploaded bounds** (`pos *= 3.0`), with the old bounds-normalised
 *    keys and then with [GaussianSplatRenderer.DepthKey.ViewDepth]. Bounds keys clamp everything
 *    outside the box onto the same key, so many pairs come out in arbitrary order; view-depth keys
 *    put none out of order.
 * 4. **An insert that changes with `t` under a still camera**: with `sortOnlyWhenViewChanges` the
 *    sort is never redone and the order goes stale once `t` flips the cloud; without it, it stays
 *    right.
 */
object SplatShaderSortSmoke {
    private const val COUNT = 4096

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        Options.enableHDR = false
        Options.logShadercSource = false
        Options.doVulkanSDK()
        val window = Window("Field · splat shader sort smoke")
        val renderer = Renderer(window)
        val box = Box("splat-shader-sort-smoke")
        box.setPropertyRaw("renderer", renderer, doResync = false)
        box.setPropertyRaw("window", window, doResync = false)
        var failed = false
        try {
            Box.withBoxContextNow(box) { failed = !run(renderer, window) }
        } catch (t: Throwable) {
            t.printStackTrace()
            failed = true
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        if (failed) {
            println("splat shader sort smoke FAILED")
            exitProcess(1)
        }
        println("splat shader sort smoke PASSED")
        exitProcess(0)
    }

    private fun cube(): SplatSet {
        val random = Random(7)
        val set = SplatSet(COUNT)
        repeat(COUNT) {
            set.push(
                random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0),
                0.01, 0.01, 0.01, 0.0, 0.0, 0.0, 1.0, 0.8, 1.0, 1.0, 1.0
            )
        }
        return set
    }

    private fun pump(renderer: Renderer, window: Window, frames: Int) {
        repeat(frames) {
            window.pollEvents()
            renderer.render()
        }
        val ctx = renderer.windowContext()
        ctx.fences.forEach { it.fenceWait() }
        ctx.computeFences.forEach { it.fenceWait() }
    }

    private fun readInts(cloud: GaussianSplatRenderer.Cloud, n: Int): IntArray {
        val out = IntArray(n)
        requireNotNull(cloud.sortedIndices.result).mapAndRead_ints { get(out, 0, n) }
        return out
    }

    private fun readSplats(cloud: GaussianSplatRenderer.Cloud, n: Int): FloatArray {
        val out = FloatArray(n * SplatSet.STRIDE)
        requireNotNull(cloud.splatData.result).mapAndRead { get(out, 0, out.size) }
        return out
    }

    /**
     * Adjacent pairs in draw order where the later one is farther than the earlier one by more
     * than [epsilon], given each splat's centre (after [transformZ] for the vertex-seam case).
     */
    private fun outOfOrder(
        order: IntArray, splats: FloatArray, modelView: Matrix4f, epsilon: Float = 1e-5f,
        transformZ: (Float) -> Float = { it }
    ): Int {
        val depth = FloatArray(order.size) { slot ->
            val o = order[slot] * SplatSet.STRIDE
            -modelView.transform(Vector4f(splats[o], splats[o + 1], transformZ(splats[o + 2]), 1f)).z
        }
        var wrong = 0
        for (slot in 0 until order.size - 1) if (depth[slot + 1] > depth[slot] + epsilon) wrong++
        return wrong
    }

    private fun insertModule(renderer: Renderer, name: String, insert: String) =
        ComputeModule(parameters(renderer) {
            this.name = name
            compute = SplatShader.computeSource(insert)
        }).pointer()

    private fun run(renderer: Renderer, window: Window): Boolean {
        val splats = cube()
        var ok = true
        fun claim(label: String, measured: Int, passes: Boolean) {
            println("${if (passes) "PASS" else "FAIL"} $label: $measured of ${COUNT - 1} adjacent pairs out of order")
            if (!passes) ok = false
        }
        fun shader(name: String, insert: String, configure: SplatShaderSettings.() -> Unit = {}) =
            SplatShader(insertModule(renderer, name, insert), splats, renderer) {
                this.name = name
                autoRotate = 0.0
                fit = false
                configure()
            }

        // 1 and 2: the same mirror, two seams.
        val mirrored = shader("smoke-mirror", "pos.z = -pos.z;")
        pump(renderer, window, 6)
        val mirroredSplats = readSplats(mirrored.cloud, COUNT)
        val newSeam = outOfOrder(readInts(mirrored.cloud, COUNT), mirroredSplats, mirrored.cloud.camera.modelView().mul(mirrored.model()))
        mirrored.remove()

        val unmirrored = shader("smoke-identity", "")
        pump(renderer, window, 6)
        val packed = readSplats(unmirrored.cloud, COUNT)
        unmirrored.remove()
        val camera = Camera(renderer).apply { at = unmirrored.cloud.camera.at; target = unmirrored.cloud.camera.target }
        val (min, max) = splats.bounds()
        val plain = GaussianSplatRenderer.Cloud(
            renderer = renderer, camera = camera, table = null,
            packed = GaussianSplatRenderer.PackedSplats(packed, COUNT, min, max),
            config = GaussianSplatRenderer.Config(), sourceName = "smoke-vertex-seam"
        ).install()
        pump(renderer, window, 6)
        val vertexSeam = outOfOrder(readInts(plain, COUNT), packed, camera.modelView()) { -it }
        plain.remove()
        // reversed against what is drawn: nearly every pair is wrong
        claim("vertex-shader seam, insert pos.z = -pos.z", vertexSeam, vertexSeam > COUNT / 3)
        claim("SplatShader seam, insert pos.z = -pos.z", newSeam, newSeam == 0)

        // 3: out of the uploaded bounds.
        val grown = shader("smoke-grown", "pos *= 3.0;")
        grown.cloud.depthKey = GaussianSplatRenderer.DepthKey.Bounds
        pump(renderer, window, 6)
        val grownView = grown.cloud.camera.modelView().mul(grown.model())
        val boundsKeys = outOfOrder(readInts(grown.cloud, COUNT), readSplats(grown.cloud, COUNT), grownView)
        grown.cloud.depthKey = GaussianSplatRenderer.DepthKey.ViewDepth
        pump(renderer, window, 6)
        val viewKeys = outOfOrder(readInts(grown.cloud, COUNT), readSplats(grown.cloud, COUNT), grownView)
        grown.remove()
        claim("bounds-normalised keys, insert pos *= 3.0", boundsKeys, boundsKeys > 50)
        claim("view-depth keys, insert pos *= 3.0", viewKeys, viewKeys == 0)

        // 4: a change in t under a still camera.
        val flip = "if (t > 0.5) pos.z = -pos.z;"
        val stale = shader("smoke-stale", flip)
        stale.playing = false
        stale.cloud.sortOnlyWhenViewChanges = true
        pump(renderer, window, 4)
        stale.t = 1.0
        pump(renderer, window, 4)
        val staleView = stale.cloud.camera.modelView().mul(stale.model())
        val staleOrder = outOfOrder(readInts(stale.cloud, COUNT), readSplats(stale.cloud, COUNT), staleView)
        stale.cloud.sortOnlyWhenViewChanges = false
        pump(renderer, window, 4)
        val freshOrder = outOfOrder(readInts(stale.cloud, COUNT), readSplats(stale.cloud, COUNT), staleView)
        stale.remove()
        claim("sort only when the view moves, t flips the cloud", staleOrder, staleOrder > COUNT / 3)
        claim("sort every frame, t flips the cloud", freshOrder, freshOrder == 0)
        return ok
    }
}
