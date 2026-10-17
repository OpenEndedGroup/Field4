package afield.examples.four

import afield.document.graph.Box
import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.TexturePreviewCache
import afield.graphics.render.fullscreenTriangle
import afield.graphics.render.pointer
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.PingPongRenderToTexture
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.Vector2f
import afield.math.linalg.vec
import afield.simulation.agents.ShaderForestFireSystem
import afield.simulation.agents.ShaderLatticeBoltzmannSystem
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.pow
import kotlin.system.exitProcess

/*
FIELD_TEST
id: alife.shader-systems.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.AlifeShaderSystemsSmoke
tags: [smoke, four, alife, fluid, lattice-boltzmann, forest-fire, gpu, image]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "alife shader systems tests PASSED"
*/
/**
 * Runs `ShaderLatticeBoltzmannSystem` and `ShaderForestFireSystem` on a real renderer and reads their
 * textures back, the way the `lattice_boltzmann_cylinder` and `forest_fire` starting points drive
 * them: a module built on the system's own `controlsParameters` / `fireInputParameters`, added with
 * `addControls` / `addFireInput`, drawn by a `fullscreenTriangle`.
 *
 * Those transcripts are compile-only in CI because they open a window. This is what evaluates the
 * calls they teach, and what would notice the failure each system's history actually had: the fluid's
 * wind accelerating the whole tunnel to its speed limit, a drawn control that never reaches the
 * fluid, a fire that never leaves the point it was lit at, a clear that does not clear.
 *
 * Every number here is read through `TexturePreviewCache`, which blits to 8-bit sRGB, so values are
 * decoded back to linear before they are compared with anything absolute.
 */
object AlifeShaderSystemsSmoke {
    private val DATA_IMAGE_RE = Regex("base64,([^']+)")
    private val outputDir = File(System.getProperty("afield.alife.out") ?: "build/alife-shader-systems")
    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        Options.doVulkanSDK()
        outputDir.mkdirs()

        val window = Window("Field · alife shader systems")
        val renderer = Renderer(window)

        // Each system adopts its rule into the box it is built in, as it would in a document, so
        // each gets a box of its own: a second system built in the same box would find the first
        // one's shader tabs there and try to run its own textures through them.
        fun hostBox(name: String) = Box(name).apply {
            this.name = name
            setPropertyRaw("renderer", renderer, doResync = false)
            setPropertyRaw("window", window, doResync = false)
        }

        try {
            Box.withBoxContextNow(hostBox("alife-smoke-lbm")) { check("lattice Boltzmann") { latticeBoltzmann(renderer, window) } }
            Box.withBoxContextNow(hostBox("alife-smoke-fire")) { check("forest fire") { forestFire(renderer, window) } }
        } catch (t: Throwable) {
            println("FAIL: ${t.message}")
            t.printStackTrace()
            failures++
        } finally {
            runCatching { renderer.cleanup() }
            runCatching { window.cleanup() }
        }

