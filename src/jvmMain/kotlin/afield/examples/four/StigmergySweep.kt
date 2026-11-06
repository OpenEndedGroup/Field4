package afield.examples.four

import afield.document.graph.Box
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import afield.media.image.Img
import afield.simulation.agents.ShaderPhysarumSettings
import afield.simulation.agents.ShaderPhysarumSystem
import afield.simulation.agents.TrailField
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.exp
import kotlin.system.exitProcess

/**
 * A parameter sweep for multi-species [ShaderPhysarumSystem] tuning: each named variant runs on a
 * real renderer, its trail is read back and tone-mapped to `<out>/<variant>.png`, and its
 * segregation (share of marked pixels whose top channel holds 80 % of the trail) is printed.
 * Not a test; run with `run-jvm-main afield.examples.four.StigmergySweep --arg <outDir> --arg <variant,...>`.
 */
object StigmergySweep {
    private class Variant(val trail: afield.simulation.agents.TrailFieldSettings.() -> Unit = {}, val agents: ShaderPhysarumSettings.() -> Unit)

    private fun repel(n: Int, r: Float): List<Vec4> = (0 until n).map { s ->
        val w = FloatArray(4) { c -> if (c == s) 1f else if (c < n) -r else 0f }
        vec(w[0], w[1], w[2], w[3])
    }

