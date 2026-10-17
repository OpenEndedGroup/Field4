@file:afield.runtime.completion.CompletionCategory("ALife")
@file:Suppress("MemberVisibilityCanBePrivate")

package afield.simulation.agents

import afield.data.collections.invoke
import afield.data.collections.r
import afield.data.collections.s
import afield.data.collections.times
import afield.data.graph.main
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.document.workspace.path
import afield.graphics.render.rangeTo
import afield.graphics.shader.fieldProbeHere
import afield.graphics.shader.mesh
import afield.graphics.text.layout
import afield.io.osc.main
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.control.main
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.linalg.x
import afield.math.linalg.xy
import afield.math.statistics.main
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.runtime.execution.cell
import afield.runtime.lifecycle.once
import afield.simulation.automata.main

import afield.audio.synth.*
import afield.data.collections.get
import afield.data.graph.fragment
import afield.graphics.shader.attachShader
import afield.graphics.shader.fragment
import afield.graphics.shader.vertex
import afield.lang.stacksl.evolution.render
import afield.math.linalg.vec
import afield.media.image.resize
import afield.simulation.automata.step
import afield.document.graph.Box
import afield.app.protocol.Rect
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.render.Mesh
import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.document.context.fromContext
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.vulkan.targets.PingPongRenderToTexture
import afield.graphics.vulkan.targets.RenderTarget
import afield.graphics.texture.HasImageView
import afield.graphics.texture.HasImageViewArray
import afield.graphics.texture.ImageView
import afield.graphics.texture.TextureSampler
import org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM

/**
 * The Biham–Middleton–Levine traffic model on the GPU: a torus of cells, each empty or holding one
 * car, advanced by a single full-screen fragment pass into a ping-pong texture.
 *
 * #### The rule
 *
 * There are two kinds of car. Eastbound cars (red) only ever move one cell to the right, southbound
 * cars (blue) only ever move one cell down, and the two take turns: on even steps every eastbound
 * car whose right-hand cell is empty moves into it, all at once; on odd steps the southbound cars do
 * the same downwards. The grid wraps in both directions. Nothing else happens, and no car is ever
 * created or destroyed.
 *
 * What makes the model famous is that its only parameter, the fraction of cells holding a car, has
 * a sharp transition: at low density the cars sort themselves into diagonal bands that flow freely,
 * at high density a single jam spanning the grid forms and nothing moves. On a 512 × 512 torus, 0.20
 * reaches a moving fraction of 0.998 and 0.45 reaches 0 by step 1750, while 0.32 is near the
 * transition and decays slowly from flow toward a jam.
 *
 * #### One texel is one cell
 *
 * [ShaderBmlSystem] holds the state in an RGBA8 ping-pong target: red at 1 is an eastbound car, blue
 * at 1 a southbound one, black an empty cell. Each frame the pass reads the previous frame's texture
 * and writes this one, so one frame is one half-step, and a full step (both colours) takes two
 * frames. An 8-bit target is enough because the values are only ever 0 or 1.
 *
 * The update is a pure gather. A cell becomes empty if it holds a car of the moving colour and the
 * cell ahead is empty, becomes that car if it is empty and the cell behind holds one, and otherwise
 * keeps what it had. Each cell decides from three texels of the previous frame, so the whole grid
 * advances in parallel with no conflicts and conserves the car count exactly.
 *
 * The first frame after construction or [ShaderBmlSystem.reset] ignores the previous texture and
 * scatters cars instead, each cell independently holding a car with probability
 * [ShaderBmlSettings.density], eastbound with probability [ShaderBmlSettings.eastboundFraction].
 *
 * #### Getting one on screen
 *
 * `installInto` registers the simulation as a pre-target of the render list and blits it to the
 * window. As with [ShaderDlaSystem], constructing it inside a box seeds that box's `fragment` tab with
 * the update rule, so the rule arrives as source you can edit. The CPU-side alife family does not
 * have this model; nothing here reads the grid back.
 */

/**
 * Everything [ShaderBmlSystem] reads. Most are bound as lambdas, so changing one while it runs takes
 * effect on the next frame; [density], [eastboundFraction] and [randomSeed] are only read by the
 * scatter, so they take effect at the next [ShaderBmlSystem.reset].
 */
data class ShaderBmlSettings(
    /** Prefix for the module, mesh and pre-target names. Two systems in one renderer need two names. */
    var name: String = "bml",
    /** Width of the torus in cells, which is also the texture width in pixels. */
    var width: Int = 512,
    /** Height of the torus in cells. Must be positive, as must [width]. */
    var height: Int = 512,
    /**
     * The fraction of cells that hold a car. On a 512 × 512 torus 0.20 flows freely, 0.45 locks, and
     * the default sits near the transition between them.
     */
    var density: Float = 0.32f,
    /** The fraction of cars that are eastbound (red); the rest are southbound (blue). */
    var eastboundFraction: Float = 0.5f,
    /** `false` holds the grid as it is; `true` advances it one half-step per frame. */
    var running: Boolean = true,
    /** Seed for the scatter; [ShaderBmlSystem.reset] can override it. */
    var randomSeed: Int = 1,
    /**
     * A GLSL expression to hand to `fieldProbe(...)` once per moving car per half-step, for example
     * `"float(p.x + p.y)"`; `p` is the cell the car is leaving. `null` (the default) leaves the rule
     * uninstrumented. Setting it adds the include and the call to the rule and writes the result into
     * the box's `fragment` tab, replacing what was there; turn the probe on with `fieldProbeHere()`.
     * Read once, in the constructor.
     */
    var probe: String? = null
)

