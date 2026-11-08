package afield.examples.four

import afield.document.graph.Box
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.Vec2
import afield.math.linalg.vec
import afield.media.image.Img
import afield.simulation.agents.NetworkScore
import afield.simulation.agents.PhysarumFood
import afield.simulation.agents.ShaderPhysarumSettings
import afield.simulation.agents.ShaderPhysarumSystem
import afield.simulation.agents.TransportNetwork
import afield.simulation.agents.costTolerancePlot
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.system.exitProcess

/*
FIELD_TEST
id: alife.physarum-networks-gpu.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.PhysarumNetworkGpuSmoke
tags: [smoke, four, alife, physarum, stigmergy, gpu, network]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "physarum network gpu tests PASSED"
*/
/**
 * Runs [ShaderPhysarumSystem] with food sources ([ShaderPhysarumSettings.food]) on a real renderer,
 * reads each trail back and measures the network it grew with [TransportNetwork.extract] and
 * [TransportNetwork.score]: Tero's cost, stretch and fault tolerance, for the numbers in
 * `alife_examples.md` §7 "Designing a network".
 *
 * First a placement check (a food source at a known pixel must brighten that pixel of the readback),
 * then one sweep: agent count, trail half-life, diffusion and sensor angle, each varied about one
 * baseline with the others held, plus the blind control. Every configuration is installed at once
 * and stepped in the same frames, so the sweep costs one run's frames.
 *
 * Writes `build/physarum-networks/`: one PNG per run (trail, extracted network, sources), the cost
 * against fault-tolerance plane, and `sweep.csv`.
 */
object PhysarumNetworkGpuSmoke {
    private val outputDir = File(System.getProperty("afield.physarum.out") ?: "build/physarum-networks")
    private var failures = 0

    /** One configuration of the sweep: what is varied, its value, and the settings it makes. */
    private data class Arm(val group: String, val label: String, val value: Double, val configure: ShaderPhysarumSettings.() -> Unit)

