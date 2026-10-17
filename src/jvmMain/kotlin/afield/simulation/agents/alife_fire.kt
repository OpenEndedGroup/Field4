@file:afield.runtime.completion.CompletionCategory("ALife")
@file:Suppress("MemberVisibilityCanBePrivate")

package afield.simulation.agents

import afield.app.protocol.Rect
import afield.document.context.fromContext
import afield.document.graph.Box
import afield.graphics.render.Mesh
import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.Parameters
import afield.graphics.render.fullscreenTriangle
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.shader.Introspected
import afield.graphics.shader.attachShader
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
import afield.math.linalg.vec
import org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
import org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT

/**
 * Fire spreading through a forest on the GPU: a fixed scatter of trees, fire that you light by
 * drawing into a texture, and four kinds of fire, one per colour channel, that compete for the same
 * trees.
 *
 * #### The rule
 *
 * Each texel is a tree or bare ground, decided once by a hash of its position and
 * [ShaderForestFireSettings.randomSeed], so the forest is the same every run with the same seed. A
 * tree is *ready*, *burning* or *recovering*, and which it is lives in one number per kind of fire: 1
 * the frame it catches, then falling by that kind's [ShaderForestFireSettings.refractoryDecayPerFrame]
 * each frame. Above [ShaderForestFireSettings.activeFireThreshold] it is burning; below
 * [ShaderForestFireSettings.readyThreshold] it can catch again; in between it is recovering and
 * cannot. A ready tree catches when a burning neighbour's fire, times
 * [ShaderForestFireSettings.spreadGain], reaches [ShaderForestFireSettings.ignitionThreshold] and a
 * random draw against [ShaderForestFireSettings.spreadProbability] lets it across, or when the
 * ignition texture at that texel reaches the threshold. If several kinds could take it, the strongest wins, and
 * catching one kind sets the other three to zero.
 *
 * Because trees recover, this is an excitable medium rather than a fire that leaves ash: a front
 * passes, the forest behind it recovers, and a second front can follow the first. A front broken by
 * bare ground curls at the break, and steady ignition makes a train of rings.
 *
 * #### What is in each texture
 *
 * [ShaderForestFireSystem.state] holds the four fire levels in RGBA; it is what [imageView] returns.
 * [ShaderForestFireSystem.fireInput] is where ignition is drawn, RGBA one kind per channel, carried
 * forward and faded by [ShaderForestFireSettings.ignitionDecayPerFrame].
 * [ShaderForestFireSystem.ink] is a colour that burning trees pass on to their neighbours, so that what
 * you draw into it travels with the fire. The preview colours each kind red, green, blue or magenta,
 * tinted by the ink.
 *
 * #### Lighting it
 *
 * Nothing burns until something is lit. Draw into [ShaderForestFireSystem.fireInput] with a module
 * built on [ShaderForestFireSystem.fireInputParameters], which blends additively: a fragment writing
 * `vec4(1, 0, 0, 0)` lights the red kind there. As with [ShaderDlaSystem], constructing the system
 * inside a box seeds that box's `fragment` tab with the spreading rule, so the rule arrives as source
 * you can edit.
 */

/** Four numbers, one per kind of fire, red to alpha. */
data class FireChannels(
    /** Kind 0, drawn red. */
    val r: Float = 0f,
    /** Kind 1, drawn green. */
    val g: Float = 0f,
    /** Kind 2, drawn blue. */
    val b: Float = 0f,
    /** Kind 3, drawn magenta. */
    val a: Float = 0f
) {
    /** All four scaled. */
    operator fun times(scale: Float) = FireChannels(r * scale, g * scale, b * scale, a * scale)

    /** As a `vec4`, for a push constant. */
    fun toVec4(): Vec4 = vec(r, g, b, a)

    companion object {
        /** [strength] in the channel of kind [index], 0 to 3, and zero in the others. */
        fun kind(index: Int, strength: Float = 1f): FireChannels = when (index) {
            0 -> FireChannels(r = strength)
            1 -> FireChannels(g = strength)
            2 -> FireChannels(b = strength)
            3 -> FireChannels(a = strength)
            else -> throw IllegalArgumentException("fire kind index must be in 0..3, got $index")
        }
    }
}

