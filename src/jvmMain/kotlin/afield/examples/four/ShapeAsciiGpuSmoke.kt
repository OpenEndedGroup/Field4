package afield.examples.four

import afield.document.graph.Box
import afield.graphics.render.Mesh
import afield.graphics.render.Module
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.media.image.Img
import afield.media.image.toBufferedImage
import afield.ui.output.ShapeAscii
import afield.ui.output.ShapeAsciiGpu
import afield.ui.output.ShapeAsciiReadback
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
FIELD_TEST
id: ascii.shape-gpu.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.ShapeAsciiGpuSmoke
tags: [smoke, four, ascii, gpu, image]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "shape ascii gpu tests PASSED"
*/
/**
 * Runs [ShapeAsciiGpu] on a real renderer over the lit torus knot `ascii_shape.md` draws, and checks
 * it against the CPU: each frame's scene and GPU characters are read back together, and the scene
 * is converted again by [ShapeAscii.convert], both exhaustively (`exact = true`, the same search the
 * GPU runs) and through the default quantised cache. Then times the matching pass with GPU timestamp
 * queries at four grid sizes, next to the CPU's time for the same frame. These are the numbers the
 * page quotes.
 *
 * Writes each frame's scene and both renderings to `build/shape-ascii-gpu/`.
 */
