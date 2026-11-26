import afield.graphics.render.ComputeModule
import afield.graphics.render.MeshMesh
import afield.graphics.render.MeshModule
import afield.graphics.render.Module
import afield.graphics.render.Parameters
import afield.graphics.render.pointer
import afield.graphics.scene.fabricated.FabricatePassLibrary
import afield.graphics.shader.fromViews
import afield.graphics.texture.HasImageView
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.vulkan.util.FIELD_STATS_PARTIAL_BUFFER_NAME
import afield.lang.stacksl.StackSLCompileOptions
import afield.lang.stacksl.StackSLCompiler
import afield.lang.stacksl.stackSL
import afield.math.linalg.Rectanglef
import afield.math.linalg.Vector4f
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkDependencyInfo
import org.lwjgl.vulkan.VkMemoryBarrier2

val resolution = StrokeResolution()
val analysisWidth = resolution.analysisWidth
val analysisHeight = resolution.analysisHeight
val pixelScale = analysisWidth / 896f // author brush sizes in the original analysis grid
val capacity = 10240
val knots = 9
val summaryLeafSize = 128
val summaryDepth = 5
prop<StrokeResolution>(strokeResolution) = resolution
prop<Int>(strokeCapacity) = capacity
prop<AtomicReference<StrokeFrame?>>(strokeFrame) = AtomicReference(null)
val defaults = StrokeSettings()
prop<AtomicReference<StrokeSettings>>(strokeSettings) = AtomicReference(defaults)
val settings = strokeSettings
val publishedFrame = strokeFrame
val clearPaint = AtomicBoolean(false)
prop<AtomicBoolean>(strokeClear) = clearPaint
val orbitNudge = AtomicReference(0f)
val directory = File(inside.filename, "files/shaders")
val stageBounds = Rectanglef()
val stageError = AtomicReference<String?>(null)
val stats = fieldStatsHere(channels = 1, sketchSize = 64, maxPartials = 1024)
stats.cpuSnapshotsEnabled = false

fun rtt(vararg formats: Int, targetWidth: Int = analysisWidth, targetHeight: Int = analysisHeight) = RenderToTexture(renderer) {
    width = targetWidth
    height = targetHeight
    colorAttachments = formats.size
    colorFormats = formats.toList()
    useDepth = false
    clearColor = vec(0, 0, 0, 0)
}

val half = VK_FORMAT_R16G16B16A16_SFLOAT
val full = VK_FORMAT_R32G32B32A32_SFLOAT

// The only stage that knows the scene. The downstream passes use these three sampled views.
prop<RenderToTexture>(strokeGBuffer)
once.strokeGBuffer { rtt(half, full, full) }

prop<RenderToTexture>(strokeEdgeInput)
once.strokeEdgeInput { rtt(half, half) }
prop<RenderToTexture>(strokeVelocity)
once.strokeVelocity { rtt(half) }
prop<RenderToTexture>(strokeBlur)
once.strokeBlur { rtt(half) }
prop<RenderToTexture>(strokeTensor)
once.strokeTensor { rtt(half) }
prop<RenderToTexture>(strokeSoftTensor)
once.strokeSoftTensor { rtt(half) }
prop<RenderToTexture>(strokeDirection)
once.strokeDirection { rtt(half) }
prop<RenderToTexture>(strokePaint)
once.strokePaint { rtt(half, half, targetWidth = resolution.paintWidth, targetHeight = resolution.paintHeight) }
prop<RenderToTexture>(strokeComposite)
once.strokeComposite {
    rtt(VK_FORMAT_R8G8B8A8_SRGB, targetWidth = resolution.paintWidth, targetHeight = resolution.paintHeight)
}
prop<RenderToTexture>(strokeMediumPaint)
once.strokeMediumPaint {
    rtt(half, targetWidth = max(1, resolution.paintWidth / 2), targetHeight = max(1, resolution.paintHeight / 2))
}
prop<RenderToTexture>(strokeBroadPaint)
once.strokeBroadPaint {
    rtt(half, targetWidth = max(1, resolution.paintWidth / 4), targetHeight = max(1, resolution.paintHeight / 4))
}
prop<RenderToTexture>(strokeMediumHorizontal)
once.strokeMediumHorizontal {
    rtt(half, targetWidth = strokeMediumPaint.width, targetHeight = strokeMediumPaint.height)
}
prop<RenderToTexture>(strokeBroadHorizontal)
once.strokeBroadHorizontal { rtt(half, targetWidth = strokeBroadPaint.width, targetHeight = strokeBroadPaint.height) }
prop<FrameBufferGroup>(strokeSlots)
once.strokeSlots {
    defaultBuffer {
        size = (capacity * (knots + 5)).vec4s
        zeroInit()
    }.buffer
}

