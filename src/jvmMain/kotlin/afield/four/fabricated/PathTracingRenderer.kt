package afield.four.fabricated

import afield.four.Renderer
import afield.four.Target
import afield.four.targets.RenderToTexture
import afield.four.buffers.*
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.modules.*
import afield.four.modules.Module
import afield.four.texture.*
import afield.four.util.Camera
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
import org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkBufferCopy
import org.lwjgl.vulkan.VkDependencyInfo
import org.lwjgl.vulkan.VkMemoryBarrier2
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.tan

/**
 * Progressive GPU path tracer: stackless software BVH on every Field Vulkan device, optional KHR ray queries.
 * Construct/install/close on Field's render thread, like other afield.four modules. Scene and environment
 * are snapshots; recreate the tracer to edit them. Camera motion resets or optionally reprojects history;
 * lighting edits always reset accumulation.
 * Output is a fixed-resolution linear HDR RTT. Call blitToScreen separately for exposure + Reinhard presentation.
 * Installing under the same name retires the previous tracer; no coroutine lifetime bookkeeping is needed.
 */
class PathTracingRenderer(
    val renderer: Renderer,
    scene: PathTraceScene,
    environment: PathTraceEnvironment,
    val width: Int = 640,
    val height: Int = 360,
    var camera: Camera = Camera(renderer),
    requestedBackend: Backend = Backend.AUTO,
    val name: String = "path-tracer",
    target: RenderToTexture? = null,
    moduleFactory: (Parameters) -> ComputeModulePointer = { ComputeModule(it).pointer() }
) : AutoCloseable, Target {
    enum class Backend { AUTO, COMPUTE_BVH, RAY_QUERY }
    val backend: Backend
    var samplesPerFrame: Int = 1
        set(value) { require(value in 1..64); field = value }
    /** Subpixel sample width in pixels: one covers the pixel, zero always samples its centre. */
    var pixelJitter: Float = 1f
        set(value) { require(value.isFinite() && value in 0f..1f); if (value != field) reset(); field = value }
    /** Circular thin-lens radius in world units. Zero gives a pinhole camera with everything in focus. */
    var apertureRadius: Float = 0f
        set(value) { require(value.isFinite() && value >= 0f); if (value != field) reset(); field = value }
    /** Focus-plane distance along camera.forward in world units; null follows camera.target. */
    var focusDistance: Float? = null
        set(value) { require(value == null || (value.isFinite() && value > 0f)); if (value != field) reset(); field = value }
    /** Reproject compatible surface history on camera motion. Approximate, bounded, and off by default. */
    var temporalReuse: Boolean = false
        set(value) { if (value != field) reset(); field = value }
    /** Maximum old samples carried across a camera move; glossy surfaces or a changing view may retain fewer. */
    var temporalMaxSamples: Int = 16
        set(value) { require(value in 1..256); if (value != field) reset(); field = value }
    /** Camera-angle penalty: one uses conservative view rejection; zero ignores view-angle changes. */
    var temporalMotionSensitivity: Float = 1f
        set(value) { require(value.isFinite() && value in 0f..1f); if (value != field) reset(); field = value }
    /** Roughness penalty: one rejects polished surfaces and reduces glossy history; zero removes both restrictions. */
    var temporalRoughnessSensitivity: Float = 1f
        set(value) { require(value.isFinite() && value in 0f..1f); if (value != field) reset(); field = value }
    /** Largest estimated defocus radius in pixels allowed to retain surface history during motion. */
    var temporalMaxBlurRadius: Float = 0.5f
        set(value) { require(value.isFinite() && value >= 0f); if (value != field) reset(); field = value }
    /** Exponential depth-fog density per world unit. Zero disables fog. */
    var fogDensity: Float = 0f
        set(value) { require(value.isFinite() && value >= 0f); if (value != field) reset(); field = value }
    /** Clear distance from the camera, in world units, before depth fog starts. */
    var fogStartDistance: Float = 0f
        set(value) { require(value.isFinite() && value >= 0f); if (value != field) reset(); field = value }
    private var fogColorValue = Vector3f(0.5f, 0.5f, 0.5f)
    /** Linear HDR fog colour; returned by copy. Depth fog preserves image alpha. */
    var fogColor: Vector3f
        get() = Vector3f(fogColorValue)
        set(value) {
            require(value.x.isFinite() && value.y.isFinite() && value.z.isFinite())
            require(value.x >= 0f && value.y >= 0f && value.z >= 0f)
            if (value != fogColorValue) reset()
            fogColorValue = Vector3f(value)
        }
    /** Treat background misses as infinitely distant when fog is enabled; transparent backgrounds stay transparent. */
    var fogBackground: Boolean = true
        set(value) { if (value != field) reset(); field = value }
    var maxBounces: Int = 5
        set(value) { require(value in 1..12); if (value != field) reset(); field = value }
    var environmentIntensity: Float = 1f
        set(value) { require(value.isFinite() && value >= 0f); if (value != field) reset(); field = value }
    var environmentRotation: Float = 0f
        set(value) { require(value.isFinite()); if (value != field) reset(); field = value }
    /** Hide only primary-ray misses. Lighting, reflections and refraction still use the environment. */
    var showEnvironment: Boolean = true
        set(value) { if (field != value) reset(); field = value }
    private var backgroundValue = Vector4f(0f, 0f, 0f, 1f)
    /** Linear, straight RGBA background; alpha zero gives a transparent background for compositing. */
    var background: Vector4f
        get() = Vector4f(backgroundValue)
        set(value) {
            require(value.x.isFinite() && value.y.isFinite() && value.z.isFinite() && value.w.isFinite())
            require(value.x >= 0f && value.y >= 0f && value.z >= 0f && value.w in 0f..1f)
            if (backgroundValue != value) reset()
            backgroundValue = Vector4f(value)
        }
    /**
     * Maximum summed indirect luminance per path sample, in linear HDR units before exposure.
     * Zero disables this biased firefly filter (the default). RGB is scaled together; camera-visible
     * emission, background and first-surface lighting are preserved. Deeper glass transmission and
     * caustics can darken. Changing this value resets accumulation.
     */
    var indirectClamp: Float = 0f
        set(value) { require(value.isFinite() && value >= 0f); if (field != value) reset(); field = value }
    /**
     * Sample emissive triangles directly, in addition to the environment, and combine that estimate
     * with the emission a BSDF sample finds using the same power heuristic. This is unbiased: a
     * converged image matches one rendered with it off. It costs one extra shadow ray per
     * non-delta surface interaction, and it is what makes a small, bright lamp converge at all.
     * Emitters behind stochastic alpha coverage, and emissive glass, stay BSDF-only.
     * Turning it off reaches the previous estimator exactly. Changing this value resets accumulation.
     */
    var emitterSampling: Boolean = true
        set(value) { if (value != field) reset(); field = value }
    /** Emissive triangles this scene offers the direct sampler; zero leaves emission to BSDF hits. */
    val emitterCount: Int get() = packed.emitterCount
    var rayEpsilon: Float = 0.0001f
        set(value) { require(value.isFinite() && value > 0f); if (value != field) reset(); field = value }
    /** Exposure affects display only and never discards samples. */
    var exposure: Float = 1f
        set(value) { require(value.isFinite() && value >= 0f); field = value }
    /** Fresh samples per pixel since the last view change/reset, excluding reprojected history. */
    @Volatile
    var samples: Int = 0
        private set
    @Volatile var paused: Boolean = false
    private val resetVersion = AtomicLong(0)
    private var seenReset = -1L
    private var previousCamera = FloatArray(0)
    private var sequenceIndex = 0
    private var historyWriteIndex = 0
    private var historyBuffers = emptyList<VulkanBuffer>()
    private var previousFrameCamera = Array(4) { Vector4f() }
    private val disposals = mutableListOf<() -> Unit>()
    private val packed = scene.pack()
    private val envWidth = environment.width
    private val envHeight = environment.height
    private val accumulation: VulkanBuffer
    val materialModule: ComputeModulePointer
    val materialMesh: ComputeMesh
    /** Premultiplied linear HDR RGBA; alpha is primary visibility coverage, not sample count. */
    val output: RenderToTexture
    private val resolve: ModulePointer
    private var blit: ModulePointer? = null
    private val ownedUniforms = mutableListOf<BufferParameters>()
    private val sceneBuffers = linkedMapOf<String, VulkanBuffer>()
    private var previousPipeline = 0L
    private val outputPass = Target { command, index -> if (!closed && samples > 0) output.render(command, index) }
    private var installed = false
    private var closed = false
    val triangleCount: Int get() = packed.triangles.size

    init {
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216)
        require(requestedBackend != Backend.RAY_QUERY || renderer.device.supportsRayQuery) { "This device has no enabled KHR ray queries; use AUTO or COMPUTE_BVH" }
        backend = if (requestedBackend != Backend.COMPUTE_BVH && renderer.device.supportsRayQuery) Backend.RAY_QUERY else Backend.COMPUTE_BVH
        try {
            disposals += {
                ownedUniforms.mapNotNull { it.result }.flatMap { it.buffers }.distinct().forEach { it.cleanup() }
                ownedUniforms.clear()
            }
            val triangles = upload(packed.triangles.size * 240L, packed::writeTriangles)
            val nodes = upload(packed.nodes.size * 48L, packed::writeNodes)
            val materials = upload(packed.materials.size * PathTraceMaterial.STRIDE.toLong(), packed::writeMaterials)
            val distribution = upload(environment.distribution.size * 4L) { it.asFloatBuffer().put(environment.distribution) }
            val emitterTable = upload(packed.emitterByteSize, packed::writeEmitters)
            val emitterLookup = upload(packed.emitterLookupByteSize, packed::writeEmitterLookup)
            val textures = upload(packed.textureByteSize, packed::writeTextures)
            val directions = upload(sobolWords.size * 4L) { it.asIntBuffer().put(sobolWords) }
            accumulation = own(VulkanBuffer(renderer, width.toLong() * height * 16,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT or VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, needsDeviceAddress = true)) { cleanup() }
            val bytes = MemoryUtil.memAlloc(environment.pixels.size * 4)
            val texture = try {
                bytes.asFloatBuffer().put(environment.pixels)
                own(LoadedTexture.fromRawRGBA("$name-environment", envWidth, envHeight, bytes, renderer,
                    VK_FORMAT_R32G32B32A32_SFLOAT)) { cleanup() }
            } finally { MemoryUtil.memFree(bytes) }
            texture.transferNow()
            val sampler = own(TextureSampler(renderer, 1, false,
                addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT)) { cleanup() }
            val provider = object : HasImageViewArray { override val imageViewArray = listOf<HasImageView>(texture) }
            val tlas = if (backend == Backend.RAY_QUERY) {
                val vertices = own(VulkanBuffer(renderer, packed.triangles.size * 36L,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, needsDeviceAddress = true)) { cleanup() }
                vertices.mapAndWrite_bytes { packed.triangles.forEach { t -> t.p.forEach { p -> putFloat(p.x).putFloat(p.y).putFloat(p.z) } } }
                val blas = own(AccelerationStructure(renderer, "$name-blas")) { cleanup() }
                blas.buildBottomLevelNow(listOf(AccelerationStructureTriangles(vertices,
                    maxVertex = packed.triangles.size * 3 - 1, primitiveCount = packed.triangles.size)))
                own(AccelerationStructure(renderer, "$name-tlas")) { cleanup() }.also { it.buildTopLevelNow(listOf(RayTracingInstance(blas))) }
            } else null
            output = target ?: RenderToTexture(renderer) {
                this.width = this@PathTracingRenderer.width; this.height = this@PathTracingRenderer.height
                format = VK_FORMAT_R32G32B32A32_SFLOAT; useDepth = false
                debugName = "${this@PathTracingRenderer.name}-output"
            }
            require(output.renderer === renderer && output.width == width && output.height == height)
            require(output.colorFormats.size == 1 && output.colorFormats.single() in listOf(VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT)) {
                "Path tracer output needs one floating-point HDR color attachment"
            }
            val errors = mutableListOf<String>()
            materialModule = moduleFactory(Parameters(renderer,
                name = name, compute = shader(backend == Backend.RAY_QUERY),
                computeSourceName = "pathtracing/trace.comp",
                // The CPU may prepare the next frame while a previous dispatch still reads its uniforms.
                uboResolver = { it.apply { perFrame = true; ownedUniforms += this } },
                textureResolver = { if (it.name == "environmentMap") provider to sampler else null }, accelerationStructureResolver = { tlas },
                errorHandler = { message, _ -> errors += message }
            ))
            val module = materialModule.current
            disposals += { materialModule.current.cleanup() }
            check(module.valid) { "Path tracer shader failed: ${errors.joinToString("\n")}" }
            materialMesh = ComputeMesh(renderer, materialModule).also { it.x = (width + 7) / 8; it.y = (height + 7) / 8 }
            materialModule += "trace" to materialMesh
            materialMesh.push.setMembers(module.ci.pushConstants.flatMap { it.members })
            sceneBuffers.putAll(mapOf("triangles" to triangles, "nodes" to nodes, "materials" to materials,
                "accumulation" to accumulation, "distribution" to distribution, "directions" to directions, "textures" to textures,
                "emitterTable" to emitterTable, "emitterLookup" to emitterLookup))
            val display = own(Module(parameters(renderer) {
                name = "${this@PathTracingRenderer.name}-resolve"
                output.apply()
                vertex = DISPLAY_VERTEX; fragment = RESOLVE_FRAGMENT
                depthTest = false; depthWrite = false; blend = false
                errorHandler = { message, _ -> errors += message }
            })) { cleanup() }
            check(display.valid) { "Path tracer display shader failed: ${errors.joinToString("\n")}" }
            resolve = display.pointer()
            val screen = Mesh(renderer, resolve)
            screen.resize(3, 0, resolve)
            screen.push.setMembers(display.fi.pushConstants.single().members)
            screen.push["accumulation"] = accumulation
            screen.push["width"] = width; screen.push["height"] = height
            resolve.current.meshes["screen"] = screen
            output.modules["$name-resolve"] = resolve

        } catch (failure: Throwable) {
            disposals.asReversed().forEach { runCatching(it) }; disposals.clear()
            throw failure
        }
    }

    private fun <T> own(value: T, dispose: T.() -> Unit): T = value.also { disposals += { it.dispose() } }
    private fun upload(size: Long, writer: (ByteBuffer) -> Unit): VulkanBuffer {
        val destination = own(VulkanBuffer(renderer, size,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, needsDeviceAddress = true)) { cleanup() }
        val staging = VulkanBuffer(renderer, size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
        val command = CommandBuffer(renderer.commandPool, primary = true, oneTimeSubmit = true)
        val fence = Fence(renderer.device, signaled = false)
        try {
            staging.mapAndWrite_bytes { writer(this) }
            command.beginRecording()
            MemoryStack.stackPush().use { stack ->
                vkCmdCopyBuffer(command.vkCommandBuffer, staging.buffer, destination.buffer, VkBufferCopy.calloc(1, stack).size(size))
            }
            memoryBarrier(command, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT)
            command.endRecording()
            MemoryStack.stackPush().use { renderer.graphQueue.submit(it.pointers(command.vkCommandBuffer), null, null, null, fence) }
            fence.fenceWait()
        } finally { fence.cleanup(); command.cleanup(); staging.cleanup() }
        return destination
    }

    /** Register compute and RTT passes. Reusing a name retires the previous tracer. */
    fun install(): PathTracingRenderer {
        check(!closed)
        if (!installed) {
            val previous = renderer.computeList.others[name]
            require(previous == null || previous is PathTracingRenderer) { "Scene name '$name' belongs to another kind of target" }
            previous?.close()
            renderer.computeList.others[name] = this
            renderer.renderList.preTargets["$name-output"] = outputPass
            installed = true
        }
        return this
    }

    override fun render(command: CommandBuffer, index: Int) {
        if (paused || closed) return
        updateFrame()
        memoryBarrier(command, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)
        materialModule.current.render(command, afield.stdlib.linalg.Rectanglef())
        graphicsMemoryBarrier(command, accumulation)
        if (temporalReuse) historyWriteIndex = 1 - historyWriteIndex
        sequenceIndex += samplesPerFrame
        blit?.enable()
        samples += samplesPerFrame
    }

    /** Optional second pass: sample the RTT, apply exposure/Reinhard, and composite on screen. */
    fun blitToScreen(blend: Boolean = true): ModulePointer {
        check(!closed)
        blit?.let { return it }
        val sampler = own(TextureSampler(renderer, 1, false)) { cleanup() }
        val display = own(Module(parameters(renderer) {
            name = "${this@PathTracingRenderer.name}-display"
            vertex = DISPLAY_VERTEX; fragment = DISPLAY_FRAGMENT
            depthTest = false; depthWrite = false; this.blend = blend
            srcColor = VK_BLEND_FACTOR_ONE; dstColor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
            srcAlpha = VK_BLEND_FACTOR_ONE; dstAlpha = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
            textureResolver = { if (it.name == "sourceTex") output to sampler else null }
        })) { cleanup() }
        check(display.valid) { "Path tracer presentation shader failed" }
        val pointer = display.pointer()
        val screen = Mesh(renderer, pointer)
        screen.resize(3, 0, pointer)
        screen.push.setMembers(display.fi.pushConstants.single().members)
        screen.push.bind("exposure") { exposure }
        screen.push.bind("aspectRatio") {
            val extent = renderer.swapChain.swapChainExtent
            (extent.width().toFloat() / extent.height().coerceAtLeast(1)) / (width.toFloat() / height)
        }
        pointer.current.meshes["screen"] = screen
        if (samples == 0) pointer.disable()
        renderer += "$name-display" to pointer
        blit = pointer
        return pointer
    }

    fun reset() { resetVersion.incrementAndGet() }

    private fun updateFrame() {
        val forward = camera.forward; val right = camera.right
        val up = Vector3f(right).cross(forward).normalize()
        require(camera.p.fov.isFinite() && camera.p.fov > 0.0 && camera.p.fov < Math.PI) {
            "Camera field of view must lie between zero and pi radians"
        }
        val scale = tan(camera.p.fov * 0.5).toFloat()
        val focus = focusDistance ?: camera.distanceToTarget()
        require(apertureRadius == 0f || (focus.isFinite() && focus > 0f)) {
            "Depth of field needs a positive focus distance or a camera target away from the eye"
        }
        require(scale.isFinite() && scale > 0f) { "Camera field of view must lie between zero and pi radians" }
        val values = floatArrayOf(camera.at.x, camera.at.y, camera.at.z, forward.x, forward.y, forward.z,
            right.x, right.y, right.z, up.x, up.y, up.z, scale, if (apertureRadius > 0f) focus else 0f)
        require(values.all { it.isFinite() }) { "Path tracer camera contains non-finite values" }
        val pipeline = materialModule.current.pipeline
        if (pipeline != previousPipeline) {
            reset(); previousPipeline = pipeline
            // Full reload allocates new UBOs. Retire our superseded allocations after in-flight frames.
            val active = materialModule.current.uniforms.values.filterIsInstance<Module.Uniform>().map { it.buffer }.toSet()
            val unused = ownedUniforms.filter { it.result != null && it.result !in active }
            if (unused.isNotEmpty()) {
                ownedUniforms.removeAll(unused.toSet())
                val release = unused.mapNotNull { it.result }.flatMap { it.buffers }.distinct()
                renderer.cleanup += Runnable { release.forEach { it.cleanup() } }
            }
        }
        val revision = resetVersion.get()
        val cameraChanged = !values.contentEquals(previousCamera)
        val viewChanged = previousCamera.size != values.size || (0..12).any { values[it] != previousCamera[it] }
        // A focus-only edit is not camera motion. Never blend two different lens configurations.
        val focusOnlyChange = cameraChanged && !viewChanged
        val hardReset = previousCamera.isEmpty() || seenReset != revision || focusOnlyChange ||
            sequenceIndex > 16_000_000 - samplesPerFrame
        val historyMode = when {
            !temporalReuse -> -1
            hardReset -> 0
            cameraChanged -> 2
            else -> 1
        }
        if (hardReset || cameraChanged) samples = 0
        if (hardReset || (cameraChanged && !temporalReuse)) sequenceIndex = 0
        previousCamera = values; seenReset = revision
        if (temporalReuse && historyBuffers.isEmpty()) {
            // Two disjoint histories: this dispatch only reads the previous one and writes the other.
            historyBuffers = List(2) {
                own(VulkanBuffer(renderer, width.toLong() * height * 64,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT or VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, needsDeviceAddress = true)) { cleanup() }
            }
        }
        val buffers = materialModule.current.liveUniformStructures.getValue("SceneBuffers")
        sceneBuffers.forEach { (name, buffer) -> buffers[name] = buffer }
        // In disabled mode the shader never dereferences these placeholders.
        buffers["historyRead"] = historyBuffers.getOrNull(1-historyWriteIndex) ?: accumulation
        buffers["historyWrite"] = historyBuffers.getOrNull(historyWriteIndex) ?: accumulation
        val frame = materialModule.current.liveUniformStructures.getValue("Frame")
        frame["previousOrigin"] = previousFrameCamera[0]
        frame["previousRight"] = previousFrameCamera[1]
        frame["previousUp"] = previousFrameCamera[2]
        frame["previousForward"] = previousFrameCamera[3]
        previousFrameCamera = arrayOf(Vector4f(camera.at, apertureRadius),
            Vector4f(right.mul(scale * width / height), if (apertureRadius > 0f) focus else 1f),
            Vector4f(up.mul(scale), 0f), Vector4f(forward, 0f))
        frame["origin"] = previousFrameCamera[0]
        frame["right"] = previousFrameCamera[1]
        frame["up"] = previousFrameCamera[2]
        frame["forward"] = previousFrameCamera[3]
        frame["temporal"] = Int4(historyMode, sequenceIndex, temporalMaxSamples, 0)
        frame["historyPolicy"] = Vector4f(temporalMotionSensitivity, temporalRoughnessSensitivity, temporalMaxBlurRadius, 0f)
        frame["fog"] = Vector4f(fogColorValue, fogDensity)
        frame["fogOptions"] = Vector4f(fogStartDistance, if (fogBackground) 1f else 0f, 0f, 0f)
        frame["image"] = Int4(width, height, samples, samplesPerFrame)
        frame["scene"] = Int4(packed.nodes.size, envWidth, envHeight, maxBounces)
        frame["lighting"] = Vector4f(environmentIntensity, environmentRotation, rayEpsilon, if (showEnvironment) 1f else 0f)
        frame["sampling"] = Vector4f(indirectClamp, pixelJitter, apertureRadius, if (apertureRadius > 0f) focus else 1f)
        frame["emitters"] = Int4(if (emitterSampling) packed.emitterCount else 0, 0, 0, 0)
        frame["background"] = Vector4f(backgroundValue.x * backgroundValue.w, backgroundValue.y * backgroundValue.w,
            backgroundValue.z * backgroundValue.w, backgroundValue.w)
    }

    /** Synchronous GPU readback for export/verification. Call on the render thread, outside frame recording. */
    fun readLinear(): FloatArray {
        check(!closed && samples > 0) { "Render at least one frame before readback" }
        return readBuffer(accumulation)
    }

    internal fun readHistorySampleCounts(): FloatArray {
        check(temporalReuse && samples > 0)
        val values = readBuffer(historyBuffers[1-historyWriteIndex])
        return FloatArray(width*height) { values[it*16+15] }
    }

    private fun readBuffer(source: VulkanBuffer): FloatArray {
        val staging = VulkanBuffer(renderer, source.requestedSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
        val command = CommandBuffer(renderer.commandPool, primary = true, oneTimeSubmit = true)
        val fence = Fence(renderer.device, signaled = false)
        try {
            command.beginRecording()
            memoryBarrier(command, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT)
            MemoryStack.stackPush().use { stack ->
                val region = VkBufferCopy.calloc(1, stack).size(source.requestedSize)
                vkCmdCopyBuffer(command.vkCommandBuffer, source.buffer, staging.buffer, region)
            }
            memoryBarrier(command, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_HOST_BIT, VK_ACCESS_2_HOST_READ_BIT)
            command.endRecording()
            MemoryStack.stackPush().use { renderer.graphQueue.submit(it.pointers(command.vkCommandBuffer), null, null, null, fence) }
            fence.fenceWait()
            return FloatArray((source.requestedSize/4).toInt()).also { data -> staging.mapAndRead { get(data) } }
        } finally { fence.cleanup(); command.cleanup(); staging.cleanup() }
    }

    /** Detach now; retire GPU resources through Field's frames-in-flight cleanup queue. */
    override fun close() {
        if (closed) return
        closed = true
        if (installed) {
            renderer.computeList.others.remove(name, this)
            renderer.renderList.preTargets.remove("$name-output", outputPass)
        }
        blit?.let { renderer.renderList.modules.remove("$name-display", it) }
        output.modules.remove("$name-resolve", resolve)
        val release = disposals.asReversed().toList(); disposals.clear()
        renderer.cleanup += Runnable { release.forEach { it() } }
    }

    private class Int4(val x: Int, val y: Int, val z: Int, val w: Int) : WritesToBuffer {
        override fun writeTo(b: ByteBuffer, baseOffset: Int) {
            b.putInt(baseOffset, x).putInt(baseOffset+4, y).putInt(baseOffset+8, z).putInt(baseOffset+12, w)
        }
    }
    companion object {
        internal val sobolWords: IntArray by lazy {
            val data = PathTracingRenderer::class.java.getResourceAsStream("/afield/four/pathtracing/sobol-100x32.bin")!!.use { it.readBytes() }
            check(data.size == 100 * 32 * 4)
            IntArray(100 * 32).also { ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(it) }
        }
        internal fun shader(hardware: Boolean): String {
            val source = PathTracingRenderer::class.java.getResource("/afield/four/pathtracing/trace.comp")!!.readText()
            return if (hardware) source.replace("#version 460", "#version 460\n#define HARDWARE_QUERY") else source
        }
        private fun memoryBarrier(cmd: CommandBuffer, srcStage: Long, srcAccess: Long, dstStage: Long, dstAccess: Long) {
            MemoryStack.stackPush().use { stack ->
                val barrier = VkMemoryBarrier2.calloc(1, stack).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                    .srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess)
                val dependency = VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO).pMemoryBarriers(barrier)
                vkCmdPipelineBarrier2(cmd.vkCommandBuffer, dependency)
            }
        }
        private const val DISPLAY_VERTEX = """
            #version 460
            layout(location=0) out vec2 uv;
            void main() {
                vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
                gl_Position=vec4(p*2.0-1.0,0,1); uv=vec2(p.x,1.0-p.y);
            }
        """
        private const val RESOLVE_FRAGMENT = """
            #version 460
            #extension GL_EXT_buffer_reference : require
            layout(buffer_reference,std430,buffer_reference_align=16) readonly buffer Accumulation { vec4 items[]; };
            layout(push_constant) uniform Resolve { Accumulation accumulation; int width; int height; } pc;
            layout(location=0) in vec2 uv;
            layout(location=0) out vec4 color;
            void main() {
                ivec2 p=clamp(ivec2(uv*vec2(pc.width,pc.height)),ivec2(0),ivec2(pc.width-1,pc.height-1));
                color=pc.accumulation.items[p.y*pc.width+p.x];
            }
        """
        private const val DISPLAY_FRAGMENT = """
            #version 460
            layout(set=0,binding=0) uniform sampler2D sourceTex;
            layout(push_constant) uniform Display { float exposure; float aspectRatio; } pc;
            layout(location=0) in vec2 uv;
            layout(location=0) out vec4 color;
            void main() {
                vec2 sampleUV=uv-0.5;
                if (pc.aspectRatio>1.0) sampleUV.x*=pc.aspectRatio; else sampleUV.y/=pc.aspectRatio;
                sampleUV+=0.5;
                if (any(lessThan(sampleUV,vec2(0))) || any(greaterThan(sampleUV,vec2(1)))) { color=vec4(0); return; }
                vec4 sampleColor=texture(sourceTex,sampleUV);
                vec3 hdr=max(sampleColor.rgb/max(sampleColor.a,1e-20)*pc.exposure,vec3(0));
                color=vec4(hdr/(1.0+hdr)*sampleColor.a,sampleColor.a);
            }
        """
    }
}