    private val variants: Map<String, Variant> = mapOf(
        "base3" to Variant { speciesCount = 3; agentCount = 250_000; width = 512; height = 512 },
        "dense3" to Variant({ diffusion = 0.3f; halfLifeFrames = vec(6f, 6f, 6f, 6f) }) {
            speciesCount = 3; agentCount = 2_000_000; width = 1024; height = 1024
            attraction = repel(3, 1f); sensorDistance = 6f; deposit = 0.2f
        },
        "dense3r2" to Variant({ diffusion = 0.3f; halfLifeFrames = vec(6f, 6f, 6f, 6f) }) {
            speciesCount = 3; agentCount = 2_000_000; width = 1024; height = 1024
            attraction = repel(3, 2f); sensorDistance = 6f; deposit = 0.2f
        },
        "dense3fine" to Variant({ diffusion = 0.15f; halfLifeFrames = vec(4f, 4f, 4f, 4f) }) {
            speciesCount = 3; agentCount = 3_000_000; width = 1024; height = 1024
            attraction = repel(3, 2f); sensorDistance = 4f; sensorAngle = 0.6f; turnAngle = 0.6f; deposit = 0.15f
        },
        "dense3long" to Variant({ diffusion = 0.3f; halfLifeFrames = vec(20f, 20f, 20f, 20f) }) {
            speciesCount = 3; agentCount = 2_000_000; width = 1024; height = 1024
            attraction = repel(3, 2f); sensorDistance = 9f; deposit = 0.1f
        },
        "excl3" to Variant({ diffusion = 0.5f }) {
            speciesCount = 3; agentCount = 300_000; width = 1024; height = 1024
            attraction = repel(3, 2f); exclusive = true; deposit = 1f
        },
        "dense1" to Variant({ diffusion = 0.3f; halfLifeFrames = vec(6f, 6f, 6f, 6f) }) {
            speciesCount = 1; agentCount = 2_000_000; width = 1024; height = 1024
            sensorDistance = 6f; deposit = 0.2f
        },
        "dense1fine" to Variant({ diffusion = 0.15f; halfLifeFrames = vec(4f, 4f, 4f, 4f) }) {
            speciesCount = 1; agentCount = 3_000_000; width = 1024; height = 1024
            sensorDistance = 4f; sensorAngle = 0.6f; turnAngle = 0.6f; deposit = 0.15f
        },
        "excl3_500k" to Variant({ diffusion = 0.5f }) {
            speciesCount = 3; agentCount = 500_000; attraction = repel(3, 2f); width = 1024; height = 1024; exclusive = true; deposit = 1f
        },
        "excl3_d02" to Variant({ diffusion = 0.2f }) {
            speciesCount = 3; agentCount = 300_000; attraction = repel(3, 2f); width = 1024; height = 1024; exclusive = true; deposit = 1f
        },
        "excl3_r4" to Variant({ diffusion = 0.5f }) {
            speciesCount = 3; agentCount = 300_000; attraction = repel(3, 4f); width = 1024; height = 1024; exclusive = true; deposit = 1f
        },
        "excl3_sd15" to Variant({ diffusion = 0.5f }) {
            speciesCount = 3; agentCount = 300_000; attraction = repel(3, 2f); sensorDistance = 15f; width = 1024; height = 1024; exclusive = true; deposit = 1f
        },
        "excl3_hl20" to Variant({ diffusion = 0.5f; halfLifeFrames = vec(20f, 20f, 20f, 20f) }) {
            speciesCount = 3; agentCount = 300_000; attraction = repel(3, 2f); width = 1024; height = 1024; exclusive = true; deposit = 1f
        },
        "excl2" to Variant({ diffusion = 0.5f }) {
            speciesCount = 2; agentCount = 300_000; attraction = repel(2, 2f); width = 1024; height = 1024; exclusive = true; deposit = 1f
        },
        "excl4" to Variant({ diffusion = 0.5f }) {
            speciesCount = 4; agentCount = 300_000; attraction = repel(4, 2f); width = 1024; height = 1024; exclusive = true; deposit = 1f
        },
        "excl1" to Variant({ diffusion = 0.5f }) {
            speciesCount = 1; agentCount = 300_000; width = 1024; height = 1024; exclusive = true; deposit = 1f
        },
        "exclchase3" to Variant({ diffusion = 0.5f }) {
            speciesCount = 3; agentCount = 300_000; width = 1024; height = 1024; exclusive = true; deposit = 1f; attraction = listOf(vec(1f, 1f, -2f, 0f), vec(-2f, 1f, 1f, 0f), vec(1f, -2f, 1f, 0f))
        },
        "dom_hl60" to Variant({ diffusion = 1f; halfLifeFrames = vec(60f, 60f, 60f, 60f) }) {
            speciesCount = 3; agentCount = 300_000; attraction = repel(3, 3f); width = 1024; height = 1024; exclusive = true; deposit = 1f
        },
        "dom_sd25" to Variant({ diffusion = 0.5f }) {
            speciesCount = 3; agentCount = 300_000; attraction = repel(3, 3f); sensorDistance = 25f; sensorAngle = 0.8f; width = 1024; height = 1024; exclusive = true; deposit = 1f
        },
        "dom_r1" to Variant({ diffusion = 0.5f; halfLifeFrames = vec(30f, 30f, 30f, 30f) }) {
            speciesCount = 3; agentCount = 300_000; attraction = repel(3, 0.5f); width = 1024; height = 1024; exclusive = true; deposit = 1f
        },
        "dom2_hl60" to Variant({ diffusion = 1f; halfLifeFrames = vec(60f, 60f, 60f, 60f) }) {
            speciesCount = 2; agentCount = 300_000; attraction = repel(2, 3f); width = 1024; height = 1024; exclusive = true; deposit = 1f
        },
        "chase3" to Variant({ diffusion = 0.15f; halfLifeFrames = vec(4f, 4f, 4f, 4f) }) {
            speciesCount = 3; agentCount = 3_000_000; width = 1024; height = 1024
            sensorDistance = 4f; sensorAngle = 0.6f; turnAngle = 0.6f; deposit = 0.15f
            // rock-paper-scissors: each species is drawn to the one it hunts and flees the one hunting it
            attraction = listOf(vec(1f, 1.5f, -2f, 0f), vec(-2f, 1f, 1.5f, 0f), vec(1.5f, -2f, 1f, 0f))
        },
    )

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        Options.doVulkanSDK()
        val out = File(args.getOrNull(0) ?: "build/stigmergy-sweep").also { it.mkdirs() }
        val names = args.getOrNull(1)?.split(",") ?: variants.keys.toList()
        val frames = (System.getProperty("afield.sweep.frames") ?: "900").toInt()
        val window = Window("stigmergy sweep")
        val renderer = Renderer(window)
        try {
            for (name in names) {
                val v = variants[name] ?: error("no variant $name")
                Box.withBoxContextNow(Box(name).apply {
                    setPropertyRaw("renderer", renderer, doResync = false)
                    setPropertyRaw("window", window, doResync = false)
                }) {
                    val system = ShaderPhysarumSystem(renderer) { this.name = "sweep-$name"; v.agents(this) }
                    v.trail(system.trail.settings)
                    system.installInto(withPreview = false)
                    val t0 = System.nanoTime()
                    repeat(frames) { window.pollEvents(); renderer.render() }
                    val ms = (System.nanoTime() - t0) / 1e6 / frames
                    val f = system.trail.read()
                    repeat(6) { if (!f.isDone) { window.pollEvents(); renderer.render() } }
                    val img = f.get(20, TimeUnit.SECONDS)
                    renderer.renderList.preTargets.remove("sweep-$name-simulation")
                    val n = system.settings.speciesCount
                    println("SWEEP $name: ${system.settings.agentCount} agents, ${"%.2f".format(ms)} ms/frame, segregation ${"%.3f".format(segregation(img, n))}")
                    write(img, File(out, "$name.png"), n)
                }
            }
        } finally {
            runCatching { renderer.cleanup() }
            runCatching { window.cleanup() }
        }
        exitProcess(0)
    }

    private fun segregation(img: Img, species: Int): Double {
        val n = img.width * img.height
        val tot = DoubleArray(n) { i -> (0 until species).sumOf { maxOf(0f, img.data[i * 4 + it]).toDouble() } }
        val cut = 0.5 * tot.average()
        var marked = 0; var pure = 0
        for (i in 0 until n) {
            if (tot[i] <= cut) continue
            marked++
            if ((0 until species).maxOf { img.data[i * 4 + it] } >= 0.8 * tot[i]) pure++
        }
        return pure.toDouble() / maxOf(1, marked)
    }

    /** Tone map with exposure chosen so the 98th-percentile total maps to ~0.9. */
    private fun write(img: Img, file: File, species: Int) {
        val n = img.width * img.height
        val tot = FloatArray(n) { i -> (0 until species).sumOf { maxOf(0f, img.data[i * 4 + it]).toDouble() }.toFloat() }
        val p98 = tot.sortedArray()[(n * 0.98).toInt()].coerceAtLeast(1e-6f)
        val exposure = 2.3 / p98
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val palette = TrailField.DEFAULT_PALETTE
        for (y in 0 until img.height) for (x in 0 until img.width) {
            var r = 0.0; var g = 0.0; var b = 0.0
            for (c in 0 until species) {
                val k = 1 - exp(-exposure * maxOf(0f, img.data[(y * img.width + x) * 4 + c]))
                r += k * palette[c].x; g += k * palette[c].y; b += k * palette[c].z
            }
            fun q(v: Double) = (v.coerceIn(0.0, 1.0) * 255).toInt()
            out.setRGB(x, y, (q(r) shl 16) or (q(g) shl 8) or q(b))
        }
        ImageIO.write(out, "png", file)
        println("SWEEP ${file.nameWithoutExtension} exposure ${"%.4f".format(exposure)}")
    }
}