prop<FrameBufferGroup>(strokeCalibration)
once.strokeCalibration {
    defaultBuffer {
        size = 1.vec4s
        zeroInit()
    }.buffer
}

// Library-produced leaf and pooled 2×2 passes. Keep every plane visible to child boxes.
val prefix = "stroke-summary"
val summarySpecs = FabricatePassLibrary.summaryPyramid(
    prefix = prefix,
    resolution = summaryLeafSize,
    source = "edge-input",
    weightChannel = -1,
    valueChannel = -1,
)
prop<Map<String, RenderToTexture>>(strokeSummary)
once.strokeSummary {
    summarySpecs.associate { spec ->
        val size = 1 shl spec.name.substringAfterLast('-').toInt()
        spec.name to rtt(full, targetWidth = size, targetHeight = size)
    }
}
fun summaryPlane(plane: String) = strokeSummary.getValue(FabricatePassLibrary.summaryPlane(prefix, plane, summaryDepth))
prop<RenderToTexture>(strokeSummaryValue) = summaryPlane(FabricatePassLibrary.SUMMARY_VALUE)
prop<RenderToTexture>(strokeSummaryMoment) = summaryPlane(FabricatePassLibrary.SUMMARY_MOMENT)
prop<RenderToTexture>(strokeSummaryExtent) = summaryPlane(FabricatePassLibrary.SUMMARY_EXTENT)

prop<HasImageView>(strokeLitColor) = strokeGBuffer.imageViewArray[0]
prop<HasImageView>(strokeWorld) = strokeGBuffer.imageViewArray[1]
prop<HasImageView>(strokePreviousWorld) = strokeGBuffer.imageViewArray[2]
prop<HasImageView>(strokeEdgeColor) = strokeEdgeInput.imageViewArray[0]
prop<HasImageView>(strokeFeedback) = strokeEdgeInput.imageViewArray[1]
prop<HasImageView>(strokePaintColor) = strokePaint.imageViewArray[0]
prop<HasImageView>(strokePaintPhases) = strokePaint.imageViewArray[1]

val layers = mapOf(
    "edgeColor" to (strokeEdgeInput to 0),
    "litLayer" to (strokeGBuffer to 0),
    "worldLayer" to (strokeGBuffer to 1),
    "previousLayer" to (strokeGBuffer to 2),
    "velocityLayer" to (strokeVelocity to 0),
    "summaryValue" to (strokeSummaryValue to 0),
    "summaryMoment" to (strokeSummaryMoment to 0),
    "summaryExtent" to (strokeSummaryExtent to 0),
    "directionLayer" to (strokeDirection to 0),
    "paintLayer" to (strokePaint to 0),
    "diagnosticLayer" to (strokePaint to 1),
    "mediumPaint" to (strokeMediumPaint to 0),
    "broadPaint" to (strokeBroadPaint to 0),
    "compositeLayer" to (strokeComposite to 0),
)

