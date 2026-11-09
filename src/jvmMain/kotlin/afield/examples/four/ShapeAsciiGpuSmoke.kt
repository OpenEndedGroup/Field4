package afield.examples.four

import afield.document.graph.Box
import afield.graphics.render.Mesh
import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.fullscreenTriangle
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.shader.fromViews
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.vec
import afield.media.image.Img
import afield.media.image.toBufferedImage
import afield.ui.output.ShapeAscii
import afield.ui.output.ShapeAsciiReadback
import afield.ui.output.after
import afield.ui.output.glyphAtlasTexture
import afield.ui.output.glyphVectorTexture
import afield.ui.output.readCells
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import org.yaml.snakeyaml.Yaml
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
 * Runs the GPU matcher of `ascii_shape.md` on a real renderer and checks it against the CPU.
 *
 * The shaders are not copies: they are read from the scene, match and draw boxes of
 * `dev_docs/agents/transcripts/ascii_shape.yaml`, the boxes a reader opens and edits, and wired the
 * way those boxes wire them (a scene target a whole number of font cells in size, a cells target
 * one texel per cell, [glyphVectorTexture] and [glyphAtlasTexture] bound by name). Each frame's
 * scene and glyph indices are read back together with [readCells] and the scene is converted again by
 * [ShapeAscii.convert], both exhaustively (`exact = true`, the search the shader runs) and through the
 * default quantised cache. Then the match pass is timed with GPU timestamp queries at four grid sizes,
 * next to the CPU's time for the same frame. These are the numbers the page quotes.
 *
 * Writes a frame's scene, the GPU's glyphs and the glyphs as text to `build/shape-ascii-gpu/`.
 */
object ShapeAsciiGpuSmoke {
    private val outputDir = File(System.getProperty("afield.shapeascii.out") ?: "build/shape-ascii-gpu")
    private var failures = 0

    /** The starting-point transcript whose GPU boxes hold the shaders under test. */
    internal const val TRANSCRIPT = "dev_docs/agents/transcripts/ascii_shape.yaml"

    /** The properties (`code`, `vertex`, `fragment`, ...) of the box [id] created in [TRANSCRIPT]. */
    @Suppress("UNCHECKED_CAST")
    internal fun boxProperties(id: String, transcript: File = File(TRANSCRIPT)): Map<String, String> {
        val doc = Yaml().load<Map<String, Any?>>(transcript.readText())
        val ops = doc["operations"] as List<Map<String, Any?>>
        val create = ops.mapNotNull { it["create"] as? Map<String, Any?> }.firstOrNull { it["box"] == id }
            ?: error("no box '$id' in $transcript")
        return (create["properties"] as Map<String, Any?>).mapValues { it.value.toString() }
    }

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
            Box.withBoxContextNow(hostBox("ascii-gpu-upright")) { check("upright") { upright(renderer, window, ascii) } }
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

