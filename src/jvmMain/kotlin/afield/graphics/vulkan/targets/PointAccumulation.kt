@file:Suppress("MemberVisibilityCanBePrivate")

package afield.graphics.vulkan.targets

import afield.app.protocol.Rect
import afield.document.context.fromContext
import afield.graphics.render.Mesh
import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.Parameters
import afield.graphics.render.fullscreenTriangle
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.shader.fromViews
import afield.graphics.texture.HasImageView
import afield.graphics.texture.ImageView
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.buffers.HasBuffer
import afield.graphics.vulkan.device.CommandBuffer
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
import org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT
import kotlin.math.max

/**
 * Accumulating points: GPU points plotted into a float image that fades a little every frame, so
 * they build up into dust, trails or a density picture. [PointAccumulation] is the target;
 * [AccumulatedPoint][PointAccumulation.plot] records are what it plots.
 */

/** How points combine with what is already in a [PointAccumulation]. */
enum class PointBlend {
    /** Points add light. Dense places get brighter without limit; the tone map decides how it reads. */
    ADDITIVE,

    /** Points cover what is under them by their alpha (premultiplied "over"), like paint on a canvas. */
    ALPHA
}

/** How a [PointAccumulation]'s accumulated light becomes a colour between 0 and 1 on screen. */
enum class PointToneMap {
    /** Clamp: what you plotted is what you see, up to 1. */
    LINEAR,

    /** `1 - exp(-x)`: saturates smoothly, the look of dust that glows as it piles up. */
    EXPONENTIAL,

    /** Extended Reinhard, `x (1 + x / w²) / (1 + x)`, reaching 1 at [PointAccumulationSettings.whitePoint]. */
    REINHARD,

    /** `log(1 + x) / log(1 + w)`: the fractal-flame log-density look, which keeps detail in sparse and dense places at once. */
    LOG
}

/**
 * Settings for a [PointAccumulation]. Everything except the size and [format] can change while it
 * runs: the passes read these each frame.
 */
open class PointAccumulationSettings {
    /** Names the modules this accumulation installs, so a second one with the same name replaces it. */
    var name: String = "points"

    /** The target's size in pixels; 0 means the renderer's window. */
    var width: Int = 0
    var height: Int = 0

    /** The float format of the accumulation. 16-bit float is enough while [fade] is above zero. */
    var format: Int = VK_FORMAT_R16G16B16A16_SFLOAT

    /** How much of the picture disappears each frame, 0..1. 0 keeps everything ever plotted. */
    var fade: Double = 0.05

    var blend: PointBlend = PointBlend.ADDITIVE
    var toneMap: PointToneMap = PointToneMap.EXPONENTIAL

    /** Multiplies the accumulated light before the tone map. */
    var exposure: Double = 1.0

    /** Where [PointToneMap.REINHARD] and [PointToneMap.LOG] reach full brightness. */
    var whitePoint: Double = 16.0

    /** Multiplies every point's alpha as it is plotted, so a denser system can keep the same look. */
    var intensity: Double = 1.0

    /** The ground the light is shown on, as an sRGB colour (what a CSS colour or `hexColor("#010301")` gives). */
    var background: Vec4 = vec(0.0, 0.0, 0.0, 1.0)

    /** The world rectangle that fills the target, as `Rect(xMin, yMin, width, height)` with y up. */
    var viewport: Rect = Rect(-1.0, -1.0, 2.0, 2.0)

    /** Grow [viewport] to the target's shape, so a circle stays round, instead of stretching it. */
    var keepAspect: Boolean = true

    /** The viewport by its edges, the way a canvas demo writes it. */
    fun viewport(xMin: Double, xMax: Double, yMin: Double, yMax: Double) {
        viewport = Rect(xMin, yMin, xMax - xMin, yMax - yMin)
    }
}

/**
 * Points that accumulate: a floating-point [PingPongRenderToTexture] that every frame keeps
 * `1 - fade` of what it held, then lets point modules draw on top with [PointBlend.ADDITIVE] or
 * [PointBlend.ALPHA] blending, and shows the result tone mapped over a background colour.
 *
 * The usual way in is [plot]: hand it a buffer of `AccumulatedPoint` records — two vec4s each,
 * `position = (world x, world y, size in pixels, weight)` and a linear `color` whose alpha is how
 * much one point adds — and it draws them every frame in world coordinates, mapped through
 * [PointAccumulationSettings.viewport]. A compute shader that writes that buffer gets accumulation
 * with nothing else to set up; [afield.simulation.particles.IteratedSystem] is one. A module that
 * wants its own shaders takes [pointParameters], which carry the target's format and the blend.
 *
 * ```
 * val dust = PointAccumulation {
 *     fade = 0.02
 *     viewport(-2.0, 2.0, -2.0, 2.0)
 * }
 * dust.plot(points, count)   // points: an AccumulatedPoint buffer some compute shader fills
 * dust.installInto()
 * ```
 *
 * [installInto] registers the accumulation to run before the window's own modules and adds the
 * display pass. Set [advance] to decide, frame by frame, whether the accumulation moves on at all;
 * while it returns false the picture holds still, which is how a system runs slower than the
 * renderer.
 */