// Each group binds the textures which already exist when its shader runs.
val drawSamplers = listOf("litLayer", "velocityLayer", "worldLayer", "previousLayer")
val traceSamplers = listOf("velocityLayer", "directionLayer", "paintLayer", "litLayer")
val summarySamplers = listOf("summaryValue", "summaryExtent", "summaryMoment", "directionLayer")
val edgeSamplers = listOf("paintLayer", "litLayer", "velocityLayer", "worldLayer")
val hookSamplers = mapOf(
    "brushStyle" to drawSamplers,
    "brushSurface" to drawSamplers,
    "brushOffset" to drawSamplers,
    "lifeEnvelope" to drawSamplers,
    "guideVelocity" to traceSamplers,
    "pigmentStyle" to traceSamplers,
    "edgeTone" to edgeSamplers,
    "regionalStyle" to summarySamplers,
    "compositeTone" to listOf("paintLayer", "mediumPaint", "broadPaint"),
)

fun compilePrograms(text: String) = hookSamplers.mapValues { (entry, samplers) ->
    val result = StackSLCompiler.compile(
        text,
        StackSLCompileOptions(
            prefix = entry + "Fn",
            entrypoint = entry,
            useEntrypointAsRoot = true,
            sampleTextures = samplers,
            declareSamplers = false,
            includeHeader = false,
        ),
    )
    check(result.ok) { "$entry · " + result.diagnostics.joinToString("\n") }
    result.glsl
}
fun readPrograms() = stackSL.orEmpty()
var attemptedPrograms = readPrograms()
var programs = compilePrograms(attemptedPrograms)
fun hooks(vararg names: String) = names.joinToString("\n") { programs.getValue(it) }

fun source(file: String): String {
    return File(directory, file)
        .readText()
        .replace("// CAMERA", File(directory, "camera.glsl").readText())
        .replace("// STROKES", File(directory, "strokes.glsl").readText())
        .replace("// RIBBON_PUSH", File(directory, "ribbon_push.glsl").readText())
        .replace("// CANVAS", File(directory, "canvas.glsl").readText())
        .replace("// PAINT_GAMUT", File(directory, "paint_gamut.glsl").readText())
        .replace("// ENVELOPE", File(directory, "envelope.glsl").readText())
        .let { text ->
            when (file) {
                "paint.mesh" ->
                    text.replace("// DRAW_STACKSL", hooks("brushStyle", "brushSurface", "brushOffset", "lifeEnvelope"))
                "update.comp" ->
                    text.replace(
                        "// UPDATE_STACKSL",
                        hooks("guideVelocity", "pigmentStyle", "regionalStyle", "lifeEnvelope"),
                    )
                "edge_input.frag" -> text.replace("// EDGE_STACKSL", hooks("edgeTone"))
                "composite.frag" -> text.replace("// COMPOSITE_STACKSL", hooks("compositeTone"))
                else -> text
            }
        }
}

fun stage(label: String, textures: Map<String, Pair<RenderToTexture, Int>> = layers): Parameters = Parameters(
    renderer = renderer,
    name = "stroke_three / $label",
    depthTest = false,
    depthWrite = false,
    blend = false,
    textureResolver = { request ->
        textures[request.name]?.let { (target, attachment) ->
            fromViews(listOf(target.imageViewArray[attachment]), request.name)
        }
    },
    errorHandler = { message, _ ->
        stageError.set("$label — $message")
        println("$label — $message")
    },
)

fun graphics(
    frag: String,
    target: RenderToTexture? = null,
    vertexFile: String = "fullscreen.vert",
    textures: Map<String, Pair<RenderToTexture, Int>> = layers,
): Parameters = stage(frag, textures).apply {
    vertexSourceName = File(directory, vertexFile).path
    fragmentSourceName = File(directory, frag).path
    vertex = source(vertexFile)
    fragment = source(frag)
    if (target != null) +target
}

fun updaterParameters() = stage("update.comp", layers + ("paintLayer" to (strokeEdgeInput to 1))).apply {
    computeSourceName = File(directory, "update.comp").path
    compute = source("update.comp")
}

