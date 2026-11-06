@file:afield.runtime.completion.CompletionCategory("ALife")
@file:Suppress("MemberVisibilityCanBePrivate")

package afield.simulation.agents

import afield.app.protocol.Rect
import afield.document.context.fromContext
import afield.graphics.render.Mesh
import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.Parameters
import afield.graphics.render.fullscreenTriangle
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.shader.Introspected
import afield.graphics.shader.fromViews
import afield.graphics.texture.HasImageView
import afield.graphics.texture.HasImageViewArray
import afield.graphics.texture.ImageView
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.PingPongRenderToTexture
import afield.graphics.vulkan.targets.RenderTarget
import afield.math.linalg.Vec4
import afield.math.linalg.Vector2f
import afield.math.linalg.vec
import afield.media.image.Img
import org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
import org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import kotlin.math.pow

/**
 * A stigmergy field on the GPU: a four-channel trail texture that agents write into and read from,
 * and that diffuses, decays and drifts on its own between their visits.
 *
 * This is the medium of every "leave a mark, follow the marks" system: Physarum's chemoattractant,
 * ant pheromone, the dust in a particle system that remembers where the crowd went. [TrailField]
 * owns only the medium. Whatever writes into it is a *deposit module* you add with [addDeposit],
 * built on [depositParameters]; [ShaderPhysarumSystem] is one such writer, and its agent shader is
 * the worked example of reading the field back.
 *
 * #### One frame
 *
 * The field is an RGBA16F [PingPongRenderToTexture]. Each frame, first, a full-screen *evolve* pass
 * rebuilds the field from the previous frame: it samples the old trail at `p - wind` (bilinear, so
 * sub-pixel wind drifts smoothly), mixes in the 3x3 mean by [TrailFieldSettings.diffusion], and
 * multiplies each channel by its decay. Then every deposit module draws, additively, into the same
 * target, reading the *previous* frame through `trailTex`. The four channels are independent trails
 * (four species, or one trail plus three other kinds of mark); each has its own half-life.
 *
 * The field is toroidal: sampling and diffusion wrap at the edges.
 *
 * #### Reading it from a shader
 *
 * `#include "trail/sample"` gives `trailAt(trailTex, px)` (bilinear, wrapping, pixel coordinates
 * with pixel centres at `+0.5`), `trailGradient(trailTex, px, weights, h)`, and `trailClip(px, dims)`
 * for placing a deposit point at a pixel. The same source is [TrailShaders.SAMPLE] for code that
 * builds shader strings itself.
 *
 * #### Reading it from Kotlin
 *
 * [read] queues a copy of the raw floats and completes with an [Img] (four channels, unclamped);
 * `channel(c)` of that is what [Img.topShare] takes.
 */

/** Everything [TrailField] reads: size, per-channel half-lives, diffusion, wind and format. All but the size and format are read every frame. */
data class TrailFieldSettings(
    /** Prefix for the modules and render-list entries this field registers. Two fields in one renderer need two names. */
    var name: String = "trail",
    /** Width of the field in pixels; agents built on it step in these pixels. */
    var width: Int = 1024,
    /** Height of the field in pixels. */
    var height: Int = 1024,
    /**
     * Per-channel half-life in frames: after this many frames with nothing deposited, a channel holds
     * half of what it had. Negative holds a channel for ever; zero clears it every frame. The CPU
     * Physarum's `decay = 0.92` per step is a half-life of about 8.3 frames.
     */
    var halfLifeFrames: Vec4 = vec(8.3f, 8.3f, 8.3f, 8.3f),
    /**
     * How much of each pixel is replaced by its 3x3 neighbourhood mean per frame, from 0 (no
     * diffusion) to 1 (a full box blur of radius one, the CPU Physarum's `blurRadius = 1`).
     */
    var diffusion: Float = 1f,
    /**
     * Per-channel diffusion, each from 0 to 1 as [diffusion]; when set it replaces [diffusion], so
     * one species' trail can spread wide while another's stays sharp. `null` (the default) uses
     * [diffusion] for all four.
     */
    var channelDiffusion: Vec4? = null,
    /** Drift of the whole field in pixels per frame, applied by sampling upstream. Zero by default. */
    var windPx: Vector2f = Vector2f(0f, 0f),
    /** Colour format. A float format is needed: the trail is unbounded above and decays below 8-bit steps. */
    var format: Int = VK_FORMAT_R16G16B16A16_SFLOAT
)

