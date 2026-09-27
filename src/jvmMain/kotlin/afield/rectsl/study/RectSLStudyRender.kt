package afield.rectsl.study

import afield.Runloop
import afield.four.Renderer
import afield.four.modules.Mesh
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.targets.RenderToTexture
import afield.rectsl.FitPolicy
import afield.rectsl.MaterialBinding
import afield.rectsl.Origin
import afield.rectsl.PaintSpec
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.rectsl.RectMesh
import afield.rectsl.SampledRect
import afield.rectsl.SceneProgram
import afield.rectsl.StackSLRectMaterialCompileResult
import afield.rectsl.Vec2d
import afield.rectsl.lowerSampledScene
import afield.rectsl.rectAnimationFragmentShader
import afield.rectsl.writeTo
import afield.stacksl.cpu.CpuRaster
import afield.stacksl.cpu.GlslValue
import afield.stacksl.cpu.StackSLCpu
import afield.stacksl.cpu.newInvocation
import afield.stacksl.cpu.parseGlslShader
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.vec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import java.util.concurrent.TimeUnit
import java.util.stream.IntStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How a RectSL scene is drawn for a study, in the window and in every piece of evidence — the one
 * place those two are made to agree.
 *
 * A scene is drawn the way the RectSL gallery draws it — the lowered rectangle mesh through
 * [RECT_ANIMATION_VERTEX_SHADER] and the scene's StackSL materials, into an 8-bit target — so what a
 * study shows is what the published RectSL pictures show:
 *
 *  - **Colours are sRGB, as typed, and blend as typed.** `0x808080` is the mid grey a colour picker
 *    shows, and a rectangle at 3% opacity is a faint wash, as in a design tool: blending happens on the
 *    typed values. A window's swapchain is sRGB and would blend in linear light instead, where 3% of a
 *    bright orange over a dark sky is a clearly visible box; so the window draws the scene into an
 *    8-bit target of its own size and shows that ([COMPOSITE_FRAGMENT]), decoding it so the swapchain's
 *    encode gives back exactly the bytes drawn. A StackSL material's output is written the same way,
 *    multiplied by the rectangle's colour.
 *  - **The wall has its background.** The scene's `->background` is drawn as the first rectangle, so
 *    a window whose aspect differs from the wall's shows the wall on black, fitted as `->fit` says
 *    ([contain]).
 *
 * Frames come back as [CpuRaster]s of *linear* colour — the drawn bytes decoded — rows top to bottom,
 * so the StackSL study's measurements and `presented` (clamp, then sRGB encode) apply unchanged, and
 * give back the drawn bytes.
 */
object RectSLStudyShader {
    /** The vertex shader: RectSL's own, unchanged. */
    val VERTEX: String = RECT_ANIMATION_VERTEX_SHADER

    /** A fullscreen triangle with `fragUV` running 0..1, `y` up — the window pass that shows the scene's target. */
    val COMPOSITE_VERTEX: String = afield.stacksl.study.StackSLStudyShader.VERTEX

    /**
     * Shows the scene's 8-bit target in an sRGB window: each texel decoded to linear light, which the
     * swapchain encodes back to the same byte. The target's first row is the top of the picture and
     * `fragUV.y` grows upwards, hence the flip.
     */
    val COMPOSITE_FRAGMENT: String = """
        #version 450
        layout(location = 0) in vec2 fragUV;
        layout(location = 0) out vec4 outColor;
        layout(set = 0, binding = 0) uniform sampler2D rectslScene;

        void main() {
            vec3 c = texture(rectslScene, vec2(fragUV.x, 1.0 - fragUV.y)).rgb;
            outColor = vec4(mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c)), 1.0);
        }
    """.trimIndent()

    /** The fragment shader for a scene with these compiled materials. */
    fun fragment(materials: List<StackSLRectMaterialCompileResult>): String = rectAnimationFragmentShader(materials)