/**
 * Everything [ShaderForestFireSystem] reads. All but the sizes and format are bound as lambdas, so
 * changing one while it runs takes effect on the next frame; changing the forest itself
 * ([treeDensity], [treeScalePx], [treeJitter], [randomSeed]) regrows it in place.
 */
data class ShaderForestFireSettings(
    /** Prefix for module, mesh and pre-target names. Two systems in one renderer need two names. */
    var name: String = "forest-fire",
    /** Forest width in texels. */
    var width: Int = 1024,
    /** Forest height in texels. */
    var height: Int = 1024,
    /** Format of all three textures. */
    var format: Int = VK_FORMAT_R16G16B16A16_SFLOAT,
    /**
     * Clear colour of every texture. Transparent black, because alpha is not opacity here: it is the
     * fourth kind of fire, and an opaque clear would light all of it at once.
     */
    var clearColor: Vec4 = vec(0, 0, 0, 0),
    /** The fraction of texels that are trees. Below about 0.4 fire stops crossing the gaps. */
    var treeDensity: Float = 0.72f,
    /** Size in texels of the coarse cells the forest is hashed on, so trees grow in clumps. */
    var treeScalePx: Float = 3f,
    /** How much per-texel noise is mixed into the clumps, 0 to 1. */
    var treeJitter: Float = 0.28f,
    /** How far, in texels, a tree looks for burning neighbours. */
    var spreadStepPx: Float = 1f,
    /** Gain on a burning neighbour's fire before it is compared with [ignitionThreshold]. */
    var spreadGain: Float = 1.02f,
    /**
     * The chance, each frame, that one burning neighbour lights a ready tree. At 1 every front
     * moves one texel a frame in all eight directions and comes out square; below 1 it moves by
     * chance, slower on average, and rounds itself into a ragged circle.
     */
    var spreadProbability: Float = 0.5f,
    /** Gain on the ignition texture before it is compared with [ignitionThreshold]. */
    var paintGain: Float = 1.0f,
    /** The signal at or above which a ready tree catches. */
    var ignitionThreshold: Float = 0.33f,
    /** The fire level at or above which a tree is burning. */
    var activeFireThreshold: Float = 0.82f,
    /** The fire level at or below which a tree can catch again. */
    var readyThreshold: Float = 0.03f,
    /**
     * How fast each kind's fire level falls per frame. With the default thresholds red burns for 18
     * frames and is ready again after 97; alpha takes two and a half times as long.
     */
    var refractoryDecayPerFrame: FireChannels = FireChannels(0.010f, 0.008f, 0.006f, 0.004f),
    /** How fast the ignition texture fades per frame, per kind. */
    var ignitionDecayPerFrame: FireChannels = FireChannels(0.002f, 0.002f, 0.002f, 0.002f),
    /** How fast the ink fades per frame, per channel. */
    var inkDecayPerFrame: FireChannels = FireChannels(0.004f, 0.004f, 0.004f, 0.004f),
    /** The fraction of a burning neighbour's ink a burning tree takes on. */
    var inkSpreadGain: Float = 0.92f,
    /** Seed for the forest. */
    var randomSeed: Long = 1L
)

/**
 * A forest-fire excitable medium: ignition, fire state and ink, each a full-screen pass into its own
 * ping-pong target. Build one, call [installInto], then light it with a module from
 * [fireInputParameters]. The file overview above describes the rule and the encoding.
 */
