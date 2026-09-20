package afield.four.modules

import afield.four.Renderer
import afield.four.targets.RenderTarget
import afield.four.util.Camera
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Render GPU-skinned characters and write frames a person can look at.
 *
 * `./gradlew --no-daemon runGpuSkinningEvidence`
 *
 * `SkinningEvidence` is the CPU companion to this, and it renders through
 * `VisionGeometryPreview.renderPng` — a **software** rasteriser fed a `TriMesh`. Its pictures
 * prove the deformation and say nothing about the GPU. These go through the real Vulkan
 * renderer, the real [GpuSkinning.skinnedVertexShader], and a real `vkCmdDraw`.
 *
 * ## The comparison that means something
 *
 * A picture of a bent cylinder is weak evidence: it is bent, and that is about all it says. So
 * every pose here is rendered **twice through the identical pipeline** — same window, same
 * camera, same shaders, same frame — once with the GPU doing the skinning
 * (`skinEnabled = 1`) and once with [CpuSkinner] posing the vertex buffer and the shader passing
 * it straight through (`skinEnabled = 0`, which is the stock glTF path bit for bit). Then the
 * two PNGs are differenced.
 *
 * That is a comparison a pixel difference can actually answer, because the only variable
 * between the two frames is *where the skinning happened*. Differencing against
 * the PNGs in `dev_docs/agents/evidence/skinning` would not be: those come from a rasteriser
 * with a different camera, and any number out of it would be a number about two renderers.
 *
 * ## Why the window and not an offscreen target
 *
 * Backlog Z7 records that `native-screenshot` times out against a per-frame `applyToMesh`, which
 * is why last night's GPU demo poses once instead of animating. That trap is **not reachable
 * here**: GPU skinning re-uploads nothing per frame — the vertex buffer is written once and the
 * per-frame write is a palette of a few dozen matrices — so `captureWindowToFile` has nothing to
 * race against. Whether that holds is itself worth knowing, so this uses the direct window
 * capture deliberately rather than routing around it, and says so in its output.
 *
 * @see GpuSkinner
 * @see afield.examples.four.GpuSkinningSmoke for the numerical half
 */
object GpuSkinningEvidence {

    const val EVIDENCE_DIR: String = "dev_docs/agents/evidence/gpu_skinning"

    private const val WIDTH = 900
    private const val HEIGHT = 700

    /** Frames to pump before a capture, so the palette write is certainly on screen. */
    private const val SETTLE_FRAMES = 30

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val out = File(EVIDENCE_DIR)
        out.mkdirs()

