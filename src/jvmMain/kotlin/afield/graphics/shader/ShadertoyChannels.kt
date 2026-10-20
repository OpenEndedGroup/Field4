/**
 * What a ShaderToy Image pass reads and draws onto in Field: [ShadertoyChannel] (a texture turned the
 * way ShaderToy samples one), [ShadertoyCanvas] (the picture a document's shadertoy boxes share, shown
 * in the renderer window) and [webcam] (the camera as a channel).
 *
 * `shadertoyHere` builds the module; these give it inputs and somewhere to draw. The `shadertrinket`
 * starting point uses all three:
 *
 * ```
 * val canvas = shadertoyCanvas()
 * canvas += shadertoyHere {
 *     canvas.rtt.apply()
 *     textures("iChannel0" to webcam())
 * }
 * ```
 */
package afield.graphics.shader

import afield.app.diagnostics.logging.lprintln
import afield.document.context.fromContext
import afield.document.graph.Box
import afield.graphics.render.Mesh
import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.texture.HasImageView
import afield.graphics.texture.HasImageViewArray
import afield.graphics.texture.LoadedTexture
import afield.graphics.texture.TextureSampler
import afield.graphics.texture.video.WebcamTexture
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.RenderToTexture
import afield.math.linalg.Vector4f
import afield.math.linalg.vec
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10.*
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

private val channelSerial = AtomicInteger()

/** True for the 8-bit UNORM colour formats, whose values a shader reads back exactly as stored. */
internal fun isUnorm8(format: Int): Boolean = format in setOf(
    VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R8_UNORM, VK_FORMAT_R8G8_UNORM,
    VK_FORMAT_A8B8G8R8_UNORM_PACK32
)

/**
 * The pixel size of something sampleable, when it says: a [RenderToTexture] or [WebcamTexture] by
 * type, anything else through a public `getWidth()`/`getHeight()` (a WebRTC source has them).
 * [fallback] otherwise.
 */
internal fun sampleableSize(view: Any, fallback: Pair<Int, Int> = 512 to 512): Pair<Int, Int> {
    when (view) {
        is RenderToTexture -> return view.width to view.height
        is WebcamTexture -> if (view.width > 0) return view.width to view.height
    }
    return runCatching {
        val w = view.javaClass.getMethod("getWidth").invoke(view) as Int
        val h = view.javaClass.getMethod("getHeight").invoke(view) as Int
        if (w > 0 && h > 0) w to h else null
    }.getOrNull() ?: fallback
}

/**
 * A texture as a ShaderToy `iChannel` sees it: a copy, redrawn every frame, that is **bottom-up** and
 * holds **display values**.
 *
 * Field textures hold their rows top-down (camera frames, browser pages, and render targets, because
 * of the renderer's negative-height viewport), while a ShaderToy program samples `fragCoord /
 * iResolution.xy` with y up, so the picture would come out upside down. And a ShaderToy program reads
 * a camera's sRGB-encoded numbers, while sampling Field's `_SRGB` textures decodes them to linear
 * light. GLSL can neither overload `texture` nor wrap a sampler (programs pass samplers to their own
 * functions), so the fix is a small pass per channel: a full-screen draw into an RGBA8 UNORM target of
 * the source's size that flips the rows and re-encodes anything that is not already 8-bit UNORM. It
 * is registered as a renderer pre-target, so it runs before the frame's passes, after any pre-target
 * already there (a [WebcamTexture]'s upload).
 *
 * One channel is kept per source and renderer ([of]), so re-running a box reuses it; nothing frees a
 * render target, so it lives as long as its source.
 */