/** The packaged GLSL behind [TrailField], reachable from any shader as `#include "trail/<name>"`. */
object TrailShaders {
    /**
     * `trail/sample`: wrapping bilinear sampling, a gradient, and pixel-to-clip placement. Expects
     * nothing to be declared beforehand; every function takes the sampler as an argument.
     */
    val SAMPLE: String = """
        // trail/sample — reading a TrailField. Coordinates are pixels, pixel centres at +0.5; the field wraps.
        vec4 trailTexel(sampler2D t, ivec2 p) {
            ivec2 d = textureSize(t, 0);
            p = ((p % d) + d) % d;
            return texelFetch(t, p, 0);
        }

        vec4 trailAt(sampler2D t, vec2 px) {
            vec2 q = px - 0.5;
            ivec2 i = ivec2(floor(q));
            vec2 f = q - floor(q);
            vec4 a = trailTexel(t, i);
            vec4 b = trailTexel(t, i + ivec2(1, 0));
            vec4 c = trailTexel(t, i + ivec2(0, 1));
            vec4 e = trailTexel(t, i + ivec2(1, 1));
            return mix(mix(a, b, f.x), mix(c, e, f.x), f.y);
        }

        // gradient of dot(trail, weights), by central differences h pixels apart
        vec2 trailGradient(sampler2D t, vec2 px, vec4 weights, float h) {
            float dx = dot(trailAt(t, px + vec2(h, 0.0)) - trailAt(t, px - vec2(h, 0.0)), weights);
            float dy = dot(trailAt(t, px + vec2(0.0, h)) - trailAt(t, px - vec2(0.0, h)), weights);
            return vec2(dx, dy) / (2.0 * h);
        }

        // where a point drawn into the field must go so that it lands on pixel px. Field's render targets
        // use a flipped viewport, so clip y is negated: without it a deposit lands on the mirrored row.
        vec4 trailClip(vec2 px, ivec2 dims) {
            vec2 wrapped = mod(px, vec2(dims));
            vec2 clip = (wrapped / vec2(dims)) * 2.0 - 1.0;
            return vec4(clip.x, -clip.y, 0.0, 1.0);
        }
    """.trimIndent()

    private val sources = mapOf("sample" to SAMPLE)

    /** Whether `trail/<name>` names one of these sources, with or without `.glsl`. */
    fun has(name: String): Boolean = name.removeSuffix(".glsl") in sources

    /** The source of `trail/<name>`. */
    fun source(name: String): String =
        sources[name.removeSuffix(".glsl")] ?: error("Unknown trail shader: $name (have ${sources.keys.sorted()})")
}

/**
 * A GPU stigmergy field: a toroidal RGBA16F trail that diffuses, decays per channel and drifts, plus
 * the plumbing for modules that deposit into it and shaders that sense it. See [TrailFieldSettings]
 * for the overview.
 *
 * Build one, add deposit modules, and register it once with [installInto] (or as a pre-target of
 * your own). It is a [HasImageView], so any module can sample it as a texture.
 */