    /** The sRGB transfer function's inverse: a drawn byte's value (0..1) as linear light. */
    fun linear(v: Double): Double = if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)

    /**
     * [scene] at [time], with its background as the first rectangle — the mesh every study draw writes.
     * The rectangles named in [outlined] get an outline drawn over everything, two [pixel]s wide (in wall
     * units): the live window's way of showing which rectangles the editor's line makes.
     */
    fun mesh(scene: SceneProgram, time: Double, outlined: Set<String> = emptySet(), pixel: Double = 0.0): RectMesh {
        val sampled = scene.sample(time)
        val wall = scene.wall
        val centre = if (wall.origin == Origin.TopLeft) Vec2d(wall.width / 2, wall.height / 2) else Vec2d(0.0, 0.0)
        val background = SampledRect(
            id = "background", objectId = -1, center = centre, size = Vec2d(wall.width, wall.height), rotation = 0.0,
            scale = Vec2d(1.0, 1.0), alpha = 1.0, z = Double.NEGATIVE_INFINITY, fill = PaintSpec.Solid(scene.background),
            material = MaterialBinding(0), tags = emptySet()
        )
        val outlines = if (outlined.isEmpty() || pixel <= 0) emptyList()
            else sampled.rects.filter { it.id in outlined && it.alpha > 0.01 }.let { picked ->
                // A dark halo under a bright line, so the outline reads over light and dark alike.
                picked.flatMap { edges(it, 4.0 * pixel, OUTLINE_HALO) } + picked.flatMap { edges(it, 2.0 * pixel, OUTLINE) }
            }
        return lowerSampledScene(sampled.copy(rects = listOf(background) + sampled.rects + outlines))
    }

    private val OUTLINE = afield.Color4(0.36, 0.88, 1.0, 1.0)
    private val OUTLINE_HALO = afield.Color4(0.0, 0.0, 0.0, 0.6)

    /** Four thin rectangles, [thickness] wide, along [r]'s edges and turned with it. */
    private fun edges(r: SampledRect, thickness: Double, colour: afield.Color4): List<SampledRect> {
        val hw = 0.5 * r.size.x * kotlin.math.abs(r.scale.x); val hh = 0.5 * r.size.y * kotlin.math.abs(r.scale.y)
        val c = kotlin.math.cos(r.rotation); val s = kotlin.math.sin(r.rotation)
        fun edge(x: Double, y: Double, w: Double, h: Double) = SampledRect(
            id = "outline-${r.id}", objectId = -1, center = Vec2d(r.center.x + c * x - s * y, r.center.y + s * x + c * y),
            size = Vec2d(w, h), rotation = r.rotation, scale = Vec2d(1.0, 1.0), alpha = 1.0, z = Double.POSITIVE_INFINITY,
            fill = PaintSpec.Solid(colour), material = MaterialBinding(0), tags = emptySet())
        return listOf(edge(0.0, hh, 2 * hw + thickness, thickness), edge(0.0, -hh, 2 * hw + thickness, thickness),
            edge(-hw, 0.0, thickness, 2 * hh + thickness), edge(hw, 0.0, thickness, 2 * hh + thickness))
    }

    /**
     * The part of the wall a [width] by [height] target shows, as its centre and size — `cx, cy, w, h` in
     * wall units — under the scene's `->fit` (see [contain]).
     */
    fun visible(scene: SceneProgram, width: Int, height: Int): DoubleArray {
        val wall = scene.wall
        val target = width.toDouble() / max(1, height)
        val aspect = wall.width / wall.height
        val wider = target > aspect
        val (w, h) = when (scene.fit) {
            FitPolicy.Stretch -> wall.width to wall.height
            FitPolicy.Cover -> if (wider) wall.width to wall.width / target else wall.height * target to wall.height
            FitPolicy.Contain -> if (wider) wall.height * target to wall.height else wall.width to wall.width / target
        }
        val (cx, cy) = if (wall.origin == Origin.TopLeft) wall.width / 2 to wall.height / 2 else 0.0 to 0.0
        return doubleArrayOf(cx, cy, w, h)
    }

    /**
     * The wall point under ([u], [v]) of a [width] by [height] target, `u` across from the left and `v`
     * down from the top, both 0..1: where a click in the window lands in the scene.
     */
    fun wallPoint(scene: SceneProgram, width: Int, height: Int, u: Double, v: Double): Vec2d {
        val (cx, cy, w, h) = visible(scene, width, height)
        val x = cx - w / 2 + u * w
        return Vec2d(x, if (scene.wall.origin == Origin.TopLeft) cy - h / 2 + v * h else cy + h / 2 - v * h)
    }

    /**
     * The world-to-clip transform that fits the wall into a [width] by [height] target the way the
     * scene's `->fit` asks: `contain` (the default) shows the whole wall centred with black around it,
     * `cover` fills the target and crops the wall, `stretch` fills it and distorts. When the aspects
     * agree — every study frame — all three are exactly the wall.
     */
    fun contain(scene: SceneProgram, width: Int, height: Int): Matrix4f {
        val wall = scene.wall
        val (cx, cy, w, h) = visible(scene, width, height)
        return if (wall.origin == Origin.TopLeft) {
            Matrix4f().ortho((cx - w / 2).toFloat(), (cx + w / 2).toFloat(), (cy + h / 2).toFloat(), (cy - h / 2).toFloat(), -1f, 1f)
        } else {
            Matrix4f().ortho((cx - w / 2).toFloat(), (cx + w / 2).toFloat(), (cy - h / 2).toFloat(), (cy + h / 2).toFloat(), -1f, 1f)
        }
    }

    /** A frame's pixel size: [longSide] along the wall's longer side, the other from its aspect, both even and at least 2. */
    fun frameSize(scene: SceneProgram, longSide: Int): Pair<Int, Int> {
        val aspect = scene.wall.width / scene.wall.height
        fun even(v: Double) = max(2, (v.roundToInt() / 2) * 2)
        return if (aspect >= 1) even(longSide.toDouble()) to even(longSide / aspect) else even(longSide * aspect) to even(longSide.toDouble())
    }
}

