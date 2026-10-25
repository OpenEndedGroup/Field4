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
import afield.math.linalg.Vector2f
import afield.math.linalg.vec
import org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
import org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT
import org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT

/**
 * A two-dimensional fluid on the GPU by the lattice Boltzmann method, with a control texture you draw
 * obstacles, forces and dye sources into, and a dye field that the flow carries.
 *
 * #### The method
 *
 * Every texel is a lattice site holding nine numbers, the D2Q9 populations: how much fluid at that
 * site is moving with each of the nine velocities `(0,0)`, the four axis neighbours and the four
 * diagonals. A step is two moves. *Collision* relaxes each site's populations a fraction
 * [ShaderLatticeBoltzmannSettings.relaxation] of the way toward the equilibrium for that site's own
 * density and velocity (the BGK operator); *streaming* then moves each population one site along its
 * direction. A population that would stream out of a solid site is reflected back where it came from
 * instead, which is the no-slip wall. Density and velocity are never stored as such; they are sums
 * over the nine populations. The viscosity follows from the relaxation, `(1/relaxation - 0.5) / 3` in
 * lattice units, so values near 2 are runny and turbulent and values near 1 are syrupy.
 *
 * The method is only accurate while the fluid is slow against the lattice, below about 0.1 sites per
 * step; [ShaderLatticeBoltzmannSettings.maxVelocity] clamps it so that a strong push degrades the
 * physics rather than the numbers.
 *
 * #### What is in each texture
 *
 * [ShaderLatticeBoltzmannSystem.fluid] holds the populations in three RGBA32F attachments, written by
 * one pass: attachment 0 holds directions 0–3, attachment 1 directions 4–7, and attachment 2 holds
 * direction 8 in red, then density, velocity x and velocity y, so attachment 2 is also the readable
 * velocity field. [ShaderLatticeBoltzmannSystem.controls] holds, per texel, red obstacle (solid at or
 * above [ShaderLatticeBoltzmannSettings.solidThreshold]), green and blue a velocity to steer toward,
 * and alpha a dye source. [ShaderLatticeBoltzmannSystem.dye] is colour advected by the velocity and
 * fed by the dye source, by speed and by vorticity.
 *
 * Every frame runs the controls (carried forward, then whatever you have added), then
 * [ShaderLatticeBoltzmannSettings.stepsPerFrame] fluid steps, then the dye.
 *
 * #### Driving it
 *
 * A fresh system is still water: nothing moves until something pushes. There are two ways. Set
 * [ShaderLatticeBoltzmannSettings.baseVelocity] for a wind everywhere, which with
 * [ShaderLatticeBoltzmannSettings.wrapX] makes a wind tunnel; or draw into the controls texture with
 * a module built on [ShaderLatticeBoltzmannSystem.controlsParameters], which blends additively, so a
 * fragment that writes `vec4(1, 0, 0, 0)` marks a texel solid and `vec4(0, 0.2, 0, 1)` pushes it right
 * and releases dye there. The controls texture is carried from frame to frame, so a module that draws
 * every frame accumulates; [ShaderLatticeBoltzmannSettings.obstacleDecayPerFrame] and its siblings let
 * old marks fade.
 *
 * As with [ShaderDlaSystem], constructing it inside a box seeds that box's `fragment` tab with the
 * collision-and-streaming rule, so the physics arrives as source you can edit.
 */

/** What [ShaderLatticeBoltzmannSystem.blitToScreen] draws. */
enum class FluidVisualizationMode {
    /** Dye, with vorticity and any departure from the wind glowing underneath. */
    COMPOSITE,
    /** The dye field alone. */
    DYE,
    /** Velocity as colour: hue is direction, brightness speed. */
    VELOCITY,
    /** Speed on a heat ramp. */
    SPEED,
    /** Density against [ShaderLatticeBoltzmannSettings.baseDensity]: compression is light, rarefaction dark. */
    DENSITY,
    /** Vorticity: orange turns one way, blue the other. */
    CURL,
    /** The control texture: steering as colour, dye sources as violet. */
    CONTROLS
}

/**
 * Everything [ShaderLatticeBoltzmannSystem] reads. All but the sizes and formats are bound as
 * lambdas, so changing one while it runs takes effect on the next frame.
 */