val reloadStages = mutableListOf<() -> Boolean>()
fun draw(
    key: String,
    parameters: () -> Parameters,
    target: RenderToTexture? = null,
    vertices: Int = 3,
): Mesh {
    val pointer = Module(parameters()).pointer()
    check(pointer.current.valid) { stageError.get() ?: "$key failed to compile" }
    val mesh = Mesh(renderer)
    pointer += "geometry" to mesh
    mesh.resize(vertices)
    if (target == null) renderer += key to pointer else target += key to pointer
    reloadStages.add {
        val next = parameters()
        val current = pointer.current.parameters
        (next.vertex != current.vertex || next.fragment != current.fragment) &&
            pointer.reload(next, forceReresolve = false)
    }
    return mesh
}

val sceneMesh = draw("stroke-scene", { graphics("scene.frag", strokeGBuffer) }, strokeGBuffer)
val motion = draw("stroke-motion", { graphics("velocity.frag", strokeVelocity) }, strokeVelocity)
val edgeInput = draw("stroke-edge-input", { graphics("edge_input.frag", strokeEdgeInput) }, strokeEdgeInput)
val colorBlur = draw(
    "stroke-color-blur",
    { graphics("blur.frag", strokeBlur, textures = mapOf("sourceLayer" to (strokeEdgeInput to 0))) },
    strokeBlur,
)
colorBlur.push["radius"] = pixelScale.roundToInt().coerceAtLeast(1)
val tensor = draw(
    "stroke-tensor",
    { graphics("tensor.frag", strokeTensor, textures = mapOf("softColor" to (strokeBlur to 0))) },
    strokeTensor,
)
val tensorBlur = draw(
    "stroke-tensor-blur",
    { graphics("blur.frag", strokeSoftTensor, textures = mapOf("sourceLayer" to (strokeTensor to 0))) },
    strokeSoftTensor,
)
tensor.push["pixelScale"] = pixelScale
tensorBlur.push["radius"] = (3 * pixelScale).roundToInt().coerceAtLeast(1)
draw(
    "stroke-direction",
    { graphics("direction.frag", strokeDirection, textures = mapOf("softTensor" to (strokeSoftTensor to 0))) },
    strokeDirection,
)

// Reuse the Summary shaders verbatim and adapt only their source and scheduling.
val summaryTargets = summarySpecs.map { spec ->
    val target = strokeSummary.getValue(spec.name)
    val input = if (spec.source == "edge-input") strokeEdgeInput else strokeSummary.getValue(checkNotNull(spec.source))
    val pointer = Module(
        stage(spec.name, mapOf("sourceTex" to (input to 0))).apply {
            +target
            vertex = source("summary.vert")
            fragment = spec.fragment
        },
    ).pointer()
    check(pointer.current.valid) { stageError.get() ?: spec.name }
    val mesh = Mesh(renderer)
    pointer += "summary cell" to mesh
    mesh.resize(3)
    spec.push.forEach { (name, value) -> mesh.push[name] = value }
    target += spec.name to pointer
    target
}

val sketch = computeModuleHere {
    textureResolver = stage("direction sketch").textureResolver
}
check(sketch.current.valid && FIELD_STATS_PARTIAL_BUFFER_NAME in sketch.current.uniforms) {
    "Load the direction sketch in this box's compute property."
}
val samplePixels = ComputeMesh(renderer)
sketch += "sample direction field" to samplePixels
samplePixels.x = 1024
samplePixels.push["calibration"] = strokeCalibration

val update = ComputeModule(updaterParameters()).pointer()
check(update.current.valid) { stageError.get() ?: "Stroke update failed to compile" }
val step = ComputeMesh(renderer)
update += "stroke slots" to step
step.x = capacity / 64
step.push["strokes"] = strokeSlots
step.push["calibration"] = strokeCalibration
reloadStages.add {
    val next = updaterParameters()
    next.compute != update.current.parameters.compute && update.reload(next)
}