    /**
     * The page's scene, match and draw boxes, built from their own shaders: [scene] is [cols] x [rows]
     * font cells, [cells] one texel per cell. With knot, the scene box's torus knot is drawn into
     * the scene; with draw, the draw box's pass is added to the window.
     */
    private class Pipeline(
        val renderer: Renderer,
        val ascii: ShapeAscii,
        val cols: Int,
        val rows: Int,
        val name: String,
        knot: Boolean = true,
        draw: Boolean = false,
    ) {
        var contrast = 3f
        var directional = 3f
        var time = 0f
        private val sampler = TextureSampler(renderer, 1, false)
        private val sceneBox = boxProperties("ascii_shape_gpu_scene")
        private val matchBox = boxProperties("ascii_shape_gpu_match")
        private val drawBox = boxProperties("ascii_shape_gpu_draw")

        val scene = RenderToTexture(renderer) {
            width = cols * ascii.cellWidth
            height = rows * ascii.cellHeight
            format = VK_FORMAT_R8G8B8A8_UNORM
            colorFormats = listOf(VK_FORMAT_R8G8B8A8_UNORM)
            useDepth = true
            debugName = "$name-scene"
        }
        val cells = RenderToTexture(renderer) {
            width = cols
            height = rows
            format = VK_FORMAT_R8G8B8A8_UNORM
            colorFormats = listOf(VK_FORMAT_R8G8B8A8_UNORM)
            useDepth = false
            debugName = "$name-cells"
        }
        private var drawModule: ModulePointer? = null

        init {
            if (knot) addKnot()
            val match = Module(
                parameters(renderer) {
                    +cells
                    this.name = "$name-match"
                    blend = false
                    depthTest = false
                    vertex = matchBox.getValue("vertex")
                    fragment = matchBox.getValue("fragment")
                    textureResolver = { tex ->
                        when (tex.name) {
                            "sceneTex" -> fromViews(listOf(scene), tex.name, sampler)
                            "glyphVectors" -> fromViews(listOf(ascii.glyphVectorTexture(renderer)), tex.name, sampler)
                            else -> null
                        }
                    }
                }
            ).pointer()
            match.current.meshes["match"] = triangle(match).apply {
                push.bind("cell") { vec(ascii.cellWidth, ascii.cellHeight) }
                push.bind("contrast") { contrast }
                push.bind("directional") { directional }
            }
            cells += "match" to match
            if (draw) {
                val d = Module(
                    parameters(renderer) {
                        this.name = "$name-draw"
                        blend = false
                        depthTest = false
                        vertex = drawBox.getValue("vertex")
                        fragment = drawBox.getValue("fragment")
                        textureResolver = { tex ->
                            when (tex.name) {
                                "cellsTex" -> fromViews(listOf(cells), tex.name, sampler)
                                "atlasTex" -> fromViews(listOf(ascii.glyphAtlasTexture(renderer)), tex.name, sampler)
                                "sceneTex" -> fromViews(listOf(scene), tex.name, sampler)
                                else -> null
                            }
                        }
                    }
                ).pointer()
                d.current.meshes["characters"] = triangle(d).apply {
                    push.bind("cell") { vec(ascii.cellWidth, ascii.cellHeight) }
                    push.bind("ink") { vec(0.79f, 0.79f, 0.79f, 1f) }
                    push.bind("background") { vec(0.008f, 0.008f, 0.01f, 1f) }
                }
                drawModule = d
            }
        }

        /** As the boxes do: the scene, then `cells.after(scene)`, and the draw pass into the window. */
        fun install() {
            renderer.renderList.others["$name-scene"] = scene
            renderer.renderList.others["$name-cells"] = cells.after(scene)
            drawModule?.let { renderer.renderList.modules["$name-draw"] = it }
        }

        fun remove() {
            renderer.renderList.others.remove("$name-scene")
            renderer.renderList.others.remove("$name-cells")
            renderer.renderList.modules.remove("$name-draw")
        }

        /** The scene box's torus knot, with its own shaders. */
        private fun addKnot() {
            val module = Module(
                parameters(renderer) {
                    +scene
                    this.name = "$name-knot"
                    blend = false
                    depthTest = true
                    vertex = sceneBox.getValue("vertex")
                    fragment = sceneBox.getValue("fragment")
                }
            ).pointer()
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
            mesh.push.bind("aspect") { scene.parameters.width.toFloat() / scene.parameters.height }
            module.current.meshes["knot"] = mesh
            scene += "knot" to module
        }

        /** The boxes' one triangle covering the target, `pos` (-1, -1), (3, -1), (-1, 3). */
        private fun triangle(module: ModulePointer): Mesh {
            val mesh = Mesh(renderer, module).resize(3, 1)
            mesh.write("pos") { put(-1f).put(-1f).put(0f).put(3f).put(-1f).put(0f).put(-1f).put(3f).put(0f) }
            mesh.t!!.mapAndWrite_ints { put(0).put(1).put(2) }
            return mesh
        }
    }

