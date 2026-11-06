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
import afield.simulation.agents.stepTimes
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
            Box.withBoxContextNow(hostBox("physarum-territories")) { check("territories") { territories(renderer, window, frames) } }
            Box.withBoxContextNow(hostBox("physarum-exclusion")) { check("exclusion") { exclusion(renderer, window) } }
            Box.withBoxContextNow(hostBox("trail-followers")) { check("followers") { followers(renderer, window) } }
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

    /**
     * Fraction of marked pixels (total trail above [floor] of the mean) where one channel holds at
     * least [purity] of the total, over the first [species] channels: 1 for disjoint territories, low
     * when the species share their roads. Also returns each species' share of the dominated area.
     */
    private fun segregation(img: Img, species: Int, purity: Double = 0.8, floor: Double = 0.5): Pair<Double, List<Double>> {
        val n = img.width * img.height
        val totals = DoubleArray(n) { i -> (0 until species).sumOf { maxOf(0f, img.data[i * 4 + it]).toDouble() } }
        val cut = floor * totals.average()
        var marked = 0
        var pure = 0
        val owned = IntArray(species)
        for (i in 0 until n) {
            if (totals[i] <= cut) continue
            marked++
            val best = (0 until species).maxBy { img.data[i * 4 + it] }
            if (img.data[i * 4 + best] >= purity * totals[i]) { pure++; owned[best]++ }
        }
        return (pure.toDouble() / maxOf(1, marked)) to owned.map { it.toDouble() / maxOf(1, pure) }
    }

    /**
     * Two and three species with the Jones-style repulsion matrix form territories; the same agents
     * attracted by every channel alike share one network. Measured by [segregation].
     */
    private fun territories(renderer: Renderer, window: Window, frames: Int) {
        fun one(label: String, species: Int, configure: afield.simulation.agents.ShaderPhysarumSettings.() -> Unit): Double {
            val system = ShaderPhysarumSystem(renderer) {
                name = "physarum-$label"
                width = 512; height = 512
                agentCount = 250_000
                speciesCount = species
                exposure = 0.12f
                configure()
            }
            system.installInto(withPreview = false)
            pump(renderer, window, frames)
            val img = read(renderer, window, system.trail)
            renderer.renderList.preTargets.remove("${system.settings.name}-simulation")
            val (seg, owned) = segregation(img, species)
            val tops = (0 until species).map { channel(img, it).topShare(0.05) }
            println(" -- $label: segregation ${"%.3f".format(seg)}, area shares ${owned.joinToString { "%.2f".format(it) }}, " +
                "topShare ${tops.joinToString { "%.3f".format(it) }}")
            writeToneMapped(img, File(outputDir, "$label.png"), system.settings.exposure, species)
            return seg
        }
        val two = one("territories-2", 2) { repulsion = 1f }
        val three = one("territories-3", 3) { repulsion = 1f }
        val shared = one("territories-shared", 2) { attraction = listOf(vec(1f, 1f, 0f, 0f), vec(1f, 1f, 0f, 0f)) }
        val chase = one("territories-chase", 2) {
            // species 1 marks channel 0 as well as its own; species 0 flees channel 0 marks it did not lay itself
            attraction = listOf(vec(1f, -2f, 0f, 0f), vec(1f, 1f, 0f, 0f))
            depositInto = listOf(vec(1f, 0f, 0f, 0f), vec(0.5f, 1f, 0f, 0f))
        }
        expect("two repelling species hold distinct territories (segregation > 0.6)", two > 0.6)
        expect("three repelling species hold distinct territories (segregation > 0.5)", three > 0.5)
        expect("species attracted by every channel alike share their roads (segregation < 0.5x the repelling run)", shared < 0.5 * two)
        expect("the chase matrix runs and stays finite", chase.isFinite())
    }

    /**
     * Jones' exclusion on the GPU against none, at the density Jones uses, and the CPU
     * [afield.simulation.agents.PhysarumSwarm] on the same 256 x 256 grid with 6000 agents in a disc
     * for 400 steps: the page's experiment. Prints the numbers the docs quote.
     */
    private fun exclusion(renderer: Renderer, window: Window) {
        fun gpu(label: String, exclusive: Boolean, sensing: Boolean, size: Int, agents: Int, disc: Float, frames: Int): Double {
            val system = ShaderPhysarumSystem(renderer) {
                name = "physarum-$label"
                width = size; height = size
                agentCount = agents
                spawnRadiusFraction = disc
                this.exclusive = exclusive
                this.sensing = sensing
                exposure = 0.3f
            }
            system.installInto(withPreview = false)
            val t0 = System.nanoTime()
            pump(renderer, window, frames)
            val ms = (System.nanoTime() - t0) / 1e6 / frames
            val img = read(renderer, window, system.trail)
            renderer.renderList.preTargets.remove("${system.settings.name}-simulation")
            val share = channel(img, 0).topShare(0.05)
            println(" -- $label: $agents agents on ${size}x$size, exclusive=$exclusive sensing=$sensing, " +
                "topShare(0.05) ${"%.3f".format(share)}, ${"%.2f".format(ms)} ms/frame")
            writeToneMapped(img, File(outputDir, "$label.png"), system.settings.exposure, 1)
            return share
        }
        val p = afield.simulation.agents.PhysarumParams()
        val cpuSensing = afield.simulation.agents.PhysarumSwarm.disc(256, 256, 6000, 0.55, seed = 6L).stepTimes(p, 400, seed = 6L).trail.topShare(0.05)
        val cpuBlind = afield.simulation.agents.PhysarumSwarm.disc(256, 256, 6000, 0.55, seed = 6L).stepTimes(p.copy(sensing = false), 400, seed = 6L).trail.topShare(0.05)
        val cpuCollapse = afield.simulation.agents.PhysarumSwarm.disc(256, 256, 6000, 0.55, seed = 6L).stepTimes(p.copy(exclusive = false), 400, seed = 6L).trail.topShare(0.05)
        println(" -- cpu 6000 on 256x256 disc 0.55, 400 steps: sensing ${"%.3f".format(cpuSensing)}, blind ${"%.3f".format(cpuBlind)}, no exclusion ${"%.3f".format(cpuCollapse)}")
        val gpuEx = gpu("cpu-match-exclusive", true, true, 256, 6000, 0.55f, 400)
        val gpuBlind = gpu("cpu-match-blind", true, false, 256, 6000, 0.55f, 400)
        val gpuFree = gpu("cpu-match-free", false, true, 256, 6000, 0.55f, 400)
        expect("GPU exclusion keeps a network short of collapse (topShare < the non-exclusive run)", gpuEx < gpuFree)
        expect("GPU exclusive sensing beats its blind control", gpuEx > 1.5 * gpuBlind)
        expect("GPU and CPU exclusive runs agree within 0.15 topShare", kotlin.math.abs(gpuEx - cpuSensing) < 0.15)
        // the branching regime: Jones' density, exclusion on, at window scale
        val dense = gpu("branching-exclusive", true, true, 1024, 150_000, 0f, 800)
        expect("exclusive at 15% density forms a network (topShare > 0.15)", dense > 0.15)
    }

    /** Gradient-following particles over a field whose only mark is a ring: they must gather on it. */
    private fun followers(renderer: Renderer, window: Window) {
        val field = TrailField(renderer) {
            name = "followers-trail"
            width = 256; height = 256
            halfLifeFrames = vec(-1f, -1f, -1f, -1f)
            diffusion = 0f
        }
        field.installInto()
        val ring = Module(field.depositParameters("ring") {
            vertex = TrailField.FULLSCREEN_TRIANGLE_VERTEX
            fragment = """
                #version 450
                layout(location = 0) out vec4 outColor;
                void main() {
                    float r = length(gl_FragCoord.xy - vec2(128.0));
                    outColor = vec4(exp(-pow((r - 70.0) / 40.0, 2.0)) * 0.02, 0.0, 0.0, 0.0);
                }
            """.trimIndent()
        }).pointer()
        ring.current.meshes["quad"] = fullscreenTriangle(ring)
        field.addDeposit("ring", ring)
        pump(renderer, window, 50)
        field.removeDeposit("ring")
        val followers = afield.simulation.agents.TrailFollowers(field) {
            count = 4000
            threshold = 1e-6f
            wander = 0.05f
        }
        followers.installInto()
        fun meanRingDistance(): Double {
            var sum = 0.0
            followers.particleBuffer.get().mapAndRead_bytes {
                order(java.nio.ByteOrder.LITTLE_ENDIAN)
                repeat(followers.settings.count) {
                    val x = getFloat(); val y = getFloat(); getFloat(); getFloat()
                    sum += kotlin.math.abs(kotlin.math.hypot(x - 128.0, y - 128.0) - 70.0)
                }
            }
            return sum / followers.settings.count
        }
        pump(renderer, window, 2)
        val before = meanRingDistance()
        pump(renderer, window, 300)
        val after = meanRingDistance()
        renderer.renderList.modules.remove(followers.settings.name)
        renderer.renderList.preTargets.remove("followers-trail-field")
        println(" -- followers: mean distance from the ring ${"%.1f".format(before)} px -> ${"%.1f".format(after)} px")
        expect("gradient followers gather on the trail they climb (mean ring distance halves)", after < 0.5 * before)
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