fun ribbonParameters() = stage("paint task / mesh").apply {
    +strokePaint
    taskSourceName = File(directory, "paint.task").path
    meshSourceName = File(directory, "paint.mesh").path
    fragmentSourceName = File(directory, "paint.frag").path
    task = source("paint.task")
    mesh = source("paint.mesh")
    fragment = source("paint.frag")
    blend = true
    srcColor = VK_BLEND_FACTOR_ONE
    dstColor = VK_BLEND_FACTOR_ONE
    srcAlpha = VK_BLEND_FACTOR_ONE
    dstAlpha = VK_BLEND_FACTOR_ONE
}
val ribbonModule = MeshModule(ribbonParameters()).pointer()
check(ribbonModule.current.valid) { stageError.get() ?: "Task/mesh ribbons failed to compile" }
val ribbons = MeshMesh(renderer)
ribbonModule += "one workgroup per live ribbon" to ribbons
ribbons.x = capacity / 32 // task workgroups of 32 slots, compacted before mesh expansion
ribbons.push["strokes"] = strokeSlots
strokePaint += "stroke-ribbons" to ribbonModule
reloadStages.add {
    val next = ribbonParameters()
    val current = ribbonModule.current.parameters
    (next.task != current.task || next.mesh != current.mesh || next.fragment != current.fragment) &&
        ribbonModule.reload(next)
}

fun gaussian(name: String, input: RenderToTexture, output: RenderToTexture, horizontal: Boolean) {
    val pass =
        draw(name, { graphics("gaussian.frag", output, textures = mapOf("sourceLayer" to (input to 0))) }, output)
    val x = 1f / output.width
    val y = 1f / output.height
    pass.push["taps"] = if (horizontal) Vector4f(x, 0f, x, y) else Vector4f(0f, y, x, y)
}
gaussian("medium-h", strokePaint, strokeMediumHorizontal, true)
gaussian("medium-v", strokeMediumHorizontal, strokeMediumPaint, false)
gaussian("broad-h", strokeMediumPaint, strokeBroadHorizontal, true)
gaussian("broad-v", strokeBroadHorizontal, strokeBroadPaint, false)
val composite = draw("stroke-composite", { graphics("composite.frag", strokeComposite) }, strokeComposite)
val present = draw("stroke-preview", { graphics("present.frag") })

// Publish a render target only after every pipeline and binding is ready.
var serial = 0
var lastNanos = 0L
var sceneTime = 0f
var yaw = 0.12f
val needsReload = AtomicBoolean(false)

fun gpuBarrier(command: CommandBuffer) =
    MemoryStack.stackPush().use { stack ->
        val memory =
            VkMemoryBarrier2.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                .srcAccessMask(VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                .dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT)
        vkCmdPipelineBarrier2(
            command.vkCommandBuffer,
            VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO).pMemoryBarriers(memory),
        )
    }

