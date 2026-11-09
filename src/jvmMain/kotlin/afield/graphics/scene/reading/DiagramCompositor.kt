package afield.graphics.scene.reading

import afield.graphics.render.*
import afield.graphics.shader.fromViews
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.buffers.bindPushConstant
import afield.graphics.vulkan.targets.MultiviewStereoTarget
import afield.graphics.vulkan.targets.RenderToTexture
import afield.math.linalg.*
import kotlin.math.roundToInt
import org.lwjgl.vulkan.VK10.*

/** Live linear-light controls shared by the blur cascade and its final green-channel compositor. */
class DiagramCompositeSettings {
    var mediumRadius = 1f
    var broadRadius = 2f
    val selection = Vector4f(1f, 0f, 0f, 1f) // gain, offset, blur mix, source-green scale
    val reconstruction = Vector4f(.5f, .5f, 1f, 0f) // red/blue weights, exposure, reserved
    val selectorRange = Vector4f(0f, 1f, 0f, 0f)
    val selectorSource = Vector4f(0f, 1f, 0f, 0f)
    val paint = Vector4f(1f, .1f, 1f, 0f)
    val colorFold = Vector4f(0f)
    val foldLimits = Vector4f(1f, 1f, 1f, .5f)
    val alphaFold = Vector4f(1f, 1f, 0f, 0f)
    val saturation = Vector4f(1f, 0f, .5f, 1f)
    val gain = Vector4f(1f)
    val gamma = Vector4f(1f)
    val shadowTone = Vector4f(.1f, .2f, .5f, 0f)
    val highlightTone = Vector4f(.7f, .25f, .05f, 0f)
    val toneRange = Vector4f(.35f, .65f, 0f, 0f)
    val background = Vector4f(.003f, .005f, .010f, 1f)
    val fxaa = Vector4f(0f, .125f, .03125f, 8f)
    val subpixel = Vector4f(.5f, 0f, 0f, 1f)
    val supersampling = Vector4f(1f, 1f, 0f, 0f)
}

/**
 * Two-layer float scene → half-size H/V blur → quarter-size H/V blur → current eye output.
 * Every stage records on the caller's command buffer after the stereo eye UBO is updated.
 * RGB accumulates opacity-weighted light; alpha accumulates opacity, including overlapping glyphs.
 */