data class ShaderLatticeBoltzmannSettings(
    /** Prefix for module, mesh and pre-target names. Two systems in one renderer need two names. */
    var name: String = "lbm",
    /** Lattice width in sites, which is also the texture width in pixels. */
    var width: Int = 384,
    /** Lattice height in sites. */
    var height: Int = 384,
    /** Format of the controls and dye textures. */
    var format: Int = VK_FORMAT_R16G16B16A16_SFLOAT,
    /**
     * Format of the three population attachments. 32-bit, because the populations are small numbers
     * whose differences carry the velocity, and half precision rounds slow flows away.
     */
    var populationFormat: Int = VK_FORMAT_R32G32B32A32_SFLOAT,
    /** Clear colour of every texture on the first frame. */
    var clearColor: Vec4 = vec(0, 0, 0, 1),
    /**
     * Lattice steps per frame. The fluid moves at most a fraction of a site per step, so at one step
     * a frame a flow takes thousands of frames to cross the lattice; this is the speed of the film,
     * not of the physics. The dye is advected once per frame, the whole distance.
     */
    var stepsPerFrame: Int = 4,
    /**
     * The BGK relaxation rate ω, in (0, 2): how far each collision moves the populations toward
     * equilibrium. Viscosity is `(1/ω - 0.5) / 3`, so larger is thinner and less stable.
     */
    var relaxation: Float = 1.32f,
    /** The density the fluid starts at and that solid sites are held at. */
    var baseDensity: Float = 1f,
    /** A wind applied everywhere, in sites per step; the fluid starts moving at this velocity. */
    var baseVelocity: Vector2f = Vector2f(0.0f, 0.0f),
    /**
     * Random velocity, in sites per step, added to every site when the fluid starts or is cleared.
     * A symmetric setup computed on a GPU stays exactly symmetric, so without it a wake that ought to
     * shed vortices can sit still for ever; the default is small enough not to show.
     */
    var initialNoise: Float = 0.002f,
    /**
     * How far each site's velocity is pulled toward [baseVelocity] each step, 0 to 1. This is what
     * keeps the wind blowing against the drag of obstacles, and also what erases a wake: a
     * disturbance fades over about `1 / driveBlend` steps, so small values let wakes travel far.
     */
    var driveBlend: Float = 0.002f,
    /**
     * Scale from the controls texture's green and blue to a velocity added each step. A push held
     * in place keeps accelerating the fluid there until [maxVelocity] stops it.
     */
    var controlVelocityScale: Float = 0.35f,
    /** The speed no site may exceed, in sites per step. Above about 0.3 the method becomes unstable. */
    var maxVelocity: Float = 0.28f,
    /** Whether the lattice wraps left to right; otherwise the edges clamp. */
    var wrapX: Boolean = true,
    /** Whether the lattice wraps top to bottom. */
    var wrapY: Boolean = true,
    /** A solid frame this many pixels thick around the edge; 0 for none. */
    var borderSolidPx: Float = 0f,
    /** The controls' red value at or above which a site is solid. */
    var solidThreshold: Float = 0.5f,
    /** How much each obstacle mark fades per frame. */
    var obstacleDecayPerFrame: Float = 0f,
    /** How much each steering mark fades per frame, toward zero from either side. */
    var directionDecayPerFrame: Float = 0f,
    /** How much each dye-source mark fades per frame. */
    var dyeSourceDecayPerFrame: Float = 0f,
    /** The largest steering value the controls texture keeps, either sign, so accumulated pushes saturate. */
    var controlDirectionLimit: Float = 1f,
    /** The fraction of the dye that fades each frame. */
    var dyeDecayPerFrame: Float = 0.012f,
    /** How far, in multiples of the velocity times [stepsPerFrame], the dye is carried each frame. */
    var dyeAdvectionScale: Float = 1f,
    /** Dye released per frame by a full dye-source mark. */
    var controlDyeGain: Float = 1.15f,
    /** Dye released by speed above the wind. */
    var speedDyeGain: Float = 0.02f,
    /** Dye released by vorticity, which is what makes vortices draw themselves. */
    var curlDyeGain: Float = 0.14f,
    /** The brightest the dye may get. */
    var dyeLimit: Float = 1.5f,
    /** Gain from speed to brightness in the speed and velocity views. */
    var speedVisualizationGain: Float = 6f,
    /** Gain from vorticity to saturation in the curl view. */
    var curlVisualizationGain: Float = 42f,
    /** Gain from density deviation to brightness in the density view. */
    var densityVisualizationGain: Float = 4f,
    /** Exposure of the dye in the composite view. */
    var compositeDyeExposure: Float = 0.55f,
    /** Weight of the velocity colour in the composite view. */
    var compositeVelocityGain: Float = 0.26f,
    /** Weight of the vorticity colour in the composite view. */
    var compositeCurlGain: Float = 0.42f,
    /** What [ShaderLatticeBoltzmannSystem.blitToScreen] shows when it is not given a mode. */
    var previewMode: FluidVisualizationMode = FluidVisualizationMode.COMPOSITE
)

/**
 * A D2Q9 lattice Boltzmann fluid: controls, one fluid step and dye advection, each a full-screen
 * pass into its own ping-pong target. Build one, call [installInto], then push it, with
 * [ShaderLatticeBoltzmannSettings.baseVelocity] or a module from [controlsParameters]. The file
 * overview above describes the method and what each texture holds.
 */