/** What a study draws: a compiled scene, the source it came from (the key a GPU pipeline is cached by), and its compiled materials. */
class RectSLDrawable(
    val source: String,
    val scene: SceneProgram,
    val materials: List<StackSLRectMaterialCompileResult>,
    /** Which line made each rectangle, by id — what the live window outlines and picks by. */
    val origins: Map<String, afield.rectsl.RectSLOrigin> = emptyMap()
)

/**
 * Where a RectSL study's frames come from: the GPU when a window is running ([RectSLGpuRasterizer]),
 * the CPU reference when it is not ([CpuRectSLRasterizer]). Both return linear colour, rows top to
 * bottom, the wall filling the frame.
 */
interface RectSLRasterizer {
    /** Which renderer produced the last frames, and why — repeated in every tool result. */
    val description: String

    /** Whether a frame is cheap right now. */
    val fast: Boolean

    /** [drawable] at [width] by [height], once per time in [seconds], handed to [each] in order. */
    fun render(drawable: RectSLDrawable, width: Int, height: Int, seconds: List<Double>, each: (index: Int, raster: CpuRaster) -> Unit)
}

/** [RectSLRasterizer.render], collected. */
fun RectSLRasterizer.renderAll(drawable: RectSLDrawable, width: Int, height: Int, seconds: List<Double>): List<CpuRaster> {
    val out = arrayOfNulls<CpuRaster>(seconds.size)
    render(drawable, width, height, seconds) { i, raster -> out[i] = raster }
    return out.map { requireNotNull(it) }
}

/**
 * The CPU reference: the lowered mesh rasterized quad by quad at pixel centres, blended as the GPU
 * blends into its 8-bit target (source alpha over, on the typed values), shaded as
 * [RectSLStudyShader.fragment] shades — the three built-in materials in Kotlin, a StackSL material
 * through the StackSL GLSL interpreter — and decoded to linear light at the end, as the GPU's bytes
 * are. It needs no graphics context, so tests and headless runs see the scene the window shows, a
 * few levels apart at rectangle edges (it samples one point per pixel; the GPU's edge rule differs).
 */