class TrailField(
    /** The renderer to build on; by default the one this box is running in. */
    val renderer: Renderer = fromContext(),
    configure: TrailFieldSettings.() -> Unit = {}
) : Target, HasImageView, HasImageViewArray {

    /** The settings, read every frame, so changing one takes effect on the next frame. */
    val settings = TrailFieldSettings().apply(configure)

    /** The ping-pong target holding the trail. The evolve pass and every deposit module render into it. */
    val target = PingPongRenderToTexture(renderer) {
        width = settings.width
        height = settings.height
        format = settings.format
        colorFormats = listOf(settings.format)
        clearColor = vec(0, 0, 0, 0)
        colorStoreOp = VK_ATTACHMENT_STORE_OP_STORE
        colorSampled = true
        colorTransferSrc = true
        useDepth = false
    }

    /** One sampler for every read of the trail; filtering is done in GLSL, so nothing depends on its mode. */
    val sampler = TextureSampler(renderer, 1, false)

    /** The full-screen pass that carries the trail from one frame to the next: wind, diffusion, decay. */
    val evolveModule: ModulePointer

    private var clearFrames = 0

    init {
        require(settings.width > 0 && settings.height > 0) { "width and height must be positive" }
        evolveModule = Module(
            parameters(renderer) {
                +this@TrailField.target
                name = "${settings.name}-evolve"
                depthTest = false
                blend = false
                vertex = FULLSCREEN_TRIANGLE_VERTEX
                fragment = EVOLVE_FRAGMENT
                textureResolver = previousTrailResolver()
            }
        ).pointer()
        evolveModule.current.meshes["${settings.name}-evolve-quad"] = fullscreenTriangle(evolveModule).apply {
            push.bind("decay") { decayFactors() }
            push.bind("windPx") { settings.windPx }
            push.bind("diffusion") { diffusionFactors() }
            push.bind("clearing") { if (clearFrames > 0) 1f else 0f }
        }
        target += "evolve" to evolveModule
    }

    /** Per-channel multiplicative decay per frame, from [TrailFieldSettings.halfLifeFrames]. */
    fun decayFactors(): Vec4 {
        val h = settings.halfLifeFrames
        fun f(v: Float) = when {
            v < 0f -> 1f
            v == 0f -> 0f
            else -> 0.5f.pow(1f / v)
        }
        return vec(f(h.x), f(h.y), f(h.z), f(h.w))
    }

    /** Per-channel diffusion this frame, from [TrailFieldSettings.channelDiffusion] or, when that is `null`, [TrailFieldSettings.diffusion]. */
    fun diffusionFactors(): Vec4 {
        val d = settings.channelDiffusion
        fun c(v: Float) = v.coerceIn(0f, 1f)
        return if (d == null) settings.diffusion.let { vec(c(it), c(it), c(it), c(it)) }
        else vec(c(d.x), c(d.y), c(d.z), c(d.w))
    }

    /**
     * Parameters for a module that deposits into this field: rendering into [target], additively,
     * with the previous frame of the trail bound to any sampler named `trailTex`. Build the module on
     * these, then hand it to [addDeposit].
     */
    fun depositParameters(name: String, configure: Parameters.() -> Unit = {}): Parameters = parameters(renderer) {
        +this@TrailField.target
        this.name = name
        depthTest = false
        blend = true
        srcColor = VK_BLEND_FACTOR_ONE
        dstColor = VK_BLEND_FACTOR_ONE
        colorBlendOp = VK_BLEND_OP_ADD
        srcAlpha = VK_BLEND_FACTOR_ONE
        dstAlpha = VK_BLEND_FACTOR_ONE
        alphaBlendOp = VK_BLEND_OP_ADD
        textureResolver = previousTrailResolver()
        configure()
    }

    /** Add a deposit module; deposits draw after the evolve pass, in the order they were added. */
    fun addDeposit(name: String, module: ModulePointer) {
        require(name != "evolve") { "'evolve' is the field's own pass" }
        target += name to module
    }

    /** Remove a deposit module added with [addDeposit]. */
    fun removeDeposit(name: String) {
        require(name != "evolve") { "'evolve' is the field's own pass" }
        target.modules.remove(name)
    }

    /** Empty every channel. Takes effect over the next [PingPongRenderToTexture.frameCount] frames, so no history slot keeps old trail. */
    fun clear() {
        clearFrames = target.frameCount
    }

    /**
     * Resolver binding the trail's previous frame to a sampler named `trailTex` (and its latest to
     * `trailLatestTex`), for modules built outside [depositParameters] that read the field.
     */
    fun previousTrailResolver(): (Introspected.Texture) -> Pair<HasImageViewArray, TextureSampler>? = { tex ->
        when (tex.name) {
            "trailTex" -> fromViews(listOf(target.otherImageView), tex.name, sampler)
            "trailLatestTex" -> fromViews(listOf(target.latestImageView), tex.name, sampler)
            else -> null
        }
    }

    override val imageView: ImageView
        get() = target.imageView

    override val imageViewArray: List<HasImageView>
        get() = target.imageViewArray

    override fun render(command: CommandBuffer, index: Int) {
        target.render(command, index)
        if (clearFrames > 0) clearFrames--
    }

    /** Register this field as a pre-target of [renderTarget], so it steps once per frame. */
    fun installInto(renderTarget: RenderTarget = renderer.renderList) {
        renderTarget.preTargets["${settings.name}-field"] = this
    }

    /**
     * Draw the trail to [target] with an exposure tone map: channel `c` contributes
     * `palette[c] * (1 - exp(-exposure * trail.c))`. Registers nothing else, so it can be called
     * for a second view.
     */
    fun previewInto(
        target: RenderTarget = renderer.renderList,
        name: String = "${settings.name}-preview",
        viewport: Rect? = null,
        exposure: () -> Float = { 0.15f },
        palette: List<Vec4> = DEFAULT_PALETTE
    ): ModulePointer {
        require(palette.size == 4) { "palette needs one colour per channel" }
        val module = Module(
            parameters(renderer) {
                this.name = name
                depthTest = false
                blend = false
                this.viewport = viewport
                vertex = PREVIEW_VERTEX
                fragment = PREVIEW_FRAGMENT
                textureResolver = previousTrailResolver()
            }
        ).pointer()
        module.current.meshes["$name-quad"] = fullscreenTriangle(module).apply {
            push.bind("exposure") { exposure() }
            push.bind("c0") { palette[0] }
            push.bind("c1") { palette[1] }
            push.bind("c2") { palette[2] }
            push.bind("c3") { palette[3] }
        }
        target += name to module
        return module
    }

    /**
     * Read the trail's raw floats back after its next frame: an [Img] of `width x height x 4`,
     * top row first as the field stores it, unclamped. Completes once that frame's GPU work finishes.
     */
    fun read(): CompletableFuture<Img> = target.readPixelsAsync(0).thenApply { bytes ->
        require(settings.format == VK_FORMAT_R16G16B16A16_SFLOAT) { "read() decodes RGBA16F only" }
        val n = settings.width * settings.height * 4
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        val data = FloatArray(n) { java.lang.Float.float16ToFloat(buffer.getShort(it * 2)) }
        Img(settings.width, settings.height, 4, data)
    }

    companion object {
        /** Red, green, blue and a warm white: the four channels as [previewInto] colours them by default. */
        val DEFAULT_PALETTE: List<Vec4> = listOf(
            vec(1.0f, 0.78f, 0.35f, 1f),
            vec(0.25f, 0.85f, 1.0f, 1f),
            vec(1.0f, 0.3f, 0.6f, 1f),
            vec(0.6f, 1.0f, 0.4f, 1f)
        )

        internal val FULLSCREEN_TRIANGLE_VERTEX = """
            #version 450

            void main() {
                vec2 p = gl_VertexIndex == 0 ? vec2(-1.0, -1.0) : (gl_VertexIndex == 1 ? vec2(3.0, -1.0) : vec2(-1.0, 3.0));
                gl_Position = vec4(p, 0.0, 1.0);
            }
        """.trimIndent()

        private val EVOLVE_FRAGMENT = """
            #version 450

            layout(set = 0, binding = 0) uniform sampler2D trailTex;

            layout(push_constant) uniform Push {
                vec4 decay;
                vec4 diffusion;
                vec2 windPx;
                float clearing;
            } push;

            layout(location = 0) out vec4 outColor;

            ${TrailShaders.SAMPLE.prependIndent("            ").trimStart()}

            void main() {
                if (push.clearing > 0.5) { outColor = vec4(0.0); return; }
                vec2 px = gl_FragCoord.xy - push.windPx;
                vec4 centre = trailAt(trailTex, px);
                vec4 mean = vec4(0.0);
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++)
                        mean += trailAt(trailTex, px + vec2(dx, dy));
                mean /= 9.0;
                outColor = mix(centre, mean, push.diffusion) * push.decay;
            }
        """.trimIndent()

        private val PREVIEW_VERTEX = """
            #version 450

            layout(location = 0) out vec2 uv;

            void main() {
                vec2 p = gl_VertexIndex == 0 ? vec2(-1.0, -1.0) : (gl_VertexIndex == 1 ? vec2(3.0, -1.0) : vec2(-1.0, 3.0));
                uv = p * 0.5 + 0.5;
                gl_Position = vec4(p, 0.0, 1.0);
            }
        """.trimIndent()

        private val PREVIEW_FRAGMENT = """
            #version 450

            layout(set = 0, binding = 0) uniform sampler2D trailLatestTex;

            layout(push_constant) uniform Push {
                vec4 c0;
                vec4 c1;
                vec4 c2;
                vec4 c3;
                float exposure;
            } push;

            layout(location = 0) in vec2 uv;
            layout(location = 0) out vec4 outColor;

            void main() {
                ivec2 dims = textureSize(trailLatestTex, 0);
                ivec2 pixel = clamp(ivec2(uv * vec2(dims)), ivec2(0), dims - 1);
                vec4 t = max(texelFetch(trailLatestTex, pixel, 0), vec4(0.0));
                vec4 k = 1.0 - exp(-push.exposure * t);
                vec3 c = k.x * push.c0.rgb + k.y * push.c1.rgb + k.z * push.c2.rgb + k.w * push.c3.rgb;
                outColor = vec4(c, 1.0);
            }
        """.trimIndent()
    }
}
