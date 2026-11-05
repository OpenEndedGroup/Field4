package afield.examples.four

import afield.document.graph.Box
import afield.graphics.render.Module
import afield.graphics.render.fullscreenTriangle
import afield.graphics.render.pointer
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.vec
import afield.media.image.Img
import afield.simulation.agents.ShaderPhysarumSystem
import afield.simulation.agents.TrailField
import afield.simulation.agents.topShare
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.exp
import kotlin.system.exitProcess

/*
FIELD_TEST
id: alife.physarum-gpu.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.PhysarumGpuSmoke
tags: [smoke, four, alife, physarum, stigmergy, gpu, image]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "physarum gpu tests PASSED"
*/
/**
 * Runs [ShaderPhysarumSystem] (a million agents on a 1024x1024 [TrailField]) on a real renderer and
 * reads its trail back as floats, so the GPU network is measured the way `alife_examples.md` §7
 * measures the CPU one: `topShare(0.05)` of the sensing population against the same population
 * turning at random. Also checks [TrailField] on its own: a held channel keeps a deposit, a decaying
 * one halves in its half-life, and `clear()` empties it.
 *
 * Writes tone-mapped PNGs of each run to `build/physarum-gpu/`.
 */
object PhysarumGpuSmoke {
    private val outputDir = File(System.getProperty("afield.physarum.out") ?: "build/physarum-gpu")
    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        Options.doVulkanSDK()
        outputDir.mkdirs()
        val window = Window("Field · GPU physarum")
        val renderer = Renderer(window)
        fun hostBox(name: String) = Box(name).apply {
            this.name = name
            setPropertyRaw("renderer", renderer, doResync = false)
            setPropertyRaw("window", window, doResync = false)
        }
        try {
            val frames = (System.getProperty("afield.physarum.frames") ?: "600").toInt()
            var sensingShare = 0.0
            var blindShare = 0.0
            Box.withBoxContextNow(hostBox("physarum-sensing")) {
                check("sensing") { sensingShare = run(renderer, window, "sensing", frames) { } }
            }
            Box.withBoxContextNow(hostBox("physarum-blind")) {
                check("blind") { blindShare = run(renderer, window, "blind", frames) { sensing = false } }
            }
            println(" -- topShare(0.05): sensing ${"%.3f".format(sensingShare)}, blind ${"%.3f".format(blindShare)} (CPU page: 0.480 vs its blind arm)")
            expect("the sensing population concentrates its trail into a network (topShare > 0.15)", sensingShare > 0.15)
            expect("the blind control stays near flat (topShare < 0.10)", blindShare < 0.10)
            expect("sensing concentrates at least twice as much as blind", sensingShare > 2 * blindShare)

            Box.withBoxContextNow(hostBox("physarum-species")) {
                check("two species") {
                    run(renderer, window, "two-species", frames) { speciesCount = 2; name = "physarum-two" }
                }
            }
            Box.withBoxContextNow(hostBox("trail-field")) { check("trail field") { trailField(renderer, window) } }
            Box.withBoxContextNow(hostBox("physarum-placement")) { check("placement") { placement(renderer, window) } }
        } catch (t: Throwable) {
            println("FAIL: ${t.message}")
            t.printStackTrace()
            failures++
        } finally {
            runCatching { renderer.cleanup() }
            runCatching { window.cleanup() }
        }
        println(if (failures == 0) "physarum gpu tests PASSED — images in ${outputDir.absolutePath}"
                else "physarum gpu tests FAILED ($failures)")
        exitProcess(if (failures == 0) 0 else 1)
    }

    private fun run(renderer: Renderer, window: Window, label: String, frames: Int,
                    configure: afield.simulation.agents.ShaderPhysarumSettings.() -> Unit): Double {
        val system = ShaderPhysarumSystem(renderer) {
            name = "physarum-$label"
            configure()
        }
        system.installInto(withPreview = false)
        val t0 = System.nanoTime()
        pump(renderer, window, frames)
        val ms = (System.nanoTime() - t0) / 1e6 / frames
        val img = read(renderer, window, system.trail)
        renderer.renderList.preTargets.remove("${system.settings.name}-simulation")
        val shares = (0 until system.settings.speciesCount).map { channel(img, it).topShare(0.05) }
        val mean = (0 until system.settings.speciesCount).map { c -> channel(img, c).data.average() }
        println(" -- $label: ${system.settings.agentCount} agents, ${"%.2f".format(ms)} ms/frame, " +
            "mean trail ${mean.joinToString { "%.2f".format(it) }}, topShare(0.05) ${shares.joinToString { "%.3f".format(it) }}")
        expect("$label: the trail is finite and non-empty", img.data.all { it.isFinite() } && mean.all { it > 0.0 })
        writeToneMapped(img, File(outputDir, "$label.png"), system.settings.exposure, system.settings.speciesCount)
        return shares.first()
    }

    /** One agent standing still at a known pixel must deposit on exactly that pixel, or sensing reads a mirrored field. */
    private fun placement(renderer: Renderer, window: Window) {
        val system = ShaderPhysarumSystem(renderer) {
            name = "physarum-placement"
            width = 256
            height = 128
            agentCount = 1
            stepPx = 0f
            sensing = false
            turnAngle = 0f
        }
        system.trail.settings.diffusion = 0f
        system.agentBuffer.get(write = true).mapAndWrite_bytes {
            order(java.nio.ByteOrder.LITTLE_ENDIAN)
            clear()
            putFloat(40.5f); putFloat(100.5f); putFloat(0f); putInt(1)
            rewind()
        }
        system.installInto(withPreview = false)
        pump(renderer, window, 4)
        val img = read(renderer, window, system.trail)
        renderer.renderList.preTargets.remove("physarum-placement-simulation")
        var best = 0
        for (i in 0 until img.width * img.height) if (img.data[i * 4] > img.data[best * 4]) best = i
        val bx = best % img.width
        val by = best / img.width
        println(" -- placement: agent at (40, 100) deposited brightest at ($bx, $by), value ${img.data[best * 4]}")
        expect("an agent's deposit lands on the pixel it senses from (readback row 0 = pixel row 0)", bx == 40 && by == 100)
    }

    private fun trailField(renderer: Renderer, window: Window) {
        val field = TrailField(renderer) {
            name = "smoke-trail"
            width = 64
            height = 64
            halfLifeFrames = vec(-1f, 10f, 0f, -1f)
            diffusion = 0f
        }
        field.installInto()
        // one deposit pass of 1 into every channel, then remove it
        val pointer = Module(field.depositParameters("smoke-deposit") {
            vertex = TrailField.FULLSCREEN_TRIANGLE_VERTEX
            fragment = """
                #version 450
                layout(location = 0) out vec4 outColor;
                void main() { outColor = vec4(1.0); }
            """.trimIndent()
        }).pointer()
        pointer.current.meshes["quad"] = fullscreenTriangle(pointer)
        field.addDeposit("smoke-deposit", pointer)
        pump(renderer, window, 1)
        field.removeDeposit("smoke-deposit")
        pump(renderer, window, 10)
        val img = read(renderer, window, field)
        val px = { c: Int -> img.data[(32 * 64 + 32) * 4 + c] }
        println(" -- trail field after 1 deposit + ~10 frames: held ${px(0)}, half-life-10 ${px(1)}, cleared ${px(2)}")
        expect("a negative half-life holds the deposit", px(0) in 0.99f..1.01f)
        expect("a 10-frame half-life has roughly halved it", px(1) in 0.35f..0.6f)
        expect("a zero half-life clears it", px(2) == 0f)
        field.clear()
        pump(renderer, window, 3)
        expect("clear() empties every channel", read(renderer, window, field).data.all { it == 0f })
        renderer.renderList.preTargets.remove("smoke-trail-field")
    }

    private fun read(renderer: Renderer, window: Window, field: TrailField): Img {
        val future = field.read()
        repeat(6) { if (!future.isDone) pump(renderer, window, 1) }
        return future.get(20, TimeUnit.SECONDS)
    }

    private fun channel(img: Img, c: Int) = Img(img.width, img.height, 1, FloatArray(img.width * img.height) { img.data[it * 4 + c] })

    private fun writeToneMapped(img: Img, file: File, exposure: Float, species: Int) {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val palette = TrailField.DEFAULT_PALETTE
        for (y in 0 until img.height) for (x in 0 until img.width) {
            var r = 0.0; var g = 0.0; var b = 0.0
            for (c in 0 until species) {
                val k = 1 - exp(-exposure * maxOf(0f, img.data[(y * img.width + x) * 4 + c]).toDouble())
                r += k * palette[c].x; g += k * palette[c].y; b += k * palette[c].z
            }
            fun q(v: Double) = (v.coerceIn(0.0, 1.0) * 255).toInt()
            out.setRGB(x, y, (q(r) shl 16) or (q(g) shl 8) or q(b))
        }
        ImageIO.write(out, "png", file)
    }

    private fun pump(renderer: Renderer, window: Window, frames: Int) {
        repeat(frames) {
            window.pollEvents()
            renderer.render()
        }
    }

    private fun check(what: String, body: () -> Unit) {
        try { body() } catch (t: Throwable) {
            println("FAIL: $what — ${t.message}")
            t.printStackTrace()
            failures++
        }
    }

    private fun expect(what: String, ok: Boolean) {
        println(if (ok) "ok: $what" else "FAIL: $what")
        if (!ok) failures++
    }
}