class DiagramCompositor(
    private val renderer: Renderer,
    private val stereo: MultiviewStereoTarget,
    shaders: DiagramShaderSet,
    val sceneScale: Float = 1f,
    private val moduleFactories: Map<String, DiagramModuleFactory> = emptyMap(),
) : AutoCloseable {
    val settings = DiagramCompositeSettings()
    private val width = (stereo.eyeWidth * sceneScale).roundToInt().coerceAtLeast(1)
    private val height = (stereo.eyeHeight * sceneScale).roundToInt().coerceAtLeast(1)

    init { require(sceneScale.isFinite() && sceneScale in 1f..2f) }

    private fun target(w: Int, h: Int, label: String) = RenderToTexture(renderer) {
        width = w
        height = h
        colorLayerCount = 2
        multiviewViewMask = 3
        format = VK_FORMAT_R16G16B16A16_SFLOAT
        useDepth = false
        colorSampled = true
        colorTransferSrc = true
        clearColor = vec(0f, 0f, 0f, 0f)
        debugName = label
    }

    val sharp = target(width, height, "reading-sharp")
    private val mediumH = target(maxOf(1, width / 2), maxOf(1, height / 2), "reading-medium-h")
    val medium = target(mediumH.width, mediumH.height, "reading-medium-v")
    private val broadH = target(maxOf(1, width / 4), maxOf(1, height / 4), "reading-broad-h")
    val broad = target(broadH.width, broadH.height, "reading-broad-v")
    private val stages = listOf(sharp, mediumH, medium, broadH, broad)
    private val sampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = false)
    private val blurModules = (1..4).map { i ->
        val target = stages[i]
        val source = stages[i - 1]
        val module = readingModule(parameters(renderer) {
            name = "reading-blur-$i"
            +target
            depthTest = false
            depthWrite = false
            blend = false
            textureResolver = { texture ->
                if (texture.name == "source") fromViews(listOf(source), texture.name, sampler) else null
            }
        }, shaders.blurVertex, shaders.blurFragment, moduleFactories)
        val mesh = Mesh(renderer, module)
        mesh.resize(3, 0)
        mesh.push.bindPushConstant("tapStep", {
            Vector4f(
                if (i % 2 == 1) 1f / target.width else 0f,
                if (i % 2 == 0) 1f / target.height else 0f,
                (if (i <= 2) settings.mediumRadius else settings.broadRadius) * sceneScale,
                1f,
            )
        }) { error("Missing blur push constant") }
        module += "blur" to mesh
        target += "blur" to module
        module
    }

    val module = readingModule(parameters(renderer) {
        name = "reading-composite"
        stereo.configure(this)
        depthTest = false
        depthWrite = false
        blend = false
        textureResolver = { texture ->
            val source = when (texture.name) {
                "sharpImage" -> sharp
                "mediumImage" -> medium
                "broadImage" -> broad
                else -> null
            }
            source?.let { fromViews(listOf(it), texture.name, sampler) }
        }
    }, shaders.compositeVertex, shaders.compositeFragment, moduleFactories)
    private val mesh = Mesh(renderer, module)
    /** Last recorded frame and command buffer for each of the five inputs and final composite. */
    val stageFrames = LongArray(6) { -1L }
    val stageCommands = LongArray(6)
    /** Called outside any render pass, before the current scene and its blur cascade. */
    var beforeScene: Target? = null
    private var closed = false

    init {
        mesh.resize(3, 0)
        mesh.push.bindPushConstant("selection", { settings.selection }) { error("Missing selector") }
        mesh.push.bindPushConstant("reconstruction", { settings.reconstruction }) { error("Missing reconstruction") }
        module += "composite" to mesh
        settings.subpixel.y = stereo.eyeWidth.toFloat()
        settings.subpixel.z = stereo.eyeHeight.toFloat()
        val grade = module.current.liveUniformStructures.getValue("CompositeGrade")
        with(settings) {
            grade.bind("selectorRange") { selectorRange }
            grade.bind("selectorSource") { selectorSource }
            grade.bind("paint") { paint }
            grade.bind("colorFold") { colorFold }
            grade.bind("foldLimits") { foldLimits }
            grade.bind("alphaFold") { alphaFold }
            grade.bind("saturation") { saturation }
            grade.bind("gain") { gain }
            grade.bind("gamma") { gamma }
            grade.bind("shadowTone") { shadowTone }
            grade.bind("highlightTone") { highlightTone }
            grade.bind("toneRange") { toneRange }
            grade.bind("background") { background }
            grade.bind("fxaa") { fxaa }
            grade.bind("subpixel") { subpixel }
            grade.bind("supersampling") { supersampling }
        }
        module.current.before["reading-scene-and-blur"] = Target { command, index ->
            beforeScene?.render(command, index)
            for ((i, target) in stages.withIndex()) {
                target.render(command, index)
                stageFrames[i] = renderer.frameCounter
                stageCommands[i] = command.vkCommandBuffer.address()
            }
        }
        module.current.after["reading-composite-recorded"] = Target { command, _ ->
            stageFrames[5] = renderer.frameCounter
            stageCommands[5] = command.vkCommandBuffer.address()
        }
    }

    /** Reloading the editable shaders retains descriptors, live controls and same-frame hooks. */
    fun reload(shaders: DiagramShaderSet) {
        for (blur in blurModules) check(blur.reload(blur.current.parameters.copy(
            vertex = shaders.blurVertex, fragment = shaders.blurFragment,
        ))) { "Blur shader reload failed" }
        check(module.reload(module.current.parameters.copy(
            vertex = shaders.compositeVertex, fragment = shaders.compositeFragment,
        ))) { "Compositor shader reload failed" }
    }

    override fun close() {
        if (closed) return
        closed = true
        beforeScene = null
        module.current.before.remove("reading-scene-and-blur")
        module.current.after.remove("reading-composite-recorded")
        (blurModules + module).forEach { it.current.cleanup() }
        // Retire only after previously submitted frames have finished sampling these images.
        renderer.cleanup.add {
            sampler.cleanup()
            stages.forEach { target ->
                target.imageViewArray.forEach { it.imageView.cleanup() }
                target.colorImageRefs.filterNotNull().forEach { it.cleanup() }
            }
        }
    }
}