    /** An empty scene reads back black and comes out all spaces. */
    private fun blank(renderer: Renderer, window: Window, ascii: ShapeAscii) {
        val p = Pipeline(renderer, ascii, 16, 8, "ascii-blank", knot = false)
        p.install()
        pump(renderer, window, 3)
        val r = read(renderer, window, ascii, p)
        p.remove()
        expect("an empty scene reads back black", r.scene.data.all { it == 0f })
        expect("an empty scene is all spaces on the GPU", r.frame.text().all { it == ' ' || it == '\n' })
    }

    /**
     * A module lighting the top half of the scene (clip-space y above 0, which is up in Field's
     * flipped viewports) reads back with its light in the top rows, on both the scene and the
     * characters: the readback is top row first, and the match shader sees the picture upright.
     */
    private fun upright(renderer: Renderer, window: Window, ascii: ShapeAscii) {
        val p = Pipeline(renderer, ascii, 16, 8, "ascii-upright", knot = false)
        val top = Module(
            parameters(renderer) {
                +p.scene
                name = "ascii-upright-top"
                blend = false
                depthTest = false
                vertex = """
                    #version 450
                    void main() {
                        vec2 q = gl_VertexIndex == 0 ? vec2(-1.0, 0.0) : (gl_VertexIndex == 1 ? vec2(3.0, 0.0) : vec2(-1.0, 2.0));
                        gl_Position = vec4(q, 0.0, 1.0);
                    }
                """.trimIndent()
                fragment = """
                    #version 450
                    layout(location = 0) out vec4 outColor;
                    void main() { outColor = vec4(1.0); }
                """.trimIndent()
            }
        ).pointer()
        top.current.meshes["quad"] = fullscreenTriangle(top)
        p.scene += "top" to top
        p.install()
        pump(renderer, window, 3)
        val r = read(renderer, window, ascii, p)
        p.remove()
        val space = ascii.glyphs.indexOf(' ')
        val lines = r.frame.text().lines()
        println(" -- upright: scene top-left ${r.scene.data[0]}, bottom-left ${r.scene.data[(r.scene.height - 1) * r.scene.width * 3]}; " +
            "first row '${lines.first()}', last row '${lines.last()}'")
        expect("the readback's top row is the lit top of the picture", r.scene.data[0] == 1f && r.scene.data[(r.scene.height - 1) * r.scene.width * 3] == 0f)
        expect("the characters' top row is inked and their bottom row blank",
            (0 until p.cols).all { r.frame.indices[it] != space } && (0 until p.cols).all { r.frame.indices[(p.rows - 1) * p.cols + it] == space })
    }

    /** The match shader's glyphs against the CPU's on the same frames, at three contrast settings. */
    private fun agreement(renderer: Renderer, window: Window, ascii: ShapeAscii) {
        val p = Pipeline(renderer, ascii, 128, 48, "ascii-agree", draw = true)
        p.install()
        val settings = listOf(Triple("contrast 1", 1f, 1f), Triple("contrast 3", 3f, 1f), Triple("contrast 3, directional 3", 3f, 3f))
        val times = listOf(0.3f, 1.1f, 2.0f, 2.9f, 3.7f)
        for ((label, c, d) in settings) {
            p.contrast = c
            p.directional = d
            var exact = 0.0
            var cached = 0.0
            var inked = 0.0
            var worst = 1.0
            for (t in times) {
                p.time = t
                pump(renderer, window, 3)
                val r = read(renderer, window, ascii, p)
                val cpuExact = ascii.convert(r.scene, p.cols, contrast = c.toDouble(), directional = d.toDouble(), rows = p.rows, exact = true)
                val cpuCached = ascii.convert(r.scene, p.cols, contrast = c.toDouble(), directional = d.toDouble(), rows = p.rows)
                val a = r.frame.agreement(cpuExact)
                exact += a / times.size
                worst = minOf(worst, a)
                cached += r.frame.agreement(cpuCached) / times.size
                inked += r.frame.indices.count { it != ascii.glyphs.indexOf(' ') }.toDouble() / r.frame.indices.size / times.size
                if (t == times[1]) {
                    val tag = label.replace(", ", "-").replace(" ", "")
                    write(r.scene, File(outputDir, "scene.png"))
                    write(r.frame.toImg(), File(outputDir, "gpu-$tag.png"))
                    File(outputDir, "gpu-$tag.txt").writeText(r.frame.text())
                }
            }
            println(" -- $label: GPU = CPU exact on ${pct(exact)} of cells (worst frame ${pct(worst)}), " +
                "= CPU cached on ${pct(cached)}; ${pct(inked)} of cells hold ink")
            expect("$label: the knot puts ink in at least 5% of cells", inked > 0.05)
            expect("$label: the GPU picks the CPU's exact glyph in at least 99.5% of cells", worst >= 0.995)
        }
        p.remove()
    }