class ShadertoyChannel private constructor(
    val renderer: Renderer,
    source: Pair<HasImageViewArray, TextureSampler>,
    /** The source's width in pixels (and so this channel's `iChannelResolution`). */
    val width: Int,
    /** The source's height in pixels. */
    val height: Int,
    sourceFormat: Int
) {
    /** The flipped copy, which the shadertoy module samples. */
    val target = RenderToTexture(renderer) {
        this.width = this@ShadertoyChannel.width
        this.height = this@ShadertoyChannel.height
        format = VK_FORMAT_R8G8B8A8_UNORM
        useDepth = false
        colorSampled = true
        clearColor = vec(0f, 0f, 0f, 1f)
        debugName = "shadertoy channel ${channelSerial.incrementAndGet()}"
    }

    /** Linear filtering, clamped at the edges. */
    val sampler: TextureSampler = target.setDefaultSampler(anisotropyEnable = false)

    private val module: ModulePointer = Module(parameters(renderer) {
        name = target.parameters.debugName
        vertex = FULLSCREEN_VERTEX
        fragment = flipFragment(encode = !isUnorm8(sourceFormat), height = this@ShadertoyChannel.height)
        blend = false
        depthTest = false
        depthWrite = false
        target.apply()
        textureResolver = { if (it.name == "source") source else null }
        errorHandler = { message, _ -> lprintln("error,graphics", "shadertoy channel: $message") }
    }).pointer()

    init {
        val mesh = Mesh(renderer, module)
        mesh.resize(3, 0, module)
        module.current.meshes["fullscreen"] = mesh
        target += "flip" to module
        renderer.renderList.preTargets[target.parameters.debugName] = target
    }

    companion object {
        private val cache = WeakHashMap<Renderer, WeakHashMap<HasImageView, ShadertoyChannel>>()

        /**
         * The channel for the first view of [source] (what a texture resolver returned), made on first
         * use and kept for as long as that view lives.
         */
        fun of(renderer: Renderer, source: Pair<HasImageViewArray, TextureSampler>): ShadertoyChannel {
            val view = source.first.imageViewArray.first()
            synchronized(cache) {
                val perRenderer = cache.getOrPut(renderer) { WeakHashMap() }
                perRenderer[view]?.let { return it }
                val (w, h) = sampleableSize(view)
                val format = view.imageView.imageViewData.format
                // Forward layout preparation for sources that need it (a browser page's texture).
                val forwarded = fromViews(listOf(view), "source", source.second)
                return ShadertoyChannel(renderer, forwarded, w, h, format).also { perRenderer[view] = it }
            }
        }

        internal const val FULLSCREEN_VERTEX = """#version 450
void main() {
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
}
"""

        // gl_FragCoord rather than an interpolated uv: it starts at the top left whatever the viewport
        // convention, so "row 0 of the copy is the source's bottom row" is written down directly.
        internal fun flipFragment(encode: Boolean, height: Int) = """#version 450
layout(location = 0) out vec4 color;
layout(set = 0, binding = 0) uniform sampler2D source;

vec3 linearToDisplay(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

void main() {
    vec2 size = vec2(textureSize(source, 0));
    vec4 c = texture(source, vec2(gl_FragCoord.x / size.x, 1.0 - gl_FragCoord.y / float($height)));
    color = vec4(${if (encode) "linearToDisplay(c.rgb)" else "c.rgb"}, 1.0);
}
"""
    }
}

/**
 * The picture a document's shadertoy boxes draw into, like ShaderTrinket's canvas: a [width]×[height]
 * render target, shown letterboxed in the renderer window, that can also be published or bound as
 * another shader's `iChannel` (it is [rtt]).
 *
 * `canvas += module` draws a `shadertoyHere` module into it (build the module with
 * `canvas.rtt.apply()` in its block) and makes the module's `iResolution` the canvas size and its
 * `iMouse` the window cursor mapped into canvas pixels. Every box adds under the same name, so the
 * last one launched is the one showing, the way picking a preset in ShaderTrinket replaces the
 * program.
 *
 * Get one with [shadertoyCanvas]; it is kept per renderer and name, so every box (and every re-run)
 * shares it. A canvas that is only an input for another shader passes `inWindow = false`.
 */