        println(if (failures == 0) "alife shader systems tests PASSED — readbacks in ${outputDir.absolutePath}"
                else "alife shader systems tests FAILED ($failures)")
        exitProcess(if (failures == 0) 0 else 1)
    }

    private fun latticeBoltzmann(renderer: Renderer, window: Window) {
        val wind = 0.08f
        val lbm = ShaderLatticeBoltzmannSystem(renderer) {
            name = "smoke-lbm"
            width = 200
            height = 120
            baseVelocity = Vector2f(wind, 0f)
            relaxation = 1.85f
            driveBlend = 0f
            stepsPerFrame = 8
        }
        lbm.installInto(withPreview = false)

        // the starting point's cylinder box, built in Kotlin: a solid disc, walls, an inlet strip
        lbm.clearControls()
        val cylinder = Module(lbm.controlsParameters("smoke-cylinder") {
            vertex = FULLSCREEN_VERTEX
            fragment = CYLINDER_FRAGMENT
        }).pointer()
        quad(cylinder) {
            push.bind("center") { vec(50f, 60f) }
            push.bind("radius") { 10f }
            push.bind("height") { 120f }
        }
        lbm.addControls("smoke-cylinder", cylinder)

        pump(renderer, window, 300)

        val controls = capture(renderer, lbm.controls, 0, "lbm-controls")
        expect("the drawn disc is solid in the controls texture", controls.red(50, 60) > 0.9)
        expect("away from the disc and walls the controls are empty", controls.red(150, 30) < 0.05)

        // attachment 2 is (f8, density, velocity.x, velocity.y); velocity.x is in blue
        val velocity = capture(renderer, lbm.fluid, 2, "lbm-velocity")
        val upstream = velocity.blue(20, 30)
        val wake = velocity.blue(64, 60)
        println(" -- velocity.x upstream ${"%.4f".format(upstream)}, behind the cylinder ${"%.4f".format(wake)}")
        expect("the wind still blows at about its own speed upstream (not accelerated to the limit)",
            upstream in wind * 0.5..wind * 1.5)
        expect("the flow is slowed behind the cylinder", wake < upstream * 0.6)
        expect("the cylinder itself is at rest", velocity.blue(50, 60) < 0.01)

        lbm.clearControls()
        cylinder.current.meshes.clear()
        pump(renderer, window, 3)
        expect("clearControls() erases the disc", capture(renderer, lbm.controls, 0, "lbm-controls-cleared").red(50, 60) < 0.05)
    }

    private fun forestFire(renderer: Renderer, window: Window) {
        val fire = ShaderForestFireSystem(renderer) {
            name = "smoke-fire"
            width = 256
            height = 256
            treeDensity = 0.6f
        }
        fire.installInto(withPreview = false)

        val starter = Module(fire.fireInputParameters("smoke-starter") {
            vertex = FULLSCREEN_VERTEX
            fragment = STARTER_FRAGMENT
        }).pointer()
        quad(starter) {
            push.bind("red") { vec(128f, 128f) }
            push.bind("radius") { 4f }
        }
        fire.addFireInput("smoke-starter", starter)

        pump(renderer, window, 240)

        val state = capture(renderer, fire.state, 0, "fire-state")
        val lit = state.count { r, _, _ -> r > 0.03 }
        val far = state.count { r, _, _ -> r > 0.03 } - state.countWithin(128, 128, 20) { r, _, _ -> r > 0.03 }
        val others = state.count { _, g, b -> g > 0.03 || b > 0.03 } + state.countAlpha { it > 0.03 }
        println(" -- fire: $lit texels on fire or recovering, $far of them more than 20 texels from the point, $others of another kind")
        expect("the fire spreads well beyond the point it was lit at", far > 1000)
        // an opaque clear of the ignition texture once lit the alpha kind in every tree at once, which
        // left nothing ready for the red point to spread into
        expect("only the kind that was lit burns", others == 0)

        starter.current.meshes.clear()
        fire.clearFireInput()
        fire.clearState()
        pump(renderer, window, 3)
        val cleared = capture(renderer, fire.state, 0, "fire-state-cleared")
        expect("clearState() and clearFireInput() put every tree out", cleared.count { r, g, b -> r > 0.03 || g > 0.03 || b > 0.03 } + cleared.countAlpha { it > 0.03 } == 0)
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private fun quad(module: ModulePointer, bind: afield.graphics.render.Mesh.() -> Unit) {
        module.current.meshes["quad"] = fullscreenTriangle(module).apply(bind)
    }

    private fun pump(renderer: Renderer, window: Window, frames: Int) {
        repeat(frames) {
            window.pollEvents()
            renderer.render()
        }
    }

    private fun capture(renderer: Renderer, target: PingPongRenderToTexture, attachment: Int, name: String): Shot {
        val html = TexturePreviewCache.capture(
            renderer = renderer,
            vkImage = target.colorImageHandles[attachment],
            format = target.colorFormats[attachment],
            srcWidth = target.width,
            srcHeight = target.height,
            currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            maxDim = maxOf(target.width, target.height)
        ) ?: error("could not read back '$name'")
        val encoded = DATA_IMAGE_RE.find(html)?.groupValues?.get(1) ?: error("could not parse the readback of '$name'")
        val file = File(outputDir, "$name.png")
        file.writeBytes(Base64.getDecoder().decode(encoded))
        return Shot(name, file)
    }

    private fun check(what: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            println("FAIL: $what — ${t.message}")
            t.printStackTrace()
            failures++
        }
    }

    private fun expect(what: String, ok: Boolean) {
        println(if (ok) "ok: $what" else "FAIL: $what")
        if (!ok) failures++
    }

    /** One readback, decoded to linear light per channel. */
    private class Shot(val name: String, file: File) {
        private val image = ImageIO.read(file) ?: error("could not re-read $name")
        val width = image.width
        val height = image.height

        private fun channel(x: Int, y: Int, shift: Int) = srgbToLinear((image.getRGB(x, y) shr shift and 0xff) / 255.0)
        fun red(x: Int, y: Int) = channel(x, y, 16)
        fun green(x: Int, y: Int) = channel(x, y, 8)
        fun blue(x: Int, y: Int) = channel(x, y, 0)
        /** Alpha is data here (the fourth kind of fire), and is stored linearly. */
        fun alpha(x: Int, y: Int) = (image.getRGB(x, y) ushr 24 and 0xff) / 255.0

        fun count(test: (Double, Double, Double) -> Boolean): Int =
            (0 until height).sumOf { y -> (0 until width).count { x -> test(red(x, y), green(x, y), blue(x, y)) } }

        fun countAlpha(test: (Double) -> Boolean): Int =
            (0 until height).sumOf { y -> (0 until width).count { x -> test(alpha(x, y)) } }

        fun countWithin(cx: Int, cy: Int, radius: Int, test: (Double, Double, Double) -> Boolean): Int =
            (0 until height).sumOf { y ->
                (0 until width).count { x ->
                    (x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius && test(red(x, y), green(x, y), blue(x, y))
                }
            }
    }

    private fun srgbToLinear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private val FULLSCREEN_VERTEX = """
        #version 450

        void main() {
            vec2 p = gl_VertexIndex == 0 ? vec2(-1.0, -1.0) : (gl_VertexIndex == 1 ? vec2(3.0, -1.0) : vec2(-1.0, 3.0));
            gl_Position = vec4(p, 0.0, 1.0);
        }
    """.trimIndent()

    // the cylinder box of the lattice_boltzmann_cylinder starting point
    private val CYLINDER_FRAGMENT = """
        #version 450

        layout(push_constant) uniform Push {
            vec2 center;
            float radius;
            float height;
        } push;

        layout(location = 0) out vec4 outColor;

        void main() {
            vec2 p = gl_FragCoord.xy;
            outColor = vec4(0.0);
            if (distance(p, push.center) < push.radius) outColor.r = 1.0;
            if (p.y < 3.0 || p.y > push.height - 3.0) outColor.r = 1.0;
            if (p.x < 4.0 && mod(floor(p.y / 12.0), 2.0) == 0.0) outColor.a = 1.0;
        }
    """.trimIndent()

    // one permanent red ignition point, as the forest_fire starting point's starter draws two
    private val STARTER_FRAGMENT = """
        #version 450

        layout(push_constant) uniform Push {
            vec2 red;
            float radius;
        } push;

        layout(location = 0) out vec4 outColor;

        void main() {
            outColor = vec4(0.0);
            if (distance(gl_FragCoord.xy, push.red) < push.radius) outColor.r = 1.0;
        }
    """.trimIndent()
}