object CpuRectSLRasterizer : RectSLRasterizer {
    override val description = "CPU reference (afield.rectsl.study): exact geometry, one sample per pixel; launch the window for the GPU"
    override val fast = true

    private const val MATERIAL_TEMPLATE = """
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 1) in vec2 rectInfo;
layout(location = 0) out vec4 outColor;

{{ stack.auto.program }}

void main() {
    outColor = program(vec4(fragUV, rectInfo.x, rectInfo.y));
}
"""

    override fun render(drawable: RectSLDrawable, width: Int, height: Int, seconds: List<Double>, each: (index: Int, raster: CpuRaster) -> Unit) {
        val shaders = drawable.materials.filter { it.ok }.associate { m ->
            m.module.materialId to runCatching { parseGlslShader(StackSLCpu.expandStackAuto(MATERIAL_TEMPLATE, m.module.source)) }.getOrNull()
        }
        seconds.forEachIndexed { i, t -> each(i, frame(drawable.scene, shaders, width, height, t)) }
    }

    private class Quad(
        val x: DoubleArray, val y: DoubleArray,
        val colour: Array<DoubleArray>, val material: DoubleArray, val shaderData: DoubleArray,
        val minX: Int, val maxX: Int, val minY: Int, val maxY: Int
    )

    private fun frame(scene: SceneProgram, shaders: Map<Int, afield.stacksl.cpu.GlslShader?>, width: Int, height: Int, time: Double): CpuRaster {
        val mesh = RectSLStudyShader.mesh(scene, time)
        val wall = scene.wall
        fun px(x: Double) = if (wall.origin == Origin.TopLeft) x / wall.width * width else (x + wall.width / 2) / wall.width * width
        fun py(y: Double) = if (wall.origin == Origin.TopLeft) y / wall.height * height else (wall.height / 2 - y) / wall.height * height
        val quads = (0 until mesh.vertices.size / 4).map { q ->
            val v = (0 until 4).map { mesh.vertices[q * 4 + it] }
            val xs = DoubleArray(4) { px(v[it].position.x.toDouble()) }
            val ys = DoubleArray(4) { py(v[it].position.y.toDouble()) }
            Quad(xs, ys,
                Array(4) { doubleArrayOf(v[it].color.x.toDouble(), v[it].color.y.toDouble(), v[it].color.z.toDouble(), v[it].color.w.toDouble()) },
                doubleArrayOf(v[0].material.x.toDouble(), v[0].material.y.toDouble(), v[0].material.z.toDouble(), v[0].material.w.toDouble()),
                doubleArrayOf(v[0].shaderData.x.toDouble(), v[0].shaderData.y.toDouble(), v[0].shaderData.z.toDouble(), v[0].shaderData.w.toDouble()),
                max(0, floor(xs.min()).toInt()), min(width - 1, floor(xs.max()).toInt()),
                max(0, floor(ys.min()).toInt()), min(height - 1, floor(ys.max()).toInt()))
        }
        val rgba = FloatArray(width * height * 4)
        IntStream.range(0, height).parallel().forEach { row ->
            val invocations = HashMap<Int, afield.stacksl.cpu.GlslInvocation>()
            val cy = row + 0.5
            for (quad in quads) {
                if (row < quad.minY || row > quad.maxY) continue
                for (col in quad.minX..quad.maxX) {
                    val cx = col + 0.5
                    // Two triangles, (0 1 2) and (0 2 3), as the index buffer draws them; a centre on
                    // their shared diagonal belongs to the first, so nothing is blended twice.
                    val w = barycentric(quad, 0, 1, 2, cx, cy) ?: barycentric(quad, 0, 2, 3, cx, cy, excludeAB = true) ?: continue
                    val (a, b, c, weights) = w
                    val u = CORNER_U[a] * weights[0] + CORNER_U[b] * weights[1] + CORNER_U[c] * weights[2]
                    val vv = CORNER_V[a] * weights[0] + CORNER_V[b] * weights[1] + CORNER_V[c] * weights[2]
                    val colour = DoubleArray(4) { ch -> quad.colour[a][ch] * weights[0] + quad.colour[b][ch] * weights[1] + quad.colour[c][ch] * weights[2] }
                    val out = shade(quad, colour, u, vv, time, shaders, invocations) ?: continue
                    val base = (row * width + col) * 4
                    val alpha = out[3].coerceIn(0.0, 1.0)
                    // An 8-bit target stores each blend's result clamped to 0..1.
                    for (ch in 0 until 3) rgba[base + ch] = (out[ch] * alpha + rgba[base + ch] * (1 - alpha)).coerceIn(0.0, 1.0).toFloat()
                    rgba[base + 3] = (alpha + rgba[base + 3] * (1 - alpha)).toFloat()
                }
            }
        }
        // Rounded to the target's 8 bits and decoded, as the GPU's readback is.
        for (i in rgba.indices) if (i % 4 != 3) rgba[i] = RectSLStudyShader.linear(Math.round(rgba[i] * 255.0) / 255.0).toFloat()
        return CpuRaster(width, height, rgba)
    }