class ShadertoyCanvas internal constructor(
    val renderer: Renderer,
    val name: String,
    val width: Int,
    val height: Int,
    inWindow: Boolean
) {
    /** The canvas itself: `_SRGB`, so the display values a ShaderToy program writes are what it stores. */
    val rtt = RenderToTexture(renderer) {
        this.width = this@ShadertoyCanvas.width
        this.height = this@ShadertoyCanvas.height
        format = VK_FORMAT_B8G8R8A8_SRGB
        useDepth = false
        colorSampled = true
        mipLevels = 1
        clearColor = vec(0f, 0f, 0f, 1f)
        debugName = "shadertoy canvas $name"
    }

    private val sampler = rtt.setDefaultSampler(anisotropyEnable = false)

    /** The canvas's rectangle in the window, as fractions `(x, y, w, h)` from the top left. */
    fun fitInWindow(): Vector4f {
        val window = renderer.window
        val aw = window.width.toDouble() / window.height.coerceAtLeast(1)
        val ac = width.toDouble() / height
        val fx = if (aw > ac) ac / aw else 1.0
        val fy = if (aw > ac) 1.0 else aw / ac
        return Vector4f(((1 - fx) / 2).toFloat(), ((1 - fy) / 2).toFloat(), fx.toFloat(), fy.toFloat())
    }

    /** `iResolution` and `iMouse` for modules drawn into this canvas. */
    val surface = ShadertoySurface(
        size = { width to height },
        pointer = {
            val window = renderer.window
            val points = window.size()
            val c = window.cursor
            val fit = fitInWindow()
            val u = (c.x / points.w.coerceAtLeast(1.0) - fit.x) / fit.z
            val v = (c.y / points.h.coerceAtLeast(1.0) - fit.y) / fit.w
            val down = window.isMouseButtonPressed(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT)
            if (u < 0 || u > 1 || v < 0 || v > 1) null
            else ((u * width).toFloat() to ((1 - v) * height).toFloat()) to down
        }
    )

    private val composite: ModulePointer = Module(parameters(renderer) {
        this.name = "shadertoy canvas $name (window)"
        vertex = ShadertoyChannel.FULLSCREEN_VERTEX
        fragment = COMPOSITE_FRAGMENT
        blend = false
        depthTest = false
        depthWrite = false
        textureResolver = { if (it.name == "canvas") fromViews(listOf(rtt), "canvas", sampler) else null }
        errorHandler = { message, _ -> lprintln("error,graphics", "shadertoy canvas: $message") }
    }).pointer()

    init {
        val mesh = Mesh(renderer, composite)
        mesh.resize(3, 0, composite)
        mesh.push.bind("fit") { fitInWindow() }
        mesh.push.bind("window") { Vector4f(renderer.window.width.toFloat(), renderer.window.height.toFloat(), 0f, 0f) }
        composite.current.meshes["fullscreen"] = mesh
        renderer.renderList.preTargets[rtt.parameters.debugName] = rtt
        composite.current.enabled = inWindow
        renderer.renderList.modules[composite.current.parameters.name] = composite
    }

    /** Whether the window shows this canvas. Turn it off for a canvas that is only another shader's channel. */
    var inWindow: Boolean
        get() = composite.current.enabled
        set(value) { composite.current.enabled = value }

    /** Draw [module] (a `shadertoyHere` module built with `canvas.rtt.apply()`) as this canvas's picture. */
    operator fun plusAssign(module: ModulePointer) {
        val channels = synchronized(shadertoyChannelsByModule) { shadertoyChannelsByModule[module] } ?: arrayOfNulls(4)
        bindShadertoyUniforms(module, surface, channels)
        rtt += "shader" to module
    }

    private companion object {
        const val COMPOSITE_FRAGMENT = """#version 450
layout(location = 0) out vec4 color;
layout(set = 0, binding = 0) uniform sampler2D canvas;
layout(push_constant) uniform Fit { vec4 fit; vec4 window; } pc;

void main() {
    // Both measured from the top left: gl_FragCoord in the window, and the canvas's rows.
    vec2 c = (gl_FragCoord.xy / pc.window.xy - pc.fit.xy) / pc.fit.zw;
    if (any(lessThan(c, vec2(0.0))) || any(greaterThan(c, vec2(1.0)))) color = vec4(0.0, 0.0, 0.0, 1.0);
    else color = vec4(texture(canvas, c).rgb, 1.0);
}
"""
    }
}

private val canvases = WeakHashMap<Renderer, MutableMap<String, ShadertoyCanvas>>()

/**
 * This renderer's shadertoy canvas called [name], made [width]×[height] on first use and shared after
 * that (asking again with another size keeps the first, and says so). See [ShadertoyCanvas].
 */
fun shadertoyCanvas(
    width: Int = 960,
    height: Int = 540,
    name: String = "shadertrinket",
    inWindow: Boolean = true,
    renderer: Renderer = fromContext()
): ShadertoyCanvas = synchronized(canvases) {
    val perRenderer = canvases.getOrPut(renderer) { mutableMapOf() }
    perRenderer[name]?.let {
        if (it.width != width || it.height != height)
            lprintln("warning,graphics", "shadertoy canvas '$name' is already ${it.width}x${it.height}; keeping it")
        it.inWindow = inWindow
        return it
    }
    ShadertoyCanvas(renderer, name, width, height, inWindow).also { perRenderer[name] = it }
}

private val webcams = WeakHashMap<Renderer, MutableMap<Int, WebcamTexture>>()
private val noCamera = WeakHashMap<Renderer, LoadedTexture>()

/**
 * The camera [device] as a texture, for `textures("iChannel0" to webcam())`: opened once per renderer
 * at [width]×[height] (if the camera offers it) and kept open across re-runs, and uploaded every frame
 * as a renderer pre-target.
 *
 * With no camera, or no permission to use one, this box shows an error saying so and the channel is
 * black, which is what ShaderTrinket's Camera Input Demo shows with its camera off. The next run tries
 * again.
 */
fun Box.webcam(device: Int = 0, width: Int = 1280, height: Int = 720, renderer: Renderer = fromContext()): HasImageView {
    synchronized(webcams) {
        webcams[renderer]?.get(device)?.let { return it }
        return try {
            val camera = WebcamTexture(renderer, device, width, height).start()
            webcams.getOrPut(renderer) { mutableMapOf() }[device] = camera
            renderer.renderList.preTargets["webcam $device"] = camera
            camera
        } catch (t: Throwable) {
            val reason = generateSequence(t) { it.cause }.last().message ?: t.toString()
            error("webcam(): no camera $device ($reason); iChannel samples black. On macOS, allow Field (or the Terminal that ran it) in System Settings > Privacy > Camera.", "code")
            noCamera.getOrPut(renderer) {
                val data = MemoryUtil.memAlloc(4)
                data.put(byteArrayOf(0, 0, 0, 255.toByte()))
                data.flip()
                LoadedTexture.fromRawRGBA("no camera", 1, 1, data, renderer, VK_FORMAT_R8G8B8A8_UNORM)
            }
        }
    }
}