        val window = Window("GPU Skinning Evidence")
        val renderer = Renderer(window)
        try {
            val rt = RenderTarget(renderer)
            renderer.renderList = rt

            val path = RiggedFixture.locate()
            val model = GltfSupport.loadGltfBasic(path, GltfSupport.GltfLoadConfig(loadTextures = false))
            val instance = model.meshes.first { it.skin != null }

            val module = Module(
                Parameters(
                    renderer = renderer,
                    vertex = GpuSkinning.skinnedVertexShader,
                    fragment = GpuSkinning.skinnedFragmentShader,
                    name = "gpu-skinning-evidence",
                )
            )
            rt += "skinned" to module
            val pointer = module.pointer()
            val mesh = Mesh(renderer, pointer)
            module += "character" to mesh

            val camera = Camera(renderer)
            val skinner = GpuSkinner(instance, instances = CROWD, renderer = renderer, name = "evidence")
            val cpu = CpuSkinner(instance)

            println("fixture   : $path")
            println("mesh      : ${instance.name}, ${skinner.vertexCount} vertices, ${skinner.boneCount} bones")
            println("clips     : ${skinner.clips}")
            println("output    : ${out.absolutePath}")
            println()

            // ---------------------------------------------------------------- one character
            frameSingle(camera)
            skinner.bindTo(mesh, camera, instanceCount = 1, tint = false)

            var worstDiff = 0.0
            for (t in listOf(0f, 0.5f, 1f)) {
                val label = "bend_t%03d".format((t * 100).toInt())
                worstDiff = maxOf(
                    worstDiff,
                    pair(renderer, window, mesh, camera, skinner, cpu, instance, out, label) {
                        skinner.uploadPalette(skinner.placedPalette(Matrix4f(), "bend", t))
                        cpu.pose("bend", t)
                    }
                )
            }

            // A blend of two clips, which the CPU path can also do, so it can be differenced too.
            worstDiff = maxOf(
                worstDiff,
                pair(renderer, window, mesh, camera, skinner, cpu, instance, out, "blend_bend_curl") {
                    val sk = skinner.skin.skeleton
                    skinner.uploadPalette(
                        skinner.blendedPalette(Matrix4f(), "bend", 1f, "curl", 1f, mix = 0.5f)
                    )
                    cpu.pose(sk.blendedPalette(sk.clip("bend"), 1f, sk.clip("curl"), 1f, 0.5f))
                }
            )

            println()
            println("worst mean pixel difference over all GPU/CPU pairs: %.4f / 255".format(worstDiff))
            if (worstDiff > PIXEL_TOL) {
                println(
                    "FAIL: the GPU frame and the CPU frame are not the same picture " +
                        "($worstDiff > $PIXEL_TOL mean levels)"
                )
                exitProcess(1)
            }
            println("the GPU-skinned frame and the CPU-skinned frame are the same picture.")

            // ---------------------------------------------------------------- the control
            //
            // The pairs above came back *bit-identical*, and that is exactly the result a
            // completely dead comparison would give. If `skinEnabled` were being ignored, or if
            // the second capture were returning a stale frame, both PNGs would be the GPU frame
            // and the difference would be zero for the wrong reason.
            //
            // So: render the same camera with skinning off and the *bind pose* in the buffer.
            // That must be a visibly different picture. If it is not, nothing above means
            // anything, and this fails loudly rather than letting a zero speak for itself.
            println()
            control(renderer, window, mesh, skinner, out)

            // ---------------------------------------------------------------- the crowd
            println()
            crowd(renderer, window, mesh, camera, skinner, out)

            println()
            println("gpu skinning evidence written")
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        exitProcess(0)
    }

    /** Instances in the crowd frame. */
    private const val CROWD = 64

    /**
     * How far apart two frames of the same pose may be, as a mean 0..255 level over all channels.
     *
     * Not zero. The two paths compute the same numbers to about 1e-6 (see `GpuSkinningSmoke`) but
     * they are not bit-identical, and a vertex a millionth of a unit away can still land on the
     * other side of a triangle edge and flip one pixel. A *mean* tolerance this tight — a
     * fortieth of one level out of 255 — cannot absorb a real difference: a wrong palette index
     * or a dropped normal matrix moves entire regions of the image, not single pixels.
     */
    private const val PIXEL_TOL = 0.025

    private fun frameSingle(camera: Camera) {
        camera.p.orthoness = 0.3
        camera.p.matchDepth = 2.0
        camera.target = Vector3f(0f, 2f, 0f)
        camera.at = Vector3f(1.4f, 2.6f, -5.6f)
        camera.p()
    }

    /**
     * Render [label] twice — GPU-skinned then CPU-skinned — and return the mean absolute pixel
     * difference between them.
     */
    private fun pair(
        renderer: Renderer,
        window: Window,
        mesh: Mesh,
        camera: Camera,
        skinner: GpuSkinner,
        cpu: CpuSkinner,
        instance: GltfSupport.MeshInstance,
        out: File,
        label: String,
        pose: () -> Unit,
    ): Double {
        // --- GPU. The bind pose is already uploaded; only the palette changes.
        skinner.restoreBindPose(mesh)
        skinner.updateCamera(mesh, camera)
        pose()
        val gpuFile = capture(renderer, window, File(out, "gpu_$label.png"))

        // --- CPU. `pose()` also ran CpuSkinner.pose, which rewrote instance.buffers in place, so
        // uploading them now sends the *posed* vertices and the shader passes them straight
        // through. That is exactly the path last night shipped, through this same pipeline.
        skinner.uploadPosedVertices(mesh)
        val cpuFile = capture(renderer, window, File(out, "cpu_$label.png"))

        val d = difference(gpuFile, cpuFile, File(out, "diff_$label.png"))
        println("  %-18s gpu %,8d B   cpu %,8d B   mean |gpu-cpu| %.4f levels   max %d".format(
            label, gpuFile.length(), cpuFile.length(), d.mean, d.max
        ))
        if (d.nonBackground < MIN_INK) {
            println("  FAIL: $label rendered only ${d.nonBackground} non-background pixels -- the frame is blank")
            exitProcess(1)
        }
        return d.mean
    }