/**
 * A shader-driven BML traffic model: one fragment pass into a ping-pong target, half a step per
 * frame. Build one, call [installInto], and it runs. The file overview above describes the rule and
 * the encoding.
 */
class ShaderBmlSystem(
    /** The renderer to build the target and module on; by default the one this box is running in. */
    val renderer: Renderer = fromContext(),
    configure: ShaderBmlSettings.() -> Unit = {}
) : Target, HasImageView, HasImageViewArray {

    /** The settings this system was built with, after `configure` ran, and the handle for changing it live. */
    val settings = ShaderBmlSettings().apply(configure)

    /**
     * How many half-steps have been recorded since the last [reset]. Even values move the eastbound
     * cars, odd values the southbound ones, so `step / 2` is the model's time.
     */
    var step = 0L
        private set

    private var scatterPending = true
    private var scatterSeed = settings.randomSeed
    private var currentMode = 3

    /** The ping-pong pair holding the grid. */
    val simulation = PingPongRenderToTexture(renderer) {
        width = settings.width
        height = settings.height
        format = VK_FORMAT_R8G8B8A8_UNORM
        colorFormats = listOf(VK_FORMAT_R8G8B8A8_UNORM)
        clearColor = vec(0, 0, 0, 1)
        clearFirst = true
        colorStoreOp = VK_ATTACHMENT_STORE_OP_STORE
        colorSampled = true
        useDepth = false
    }

    /** The full-screen pass that applies the rule. This is the module a surrounding box adopts. */
    val stepModule: ModulePointer

    private val sourceSampler = TextureSampler(renderer, 1, false)

    private val stepFragmentSource: String = settings.probe?.let { expression ->
        STEP_FRAGMENT
            .replace(OUTPUT_DECLARATION, "{% property \"fieldProbe\" %}\n\n$OUTPUT_DECLARATION")
            .replace(MOVE_MARKER, "fieldProbe($expression);\n        $MOVE_MARKER")
    } ?: STEP_FRAGMENT

    init {
        require(settings.width > 0) { "width must be positive" }
        require(settings.height > 0) { "height must be positive" }

        stepModule = Module(
            parameters(renderer) {
                this@ShaderBmlSystem.simulation.apply()
                name = "${settings.name}-step"
                depthTest = false
                blend = false
                vertex = FULLSCREEN_TRIANGLE_VERTEX
                fragment = stepFragmentSource
                textureResolver = { tex ->
                    if (tex.name == "sourceTex") {
                        object : HasImageViewArray {
                            override val imageViewArray: List<HasImageView>
                                get() = listOf(simulation.otherImageView)
                        } to sourceSampler
                    } else {
                        null
                    }
                }
            }
        ).pointer()
        Mesh(renderer, stepModule).apply {
            resize(3, 0, stepModule)
            // 0 = east half-step, 1 = south half-step, 2 = hold, 3 = scatter
            push.bind("mode") { currentMode }
            push.bind("density") { settings.density }
            push.bind("eastboundFraction") { settings.eastboundFraction }
            push.bind("seed") { scatterSeed }
            stepModule.current.meshes["${settings.name}-step-quad"] = this
        }
        simulation += "step" to stepModule

        Box.context.get()?.attachShader(stepModule, overwriteProperties = settings.probe != null)
    }

    /** The grid as a texture: red is an eastbound car, blue a southbound one. */
    override val imageView: ImageView
        get() = simulation.imageView

    /** The same single colour attachment, in the list form the array-sampling path asks for. */
    override val imageViewArray: List<HasImageView>
        get() = simulation.imageViewArray

    /** Runs one half-step. Called by whatever the system is registered with; see [installInto]. */
    override fun render(command: CommandBuffer, index: Int) {
        currentMode = when {
            scatterPending -> 3
            !settings.running -> 2
            else -> (step and 1L).toInt()
        }
        simulation.render(command, index)
        when (currentMode) {
            3 -> { scatterPending = false; step = 0 }
            2 -> {}
            else -> step++
        }
    }

    /**
     * Scatter a fresh grid on the next frame from [ShaderBmlSettings.density] and
     * [ShaderBmlSettings.eastboundFraction], and restart [step] from 0. Passing no seed reuses
     * [ShaderBmlSettings.randomSeed].
     */
    fun reset(seed: Int = settings.randomSeed) {
        scatterSeed = seed
        scatterPending = true
    }

    /**
     * Wire the simulation into a render target as a pre-target and, by default, blit it to the
     * window. Returns the preview module, or `null` with `withPreview = false`.
     */
    fun installInto(
        renderTarget: RenderTarget = renderer.renderList,
        withPreview: Boolean = true,
        previewName: String = "${settings.name}-preview",
        previewViewport: Rect? = null
    ): ModulePointer? {
        renderTarget.preTargets["${settings.name}-simulation"] = this
        return if (withPreview) blitToScreen(renderTarget, previewName, previewViewport) else null
    }

    // The ping-pong's own blitToScreen registers the ping-pong as a pre-target of `target`, so it
    // would render a second time each frame, bypassing render() above: the same colour would move
    // twice per frame and the model would no longer be BML. Only this system may render it.
    private fun keepSoleRenderer(target: RenderTarget, sourceKey: String) {
        if (target.preTargets[sourceKey] === simulation) {
            if (target.preTargets.values.any { it === this }) target.preTargets.remove(sourceKey)
            else target.preTargets[sourceKey] = this
        }
    }

    /**
     * Draw the grid to a target without registering the simulation with it. `viewport` is where it
     * lands and `sourceRect` which part of the grid is taken; a small `sourceRect` over a large
     * viewport is how to see individual cars.
     */
    fun blitToScreen(
        target: RenderTarget = renderer.renderList,
        name: String = "${settings.name}-preview",
        viewport: Rect? = null,
        sourceRect: Rect? = null
    ): ModulePointer = simulation.blitToScreen(
        renderer = renderer,
        target = target,
        name = name,
        viewport = viewport,
        sourceRect = sourceRect,
        blend = false,
        additive = false
    ).also { keepSoleRenderer(target, "$name-source") }

    companion object {
        private const val OUTPUT_DECLARATION = "layout(location = 0) out vec4 outColor;"
        private const val MOVE_MARKER = "// a car moves out of this cell this half-step"

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

        /**
         * The update rule as GLSL, the source the box's `fragment` tab is seeded with. Public so box
         * code can derive an instrumented copy of it; the comment inside the `next = 0` branch marks
         * the one place each moving car passes through exactly once per half-step.
         */
        val STEP_FRAGMENT = """
            #version 450

            layout(set = 0, binding = 0) uniform sampler2D sourceTex;

            layout(push_constant) uniform Push {
                int mode;
                float density;
                float eastboundFraction;
                int seed;
            } push;

            layout(location = 0) out vec4 outColor;

            const vec4 EMPTY = vec4(0.0, 0.0, 0.0, 1.0);
            const vec4 EAST = vec4(1.0, 0.0, 0.0, 1.0);
            const vec4 SOUTH = vec4(0.0, 0.0, 1.0, 1.0);

            uint hash(uint x) {
                x ^= x >> 16u; x *= 0x7feb352du;
                x ^= x >> 15u; x *= 0x846ca68bu;
                x ^= x >> 16u;
                return x;
            }

            float random01(uvec2 p, uint salt) {
                return float(hash(p.x ^ hash(p.y ^ hash(uint(push.seed) ^ salt)))) / 4294967295.0;
            }

            // 0 empty, 1 eastbound, 2 southbound
            int cellAt(ivec2 p, ivec2 dims) {
                vec4 c = texelFetch(sourceTex, (p + dims) % dims, 0);
                if (c.r > 0.5) return 1;
                if (c.b > 0.5) return 2;
                return 0;
            }

            vec4 colourOf(int cell) {
                return cell == 1 ? EAST : (cell == 2 ? SOUTH : EMPTY);
            }

            void main() {
                ivec2 dims = textureSize(sourceTex, 0);
                ivec2 p = ivec2(gl_FragCoord.xy);

                if (push.mode == 3) {
                    uvec2 u = uvec2(p);
                    if (random01(u, 1u) < push.density) {
                        outColor = random01(u, 2u) < push.eastboundFraction ? EAST : SOUTH;
                    } else {
                        outColor = EMPTY;
                    }
                    return;
                }

                int here = cellAt(p, dims);
                if (push.mode == 2) {
                    outColor = colourOf(here);
                    return;
                }

                // even half-steps move eastbound cars +x, odd ones move southbound cars +y
                int mover = push.mode == 0 ? 1 : 2;
                ivec2 ahead = push.mode == 0 ? ivec2(1, 0) : ivec2(0, 1);

                int next = here;
                if (here == mover && cellAt(p + ahead, dims) == 0) {
                    next = 0;
                    // a car moves out of this cell this half-step: one invocation per moving car,
                    // which is where to tap it (fieldProbe, fieldStats) without counting it twice
                } else if (here == 0 && cellAt(p - ahead, dims) == mover) {
                    next = mover;
                }

                outColor = colourOf(next);
            }
        """.trimIndent()
    }
}