val passes = Target { command, image ->
    val s = settings.get()
    val now = System.nanoTime()
    val dt = if (lastNanos == 0L) 1f / 60f else ((now - lastNanos) * 1e-9).toFloat().coerceIn(0f, 0.045f)
    lastNanos = now
    serial++
    val clear = clearPaint.getAndSet(false)
    val lastSceneTime = sceneTime
    sceneTime += dt * s.sceneSpeed
    yaw += dt * s.orbitSpeed + orbitNudge.getAndSet(0f)
    val eye = Vector4f(4.85f * sin(yaw), 1.54f, 4.85f * cos(yaw), 0.400f)
    val scene = Vector4f(sceneTime, lastSceneTime, analysisWidth.toFloat(), analysisHeight.toFloat())

    sceneMesh.push["camera"] = eye
    sceneMesh.push["scene"] = scene
    strokeGBuffer.render(command, image)
    motion.push["camera"] = eye
    motion.push["scene"] = scene
    motion.push["deltaSeconds"] = dt
    strokeVelocity.render(command, image)
    publishedFrame.set(StrokeFrame(Vector4f(eye), sceneTime, dt, resolution))

    step.push["camera"] = eye
    step.push["scene"] = scene
    step.push["clock"] = Vector4f(dt, s.release, s.interruptRelease, s.feedback)
    step.push["brush"] = Vector4f(s.rate, s.width, s.length, s.frontGuide)
    step.push["minimumResidual"] = s.residual
    step.push["pigmentFraction"] = s.pigmentFraction
    step.push["attackSeconds"] = s.attack
    step.push["serial"] = serial
    step.push["trace"] = Vector4f(s.velocityMix, s.varianceTarget, s.varianceThickness, s.varianceReference)
    step.push["pixelScale"] = pixelScale
    // The first frame initializes the paint target before compute can bind its sampled view.
    if (serial > 1) {
        gpuBarrier(command)
        step.push["stage"] = if (clear) 2 else 0
        update.render(command, stageBounds)
        gpuBarrier(command)
    }

    ribbons.push["camera"] = eye
    ribbons.push["scene"] = scene
    ribbons.push["style"] = Vector4f(s.spill, s.scale, 0f, 0f)
    ribbons.push["drawStyle"] = Vector4f(s.width, s.weight, s.displacement, sceneTime)
    strokePaint.render(command, image)

    // Birth reads both color error and direction from this frame's newly drawn paint.
    edgeInput.push["litMix"] = s.edgeLitMix
    edgeInput.push["sceneTime"] = sceneTime
    strokeEdgeInput.render(command, image)
    summaryTargets.forEach { it.render(command, image) }
    strokeBlur.render(command, image)
    strokeTensor.render(command, image)
    strokeSoftTensor.render(command, image)
    strokeDirection.render(command, image)
    if (serial % 12 == 1) {
        samplePixels.push["percentile"] = s.percentile
        sketch.render(command, stageBounds)
    }
    gpuBarrier(command)
    step.push["stage"] = 1
    update.render(command, stageBounds)

    strokeMediumHorizontal.render(command, image)
    strokeMediumPaint.render(command, image)
    strokeBroadHorizontal.render(command, image)
    strokeBroadPaint.render(command, image)
    composite.push["selection"] = Vector4f(s.greenGain, s.greenBlur, s.greenReconstruct, s.greenBlue)
    composite.push["blendControls"] = Vector4f(
        s.paintMix,
        s.paintCrossover,
        1f / resolution.paintWidth,
        1f / resolution.paintHeight,
    )
    strokeComposite.render(command, image)

    present.push["camera"] = eye
    present.push["scene"] = scene
    present.push["viewport"] = Vector4f(
        window.width.coerceAtLeast(1).toFloat(),
        window.height.coerceAtLeast(1).toFloat(),
        analysisWidth.toFloat() / analysisHeight,
        s.view.toFloat(),
    )
    present.push["feedback"] = s.feedback
}
val renderList = renderer.renderList
renderList.preTargets["stroke-three"] = passes

val removeKeys = window.addKeyListener { key, _, action, _ ->
    if (action == GLFW_PRESS)
        when (key) {
            in GLFW_KEY_1..GLFW_KEY_9 -> settings.updateAndGet { it.copy(view = key - GLFW_KEY_1) }
            GLFW_KEY_SPACE ->
                settings.updateAndGet { it.copy(sceneSpeed = if (it.sceneSpeed == 0f) defaults.sceneSpeed else 0f) }
            GLFW_KEY_O ->
                settings.updateAndGet { it.copy(orbitSpeed = if (it.orbitSpeed == 0f) defaults.orbitSpeed else 0f) }
            GLFW_KEY_LEFT -> orbitNudge.updateAndGet { it - 0.16f }
            GLFW_KEY_RIGHT -> orbitNudge.updateAndGet { it + 0.16f }
            GLFW_KEY_C -> clearPaint.set(true)
            GLFW_KEY_R -> needsReload.set(true)
        }
}