    /** Below this many non-background pixels the frame is blank and the evidence is worthless. */
    private const val MIN_INK = 2000

    /**
     * How different the bind pose must look from the 60° bend for the zero above to mean
     * anything. A tenth of a level, mean, over the whole frame — four times [PIXEL_TOL].
     */
    private const val CONTROL_MIN = 0.1

    /**
     * Draw the **bind pose** with skinning off, and require it to differ from the GPU-skinned
     * bend. The falsification test for the zero difference the pairs report.
     */
    private fun control(
        renderer: Renderer,
        window: Window,
        mesh: Mesh,
        skinner: GpuSkinner,
        out: File,
    ) {
        skinner.restoreBindPose(mesh)          // bind pose back into the GPU buffer
        mesh.push["skinEnabled"] = 0           // ... and do not skin it
        val bind = capture(renderer, window, File(out, "control_unskinned.png"))
        val d = difference(bind, File(out, "gpu_bend_t100.png"), File(out, "diff_control.png"))
        println(
            "  %-18s mean |unskinned - gpu bend| %.4f levels   max %d".format(
                "control_unskinned", d.mean, d.max
            )
        )
        if (d.mean < CONTROL_MIN) {
            println(
                "  FAIL: turning skinning off changed the picture by only ${d.mean} levels. " +
                    "Either skinEnabled is being ignored or the capture is returning a stale " +
                    "frame -- so the zero difference between the GPU and CPU frames above is " +
                    "not evidence of anything."
            )
            exitProcess(1)
        }
        println("  control passed: the pipeline does produce a different picture when asked to.")
        skinner.restoreBindPose(mesh)
    }

    private fun crowd(
        renderer: Renderer,
        window: Window,
        mesh: Mesh,
        camera: Camera,
        skinner: GpuSkinner,
        out: File,
    ) {
        camera.p.orthoness = 0.25
        camera.p.matchDepth = 2.0
        camera.target = Vector3f(0f, 2f, 0f)
        camera.at = Vector3f(6.5f, 8.5f, -17f)
        camera.p()

        skinner.restoreBindPose(mesh)
        skinner.bindTo(mesh, camera, instanceCount = CROWD, tint = true)
        val clips = skinner.clips
        skinner.uploadPalettes((0 until CROWD).map { i ->
            skinner.blendedPalette(
                grid(i),
                clips[0], (i * 0.031f) % 1f,
                clips[1], (i * 0.047f) % 1f,
                mix = i / (CROWD - 1).toFloat(),
            )
        })
        val file = capture(renderer, window, File(out, "crowd_64.png"))
        val ink = inkOf(file)
        println("  %-18s %,8d B   %,d non-background pixels   $CROWD instances, one draw call".format(
            "crowd_64", file.length(), ink
        ))
        if (ink < MIN_INK) {
            println("  FAIL: the crowd frame is blank")
            exitProcess(1)
        }
    }

    /** Instance [i] on an 8x8 grid. */
    private fun grid(i: Int): Matrix4f {
        val columns = 8
        val x = (i % columns - (columns - 1) / 2f) * 2.2f
        val z = (i / columns - (columns - 1) / 2f) * 2.2f
        return Matrix4f().translate(Vector3f(x, 0f, z))
    }

    // ----------------------------------------------------------------------------------------
    // Capture
    // ----------------------------------------------------------------------------------------