    const val SIDE = 512
    const val BASE_AGENTS = 4000

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        Options.doVulkanSDK()
        outputDir.mkdirs()
        val window = Window("Field · physarum networks")
        val renderer = Renderer(window)
        fun hostBox(name: String) = Box(name).apply {
            this.name = name
            setPropertyRaw("renderer", renderer, doResync = false)
            setPropertyRaw("window", window, doResync = false)
        }
        try {
            Box.withBoxContextNow(hostBox("food-placement")) { check("food placement") { placement(renderer, window) } }
            Box.withBoxContextNow(hostBox("network-sweep")) { check("sweep") { sweep(renderer, window, args.firstOrNull()?.toIntOrNull() ?: 4000) } }
        } catch (t: Throwable) {
            println("FAIL: ${t.message}")
            t.printStackTrace()
            failures++
        } finally {
            runCatching { renderer.cleanup() }
            runCatching { window.cleanup() }
        }
        println(if (failures == 0) "physarum network gpu tests PASSED — images in ${outputDir.absolutePath}"
                else "physarum network gpu tests FAILED ($failures)")
        exitProcess(if (failures == 0) 0 else 1)
    }

    /** One food source and one agent that deposits nothing: the brightest pixel must be the source's. */
    private fun placement(renderer: Renderer, window: Window) {
        val system = ShaderPhysarumSystem(renderer) {
            name = "food-placement"
            width = 256
            height = 128
            agentCount = 1
            deposit = 0f
            food = listOf(vec(60.5, 30.5))
        }
        system.trail.settings.diffusion = 0f
        system.installInto(withPreview = false)
        pump(renderer, window, 4)
        val img = read(renderer, window, system)
        renderer.renderList.preTargets.remove("food-placement-simulation")
        var best = 0
        for (i in 0 until img.width * img.height) if (img.data[i * 4] > img.data[best * 4]) best = i
        println(" -- food placement: source at (60.5, 30.5) brightest at (${best % img.width}, ${best / img.width}), value ${img.data[best * 4]}")
        expect("a food source deposits on its own pixel (readback row 0 = pixel row 0)", best % img.width == 60 && best / img.width == 30)
    }

    /** The trail level a pixel reaches with an agent on it every frame: `deposit / (1 - decay)`. */
    private fun saturation(halfLife: Float) = 1.0 / (1.0 - 0.5.pow(1.0 / halfLife))

    private fun sweep(renderer: Renderer, window: Window, frames: Int) {
        // cell coordinates from the CPU helper, moved to pixel centres
        val food: List<Vec2> = PhysarumFood.scattered(SIDE, SIDE, 14, seed = 3L).points.map { vec(it.x + 0.5, it.y + 0.5) }
        val arms = ArrayList<Arm>()
        for (k in listOf(0.5, 0.75, 1.0, 1.5, 2.0, 3.0)) {
            val n = (BASE_AGENTS * k).toInt()
            arms += Arm("agents", "$n agents", n.toDouble()) { agentCount = n }
        }
        for (h in listOf(4.0, 16.0, 32.0)) arms += Arm("half-life", "half-life $h", h) { }
        for (d in listOf(0.25, 0.5)) arms += Arm("diffusion", "diffusion $d", d) { }
        for (deg in listOf(22.5, 67.5, 90.0)) arms += Arm("sensor angle", "sensor ${deg.toInt()} deg", deg) { sensorAngle = (deg * PI / 180).toFloat() }
        arms += Arm("blind", "blind", 0.0) { sensing = false }

        val systems = arms.mapIndexed { i, arm ->
            ShaderPhysarumSystem(renderer) {
                name = "network-$i"
                width = SIDE
                height = SIDE
                agentCount = BASE_AGENTS
                exclusive = true
                recycle = true
                this.food = food
                randomSeed = 5L
                arm.configure(this)
            }.also { s ->
                when (arm.group) {
                    "half-life" -> s.trail.settings.halfLifeFrames = vec(arm.value, arm.value, arm.value, arm.value)
                    "diffusion" -> s.trail.settings.diffusion = arm.value.toFloat()
                }
                s.installInto(withPreview = false)
            }
        }
        val t0 = System.nanoTime()
        pump(renderer, window, frames)
        val ms = (System.nanoTime() - t0) / 1e6 / frames
        println(" -- ${systems.size} systems on ${SIDE}x$SIDE, $frames frames, ${"%.2f".format(ms)} ms/frame for all of them")

        val sources = food
        val controls = listOf(
            TransportNetwork.mst(sources).score("MST"),
            TransportNetwork.delaunay(sources).score("Delaunay"),
            TransportNetwork.complete(sources).score("complete"),
        )
        val scores = ArrayList<NetworkScore>()
        val csv = StringBuilder("group,label,value,length,relativeCost,stretch,faultTolerance,linkFaultTolerance,connectedPairs,loops\n")
        for ((i, s) in systems.withIndex()) {
            val img = read(renderer, window, s)
            renderer.renderList.preTargets.remove("${s.settings.name}-simulation")
            val trail = channel0(img)
            val half = s.trail.settings.halfLifeFrames.x
            val net = TransportNetwork.extract(trail, sources, threshold = 0.25 * saturation(half))
            val score = net.score(arms[i].label)
            scores += score
            csv.append("${arms[i].group},${arms[i].label},${arms[i].value},${score.length},${score.relativeCost},${score.stretch}," +
                "${score.faultTolerance},${score.linkFaultTolerance},${score.connectedPairs},${score.loops}\n")
            println(" -- ${score.row()}")
            writePng(net.drawOver(toneMapped(trail, 3.0 / saturation(half))), File(outputDir, "net-$i.png"))
        }
        controls.forEach { println(" -- ${it.row()}") }
        File(outputDir, "sweep.csv").writeText(csv.toString())
        writePng(costTolerancePlot(controls + scores.filter { it.connectedPairs > 0.99 }, 560, 380, labels = true), File(outputDir, "plane.png"))

        val byLabel = arms.zip(scores).associate { (a, s) -> a.label to s }
        val base = byLabel["$BASE_AGENTS agents"]!!
        val blind = byLabel["blind"]!!
        val fewest = byLabel["${BASE_AGENTS / 2} agents"]!!
        val most = byLabel["${BASE_AGENTS * 3} agents"]!!
        expect("the baseline joins every food source (connected pairs ${"%.2f".format(base.connectedPairs)})", base.connectedPairs > 0.99)
        expect("the baseline costs more than the MST and less than the complete graph", base.relativeCost > 1.0 && base.length < controls[2].length)
        expect("the blind control joins few sources (${"%.2f".format(blind.connectedPairs)})", blind.connectedPairs < 0.3)
        expect("more agents build more road (${"%.0f".format(fewest.length)} -> ${"%.0f".format(most.length)})", most.length > fewest.length)
    }

    private fun read(renderer: Renderer, window: Window, system: ShaderPhysarumSystem): Img {
        val future = system.trail.read()
        repeat(6) { if (!future.isDone) pump(renderer, window, 1) }
        return future.get(20, TimeUnit.SECONDS)
    }

    private fun channel0(img: Img) = Img(img.width, img.height, 1, FloatArray(img.width * img.height) { img.data[it * 4] })

    private fun toneMapped(trail: Img, exposure: Double): Img {
        val out = FloatArray(trail.width * trail.height * 3)
        for (i in 0 until trail.width * trail.height) {
            val k = (1 - exp(-exposure * maxOf(0f, trail.data[i]))).toFloat()
            out[i * 3] = k; out[i * 3 + 1] = k * 0.78f; out[i * 3 + 2] = k * 0.35f
        }
        return Img(trail.width, trail.height, 3, out)
    }

    private fun writePng(img: Img, file: File) {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val o = (y * img.width + x) * img.channels
            fun q(c: Int) = (img.data[o + minOf(c, img.channels - 1)].coerceIn(0f, 1f) * 255).toInt()
            out.setRGB(x, y, (q(0) shl 16) or (q(1) shl 8) or q(2))
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