controlGroup("Scene and views", columns = 2, restoreValues = false) {
    slider(
        "View  ·  1 paint  2 lit  3 direction  4 residual  5 phases  6 optical flow  7 erasers  8 scene buffers  9 feedback  10 summary  11 composite",
        min = 1.0,
        max = 11.0,
        initialValue = 1.0,
        step = 1.0,
    ) {
        settings.updateAndGet { s -> s.copy(view = it.toInt() - 1) }
    }
    slider("Scene speed", min = 0.0, max = 1.5, initialValue = defaults.sceneSpeed.toDouble()) {
        settings.updateAndGet { s -> s.copy(sceneSpeed = it.toFloat()) }
    }
    slider("Camera orbit", min = 0.0, max = 0.55, initialValue = defaults.orbitSpeed.toDouble()) {
        settings.updateAndGet { s -> s.copy(orbitSpeed = it.toFloat()) }
    }
    button("Clear strokes") { if (it) clearPaint.set(true) }
    button("Reload GLSL") { if (it) needsReload.set(true) }
}
controlGroup("Brush form", columns = 2, restoreValues = false) {
    slider("Width", min = 0.4, max = 2.2, initialValue = defaults.width.toDouble()) {
        settings.updateAndGet { s -> s.copy(width = it.toFloat()) }
    }
    slider("Stroke weight", min = 0.0, max = 2.0, initialValue = defaults.weight.toDouble()) {
        settings.updateAndGet { s -> s.copy(weight = it.toFloat()) }
    }
    slider("Stroke scale", min = 0.5, max = 2.0, initialValue = defaults.scale.toDouble(), step = 0.01) {
        settings.updateAndGet { s -> s.copy(scale = it.toFloat()) }
    }
    slider("World displacement", min = 0.0, max = 0.20, initialValue = defaults.displacement.toDouble()) {
        settings.updateAndGet { s -> s.copy(displacement = it.toFloat()) }
    }
    slider("Silhouette spill", min = 0.0, max = 2.2, initialValue = defaults.spill.toDouble()) {
        settings.updateAndGet { s -> s.copy(spill = it.toFloat()) }
    }
    slider("Attack seconds", min = 0.035, max = 1.25, initialValue = defaults.attack.toDouble()) {
        settings.updateAndGet { s -> s.copy(attack = it.toFloat()) }
    }
    slider("Release seconds", min = 0.40, max = 28.0, initialValue = defaults.release.toDouble()) {
        settings.updateAndGet { s -> s.copy(release = it.toFloat()) }
    }
    slider("Interrupted release seconds", min = 0.10, max = 4.0, initialValue = defaults.interruptRelease.toDouble()) {
        settings.updateAndGet { s -> s.copy(interruptRelease = it.toFloat()) }
    }
}
controlGroup("Tracing and pigment", columns = 2, restoreValues = false) {
    slider("Birth rate", min = 0.0, max = 9.0, initialValue = defaults.rate.toDouble()) {
        settings.updateAndGet { s -> s.copy(rate = it.toFloat()) }
    }
    slider("Length", min = 0.45, max = 2.3, initialValue = defaults.length.toDouble()) {
        settings.updateAndGet { s -> s.copy(length = it.toFloat()) }
    }
    slider("Velocity integration", min = 0.0, max = 1.0, initialValue = defaults.velocityMix.toDouble()) {
        settings.updateAndGet { s -> s.copy(velocityMix = it.toFloat()) }
    }
    slider("Direction percentile", min = 0.25, max = 0.97, initialValue = defaults.percentile.toDouble()) {
        settings.updateAndGet { s -> s.copy(percentile = it.toFloat()) }
    }
    slider("Paint-front steering", min = 0.0, max = 1.0, initialValue = defaults.frontGuide.toDouble()) {
        settings.updateAndGet { s -> s.copy(frontGuide = it.toFloat()) }
    }
    slider("Variance targeting", min = 0.0, max = 1.0, initialValue = defaults.varianceTarget.toDouble()) {
        settings.updateAndGet { s -> s.copy(varianceTarget = it.toFloat()) }
    }
    slider("Variance thickness", min = -1.0, max = 1.0, initialValue = defaults.varianceThickness.toDouble()) {
        settings.updateAndGet { s -> s.copy(varianceThickness = it.toFloat()) }
    }
    slider(
        "Variance reference (luma σ)",
        min = 0.002,
        max = 0.12,
        initialValue = defaults.varianceReference.toDouble(),
    ) {
        settings.updateAndGet { s -> s.copy(varianceReference = it.toFloat()) }
    }
    slider("G-buffer in edge analysis", min = 0.0, max = 1.0, initialValue = defaults.edgeLitMix.toDouble()) {
        settings.updateAndGet { s -> s.copy(edgeLitMix = it.toFloat()) }
    }
    slider("Pigment fraction", min = 0.0, max = 1.0, initialValue = defaults.pigmentFraction.toDouble()) {
        settings.updateAndGet { s -> s.copy(pigmentFraction = it.toFloat()) }
    }
    slider("Feedback gain", min = 0.0, max = 1.6, initialValue = defaults.feedback.toDouble()) {
        settings.updateAndGet { s -> s.copy(feedback = it.toFloat()) }
    }
    slider("Minimum residual", min = 0.01, max = 0.24, initialValue = defaults.residual.toDouble()) {
        settings.updateAndGet { s -> s.copy(residual = it.toFloat()) }
    }
}
controlGroup("Final composite", columns = 2, restoreValues = false) {
    slider("Light to paint", min = 0.0, max = 1.0, initialValue = defaults.paintMix.toDouble()) {
        settings.updateAndGet { s -> s.copy(paintMix = it.toFloat()) }
    }
    slider("Paint crossover alpha", min = 0.0, max = 3.0, initialValue = defaults.paintCrossover.toDouble()) {
        settings.updateAndGet { s -> s.copy(paintCrossover = it.toFloat()) }
    }
    slider("Green blur mix", min = 0.0, max = 1.0, initialValue = defaults.greenBlur.toDouble()) {
        settings.updateAndGet { s -> s.copy(greenBlur = it.toFloat()) }
    }
    slider("Green selector gain", min = 0.0, max = 12.0, initialValue = defaults.greenGain.toDouble()) {
        settings.updateAndGet { s -> s.copy(greenGain = it.toFloat()) }
    }
    slider("Green reconstruction", min = 0.0, max = 1.0, initialValue = defaults.greenReconstruct.toDouble()) {
        settings.updateAndGet { s -> s.copy(greenReconstruct = it.toFloat()) }
    }
    slider("Green reconstruction G to B", min = 0.0, max = 1.0, initialValue = defaults.greenBlue.toDouble()) {
        settings.updateAndGet { s -> s.copy(greenBlue = it.toFloat()) }
    }
}
displayClose("controls")
displayOpen("Brush form")
displayOpen("Final composite")

frames {
    try {
        while (true) {
            val candidate = readPrograms()
            val force = needsReload.getAndSet(false)
            if (force || candidate != attemptedPrograms) {
                attemptedPrograms = candidate
                val previous = programs
                stageError.set(null)
                val result = runCatching {
                    programs = compilePrograms(candidate)
                    val changed = reloadStages.count { it() }
                    check(stageError.get() == null) { stageError.get() ?: "Shader reload failed" }
                    "Updated $changed pipelines"
                }
                    .getOrElse { error ->
                        programs = previous
                        reloadStages.forEach { it() }
                        "Kept running StackSL · ${error.message}"
                    }
                display("Brush program", result)
                displayOpen("Brush program")
            }
            frame()
        }
    } finally {
        if (renderList.preTargets["stroke-three"] === passes) {
            renderList.preTargets.remove("stroke-three")
            renderList.modules.remove("stroke-preview")
        }
        removeKeys()
    }
}