    private val CORNER_U = doubleArrayOf(0.0, 1.0, 1.0, 0.0)
    private val CORNER_V = doubleArrayOf(0.0, 0.0, 1.0, 1.0)

    private data class Weights(val a: Int, val b: Int, val c: Int, val w: DoubleArray)

    /**
     * Barycentric weights of (px, py) in triangle (a, b, c) of [quad], or null outside it; either
     * winding. With [excludeAB], a point on edge a–b is outside — the quad's diagonal, which the first
     * triangle already drew.
     */
    private fun barycentric(quad: Quad, a: Int, b: Int, c: Int, px: Double, py: Double, excludeAB: Boolean = false): Weights? {
        val ax = quad.x[a]; val ay = quad.y[a]; val bx = quad.x[b]; val by = quad.y[b]; val cx = quad.x[c]; val cy = quad.y[c]
        val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        if (kotlin.math.abs(area) < 1e-12) return null
        val w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area
        val w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area
        val w2 = 1.0 - w0 - w1
        if (w0 < 0 || w1 < 0 || w2 < 0 || (excludeAB && w2 == 0.0)) return null
        return Weights(a, b, c, doubleArrayOf(w0, w1, w2))
    }

    private fun shade(quad: Quad, colour: DoubleArray, u: Double, v: Double, time: Double,
                      shaders: Map<Int, afield.stacksl.cpu.GlslShader?>, invocations: HashMap<Int, afield.stacksl.cpu.GlslInvocation>): DoubleArray? {
        val id = (quad.material[0] + 0.5).toInt()
        return when (id) {
            0 -> colour
            1 -> {
                val edge = minOf(u, 1 - u, v, 1 - v)
                val softness = max(quad.shaderData[0], 0.0001)
                val t = (edge / softness).coerceIn(0.0, 1.0)
                doubleArrayOf(colour[0], colour[1], colour[2], colour[3] * t * t * (3 - 2 * t))
            }
            2 -> doubleArrayOf(u, v, (quad.material[2] * 0.137).let { it - floor(it) }, max(colour[3], 0.35))
            else -> {
                val shader = shaders[id] ?: return doubleArrayOf(1.0, 0.0, 1.0, 1.0)
                val invocation = invocations.getOrPut(id) { shader.newInvocation() }
                invocation.setGlobal("fragUV", GlslValue.vec2(u.toFloat(), v.toFloat()))
                invocation.setGlobal("rectInfo", GlslValue.vec2(quad.material[2].toFloat(), time.toFloat()))
                if (!invocation.runMain()) return null
                val stack = invocation.global("outColor")?.c ?: return null
                doubleArrayOf(stack[0] * colour[0], stack[1] * colour[1], stack[2] * colour[2], stack[3] * colour[3])
            }
        }
    }
}