class ShaderForestFireSystem(
    /** The renderer to build the targets and modules on; by default the one this box is running in. */
    val renderer: Renderer = fromContext(),
    configure: ShaderForestFireSettings.() -> Unit = {}
) : Target, HasImageView, HasImageViewArray {

    /** The settings this system was built with, after `configure` ran, and the handle for changing it live. */
    val settings = ShaderForestFireSettings().apply(configure)

    /** Where ignition is drawn, one kind of fire per channel. Add modules to it with [addFireInput]. */
    val fireInput = pingPong()

    /** The four fire levels per tree. This is what [imageView] returns. */
    val state = pingPong()

    /** Colour carried from tree to tree by the fire. Draw into it with [addInk]. */
    val ink = pingPong()

    /** The pass that carries [fireInput] forward a frame and fades it. */
    val fireInputPreserveModule: ModulePointer

    /** The spreading rule. The box adopts this one. */
    val stateUpdateModule: ModulePointer

    /** The pass that carries [ink] forward a frame and fades it. */
    val inkPreserveModule: ModulePointer

    /** The pass that hands ink from burning trees to their burning neighbours. Always runs last on [ink]. */
    val inkPropagateModule: ModulePointer

    private val fireInputSampler = TextureSampler(renderer, 1, false)
    private val stateSampler = TextureSampler(renderer, 1, false)
    private val inkSampler = TextureSampler(renderer, 1, false)
    // the first frame starts from nothing, whatever the targets were cleared to
    private var clearFireInputRequested = true
    private var clearStateRequested = true
    private var clearInkRequested = true
    private var frame = 0L

    init {
        require(settings.width > 0) { "width must be positive" }
        require(settings.height > 0) { "height must be positive" }
        require(settings.treeScalePx > 0f) { "treeScalePx must be positive" }

        fireInputPreserveModule = fullscreenPass(fireInput, "fire-input-preserve", PRESERVE_FRAGMENT, blend = false) { tex ->
            if (tex.name == "sourceTex") fromViews(listOf(fireInput.otherImageView), tex.name, fireInputSampler) else null
        }
        fireInputPreserveModule.quad {
            push.bind("decay") { settings.ignitionDecayPerFrame.toVec4() }
            push.bind("clearRequested") { if (clearFireInputRequested) 1f else 0f }
        }
        fireInput += "preserve" to fireInputPreserveModule

        // the state reads its own previous frame and the ignition this frame has just drawn
        stateUpdateModule = fullscreenPass(state, "state-update", STATE_UPDATE_FRAGMENT, blend = false) { tex ->
            when (tex.name) {
                "sourceTex" -> fromViews(listOf(state.otherImageView), tex.name, stateSampler)
                "ignitionTex" -> fromViews(listOf(fireInput.latestImageView), tex.name, fireInputSampler)
                else -> null
            }
        }
        stateUpdateModule.quad {
            push.bind("refractoryDecay") { settings.refractoryDecayPerFrame.toVec4() }
            push.bind("spreadStepPx") { settings.spreadStepPx }
            push.bind("spreadGain") { settings.spreadGain }
            push.bind("paintGain") { settings.paintGain }
            push.bind("ignitionThreshold") { settings.ignitionThreshold }
            push.bind("activeFireThreshold") { settings.activeFireThreshold }
            push.bind("readyThreshold") { settings.readyThreshold }
            push.bind("treeDensity") { settings.treeDensity }
            push.bind("treeScalePx") { settings.treeScalePx }
            push.bind("treeJitter") { settings.treeJitter }
            push.bind("seed") { settings.randomSeed.toInt() }
            push.bind("clearRequested") { if (clearStateRequested) 1f else 0f }
            push.bind("spreadProbability") { settings.spreadProbability }
            push.bind("frame") { frame.toInt() }
        }
        state += "update" to stateUpdateModule

        inkPreserveModule = fullscreenPass(ink, "ink-preserve", PRESERVE_FRAGMENT, blend = false) { tex ->
            if (tex.name == "sourceTex") fromViews(listOf(ink.otherImageView), tex.name, inkSampler) else null
        }
        inkPreserveModule.quad {
            push.bind("decay") { settings.inkDecayPerFrame.toVec4() }
            push.bind("clearRequested") { if (clearInkRequested) 1f else 0f }
        }
        ink += "preserve" to inkPreserveModule

        inkPropagateModule = fullscreenPass(ink, "ink-propagate", INK_PROPAGATE_FRAGMENT, blend = true) { tex ->
            when (tex.name) {
                "sourceTex" -> fromViews(listOf(ink.otherImageView), tex.name, inkSampler)
                "stateTex" -> fromViews(listOf(state.latestImageView), tex.name, stateSampler)
                else -> null
            }
        }
        inkPropagateModule.quad {
            push.bind("decay") { settings.inkDecayPerFrame.toVec4() }
            push.bind("activeFireThreshold") { settings.activeFireThreshold }
            push.bind("spreadStepPx") { settings.spreadStepPx }
            push.bind("inkSpreadGain") { settings.inkSpreadGain }
        }
        ink += "propagate" to inkPropagateModule

        Box.context.get()?.attachShader(stateUpdateModule)
    }

    /** The fire levels, so a system can be sampled by another module the way a loaded image would be. */
    override val imageView: ImageView
        get() = state.imageView

    /** The same texture, in the list form the array-sampling path asks for. */
    override val imageViewArray: List<HasImageView>
        get() = state.imageViewArray

    /** Runs ignition, the spreading rule and the ink, in that order. Called by whatever the system is registered with; see [installInto]. */
    override fun render(command: CommandBuffer, index: Int) {
        frame++
        fireInput.render(command, index)
        state.render(command, index)
        ink.render(command, index)
        clearFireInputRequested = false
        clearStateRequested = false
        clearInkRequested = false
    }

    /** Erase all drawn ignition on the next frame. */
    fun clearFireInput() {
        clearFireInputRequested = true
    }

    /** Put every tree out and make it ready on the next frame. */
    fun clearState() {
        clearStateRequested = true
    }

    /** Erase the ink on the next frame. */
    fun clearInk() {
        clearInkRequested = true
    }

    /** [clearFireInput], [clearState] and [clearInk] together. */
    fun reset() {
        clearFireInputRequested = true
        clearStateRequested = true
        clearInkRequested = true
    }

    /** Add a module that draws into [fireInput]; it runs after the carry-forward pass, every frame. */
    fun addFireInput(name: String, module: ModulePointer) {
        fireInput += name to module
    }

    /** [addFireInput] for a module that has not been given a pointer yet. */
    fun addFireInput(name: String, module: Module) {
        addFireInput(name, module.pointer())
    }

    /** Add a module that draws into [ink]; it runs before the propagate pass, so the fire carries what it draws. */
    fun addInk(name: String, module: ModulePointer) {
        ink += name to module
        val propagate = ink.modules.remove("propagate")
        if (propagate != null) ink.modules["propagate"] = propagate
    }

    /** [addInk] for a module that has not been given a pointer yet. */
    fun addInk(name: String, module: Module) {
        addInk(name, module.pointer())
    }

    /**
     * Parameters for a module that draws into [fireInput]: additive blending, so writing
     * `vec4(1, 0, 0, 0)` adds red fire and a negative value erases it (the target is floating point),
     * and the previous ignition available as `sourceTex`. Hand the result to `moduleHere(...)` in a
     * box, then to [addFireInput].
     */
    fun fireInputParameters(
        name: String,
        configure: Parameters.() -> Unit = {}
    ): Parameters = additiveParameters(fireInput, name, configure) { tex ->
        if (tex.name == "sourceTex") fromViews(listOf(fireInput.otherImageView), tex.name, fireInputSampler) else null
    }

    /** Parameters for a module that draws into [ink], additive, with the previous ink as `sourceTex`. */
    fun inkParameters(
        name: String,
        configure: Parameters.() -> Unit = {}
    ): Parameters = additiveParameters(ink, name, configure) { tex ->
        if (tex.name == "sourceTex") fromViews(listOf(ink.otherImageView), tex.name, inkSampler) else null
    }

    /**
     * Wire the simulation into a render target as a pre-target and, by default, show it with
     * [blitToScreen]. Returns the preview module, or `null` with `withPreview = false`.
     */
    fun installInto(
        renderTarget: RenderTarget = renderer.renderList,
        withPreview: Boolean = true,
        previewName: String = "${settings.name}-preview",
        previewViewport: Rect? = null
    ): ModulePointer? {
        renderTarget.preTargets["${settings.name}-simulation"] = this
        return if (withPreview) blitToScreen(target = renderTarget, name = previewName, viewport = previewViewport) else null
    }

    /**
     * Draw the forest to a target: each kind of fire in its colour, bright while burning and dim while
     * recovering, tinted by the ink. `viewport` is where it lands.
     */
    fun blitToScreen(
        target: RenderTarget = renderer.renderList,
        name: String = "${settings.name}-preview",
        viewport: Rect? = null
    ): ModulePointer {
        val module = Module(
            parameters(renderer) {
                this.name = name
                depthTest = false
                blend = false
                this.viewport = viewport
                vertex = PREVIEW_VERTEX
                fragment = PREVIEW_FRAGMENT
                textureResolver = { tex ->
                    when (tex.name) {
                        "stateTex" -> fromViews(listOf(state.latestImageView), tex.name, stateSampler)
                        "inkTex" -> fromViews(listOf(ink.latestImageView), tex.name, inkSampler)
                        else -> null
                    }
                }
            }
        ).pointer()
        module.quad(name) {
            push.bind("activeFireThreshold") { settings.activeFireThreshold }
            push.bind("readyThreshold") { settings.readyThreshold }
        }
        target += name to module
        return module
    }

    /** Draw the raw ignition texture. */
    fun blitFireInputToScreen(
        target: RenderTarget = renderer.renderList,
        name: String = "${settings.name}-fire-input-preview",
        viewport: Rect? = null,
        sourceRect: Rect? = null
    ): ModulePointer = fireInput.debugBlitToScreen(renderer = renderer, target = target, name = name, viewport = viewport, sourceRect = sourceRect)

    /** Draw the raw ink texture. */
    fun blitInkToScreen(
        target: RenderTarget = renderer.renderList,
        name: String = "${settings.name}-ink-preview",
        viewport: Rect? = null,
        sourceRect: Rect? = null
    ): ModulePointer = ink.debugBlitToScreen(renderer = renderer, target = target, name = name, viewport = viewport, sourceRect = sourceRect)

    // A plain `blitToScreen` on one of the ping-pongs would register it as a second pre-target, so it
    // would advance twice a frame outside render(); `debugBlitToScreen` draws the latest frame without
    // registering anything.

    private fun pingPong() = PingPongRenderToTexture(renderer) {
        width = settings.width
        height = settings.height
        format = settings.format
        colorFormats = listOf(settings.format)
        clearColor = settings.clearColor
        clearFirst = true
        colorStoreOp = VK_ATTACHMENT_STORE_OP_STORE
        colorSampled = true
        useDepth = false
    }

    private fun Parameters.additive() {
        blend = true
        srcColor = VK_BLEND_FACTOR_ONE
        dstColor = VK_BLEND_FACTOR_ONE
        colorBlendOp = VK_BLEND_OP_ADD
        srcAlpha = VK_BLEND_FACTOR_ONE
        dstAlpha = VK_BLEND_FACTOR_ONE
        alphaBlendOp = VK_BLEND_OP_ADD
    }

    private fun fullscreenPass(
        target: PingPongRenderToTexture,
        suffix: String,
        fragmentSource: String,
        blend: Boolean,
        resolver: (Introspected.Texture) -> Pair<HasImageViewArray, TextureSampler>?
    ): ModulePointer = Module(
        parameters(renderer) {
            target.apply()
            name = "${settings.name}-$suffix"
            depthTest = false
            if (blend) additive() else this.blend = false
            vertex = FULLSCREEN_TRIANGLE_VERTEX
            fragment = fragmentSource
            textureResolver = resolver
        }
    ).pointer()

    private fun additiveParameters(
        target: PingPongRenderToTexture,
        name: String,
        configure: Parameters.() -> Unit,
        resolver: (Introspected.Texture) -> Pair<HasImageViewArray, TextureSampler>?
    ): Parameters = parameters(renderer) {
        target.apply()
        this.name = name
        depthTest = false
        additive()
        textureResolver = resolver
        configure()
    }

    private fun ModulePointer.quad(name: String = current.parameters.name, bind: Mesh.() -> Unit) {
        current.meshes["$name-quad"] = fullscreenTriangle(this).apply(bind)
    }

    companion object {
        private val FULLSCREEN_TRIANGLE_VERTEX = """
            #version 450

            vec2 fullscreenVertex(uint index) {
                if (index == 0u) return vec2(-1.0, -1.0);
                if (index == 1u) return vec2(3.0, -1.0);
                return vec2(-1.0, 3.0);
            }

            void main() {
                gl_Position = vec4(fullscreenVertex(uint(gl_VertexIndex)), 0.0, 1.0);
            }
        """.trimIndent()

        private val PRESERVE_FRAGMENT = """
            #version 450

            layout(set = 0, binding = 0) uniform sampler2D sourceTex;

            layout(push_constant) uniform Push {
                vec4 decay;
                float clearRequested;
            } push;

            layout(location = 0) out vec4 outColor;

            void main() {
                ivec2 dims = textureSize(sourceTex, 0);
                vec4 previous = texelFetch(sourceTex, clamp(ivec2(gl_FragCoord.xy), ivec2(0), dims - 1), 0);
                outColor = push.clearRequested > 0.5 ? vec4(0.0) : clamp(previous - push.decay, vec4(0.0), vec4(1.0));
            }
        """.trimIndent()

        private val INK_PROPAGATE_FRAGMENT = """
            #version 450

            layout(set = 0, binding = 0) uniform sampler2D sourceTex;
            layout(set = 0, binding = 1) uniform sampler2D stateTex;

            layout(push_constant) uniform Push {
                vec4 decay;
                float activeFireThreshold;
                float spreadStepPx;
                float inkSpreadGain;
            } push;

            layout(location = 0) out vec4 outColor;

            float max4(vec4 v) {
                return max(max(v.x, v.y), max(v.z, v.w));
            }

            float burning(ivec2 p) {
                float denom = max(1.0 - push.activeFireThreshold, 0.0001);
                return clamp((max4(texelFetch(stateTex, p, 0)) - push.activeFireThreshold) / denom, 0.0, 1.0);
            }

            vec4 inkFrom(ivec2 pixel, ivec2 dims, float weight) {
                ivec2 p = clamp(pixel, ivec2(0), dims - 1);
                return texelFetch(sourceTex, p, 0) * (burning(p) * weight);
            }

            // added to the preserve pass's output, so the result is the larger of the faded ink here
            // and the strongest ink handed on by a burning neighbour
            void main() {
                ivec2 dims = textureSize(sourceTex, 0);
                ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), dims - 1);
                if (burning(pixel) <= 0.0) {
                    outColor = vec4(0.0);
                    return;
                }

                vec4 previous = texelFetch(sourceTex, pixel, 0);
                vec4 decayed = max(previous - push.decay, vec4(0.0));

                int s = max(int(round(push.spreadStepPx)), 1);
                float axial = push.inkSpreadGain;
                float diagonal = push.inkSpreadGain * 0.7071;
                vec4 carried = previous;
                carried = max(carried, inkFrom(pixel + ivec2(s, 0), dims, axial));
                carried = max(carried, inkFrom(pixel + ivec2(-s, 0), dims, axial));
                carried = max(carried, inkFrom(pixel + ivec2(0, s), dims, axial));
                carried = max(carried, inkFrom(pixel + ivec2(0, -s), dims, axial));
                carried = max(carried, inkFrom(pixel + ivec2(s, s), dims, diagonal));
                carried = max(carried, inkFrom(pixel + ivec2(-s, s), dims, diagonal));
                carried = max(carried, inkFrom(pixel + ivec2(s, -s), dims, diagonal));
                carried = max(carried, inkFrom(pixel + ivec2(-s, -s), dims, diagonal));

                outColor = max(carried - decayed, vec4(0.0));
            }
        """.trimIndent()

        // The preview stretches the whole texture over its viewport, so it samples by uv, not by pixel.
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

            layout(set = 0, binding = 0) uniform sampler2D stateTex;
            layout(set = 0, binding = 1) uniform sampler2D inkTex;

            layout(push_constant) uniform Push {
                float activeFireThreshold;
                float readyThreshold;
            } push;

            layout(location = 0) in vec2 uv;
            layout(location = 0) out vec4 outColor;

            float max4(vec4 v) {
                return max(max(v.x, v.y), max(v.z, v.w));
            }

            vec3 fireKindColor(vec4 s) {
                return s.r * vec3(1.0, 0.18, 0.05) + s.g * vec3(0.15, 1.0, 0.18)
                    + s.b * vec3(0.15, 0.45, 1.0) + s.a * vec3(1.0, 0.2, 0.95);
            }

            void main() {
                ivec2 dims = textureSize(stateTex, 0);
                ivec2 pixel = clamp(ivec2(uv * vec2(dims)), ivec2(0), dims - 1);
                vec4 state = texelFetch(stateTex, pixel, 0);
                vec4 ink = texelFetch(inkTex, pixel, 0);

                float level = max4(state);
                float burning = clamp((level - push.activeFireThreshold) / max(1.0 - push.activeFireThreshold, 0.0001), 0.0, 1.0);
                float recovering = clamp((level - push.readyThreshold) / max(push.activeFireThreshold - push.readyThreshold, 0.0001), 0.0, 1.0) * (1.0 - burning);

                vec3 base = fireKindColor(state);
                vec3 inkColor = clamp(ink.rgb + ink.a * vec3(1.0, 0.15, 0.95), vec3(0.0), vec3(1.0));
                vec3 fire = mix(base, inkColor, clamp(max4(ink), 0.0, 1.0));

                vec3 color = fire * (0.12 * recovering + burning) + base * 0.03 * recovering;
                outColor = vec4(color, max(burning, recovering));
            }
        """.trimIndent()

        /** The spreading rule as GLSL, the source the box's `fragment` tab is seeded with. */
        val STATE_UPDATE_FRAGMENT = """
            #version 450

            layout(set = 0, binding = 0) uniform sampler2D sourceTex;
            layout(set = 0, binding = 1) uniform sampler2D ignitionTex;

            layout(push_constant) uniform Push {
                vec4 refractoryDecay;
                float spreadStepPx;
                float spreadGain;
                float paintGain;
                float ignitionThreshold;
                float activeFireThreshold;
                float readyThreshold;
                float treeDensity;
                float treeScalePx;
                float treeJitter;
                int seed;
                float clearRequested;
                float spreadProbability;
                int frame;
            } push;

            layout(location = 0) out vec4 outColor;

            uint hash(uint x) {
                x ^= x >> 16u; x *= 0x7feb352du;
                x ^= x >> 15u; x *= 0x846ca68bu;
                x ^= x >> 16u;
                return x;
            }

            float random01(uvec2 p, uint salt) {
                return float(hash(p.x ^ hash(p.y ^ hash(uint(push.seed) ^ salt)))) / 4294967295.0;
            }

            // clumps on a coarse grid, roughened per texel by treeJitter
            bool isTree(ivec2 pixel) {
                uvec2 coarse = uvec2(floor(vec2(pixel) / max(push.treeScalePx, 1.0)));
                float mixed = mix(random01(coarse, 1u), random01(uvec2(pixel), 2u), clamp(push.treeJitter, 0.0, 1.0));
                return mixed <= push.treeDensity;
            }

            float max4(vec4 v) {
                return max(max(v.x, v.y), max(v.z, v.w));
            }

            // how strongly each kind is burning, 0 at activeFireThreshold, 1 at full
            vec4 burning(ivec2 p, ivec2 dims) {
                vec4 s = texelFetch(sourceTex, clamp(p, ivec2(0), dims - 1), 0);
                return clamp((s - vec4(push.activeFireThreshold)) / max(1.0 - push.activeFireThreshold, 0.0001), vec4(0.0), vec4(1.0));
            }

            // a burning neighbour's fire, if this frame's draw lets it across to this tree
            vec4 reaching(ivec2 pixel, ivec2 offset, ivec2 dims, uint salt) {
                uvec2 u = uvec2(pixel);
                float draw = random01(u, hash(uint(push.frame) * 8u + salt) | 4u);
                return draw < push.spreadProbability ? burning(pixel + offset, dims) : vec4(0.0);
            }

            // the kind with the strongest signal at or above the threshold, among those allowed; -1 for none
            int strongest(vec4 signal, bvec4 allowed) {
                int winner = -1;
                float best = -1.0;
                for (int k = 0; k < 4; ++k) {
                    if (allowed[k] && signal[k] >= push.ignitionThreshold && signal[k] > best) {
                        best = signal[k];
                        winner = k;
                    }
                }
                return winner;
            }

            vec4 caught(int kind) {
                vec4 v = vec4(0.0);
                v[kind] = 1.0;
                return v;
            }

            void main() {
                ivec2 dims = textureSize(sourceTex, 0);
                ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), dims - 1);

                if (push.clearRequested > 0.5 || !isTree(pixel)) {
                    outColor = vec4(0.0);
                    return;
                }

                vec4 previous = texelFetch(sourceTex, pixel, 0);
                vec4 decayed = max(previous - push.refractoryDecay, vec4(0.0));

                // drawn ignition takes any tree, burning or not
                int lit = strongest(texelFetch(ignitionTex, pixel, 0) * push.paintGain, bvec4(true));
                if (lit >= 0) {
                    outColor = caught(lit);
                    return;
                }

                if (max4(previous) >= push.activeFireThreshold) {
                    outColor = decayed;
                    return;
                }

                // each of the eight neighbours gets its own draw against spreadProbability
                int s = max(int(round(push.spreadStepPx)), 1);
                vec4 neighbours = max(max(max(reaching(pixel, ivec2(s, 0), dims, 0u), reaching(pixel, ivec2(-s, 0), dims, 1u)),
                                          max(reaching(pixel, ivec2(0, s), dims, 2u), reaching(pixel, ivec2(0, -s), dims, 3u))),
                                      0.7071 * max(max(reaching(pixel, ivec2(s, s), dims, 4u), reaching(pixel, ivec2(-s, s), dims, 5u)),
                                                   max(reaching(pixel, ivec2(s, -s), dims, 6u), reaching(pixel, ivec2(-s, -s), dims, 7u))));

                // a neighbour's fire takes a tree only if that kind has recovered there
                int spread = strongest(neighbours * push.spreadGain, lessThanEqual(decayed, vec4(push.readyThreshold)));
                outColor = spread >= 0 ? caught(spread) : decayed;
            }
        """.trimIndent()
    }
}