object ShapeAsciiGpuSmoke {
    private val outputDir = File(System.getProperty("afield.shapeascii.out") ?: "build/shape-ascii-gpu")
    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        Options.doVulkanSDK()
        outputDir.mkdirs()
        val window = Window("Field · shape ASCII on the GPU")
        val renderer = Renderer(window)
        fun hostBox(name: String) = Box(name).apply {
            this.name = name
            setPropertyRaw("renderer", renderer, doResync = false)
            setPropertyRaw("window", window, doResync = false)
        }
        try {
            val ascii = ShapeAscii()
            println(" -- charset: ${ascii.glyphs.length} glyphs, cell ${ascii.cellWidth}x${ascii.cellHeight} px")
            Box.withBoxContextNow(hostBox("ascii-gpu-blank")) { check("blank") { blank(renderer, window, ascii) } }
            Box.withBoxContextNow(hostBox("ascii-gpu-agreement")) { check("agreement") { agreement(renderer, window, ascii) } }
            Box.withBoxContextNow(hostBox("ascii-gpu-timing")) { check("timing") { timing(renderer, window, ascii) } }
        } catch (t: Throwable) {
            println("FAIL: ${t.message}")
            t.printStackTrace()
            failures++
        } finally {
            runCatching { renderer.cleanup() }
            runCatching { window.cleanup() }
        }
        println(if (failures == 0) "shape ascii gpu tests PASSED — images in ${outputDir.absolutePath}"
                else "shape ascii gpu tests FAILED ($failures)")
        exitProcess(if (failures == 0) 0 else 1)
    }

    /** An empty scene is all spaces on both sides. */
    private fun blank(renderer: Renderer, window: Window, ascii: ShapeAscii) {
        val gpu = ShapeAsciiGpu(cols = 16, rows = 8, ascii = ascii, renderer = renderer, name = "ascii-blank")
        gpu.installInto(draw = false)
        pump(renderer, window, 3)
        val r = read(renderer, window, gpu)
        gpu.uninstallFrom()
        expect("an empty scene reads back black", r.scene.data.all { it == 0f })
        expect("an empty scene is all spaces on the GPU", r.frame.text().all { it == ' ' || it == '\n' })
    }

    /** GPU characters against the CPU's on the same frames, at three contrast settings. */
    private fun agreement(renderer: Renderer, window: Window, ascii: ShapeAscii) {
        val gpu = ShapeAsciiGpu(cols = 128, rows = 48, ascii = ascii, renderer = renderer, name = "ascii-agree")
        val knot = TorusKnot(renderer, gpu)
        gpu.installInto()
        val settings = listOf(Triple("contrast 1", 1f, 1f), Triple("contrast 3", 3f, 1f), Triple("contrast 3, directional 3", 3f, 3f))
        val times = listOf(0.3f, 1.1f, 2.0f, 2.9f, 3.7f)
        for ((label, c, d) in settings) {
            gpu.contrast = c
            gpu.directional = d
            var exact = 0.0
            var cached = 0.0
            var inked = 0.0
            var worst = 1.0
            for (t in times) {
                knot.time = t
                pump(renderer, window, 3)
                val r = read(renderer, window, gpu)
                val cpuExact = ascii.convert(r.scene, gpu.cols, contrast = c.toDouble(), directional = d.toDouble(), rows = gpu.rows, exact = true)
                val cpuCached = ascii.convert(r.scene, gpu.cols, contrast = c.toDouble(), directional = d.toDouble(), rows = gpu.rows)
                val a = r.frame.agreement(cpuExact)
                exact += a / times.size
                worst = minOf(worst, a)
                cached += r.frame.agreement(cpuCached) / times.size
                inked += r.frame.indices.count { it != ascii.glyphs.indexOf(' ') }.toDouble() / r.frame.indices.size / times.size
                if (t == times[1]) {
                    val tag = label.replace(", ", "-").replace(" ", "")
                    write(r.scene, File(outputDir, "scene.png"))
                    write(r.frame.toImg(), File(outputDir, "gpu-$tag.png"))
                    write(cpuCached.toImg(), File(outputDir, "cpu-cached-$tag.png"))
                    File(outputDir, "gpu-$tag.txt").writeText(r.frame.text())
                }
            }
            println(" -- $label: GPU = CPU exact on ${pct(exact)} of cells (worst frame ${pct(worst)}), " +
                "= CPU cached on ${pct(cached)}; ${pct(inked)} of cells hold ink")
            expect("$label: the knot puts ink in at least 5% of cells", inked > 0.05)
            expect("$label: the GPU picks the CPU's exact glyph in at least 99.5% of cells", worst >= 0.995)
        }
        gpu.uninstallFrom()
        knot.remove()
    }

    /** GPU time of the matching pass at four grid sizes, and the CPU's time on the same frame. */
    private fun timing(renderer: Renderer, window: Window, ascii: ShapeAscii) {
        for ((cols, rows) in listOf(64 to 24, 128 to 48, 256 to 96, 512 to 192)) {
            val gpu = ShapeAsciiGpu(cols = cols, rows = rows, ascii = ascii, renderer = renderer, name = "ascii-time-$cols") {
                contrast = 3f
                directional = 3f
            }
            gpu.profile = true
            val knot = TorusKnot(renderer, gpu)
            gpu.installInto()
            val match = ArrayList<Double>()
            val sceneMs = ArrayList<Double>()
            repeat(90) {
                knot.time = it / 60f
                pump(renderer, window, 1)
                if (it >= 30) {
                    gpu.matchMilliseconds?.let(match::add)
                    gpu.sceneMilliseconds?.let(sceneMs::add)
                }
            }
            val r = read(renderer, window, gpu)
            fun cpu(exact: Boolean): Double {
                val fresh = ShapeAscii()
                fresh.convert(r.scene, cols, contrast = 3.0, directional = 3.0, rows = rows, exact = exact)
                val t0 = System.nanoTime()
                repeat(3) { fresh.convert(r.scene, cols, contrast = 3.0, directional = 3.0, rows = rows, exact = exact) }
                return (System.nanoTime() - t0) / 3e6
            }
            val cpuExact = cpu(true)
            val cpuCached = cpu(false)
            gpu.uninstallFrom()
            knot.remove()
            println(" -- ${cols}x$rows cells (${gpu.sceneWidth}x${gpu.sceneHeight} px): GPU match median ${ms(median(match))} ms " +
                "(scene ${ms(median(sceneMs))} ms, ${match.size} samples); CPU exact ${ms(cpuExact)} ms, CPU cached ${ms(cpuCached)} ms")
            expect("${cols}x$rows: the matching pass was timed", match.isNotEmpty())
        }
    }

    /**
     * The page's scene: a (2, 3) torus knot, turning about two axes, lit from the upper left, drawn
     * into [gpu]'s scene target with the same shaders `ascii_shape.md` gives its box.
     */
    private class TorusKnot(renderer: Renderer, private val gpu: ShapeAsciiGpu) {
        var time = 0f
        private val name = "${gpu.name}-knot"
        private val module = Module(
            parameters(renderer) {
                +gpu.scene
                this.name = this@TorusKnot.name
                blend = false
                depthTest = true
                vertex = KNOT_VERTEX
                fragment = KNOT_FRAGMENT
            }
        ).pointer()

        init {
            val u = 240
            val v = 16
            val mesh = Mesh(renderer, module).resize(u * v, u * v * 2)
            val points = ArrayList<DoubleArray>(u * v)
            for (i in 0 until u) for (j in 0 until v) points += knotPoint(i.toDouble() / u, j.toDouble() / v)
            mesh.write("pos") { points.forEach { put(it[0].toFloat()).put(it[1].toFloat()).put(it[2].toFloat()) } }
            mesh.write("normal") { points.forEach { put(it[3].toFloat()).put(it[4].toFloat()).put(it[5].toFloat()) } }
            mesh.t!!.mapAndWrite_ints {
                for (i in 0 until u) for (j in 0 until v) {
                    val a = i * v + j
                    val b = (i + 1) % u * v + j
                    val c = (i + 1) % u * v + (j + 1) % v
                    val d = i * v + (j + 1) % v
                    put(a).put(b).put(c).put(a).put(c).put(d)
                }
            }
            mesh.push.bind("time") { time }
            mesh.push.bind("aspect") { gpu.sceneWidth.toFloat() / gpu.sceneHeight }
            module.current.meshes["knot"] = mesh
            gpu.scene += name to module
        }

        fun remove() {
            gpu.scene.modules.remove(name)
        }
    }

    /** Point and outward normal at ([s], [t]) on a (2, 3) torus knot tube: s along the knot, t around the tube. */
    internal fun knotPoint(s: Double, t: Double): DoubleArray {
        fun centre(a: Double): DoubleArray {
            val r = 2 + cos(3 * a)
            return doubleArrayOf(0.42 * r * cos(2 * a), 0.42 * r * sin(2 * a), -0.42 * sin(3 * a))
        }
        val a = 2 * PI * s
        val c = centre(a)
        val p = centre(a + 1e-4)
        val m = centre(a - 1e-4)
        val tan = norm(DoubleArray(3) { p[it] - m[it] })
        val n = norm(doubleArrayOf(tan[1], -tan[0], 0.0))
        val b = doubleArrayOf(n[1] * tan[2] - n[2] * tan[1], n[2] * tan[0] - n[0] * tan[2], n[0] * tan[1] - n[1] * tan[0])
        val w = 2 * PI * t
        val dir = DoubleArray(3) { cos(w) * n[it] + sin(w) * b[it] }
        return doubleArrayOf(c[0] + 0.16 * dir[0], c[1] + 0.16 * dir[1], c[2] + 0.16 * dir[2], dir[0], dir[1], dir[2])
    }

    private fun norm(v: DoubleArray): DoubleArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return DoubleArray(3) { v[it] / l }
    }

    internal val KNOT_VERTEX = """
        #version 450
        layout(location = 0) in vec3 pos;
        layout(location = 1) in vec3 normal;
        layout(location = 0) out vec3 fnormal;

        layout(push_constant) uniform Push {
            float time;
            float aspect;
        } pc;

        void main() {
            // turn about y, then x
            float a = pc.time * 0.7;
            float b = pc.time * 0.45;
            mat3 ry = mat3(cos(a), 0.0, -sin(a), 0.0, 1.0, 0.0, sin(a), 0.0, cos(a));
            mat3 rx = mat3(1.0, 0.0, 0.0, 0.0, cos(b), sin(b), 0.0, -sin(b), cos(b));
            vec3 p = rx * ry * pos;
            fnormal = rx * ry * normal;
            // a camera 4 units back looking along +z, y up; depth runs 0..1 from z = 1 to z = 8
            float z = p.z + 4.0;
            gl_Position = vec4(p.x * 2.6 / pc.aspect, -p.y * 2.6, (z - 1.0) / 7.0 * z, z);
        }
    """.trimIndent()

    internal val KNOT_FRAGMENT = """
        #version 450
        layout(location = 0) in vec3 fnormal;
        layout(location = 0) out vec4 outColor;

        void main() {
            // a light up and to the left of the camera
            vec3 l = normalize(vec3(-0.5, 0.6, -0.65));
            float d = max(dot(normalize(fnormal), l), 0.0);
            outColor = vec4(vec3(0.04 + 0.96 * d), 1.0);
        }
    """.trimIndent()

    private fun read(renderer: Renderer, window: Window, gpu: ShapeAsciiGpu): ShapeAsciiReadback {
        val future = gpu.read()
        repeat(6) { if (!future.isDone) pump(renderer, window, 1) }
        return future.get(20, TimeUnit.SECONDS)
    }

    private fun median(v: List<Double>): Double = if (v.isEmpty()) Double.NaN else v.sorted()[v.size / 2]
    private fun ms(v: Double) = "%.3f".format(v)
    private fun pct(v: Double) = "%.2f%%".format(100 * v)

    private fun write(img: Img, file: File) {
        ImageIO.write(img.toBufferedImage(), "png", file)
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