/**
 * A study's frames from the GPU: the scene drawn by a real `afield.four` module — [RectSLStudyShader]'s
 * shaders, the scene's materials — into an offscreen 8-bit target on a running renderer, as the window
 * draws it, and read back; the bytes are decoded to linear light (see [RectSLStudyShader]).
 *
 * For each time the scene is lowered on Field's main thread as the frame is recorded, two frames are
 * allowed to finish, and a readback is queued. With no running renderer, a closed one, a window that
 * has stopped drawing, a shader the GPU rejects, or a call from the main thread (which is the thread
 * that draws), it renders on [fallback] and says why in [description].
 */
class RectSLGpuRasterizer(
    private val rendererOf: () -> Renderer?,
    private val fallback: RectSLRasterizer = CpuRectSLRasterizer,
    private val frameTimeoutMs: Long = 4000
) : RectSLRasterizer {

    @Volatile override var description: String = "GPU (no frames rendered yet)"
        private set

    override val fast: Boolean get() = true

    private val lock = Any()
    private val targets = LinkedHashMap<Pair<Int, Int>, RenderToTexture>()
    private val builtFrom = HashMap<Pair<Int, Int>, String>()
    private var targetsFor: Renderer? = null
    @Volatile private var scene: SceneProgram? = null
    @Volatile private var time = 0.0

    private class Unavailable(message: String) : RuntimeException(message)

    companion object {
        private const val MODULE = "rectsl-study-module"
        private const val MESH = "scene"
        private const val TARGET = "rectsl-study-target"
        private const val MAX_TARGETS = 4
    }

    override fun render(drawable: RectSLDrawable, width: Int, height: Int, seconds: List<Double>, each: (index: Int, raster: CpuRaster) -> Unit) {
        val renderer = rendererOf()
        val reason = when {
            renderer == null -> "no renderer: launch the window box"
            renderer.isClosed -> "the window's renderer is closed"
            Runloop.isMainThread -> "called on the main thread, which is the one that draws"
            else -> null
        }
        if (reason != null) {
            description = "CPU reference ($reason)"
            return fallback.render(drawable, width, height, seconds, each)
        }
        var done = 0
        try {
            synchronized(lock) {
                gpuRender(renderer!!, drawable, width, height, seconds) { i, raster -> each(i, raster); done = i + 1 }
            }
            description = "GPU (${renderer!!.physicalDevice.deviceName})"
        } catch (e: Unavailable) {
            description = "CPU reference (GPU: ${e.message})"
            fallback.render(drawable, width, height, seconds.drop(done)) { i, raster -> each(done + i, raster) }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Runloop.isMainThread) block() else runBlocking { withContext(Runloop.runLoop.main) { block() } }
    }

    private fun gpuRender(renderer: Renderer, drawable: RectSLDrawable, width: Int, height: Int, seconds: List<Double>, each: (Int, CpuRaster) -> Unit) {
        val key = width to height
        var target: RenderToTexture? = null
        var failure: String? = null
        scene = drawable.scene
        time = seconds.firstOrNull() ?: 0.0
        onMain {
            try {
                val t = targetFor(renderer, key)
                if (builtFrom[key] == drawable.source && t.modules[MODULE]?.current?.valid == true) {
                    renderer.renderList.preTargets[TARGET] = t
                    target = t
                    return@onMain
                }
                var error: String? = null
                val built = Module(parameters(renderer) {
                    name = MODULE
                    vertex = RectSLStudyShader.VERTEX
                    fragment = RectSLStudyShader.fragment(drawable.materials)
                    blend = true
                    depthTest = false
                    primitiveType = triangles
                    t.apply()
                    errorHandler = { message, _ -> error = message }
                }).pointer()
                if (!built.current.valid) throw Unavailable("the GPU shader did not build: ${error ?: "unknown error"}")
                val mesh = Mesh(renderer, built)
                val wall = drawable.scene
                mesh.push["worldToClip"] = RectSLStudyShader.contain(wall, width, height)
                mesh.push.bind("time") { time.toFloat() }
                mesh.push["wallWidth"] = wall.wall.width.toFloat()
                mesh.push["wallHeight"] = wall.wall.height.toFloat()
                mesh.update["rectsl-frame"] = sequence {
                    while (true) {
                        scene?.let { RectSLStudyShader.mesh(it, time).writeTo(mesh) }
                        yield(Unit)
                    }
                }
                built.current.meshes[MESH] = mesh
                val previous = t.modules[MODULE]
                t += MODULE to built
                previous?.takeIf { it !== built }?.let { old -> renderer.cleanup.add(Runnable { dispose(old) }) }
                renderer.renderList.preTargets[TARGET] = t
                builtFrom[key] = drawable.source
                target = t
            } catch (e: Unavailable) {
                failure = e.message
            } catch (e: Exception) {
                failure = "the GPU shader did not build: ${e.message}"
            }
        }
        failure?.let { throw Unavailable(it) }
        val t = target!!
        try {
            seconds.forEachIndexed { i, s ->
                time = s
                awaitFrames(renderer, 2)
                val bytes = try {
                    t.readPixelsAsync().get(frameTimeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    throw Unavailable("the window stopped drawing (hidden or minimised?)")
                }
                each(i, toRaster(bytes, width, height))
            }
        } finally {
            onMain { renderer.renderList.preTargets.remove(TARGET) }
        }
    }

    private fun awaitFrames(renderer: Renderer, frames: Int) {
        val start = renderer.frameCounter
        val deadline = System.nanoTime() + frameTimeoutMs * 1_000_000
        while (renderer.frameCounter < start + frames) {
            if (renderer.isClosed) throw Unavailable("the window's renderer closed")
            if (System.nanoTime() > deadline) throw Unavailable("the window stopped drawing (hidden or minimised?)")
            Thread.sleep(1)
        }
    }

    private fun targetFor(renderer: Renderer, key: Pair<Int, Int>): RenderToTexture {
        if (targetsFor !== renderer) {
            targets.clear()
            builtFrom.clear()
            targetsFor = renderer
        }
        targets[key]?.let { return it }
        if (targets.size >= MAX_TARGETS) {
            val oldest = targets.keys.first()
            targets.remove(oldest)?.let { old -> renderer.cleanup.add(Runnable { disposeTarget(old) }) }
            builtFrom.remove(oldest)
        }
        // 8-bit, as the window's scene target and the RectSL gallery are: blending on the typed values.
        val created = RenderToTexture(renderer) {
            width = key.first
            height = key.second
            format = VK_FORMAT_R8G8B8A8_UNORM
            useDepth = false
            colorSampled = true
            colorTransferSrc = true
            clearColor = vec(0f, 0f, 0f, 0f)
            debugName = "rectsl-study-${key.first}x${key.second}"
        }
        targets[key] = created
        return created
    }

    private fun toRaster(bytes: ByteArray, width: Int, height: Int): CpuRaster {
        val floats = FloatArray(width * height * 4)
        require(bytes.size >= floats.size) { "readback held ${bytes.size} bytes, expected ${floats.size}" }
        val decode = FloatArray(256) { RectSLStudyShader.linear(it / 255.0).toFloat() }
        for (i in floats.indices) floats[i] = if (i % 4 == 3) (bytes[i].toInt() and 0xff) / 255f else decode[bytes[i].toInt() and 0xff]
        return CpuRaster(width, height, floats)
    }

    private fun dispose(module: ModulePointer) {
        runCatching {
            module.current.meshes.values.toSet().forEach { mesh ->
                (mesh.v.values + mesh.vn.values).toSet().forEach { it.cleanup() }
                mesh.t?.cleanup()
            }
            module.current.meshes.clear()
            module.current.cleanup()
        }
    }

    private fun disposeTarget(rtt: RenderToTexture) {
        runCatching {
            rtt.modules.values.forEach(::dispose)
            rtt.modules.clear()
            rtt.defaultSampler?.cleanup()
            rtt.imageViewArray.map { it.imageView }.distinctBy { it.vkImageView }.forEach { it.cleanup() }
            rtt.colorImageRefs.filterNotNull().distinctBy { it.vkImage }.forEach { it.cleanup() }
            rtt.commandBuffers.forEach { it.cleanup() }
            rtt.fences.forEach { it.cleanup() }
        }
    }
}