class ShaderLatticeBoltzmannSystem(
    /** The renderer to build the targets and modules on; by default the one this box is running in. */
    val renderer: Renderer = fromContext(),
    configure: ShaderLatticeBoltzmannSettings.() -> Unit = {}
) : Target, HasImageView, HasImageViewArray {

    /** The settings this system was built with, after `configure` ran, and the handle for changing it live. */
    val settings = ShaderLatticeBoltzmannSettings().apply(configure)

    /**
     * The control field: red obstacle, green and blue steering velocity, alpha dye source. Draw into
     * it with modules from [controlsParameters], registered with [addControls].
     */
    val controls = pingPong(settings.format)

    /**
     * The populations, as three attachments of one ping-pong target; attachment 2 is
     * `(f8, density, velocity.x, velocity.y)`, so `fluid.latestImageViewFor(2)` is the velocity field.
     */
    val fluid = pingPong(settings.populationFormat, attachments = 3)

    /** Colour carried by the flow. This is what [imageView] returns. */
    val dye = pingPong(settings.format)

    /** The pass that carries [controls] forward a frame and fades it. */
    val controlsPreserveModule: ModulePointer

    /** One collision-and-streaming step, written to all three [fluid] attachments. The box adopts this one. */
    val fluidModule: ModulePointer

    /** The pass that carries [dye] along the velocity, fades it and adds new dye. */
    val dyeUpdateModule: ModulePointer

    private val controlsSampler = TextureSampler(renderer, 1, false)
    private val fluidSampler = TextureSampler(renderer, 1, false)
    private val dyeSampler = TextureSampler(renderer, 1, false)

    private var clearFluidRequested = true
    private var clearControlsRequested = true
    private var clearDyeRequested = true

    init {
        require(settings.width > 0) { "width must be positive" }
        require(settings.height > 0) { "height must be positive" }
        require(settings.relaxation > 0f && settings.relaxation < 2f) { "relaxation must be in (0, 2)" }
        require(settings.baseDensity > 0f) { "baseDensity must be positive" }
        require(settings.driveBlend in 0f..1f) { "driveBlend must be in [0, 1]" }
        require(settings.maxVelocity > 0f) { "maxVelocity must be positive" }
        require(settings.controlDirectionLimit >= 0f) { "controlDirectionLimit must be non-negative" }
        require(settings.dyeLimit > 0f) { "dyeLimit must be positive" }

        controlsPreserveModule = fullscreenPass(controls, "controls-preserve", CONTROLS_PRESERVE_FRAGMENT) { tex ->
            if (tex.name == "sourceTex") fromViews(listOf(controls.otherImageView), tex.name, controlsSampler) else null
        }
        controlsPreserveModule.quad {
            push.bind("obstacleDecayPerFrame") { settings.obstacleDecayPerFrame }
            push.bind("directionDecayPerFrame") { settings.directionDecayPerFrame }
            push.bind("dyeSourceDecayPerFrame") { settings.dyeSourceDecayPerFrame }
            push.bind("controlDirectionLimit") { settings.controlDirectionLimit }
            push.bind("clearRequested") { if (clearControlsRequested) 1f else 0f }
        }
        controls += "preserve" to controlsPreserveModule

        // the fluid reads its own previous frame and the controls this frame has just written
        fluidModule = fullscreenPass(fluid, "fluid", FLUID_UPDATE_FRAGMENT) { tex ->
            when (tex.name) {
                "fluid0Tex" -> fromViews(listOf(fluid.otherImageViewFor(0)), tex.name, fluidSampler)
                "fluid1Tex" -> fromViews(listOf(fluid.otherImageViewFor(1)), tex.name, fluidSampler)
                "fluid2Tex" -> fromViews(listOf(fluid.otherImageViewFor(2)), tex.name, fluidSampler)
                "controlsTex" -> fromViews(listOf(controls.latestImageView), tex.name, controlsSampler)
                else -> null
            }
        }
        fluidModule.quad {
            push.bind("relaxation") { settings.relaxation }
            push.bind("baseDensity") { settings.baseDensity }
            push.bind("baseVelocity") { settings.baseVelocity }
            push.bind("driveBlend") { settings.driveBlend }
            push.bind("controlVelocityScale") { settings.controlVelocityScale }
            push.bind("maxVelocity") { settings.maxVelocity }
            bindBoundary()
            push.bind("clearRequested") { if (clearFluidRequested) 1f else 0f }
            push.bind("initialNoise") { settings.initialNoise }
        }
        fluid += "step" to fluidModule

        dyeUpdateModule = fullscreenPass(dye, "dye-update", DYE_UPDATE_FRAGMENT) { tex ->
            when (tex.name) {
                "sourceTex" -> fromViews(listOf(dye.otherImageView), tex.name, dyeSampler)
                "velocityTex" -> fromViews(listOf(fluid.latestImageViewFor(2)), tex.name, fluidSampler)
                "controlsTex" -> fromViews(listOf(controls.latestImageView), tex.name, controlsSampler)
                else -> null
            }
        }
        dyeUpdateModule.quad {
            push.bind("baseSpeed") { settings.baseVelocity.length() }
            bindBoundary()
            push.bind("dyeDecayPerFrame") { settings.dyeDecayPerFrame }
            push.bind("dyeAdvectionScale") { settings.dyeAdvectionScale * settings.stepsPerFrame.coerceAtLeast(1) }
            push.bind("controlDyeGain") { settings.controlDyeGain }
            push.bind("speedDyeGain") { settings.speedDyeGain }
            push.bind("curlDyeGain") { settings.curlDyeGain }
            push.bind("dyeLimit") { settings.dyeLimit }
            push.bind("clearRequested") { if (clearDyeRequested) 1f else 0f }
        }
        dye += "update" to dyeUpdateModule

        Box.context.get()?.attachShader(fluidModule)
    }

    /** The dye, so a system can be sampled by another module the way a loaded image would be. */
    override val imageView: ImageView
        get() = dye.imageView

    /** The same dye texture, in the list form the array-sampling path asks for. */
    override val imageViewArray: List<HasImageView>
        get() = dye.imageViewArray

    /** Runs controls, [ShaderLatticeBoltzmannSettings.stepsPerFrame] fluid steps and the dye, in that order. Called by whatever the system is registered with; see [installInto]. */
    override fun render(command: CommandBuffer, index: Int) {
        controls.render(command, index)
        repeat(settings.stepsPerFrame.coerceAtLeast(1)) {
            fluid.render(command, index)
            clearFluidRequested = false
        }
        dye.render(command, index)
        clearFluidRequested = false
        clearControlsRequested = false
        clearDyeRequested = false
    }

    /**
     * Put the fluid back to [ShaderLatticeBoltzmannSettings.baseVelocity], plus
     * [ShaderLatticeBoltzmannSettings.initialNoise], on the next frame.
     */
    fun clearFluid() {
        clearFluidRequested = true
    }

    /** Erase every obstacle, push and dye source on the next frame. */
    fun clearControls() {
        clearControlsRequested = true
    }

    /** Erase the dye on the next frame. */
    fun clearDye() {
        clearDyeRequested = true
    }

    /** [clearFluid], [clearControls] and [clearDye] together. */
    fun reset() {
        clearFluidRequested = true
        clearControlsRequested = true
        clearDyeRequested = true
    }

    /** Add a module that draws into [controls]; it runs after the carry-forward pass, every frame. */
    fun addControls(name: String, module: ModulePointer) {
        controls += name to module
    }

    /** [addControls] for a module that has not been given a pointer yet. */
    fun addControls(name: String, module: Module) {
        addControls(name, module.pointer())
    }

    /** Add a module that draws into [dye]; it runs before the built-in update so the flow carries what it draws. */
    fun addDye(name: String, module: ModulePointer) {
        dye += name to module
        val update = dye.modules.remove("update")
        if (update != null) dye.modules["update"] = update
    }

    /** [addDye] for a module that has not been given a pointer yet. */
    fun addDye(name: String, module: Module) {
        addDye(name, module.pointer())
    }

    /**
     * Parameters for a module that draws into [controls]: additive blending, so what a fragment
     * writes is added to what is there, and the previous controls available as `sourceTex`. Hand the
     * result to `moduleHere(...)` in a box, then to [addControls].
     */
    fun controlsParameters(
        name: String,
        configure: Parameters.() -> Unit = {}
    ): Parameters = additiveParameters(controls, name, configure) { tex ->
        if (tex.name == "sourceTex") fromViews(listOf(controls.otherImageView), tex.name, controlsSampler) else null
    }

    /** Parameters for a module that draws into [dye], additive, with the previous dye as `sourceTex`. */
    fun dyeParameters(
        name: String,
        configure: Parameters.() -> Unit = {}
    ): Parameters = additiveParameters(dye, name, configure) { tex ->
        if (tex.name == "sourceTex") fromViews(listOf(dye.otherImageView), tex.name, dyeSampler) else null
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
     * Draw the fluid to a target, as [mode] or, by default, as [ShaderLatticeBoltzmannSettings.previewMode]
     * read every frame, so changing that setting switches the view live. `viewport` is where it lands;
     * call this again with another name and viewport for a second view side by side.
     */
    fun blitToScreen(
        target: RenderTarget = renderer.renderList,
        name: String = "${settings.name}-preview",
        viewport: Rect? = null,
        mode: FluidVisualizationMode? = null
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
                        "velocityTex" -> fromViews(listOf(fluid.latestImageViewFor(2)), tex.name, fluidSampler)
                        "controlsTex" -> fromViews(listOf(controls.latestImageView), tex.name, controlsSampler)
                        "dyeTex" -> fromViews(listOf(dye.latestImageView), tex.name, dyeSampler)
                        else -> null
                    }
                }
            }
        ).pointer()
        module.quad(name) {
            push.bind("baseVelocity") { settings.baseVelocity }
            push.bind("baseDensity") { settings.baseDensity }
            bindBoundary()
            push.bind("speedVisualizationGain") { settings.speedVisualizationGain }
            push.bind("curlVisualizationGain") { settings.curlVisualizationGain }
            push.bind("densityVisualizationGain") { settings.densityVisualizationGain }
            push.bind("compositeDyeExposure") { settings.compositeDyeExposure }
            push.bind("compositeVelocityGain") { settings.compositeVelocityGain }
            push.bind("compositeCurlGain") { settings.compositeCurlGain }
            push.bind("visualizationMode") { (mode ?: settings.previewMode).ordinal.toFloat() }
        }
        target += name to module
        return module
    }

    /** Draw the raw dye texture, without the composite's tone mapping. */
    fun blitDyeToScreen(
        target: RenderTarget = renderer.renderList,
        name: String = "${settings.name}-dye-preview",
        viewport: Rect? = null,
        sourceRect: Rect? = null
    ): ModulePointer = dye.debugBlitToScreen(renderer = renderer, target = target, name = name, viewport = viewport, sourceRect = sourceRect)

    /** Draw the raw controls texture: red obstacle, green and blue steering, alpha not shown. */
    fun blitControlsToScreen(
        target: RenderTarget = renderer.renderList,
        name: String = "${settings.name}-controls-preview",
        viewport: Rect? = null,
        sourceRect: Rect? = null
    ): ModulePointer = controls.debugBlitToScreen(renderer = renderer, target = target, name = name, viewport = viewport, sourceRect = sourceRect)

    // A plain `blitToScreen` on one of the ping-pongs would register it as a second pre-target, so it
    // would advance twice a frame outside render(); `debugBlitToScreen` draws the latest frame without
    // registering anything.

    private fun pingPong(format: Int, attachments: Int = 1) = PingPongRenderToTexture(renderer) {
        width = settings.width
        height = settings.height
        this.format = format
        colorAttachments = attachments
        colorFormats = List(attachments) { format }
        clearColor = settings.clearColor
        colorStoreOp = VK_ATTACHMENT_STORE_OP_STORE
        colorSampled = true
        useDepth = false
    }

    private fun fullscreenPass(
        target: PingPongRenderToTexture,
        suffix: String,
        fragmentSource: String,
        resolver: (Introspected.Texture) -> Pair<HasImageViewArray, TextureSampler>?
    ): ModulePointer = Module(
        parameters(renderer) {
            +target
            name = "${settings.name}-$suffix"
            depthTest = false
            blend = false
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
        +target
        this.name = name
        depthTest = false
        blend = true
        srcColor = VK_BLEND_FACTOR_ONE
        dstColor = VK_BLEND_FACTOR_ONE
        colorBlendOp = VK_BLEND_OP_ADD
        srcAlpha = VK_BLEND_FACTOR_ONE
        dstAlpha = VK_BLEND_FACTOR_ONE
        alphaBlendOp = VK_BLEND_OP_ADD
        textureResolver = resolver
        configure()
    }

    private fun ModulePointer.quad(name: String = current.parameters.name, bind: Mesh.() -> Unit) {
        current.meshes["$name-quad"] = fullscreenTriangle(this).apply(bind)
    }

    private fun Mesh.bindBoundary() {
        push.bind("wrapX") { if (settings.wrapX) 1f else 0f }
        push.bind("wrapY") { if (settings.wrapY) 1f else 0f }
        push.bind("borderSolidPx") { settings.borderSolidPx }
        push.bind("solidThreshold") { settings.solidThreshold }
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

        // Shared by every pass that reads across the lattice; each declares the push members it uses.
        private val BOUNDARY_GLSL = """
            ivec2 wrapPixel(ivec2 pixel, ivec2 dims) {
                int x = push.wrapX > 0.5 ? ((pixel.x % dims.x) + dims.x) % dims.x : clamp(pixel.x, 0, dims.x - 1);
                int y = push.wrapY > 0.5 ? ((pixel.y % dims.y) + dims.y) % dims.y : clamp(pixel.y, 0, dims.y - 1);
                return ivec2(x, y);
            }

            bool isSolidAt(ivec2 pixel, ivec2 dims) {
                if (push.borderSolidPx > 0.5) {
                    int border = int(round(push.borderSolidPx));
                    if (pixel.x < border || pixel.y < border || pixel.x >= dims.x - border || pixel.y >= dims.y - border) {
                        return true;
                    }
                }
                return texelFetch(controlsTex, wrapPixel(pixel, dims), 0).r >= push.solidThreshold;
            }
        """.trimIndent()

        private val CURL_GLSL = """
            float curlAt(ivec2 pixel, ivec2 dims) {
                float uyR = texelFetch(velocityTex, wrapPixel(pixel + ivec2(1, 0), dims), 0).a;
                float uyL = texelFetch(velocityTex, wrapPixel(pixel + ivec2(-1, 0), dims), 0).a;
                float uxU = texelFetch(velocityTex, wrapPixel(pixel + ivec2(0, 1), dims), 0).b;
                float uxD = texelFetch(velocityTex, wrapPixel(pixel + ivec2(0, -1), dims), 0).b;
                return 0.5 * ((uyR - uyL) - (uxU - uxD));
            }

            vec3 hsv2rgb(vec3 c) {
                vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
                rgb = rgb * rgb * (3.0 - 2.0 * rgb);
                return c.z * mix(vec3(1.0), rgb, c.y);
            }
        """.trimIndent()

        private fun String.withHelpers() = replace("// boundary helpers", BOUNDARY_GLSL).replace("// curl helpers", CURL_GLSL)

        private val CONTROLS_PRESERVE_FRAGMENT = """
            #version 450

            layout(set = 0, binding = 0) uniform sampler2D sourceTex;

            layout(push_constant) uniform Push {
                float obstacleDecayPerFrame;
                float directionDecayPerFrame;
                float dyeSourceDecayPerFrame;
                float controlDirectionLimit;
                float clearRequested;
            } push;

            layout(location = 0) out vec4 outColor;

            float decayTowardZero(float value, float amount) {
                return value > 0.0 ? max(value - amount, 0.0) : min(value + amount, 0.0);
            }

            void main() {
                if (push.clearRequested > 0.5) {
                    outColor = vec4(0.0);
                    return;
                }

                ivec2 dims = textureSize(sourceTex, 0);
                vec4 previous = texelFetch(sourceTex, clamp(ivec2(gl_FragCoord.xy), ivec2(0), dims - 1), 0);
                float limit = push.controlDirectionLimit;
                outColor = vec4(
                    clamp(decayTowardZero(previous.r, push.obstacleDecayPerFrame), 0.0, 1.0),
                    clamp(decayTowardZero(previous.g, push.directionDecayPerFrame), -limit, limit),
                    clamp(decayTowardZero(previous.b, push.directionDecayPerFrame), -limit, limit),
                    clamp(decayTowardZero(previous.a, push.dyeSourceDecayPerFrame), 0.0, 1.0)
                );
            }
        """.trimIndent()

        /**
         * One lattice Boltzmann step as GLSL, the source the box's `fragment` tab is seeded with:
         * pull-streaming with bounce-back at solid sites, each neighbour's BGK collision recomputed
         * where it is pulled from, and the forcing from the wind and the controls applied as a velocity
         * shift before collision.
         */
        val FLUID_UPDATE_FRAGMENT = """
            #version 450

            layout(set = 0, binding = 0) uniform sampler2D fluid0Tex;
            layout(set = 0, binding = 1) uniform sampler2D fluid1Tex;
            layout(set = 0, binding = 2) uniform sampler2D fluid2Tex;
            layout(set = 0, binding = 3) uniform sampler2D controlsTex;

            layout(push_constant) uniform Push {
                float relaxation;
                float baseDensity;
                vec2 baseVelocity;
                float driveBlend;
                float controlVelocityScale;
                float maxVelocity;
                float wrapX;
                float wrapY;
                float borderSolidPx;
                float solidThreshold;
                float clearRequested;
                float initialNoise;
            } push;

            // directions 0-3, 4-7, and (8, density, velocity)
            layout(location = 0) out vec4 out0;
            layout(location = 1) out vec4 out1;
            layout(location = 2) out vec4 out2;

            const vec2 DIRS[9] = vec2[](
                vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(0.0, 1.0), vec2(-1.0, 0.0), vec2(0.0, -1.0),
                vec2(1.0, 1.0), vec2(-1.0, 1.0), vec2(-1.0, -1.0), vec2(1.0, -1.0)
            );
            const float WEIGHTS[9] = float[](
                4.0 / 9.0, 1.0 / 9.0, 1.0 / 9.0, 1.0 / 9.0, 1.0 / 9.0,
                1.0 / 36.0, 1.0 / 36.0, 1.0 / 36.0, 1.0 / 36.0
            );
            const int OPPOSITE[9] = int[](0, 3, 4, 1, 2, 7, 8, 5, 6);

            // boundary helpers

            void loadPopulations(ivec2 pixel, ivec2 dims, out float f[9]) {
                ivec2 p = wrapPixel(pixel, dims);
                vec4 a = texelFetch(fluid0Tex, p, 0);
                vec4 b = texelFetch(fluid1Tex, p, 0);
                vec4 c = texelFetch(fluid2Tex, p, 0);
                f[0] = a.r; f[1] = a.g; f[2] = a.b; f[3] = a.a;
                f[4] = b.r; f[5] = b.g; f[6] = b.b; f[7] = b.a;
                f[8] = c.r;
            }

            float equilibrium(int i, float density, vec2 velocity) {
                float cu = dot(DIRS[i], velocity);
                return WEIGHTS[i] * density * (1.0 + 3.0 * cu + 4.5 * cu * cu - 1.5 * dot(velocity, velocity));
            }

            void macroscopic(float f[9], out float density, out vec2 velocity) {
                density = 0.0;
                for (int i = 0; i < 9; ++i) density += f[i];
                density = max(density, 1e-5);
                velocity = vec2(
                    f[1] - f[3] + f[5] - f[6] - f[7] + f[8],
                    f[2] - f[4] + f[5] + f[6] - f[7] - f[8]
                ) / density;
            }

            // the population heading in direction i after the site at pixel has collided
            float postCollision(ivec2 pixel, int i, ivec2 dims) {
                if (isSolidAt(pixel, dims)) return equilibrium(i, push.baseDensity, vec2(0.0));

                float f[9];
                loadPopulations(pixel, dims, f);
                float density;
                vec2 velocity;
                macroscopic(f, density, velocity);

                vec4 control = texelFetch(controlsTex, wrapPixel(pixel, dims), 0);
                vec2 controlVelocity = control.gb * push.controlVelocityScale;
                // the wind pulls every site toward it; the controls push on top
                velocity = mix(velocity, push.baseVelocity, push.driveBlend) + controlVelocity;
                // a dye source is also an inlet: hold its velocity near the wind plus its push
                velocity = mix(velocity, push.baseVelocity + controlVelocity, clamp(control.a, 0.0, 1.0) * 0.85);

                float speed = length(velocity);
                if (speed > push.maxVelocity) velocity *= push.maxVelocity / speed;

                return mix(f[i], equilibrium(i, density, velocity), push.relaxation);
            }

            // pull-streaming: what arrives here along direction i, reflected back off a solid neighbour
            float streamed(ivec2 pixel, int i, ivec2 dims) {
                ivec2 source = pixel - ivec2(DIRS[i]);
                if (isSolidAt(source, dims)) return postCollision(pixel, OPPOSITE[i], dims);
                return postCollision(source, i, dims);
            }

            uint hash(uint x) {
                x ^= x >> 16u; x *= 0x7feb352du;
                x ^= x >> 15u; x *= 0x846ca68bu;
                x ^= x >> 16u;
                return x;
            }

            vec2 noiseAt(ivec2 pixel) {
                uint h = hash(uint(pixel.x) ^ hash(uint(pixel.y) * 2654435761u));
                return (vec2(float(h & 0xffffu), float(h >> 16u)) / 65535.0 - 0.5) * 2.0;
            }

            void writeAtRest(vec2 u) {
                float rho = push.baseDensity;
                out0 = vec4(equilibrium(0, rho, u), equilibrium(1, rho, u), equilibrium(2, rho, u), equilibrium(3, rho, u));
                out1 = vec4(equilibrium(4, rho, u), equilibrium(5, rho, u), equilibrium(6, rho, u), equilibrium(7, rho, u));
                out2 = vec4(equilibrium(8, rho, u), rho, u);
            }

            void main() {
                ivec2 dims = textureSize(fluid0Tex, 0);
                ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), dims - 1);

                if (isSolidAt(pixel, dims)) {
                    writeAtRest(vec2(0.0));
                    return;
                }
                if (push.clearRequested > 0.5) {
                    writeAtRest(push.baseVelocity + noiseAt(pixel) * push.initialNoise);
                    return;
                }

                float f[9];
                for (int i = 0; i < 9; ++i) f[i] = streamed(pixel, i, dims);

                float density;
                vec2 velocity;
                macroscopic(f, density, velocity);
                out0 = vec4(f[0], f[1], f[2], f[3]);
                out1 = vec4(f[4], f[5], f[6], f[7]);
                out2 = vec4(f[8], density, velocity);
            }
        """.trimIndent().withHelpers()

        private val DYE_UPDATE_FRAGMENT = """
            #version 450

            layout(set = 0, binding = 0) uniform sampler2D sourceTex;
            layout(set = 0, binding = 1) uniform sampler2D velocityTex;
            layout(set = 0, binding = 2) uniform sampler2D controlsTex;

            layout(push_constant) uniform Push {
                float baseSpeed;
                float wrapX;
                float wrapY;
                float borderSolidPx;
                float solidThreshold;
                float dyeDecayPerFrame;
                float dyeAdvectionScale;
                float controlDyeGain;
                float speedDyeGain;
                float curlDyeGain;
                float dyeLimit;
                float clearRequested;
            } push;

            layout(location = 0) out vec4 outColor;

            // boundary helpers

            // curl helpers

            vec2 wrapUv(vec2 uv) {
                uv.x = push.wrapX > 0.5 ? fract(uv.x) : clamp(uv.x, 0.0, 1.0);
                uv.y = push.wrapY > 0.5 ? fract(uv.y) : clamp(uv.y, 0.0, 1.0);
                return uv;
            }

            void main() {
                ivec2 dims = textureSize(sourceTex, 0);
                ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), dims - 1);
                if (push.clearRequested > 0.5 || isSolidAt(pixel, dims)) {
                    outColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }

                vec2 velocity = texelFetch(velocityTex, pixel, 0).ba;
                // semi-Lagrangian: take the dye from where this texel's fluid was a frame ago
                vec2 uv = wrapUv((gl_FragCoord.xy - velocity * push.dyeAdvectionScale) / vec2(dims));
                vec3 previous = texture(sourceTex, uv).rgb;

                float curl = curlAt(pixel, dims);
                float speedExcess = max(length(velocity) - push.baseSpeed * 1.1, 0.0);
                float source = texelFetch(controlsTex, pixel, 0).a * push.controlDyeGain
                    + speedExcess * push.speedDyeGain
                    + abs(curl) * push.curlDyeGain;
                vec3 tint = hsv2rgb(vec3(fract(atan(velocity.y, velocity.x) / 6.28318530718 + 0.5), 0.75, 1.0));
                outColor = vec4(clamp(previous * (1.0 - push.dyeDecayPerFrame) + tint * source, vec3(0.0), vec3(push.dyeLimit)), 1.0);
            }
        """.trimIndent().withHelpers()

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

            layout(set = 0, binding = 0) uniform sampler2D velocityTex;
            layout(set = 0, binding = 1) uniform sampler2D controlsTex;
            layout(set = 0, binding = 2) uniform sampler2D dyeTex;

            layout(push_constant) uniform Push {
                vec2 baseVelocity;
                float baseDensity;
                float wrapX;
                float wrapY;
                float borderSolidPx;
                float solidThreshold;
                float speedVisualizationGain;
                float curlVisualizationGain;
                float densityVisualizationGain;
                float compositeDyeExposure;
                float compositeVelocityGain;
                float compositeCurlGain;
                float visualizationMode;
            } push;

            layout(location = 0) in vec2 uv;
            layout(location = 0) out vec4 outColor;

            // boundary helpers

            // curl helpers

            vec3 heatRamp(float t) {
                t = clamp(t, 0.0, 1.0);
                vec3 a = vec3(0.03, 0.06, 0.09);
                vec3 b = vec3(0.10, 0.56, 0.82);
                vec3 c = vec3(0.98, 0.74, 0.18);
                vec3 d = vec3(1.00, 0.98, 0.92);
                if (t < 0.33) return mix(a, b, t / 0.33);
                if (t < 0.66) return mix(b, c, (t - 0.33) / 0.33);
                return mix(c, d, (t - 0.66) / 0.34);
            }

            vec3 signedCurlColor(float curl, float gain) {
                float m = clamp(abs(curl) * gain, 0.0, 1.0);
                return mix(vec3(0.02), curl >= 0.0 ? vec3(1.00, 0.44, 0.10) : vec3(0.18, 0.52, 0.95), m);
            }

            void main() {
                ivec2 dims = textureSize(velocityTex, 0);
                ivec2 pixel = clamp(ivec2(uv * vec2(dims)), ivec2(0), dims - 1);

                vec4 controls = texelFetch(controlsTex, pixel, 0);
                vec4 c = texelFetch(velocityTex, pixel, 0);
                vec2 velocity = c.ba;
                float density = c.g;
                float speed = length(velocity);
                float curl = curlAt(pixel, dims);
                vec3 dye = texelFetch(dyeTex, pixel, 0).rgb;
                float hue = fract(atan(velocity.y, velocity.x) / 6.28318530718 + 0.5);
                vec3 velocityColor = hsv2rgb(vec3(hue, 0.8, clamp(speed * push.speedVisualizationGain, 0.0, 1.0)));

                // the modes are FluidVisualizationMode's ordinals
                vec3 color;
                int mode = int(round(push.visualizationMode));
                if (mode == 1) {
                    color = 1.0 - exp(-dye * 0.9);
                } else if (mode == 2) {
                    color = velocityColor;
                } else if (mode == 3) {
                    color = heatRamp(speed * push.speedVisualizationGain);
                } else if (mode == 4) {
                    float deviation = 0.5 + (density - push.baseDensity) * push.densityVisualizationGain;
                    color = mix(vec3(0.06, 0.16, 0.48), vec3(0.96, 0.94, 0.84), clamp(deviation, 0.0, 1.0));
                } else if (mode == 5) {
                    color = signedCurlColor(curl, push.curlVisualizationGain);
                } else if (mode == 6) {
                    float push_ = clamp(length(controls.gb), 0.0, 1.0);
                    vec3 forceTint = hsv2rgb(vec3(fract(atan(controls.b, controls.g) / 6.28318530718 + 0.5), 0.9, push_));
                    color = mix(vec3(0.04), forceTint, push_) + vec3(0.5, 0.2, 0.8) * controls.a;
                } else {
                    // the flow is lit by how far it departs from the wind, so a steady wind stays dark
                    vec2 departure = velocity - push.baseVelocity;
                    float departureSpeed = length(departure);
                    float departureHue = fract(atan(departure.y, departure.x) / 6.28318530718 + 0.5);
                    vec3 departureColor = hsv2rgb(vec3(departureHue, 0.8, clamp(departureSpeed * push.speedVisualizationGain, 0.0, 1.0)));
                    vec3 dyeLight = 1.0 - exp(-dye * push.compositeDyeExposure);
                    vec3 velocityLight = departureColor * (smoothstep(0.002, 0.03, departureSpeed) * push.compositeVelocityGain);
                    vec3 curlLight = signedCurlColor(curl, push.curlVisualizationGain) * (smoothstep(0.005, 0.05, abs(curl)) * push.compositeCurlGain);
                    color = 1.0 - exp(-(vec3(0.015, 0.02, 0.028) + dyeLight + velocityLight + curlLight));
                }

                if (isSolidAt(pixel, dims)) color = mix(color, vec3(0.92, 0.89, 0.82), 0.82);
                outColor = vec4(color, 1.0);
            }
        """.trimIndent().withHelpers()
    }
}