    /** GPU time of the match pass at four grid sizes, and the CPU's time on the same frame. */
    private fun timing(renderer: Renderer, window: Window, ascii: ShapeAscii) {
        for ((cols, rows) in listOf(64 to 24, 128 to 48, 256 to 96, 512 to 192)) {
            val p = Pipeline(renderer, ascii, cols, rows, "ascii-time-$cols")
            p.cells.profileGpu = true
            p.scene.profileGpu = true
            p.install()
            val match = ArrayList<Double>()
            val sceneMs = ArrayList<Double>()
            repeat(270) {
                p.time = it / 60f
                pump(renderer, window, 1)
                if (it >= 30) {
                    p.cells.gpuSample?.milliseconds?.let(match::add)
                    p.scene.gpuSample?.milliseconds?.let(sceneMs::add)
                }
            }
            val r = read(renderer, window, ascii, p)
            // median of five conversions, each with a fresh cache, after two to warm the JIT
            fun cpu(exact: Boolean): Double = (0 until 7).map {
                val fresh = ShapeAscii()
                val t0 = System.nanoTime()
                fresh.convert(r.scene, cols, contrast = 3.0, directional = 3.0, rows = rows, exact = exact)
                (System.nanoTime() - t0) / 1e6
            }.drop(2).let(::median)
            val cpuExact = cpu(true)
            val cpuCached = cpu(false)
            p.remove()
            println(" -- ${cols}x$rows cells (${p.scene.parameters.width}x${p.scene.parameters.height} px): GPU match median ${ms(median(match))} ms, " +
                "10th percentile ${ms(percentile(match, 0.1))} ms (scene median ${ms(median(sceneMs))} ms, ${match.size} frames); " +
                "CPU exact ${ms(cpuExact)} ms, CPU cached ${ms(cpuCached)} ms")
            expect("${cols}x$rows: the match pass was timed", match.isNotEmpty())
        }
    }

    /** Point and outward normal at ([s], [t]) on the scene box's (2, 3) torus knot tube: s along the knot, t around the tube. */
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
        val b = doubleArrayOf(n[1] * tan[2], -n[0] * tan[2], n[0] * tan[1] - n[1] * tan[0])
        val w = 2 * PI * t
        val dir = DoubleArray(3) { cos(w) * n[it] + sin(w) * b[it] }
        return doubleArrayOf(c[0] + 0.16 * dir[0], c[1] + 0.16 * dir[1], c[2] + 0.16 * dir[2], dir[0], dir[1], dir[2])
    }

    private fun norm(v: DoubleArray): DoubleArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return DoubleArray(3) { v[it] / l }
    }

    private fun read(renderer: Renderer, window: Window, ascii: ShapeAscii, p: Pipeline): ShapeAsciiReadback {
        val future = ascii.readCells(p.scene, p.cells, renderer)
        repeat(6) { if (!future.isDone) pump(renderer, window, 1) }
        return future.get(20, TimeUnit.SECONDS)
    }

    private fun median(v: List<Double>): Double = percentile(v, 0.5)
    private fun percentile(v: List<Double>, q: Double): Double = if (v.isEmpty()) Double.NaN else v.sorted()[(q * (v.size - 1)).toInt()]
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