class PointAccumulation(
    /** The renderer to build on. */
    val renderer: Renderer,
    /** The settings, and the handle for changing them live. */
    val settings: PointAccumulationSettings
) : Target, HasImageView {

    /** Build one on the renderer this box is running in, configured by [configure]. */
    constructor(renderer: Renderer = fromContext(), configure: PointAccumulationSettings.() -> Unit = {}) :
        this(renderer, PointAccumulationSettings().apply(configure))

    /** Width of the accumulation in pixels. */
    val width: Int = settings.width.takeIf { it > 0 } ?: renderer.swapChain.swapChainExtent.width()

    /** Height of the accumulation in pixels. */
    val height: Int = settings.height.takeIf { it > 0 } ?: renderer.swapChain.swapChainExtent.height()

    /** The accumulation itself. Point modules are added to it; [latestImageView] is what to sample. */
    val target = PingPongRenderToTexture(renderer) {
        width = this@PointAccumulation.width
        height = this@PointAccumulation.height
        format = settings.format
        colorFormats = listOf(settings.format)
        clearColor = vec(0, 0, 0, 0)
        clearFirst = true
        colorSampled = true
        useDepth = false
        generateColorMipmaps = false
        debugName = "PointAccumulation ${settings.name}"
    }

    /** Called once per frame: return false to hold the picture where it is this frame. */
    var advance: () -> Boolean = { true }

    /** The pass that carries the previous frame forward, faded. Always runs first. */
    val fadeModule: ModulePointer

    private val sampler = TextureSampler(renderer, 1, false)
    private var clearRequested = true
    private var plots = 0

    init {
        require(width > 0 && height > 0) { "a PointAccumulation needs a positive size, got ${width}x$height" }
        fadeModule = Module(
            parameters(renderer) {
                target.apply()
                name = "${settings.name}-fade"
                depthTest = false
                blend = false
                vertex = PointShaders.source("fullscreen_vertex.glsl")
                fragment = PointShaders.source("fade_fragment.glsl")
                textureResolver = { tex ->
                    if (tex.name == "previous") fromViews(listOf(target.otherImageView), tex.name, sampler) else null
                }
            }
        ).pointer()
        fadeModule += "${settings.name}-fade-quad" to fullscreenTriangle(fadeModule).apply {
            push.bind("keep") { (1.0 - settings.fade.coerceIn(0.0, 1.0)).toFloat() }
            push.bind("clearRequested") { if (clearRequested) 1f else 0f }
        }
        target += "fade" to fadeModule
    }

    /** The latest accumulated frame, so the accumulation can be sampled like any texture. */
    override val imageView: ImageView
        get() = target.latestImageView.imageView

    /** The latest accumulated frame. */
    val latestImageView: HasImageView
        get() = target.latestImageView

    /** Runs the fade and every point module, unless [advance] says to hold. */
    override fun render(command: CommandBuffer, index: Int) {
        if (!advance()) return
        target.render(command, index)
        clearRequested = false
    }

    /** Erase the picture on the next frame that advances. */
    fun clear() {
        clearRequested = true
    }

    /**
     * The viewport actually used, `(xMin, yMin, xMax, yMax)`: [PointAccumulationSettings.viewport],
     * grown about its centre to the target's shape when [PointAccumulationSettings.keepAspect] is set.
     */
    fun effectiveViewport(): Vec4 =
        fitViewport(settings.viewport, if (settings.keepAspect) width.toDouble() / height else 0.0)

    /**
     * Parameters for a module that draws into this accumulation: the target's format, no depth, and
     * the blend from [PointAccumulationSettings.blend], expecting premultiplied colour. Hand them to
     * `moduleHere(...)`, then add the module with [add].
     */
    fun pointParameters(name: String, configure: Parameters.() -> Unit = {}): Parameters = parameters(renderer) {
        target.apply()
        this.name = name
        depthTest = false
        depthWrite = false
        blendFor(settings.blend)
        configure()
    }

    /** Add a module that draws into this accumulation, after the fade. */
    fun add(name: String, module: ModulePointer) {
        target += name to module
    }

    /**
     * Plot `count` `AccumulatedPoint` records from [points] every frame, mapped from world
     * coordinates through the viewport. [points] is read when each frame is drawn, so it may hand
     * back a different buffer each time. Returns the module, already added.
     */
    fun plot(points: () -> HasBuffer, count: Int, name: String = "${settings.name}-plot"): ModulePointer {
        require(count >= 0) { "count must not be negative" }
        val module = Module(
            pointParameters(name) {
                vertex = PointShaders.source("points_vertex.glsl")
                fragment = PointShaders.source("points_fragment.glsl")
            }
        ).pointer()
        val mesh = Mesh(renderer, module).resize(count * 6)
        mesh.push.bind("points") { points() }
        mesh.push.bind("viewport") { effectiveViewport() }
        mesh.push.bind("targetSize") { vec(width.toFloat(), height.toFloat()) }
        mesh.push.bind("intensity") { settings.intensity.toFloat() }
        module += "$name-points" to mesh
        add(name + (if (plots++ == 0) "" else "-$plots"), module)
        return module
    }

    /** [plot] for a buffer that does not change. */
    fun plot(points: HasBuffer, count: Int, name: String = "${settings.name}-plot"): ModulePointer =
        plot({ points }, count, name)

    /**
     * Run this accumulation before [renderTarget]'s own modules each frame and, unless
     * `withDisplay` is false, show it there with [display]. Installing again under the same name
     * replaces the earlier one. Returns the display module, or null.
     */
    fun installInto(
        renderTarget: RenderTarget = renderer.renderList,
        withDisplay: Boolean = true,
        viewport: Rect? = null
    ): ModulePointer? {
        renderTarget.preTargets.putOwned("${settings.name}-accumulation", this, renderer)
        return if (withDisplay) display(renderTarget, viewport = viewport) else null
    }

    /**
     * Draw the accumulation to [renderTarget], tone mapped and over the background. `viewport` is
     * where on the target it lands; null fills it.
     */
    fun display(
        renderTarget: RenderTarget = renderer.renderList,
        name: String = "${settings.name}-display",
        viewport: Rect? = null
    ): ModulePointer {
        val module = Module(
            parameters(renderer) {
                this.name = name
                depthTest = false
                depthWrite = false
                blend = false
                this.viewport = viewport
                vertex = PointShaders.source("fullscreen_vertex.glsl")
                fragment = PointShaders.source("display_fragment.glsl")
                textureResolver = { tex ->
                    if (tex.name == "accumulation") fromViews(listOf(target.latestImageView), tex.name, sampler) else null
                }
            }
        ).pointer()
        module += "$name-quad" to fullscreenTriangle(module).apply {
            push.bind("background") { linearBackground() }
            push.bind("exposure") { settings.exposure.toFloat() }
            push.bind("whitePoint") { max(settings.whitePoint, 1e-3).toFloat() }
            push.bind("toneMap") { settings.toneMap.ordinal }
            push.bind("over") { if (settings.blend == PointBlend.ALPHA) 1 else 0 }
        }
        renderTarget += name to module
        return module
    }

    private fun linearBackground(): Vec4 {
        val b = settings.background
        return vec(srgbToLinear(b.x.toDouble()), srgbToLinear(b.y.toDouble()), srgbToLinear(b.z.toDouble()), 1.0)
    }

    companion object {
        /**
         * [viewport] as `(xMin, yMin, xMax, yMax)`, grown about its centre until its width over its
         * height is [aspect]; an [aspect] of 0 or less leaves it as it is.
         */
        fun fitViewport(viewport: Rect, aspect: Double): Vec4 {
            var w = viewport.w
            var h = viewport.h
            if (aspect > 0 && w > 0 && h > 0) {
                if (w / h < aspect) w = h * aspect else h = w / aspect
            }
            val cx = viewport.x + viewport.w / 2
            val cy = viewport.y + viewport.h / 2
            return vec(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        }

        /** sRGB to linear for one channel in 0..1 — what the display pass applies to the background. */
        fun srgbToLinear(c: Double): Double =
            if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

        internal fun Parameters.blendFor(mode: PointBlend) {
            blend = true
            srcColor = VK_BLEND_FACTOR_ONE
            srcAlpha = VK_BLEND_FACTOR_ONE
            colorBlendOp = VK_BLEND_OP_ADD
            alphaBlendOp = VK_BLEND_OP_ADD
            dstColor = if (mode == PointBlend.ADDITIVE) VK_BLEND_FACTOR_ONE else VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
            dstAlpha = dstColor
        }
    }
}

/**
 * The packaged GLSL behind [PointAccumulation] and [afield.simulation.particles.IteratedSystem].
 * `#include "points/system"` in a compute shader reaches `system.glsl`, the iterated-system harness.
 */
object PointShaders {
    private val names = setOf(
        "system.glsl", "fullscreen_vertex.glsl", "fade_fragment.glsl",
        "points_vertex.glsl", "points_fragment.glsl", "display_fragment.glsl"
    )

    /** Whether `points/<name>` names one of these sources, with or without `.glsl`. */
    fun has(name: String): Boolean = withExtension(name) in names

    /** The source of `name` (`system`, `system.glsl`, ...). */
    fun source(name: String): String {
        val file = withExtension(name)
        require(file in names) { "Unknown points shader: $name (have ${names.sorted()})" }
        return checkNotNull(javaClass.getResourceAsStream("/afield/four/points/$file")) {
            "Missing packaged points shader: $file"
        }.bufferedReader().use { it.readText() }
    }

    private fun withExtension(name: String) = if (name.endsWith(".glsl")) name else "$name.glsl"
}