    /**
     * Pump [SETTLE_FRAMES] frames, then capture the window to [file].
     *
     * `captureWindowToFile` is a `suspend` function that *enqueues* onto the render loop and only
     * completes once a later frame records the transfer, so calling it from the render thread
     * would deadlock. It runs on IO while this keeps pumping — the shape `ImGuiVrPanelDemo` uses.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun capture(renderer: Renderer, window: Window, file: File): File {
        file.parentFile?.mkdirs()
        file.delete()
        repeat(SETTLE_FRAMES) { window.pollEvents(); renderer.render() }

        val done = CompletableDeferred<Result<String>>()
        GlobalScope.launch(Dispatchers.IO) {
            done.complete(runCatching { renderer.captureWindowToFile(window, file.absolutePath) })
        }
        var pumped = 0
        while (!done.isCompleted && pumped++ < 600) { window.pollEvents(); renderer.render() }
        val result = runBlocking { done.await() }
        result.getOrElse { error("window capture of ${file.name} failed after $pumped frames: $it") }
        require(file.length() > 0L) { "window capture of ${file.name} wrote an empty file" }
        return downscale(file)
    }

    /** Longest side of a committed frame. */
    private const val MAX_DIM = 1400

    /**
     * Shrink the capture in place.
     *
     * The window is maximised on a retina display, so a raw capture is 4112x2524 and the set of
     * them came to 2.8 MB — four times what `SkinningEvidence` spends on the same information.
     * Scaled down, the deformation and the crowd are exactly as legible and the pixel difference
     * is computed on the scaled images, so a difference that survives resampling is a real one.
     */
    private fun downscale(file: File): File {
        val src = ImageIO.read(file) ?: error("could not read back ${file.name}")
        if (maxOf(src.width, src.height) <= MAX_DIM) return file
        val scale = MAX_DIM.toDouble() / maxOf(src.width, src.height)
        val w = (src.width * scale).toInt()
        val h = (src.height * scale).toInt()
        val dst = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
        g.drawImage(src.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
        g.dispose()
        ImageIO.write(dst, "png", file)
        return file
    }

    private class Diff(val mean: Double, val max: Int, val nonBackground: Int)

    /**
     * Mean and maximum absolute per-channel difference between two PNGs, with the difference
     * itself written out amplified so a person can see where it is.
     */
    private fun difference(a: File, b: File, into: File): Diff {
        val ia = ImageIO.read(a) ?: error("could not read ${a.name}")
        val ib = ImageIO.read(b) ?: error("could not read ${b.name}")
        require(ia.width == ib.width && ia.height == ib.height) {
            "${a.name} is ${ia.width}x${ia.height} but ${b.name} is ${ib.width}x${ib.height}"
        }
        val diff = java.awt.image.BufferedImage(ia.width, ia.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        var total = 0L
        var max = 0
        var ink = 0
        // The clear colour, sampled from a corner: what counts as "background" is the renderer's
        // business, not a constant this file should guess at.
        val background = ia.getRGB(1, 1)
        for (y in 0 until ia.height) for (x in 0 until ia.width) {
            val pa = ia.getRGB(x, y)
            val pb = ib.getRGB(x, y)
            if (pa != background) ink++
            var worst = 0
            var amplified = 0
            for (shift in listOf(16, 8, 0)) {
                val d = abs(((pa shr shift) and 0xff) - ((pb shr shift) and 0xff))
                total += d
                if (d > worst) worst = d
                // x16, so a difference of 1 level is visible rather than indistinguishable
                // from black. Saturating, so a large difference does not wrap around to dark.
                amplified = amplified or ((d * 16).coerceAtMost(255) shl shift)
            }
            if (worst > max) max = worst
            diff.setRGB(x, y, amplified)
        }
        ImageIO.write(diff, "png", into)
        return Diff(total.toDouble() / (ia.width * ia.height * 3), max, ink)
    }

    private fun inkOf(file: File): Int {
        val img = ImageIO.read(file) ?: error("could not read ${file.name}")
        val background = img.getRGB(1, 1)
        var ink = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            if (img.getRGB(x, y) != background) ink++
        }
        return ink
    }
}
