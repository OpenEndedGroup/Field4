package afield.graphics.scene.reading

import afield.graphics.render.*
import afield.graphics.shader.fromViews
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.*
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.MultiviewStereoTarget
import afield.graphics.vulkan.targets.RenderToTexture
import afield.math.linalg.*
import org.lwjgl.vulkan.VK10.*

/** A bounded, reusable pool. Captures never include frost, so panels do not recursively blur. */
class ReadingFrostRenderer
internal constructor(
    private val renderer: Renderer,
    private val stereo: MultiviewStereoTarget,
    private val output: RenderToTexture,
    private var sources: Map<String, ModulePointer>,
    private val clip: (Vector4f) -> Unit,
) : AutoCloseable {
    var enabled = true
    /** Power-mean blur space: 1 is linear light; >1 gives brighter highlights more influence. */
    var blurGamma = 1f
    var maximumSurfaces = 6
    var budgetBytes = 1024L * 1024 * 1024
    var warmFrames = 120L
    var activeSurfaces = 0
        private set

    var omittedSurfaces = 0
        private set

    var recordedFrame = -1L
        private set

    var recordedCommand = 0L
        private set

    /** Includes resources waiting for their GPU retirement fence. */
    val allocatedBytes
        get() = slots.size * bytesPerSlot + retiringBytes

    private var retiringBytes = 0L
    val allocatedSurfaces
        get() = slots.size

    private val bytesPerSlot =
        (output.width.toLong() * output.height +
            2L * (output.width / 2).coerceAtLeast(1) * (output.height / 2).coerceAtLeast(1)) * 16L
    private val slots = ArrayList<Slot>()
    private var nextId = 0
    private var closed = false

    private inner class Slot(val id: Int) {
        var owner: DiagramTextFrame? = null
        var lastUse = renderer.frameCounter
        private val sampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = false)

        private fun target(w: Int, h: Int, suffix: String) = RenderToTexture(renderer) {
            width = w
            height = h
            colorLayerCount = 2
            multiviewViewMask = 3
            format = VK_FORMAT_R16G16B16A16_SFLOAT
            useDepth = false
            colorSampled = true
            colorTransferSrc = true
            clearColor = vec(0f, 0f, 0f, 0f)
            debugName = "reading-frost-$id-$suffix"
        }

        val sharp = target(output.width, output.height, "sharp")
        private val horizontal = target((output.width / 2).coerceAtLeast(1), (output.height / 2).coerceAtLeast(1), "h")
        private val blurred = target(horizontal.width, horizontal.height, "v")
        private val blurModules = ArrayList<ModulePointer>()
        private val blurMeshes = ArrayList<Mesh>()
        private var capacity = 256L

        private fun shapeBuffer() =
            defaultBuffer(name = "reading-frost-$id-shape") {
                    size = capacity
                    perFrame = true
                    hosted()
                    addressable()
                }
                .toBuffer(renderer)

        private var shape = shapeBuffer()
        var sigma = 5f
        private var blackLevel = 0f
        val drawModule: ModulePointer
        private val drawMesh: Mesh
        val key = "reading-frost-$id"

        init {
            sharp.modules.putAll(sources)
            for ((target, source) in listOf(horizontal to sharp, blurred to horizontal)) {
                val module = Module(
                    parameters(renderer) {
                        name = target.parameters.debugName
                        +target
                        vertex = ReadingShaders.source("fullscreen_vertex")
                        fragment = ReadingShaders.source("frost_blur_fragment")
                        depthTest = false
                        depthWrite = false
                        blend = false
                        textureResolver = { texture ->
                            if (texture.name == "source") fromViews(listOf(source), texture.name, sampler) else null
                        }
                    },
                ).pointer()
                val mesh = Mesh(renderer, module).resize(3, 0)
                module += "blur" to mesh
                target += "blur" to module
                blurModules.add(module)
                blurMeshes.add(mesh)
                check(module.current.valid) { "Cannot build frost Gaussian pass" }
            }
            drawModule = Module(
                parameters(renderer) {
                    name = key
                    stereo.configure(this)
                    +output
                    vertex = ReadingShaders.source("frost_vertex")
                    fragment = ReadingShaders.source("frost_fragment")
                    depthTest = false
                    depthWrite = false
                    blend = true
                    srcColor = VK_BLEND_FACTOR_ONE
                    dstColor = VK_BLEND_FACTOR_ONE
                    srcAlpha = VK_BLEND_FACTOR_ONE
                    dstAlpha = VK_BLEND_FACTOR_ONE
                    textureResolver = { texture ->
                        when (texture.name) {
                            "sharpBehind" -> fromViews(listOf(sharp), texture.name, sampler)
                            "blurredBehind" -> fromViews(listOf(blurred), texture.name, sampler)
                            else -> null
                        }
                    }
                },
            ).pointer()
            drawMesh = Mesh(renderer, drawModule).resize(0, 0)
            drawMesh.push["shape"] = shape
            drawModule += "panel" to drawMesh
            check(drawModule.current.valid) { "Cannot build frost replacement pass" }
            val tone = drawModule.current.liveUniformStructures.getValue("FrostTone")
            tone.bind("blackLevel") { blackLevel }
            tone.bind("globalBlackLevel") { 0f }
        }

        fun update(surface: ReadingFrostSurface, world: Matrix4f) {
            val geometry = surface.geometry
            val bytes = 16L + (geometry.edges.size + geometry.rectlets.size) * 16L
            if (bytes > capacity) {
                val old = shape
                while (capacity < bytes) capacity *= 2
                shape = shapeBuffer()
                renderer.cleanup.add { old.buffers.forEach { it.cleanup() } }
            }
            shape.mapAndWrite_bytes {
                clear()
                putInt(geometry.edges.size)
                putInt(geometry.rectlets.size)
                putInt(0)
                putInt(0)
                for (e in geometry.edges) {
                    putFloat(e.a.x.toFloat())
                    putFloat(e.a.y.toFloat())
                    putFloat(e.b.x.toFloat())
                    putFloat(e.b.y.toFloat())
                }
                for (r in geometry.rectlets) {
                    putFloat(r.left.toFloat())
                    putFloat(r.bottom.toFloat())
                    putFloat(r.right.toFloat())
                    putFloat(r.top.toFloat())
                }
            }
            val settings = surface.frame.frost
            blackLevel = settings.blackLevel.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            sigma = settings.blurPixels.takeIf { it.isFinite() }?.coerceIn(0f, 32f) ?: 0f
            drawMesh.push["shape"] = shape
            drawMesh.push["model"] = world
            drawMesh.push["bounds"] = Vector4f(
                geometry.left.toFloat(),
                geometry.bottom.toFloat(),
                geometry.right.toFloat(),
                geometry.top.toFloat(),
            )
            drawMesh.push["settings"] = Vector4f(
                (surface.opacity * settings.opacity).coerceIn(0f, 1f),
                settings.brightness.takeIf { it.isFinite() }?.coerceIn(0f, 8f) ?: 1f,
                sigma,
                output.width.toFloat() / stereo.eyeWidth,
            )
            // Both Gaussian axes use half-size output pixels, including the first reduction.
            blurMeshes[0].push["stepSigma"] = Vector4f(
                1f / horizontal.width,
                0f,
                sigma * horizontal.width / sharp.width,
                blurGamma.takeIf { it.isFinite() }?.coerceIn(.25f, 4f) ?: 1f,
            )
            blurMeshes[1].push["stepSigma"] = Vector4f(
                0f,
                1f / blurred.height,
                sigma * blurred.height / sharp.height,
                blurGamma.takeIf { it.isFinite() }?.coerceIn(.25f, 4f) ?: 1f,
            )
            drawMesh.resize(6, 0)
            lastUse = renderer.frameCounter
            owner = surface.frame
            output.modules[key] = drawModule
        }

        fun render(command: CommandBuffer, index: Int) {
            sharp.render(command, index)
            // Initialize both sampled images even at zero sigma; descriptors stay valid on toggles.
            horizontal.render(command, index)
            blurred.render(command, index)
        }

        fun detach() {
            output.modules.remove(key, drawModule)
        }

        fun retire() {
            detach()
            retiringBytes += bytesPerSlot
            sharp.modules.clear() // borrowed scene modules belong to StereoDiagramRenderer
            // Pipelines/descriptors can be in flight too: retire the whole slot in one fence queue.
            renderer.cleanup.add {
                (blurModules + drawModule).forEach { it.current.cleanup() }
                shape.buffers.forEach { it.cleanup() }
                sampler.cleanup()
                for (target in listOf(sharp, horizontal, blurred)) {
                    target.imageViewArray.forEach { it.imageView.cleanup() }
                    target.colorImageRefs.filterNotNull().forEach { it.cleanup() }
                }
                retiringBytes -= bytesPerSlot
            }
        }
    }

    /** Called before main scene recording, using the same eye UBO and submitted command buffer. */
    internal fun prepare(command: CommandBuffer, index: Int, surfaces: List<ReadingFrostSurface>, model: Matrix4f) {
        if (closed) return
        slots.forEach { it.detach() }
        val left = stereo.eyeViewMatrix(0).invert()
        val right = stereo.eyeViewMatrix(1).invert()
        val eye = Vector3f(left.m30 + right.m30, left.m31 + right.m31, left.m32 + right.m32).mul(.5f)
        val requested = if (enabled) surfaces.filter { visible(it, model) } else emptyList()
        val wanted = requested.map { it.frame }.toSet()
        val inUse = HashSet<Slot>()
        activeSurfaces = 0
        try {
            for (surface in requested.take(maximumSurfaces.coerceAtLeast(0))) {
                val slot =
                    slots.firstOrNull { it !in inUse && it.owner === surface.frame }
                        ?: slots.firstOrNull { it !in inUse && it.owner !in wanted }
                        ?: if (slots.size < maximumSurfaces && allocatedBytes + bytesPerSlot <= budgetBytes)
                            Slot(nextId++).also { slots.add(it) }
                        else null
                if (slot == null) continue
                inUse.add(slot)
                val world = Matrix4f(model).mul(surface.localToDiagram)
                slot.update(surface, world)
                clip(
                    readingFrostClipPlane(
                        world,
                        eye,
                        surface.frame.frost.clipEpsilon.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: .002f,
                    ),
                )
                slot.render(command, index)
                activeSurfaces++
            }
        } finally {
            clip(Vector4f())
        }
        omittedSurfaces = requested.size - activeSurfaces
        val retired = slots.filter {
            it !in inUse &&
                (renderer.frameCounter - it.lastUse >= warmFrames ||
                    allocatedBytes > budgetBytes ||
                    slots.size > maximumSurfaces)
        }
        retired.forEach { it.retire() }
        slots.removeAll(retired.toSet())
        recordedFrame = renderer.frameCounter
        recordedCommand = command.vkCommandBuffer.address()
    }

    private fun visible(surface: ReadingFrostSurface, model: Matrix4f): Boolean {
        val g = surface.geometry
        if (g.edges.isEmpty()) return false
        return (0..1).any { eye ->
            val m =
                stereo.eyeProjectionMatrix(eye).mul(stereo.eyeViewMatrix(eye)).mul(model).mul(surface.localToDiagram)
            val corners =
                listOf(g.left to g.bottom, g.left to g.top, g.right to g.bottom, g.right to g.top).map { (x, y) ->
                    m.transform(Vector4f(x.toFloat(), y.toFloat(), 0f, 1f))
                }
            corners.all { it.w > 1e-5f && it.z > 0f } &&
                !(corners.all { it.x < -it.w } ||
                    corners.all { it.x > it.w } ||
                    corners.all { it.y < -it.w } ||
                    corners.all { it.y > it.w } ||
                    corners.all { it.z > it.w })
        }
    }

    /** Captures borrow the current text modules; refresh them before retiring a replaced cache. */
    internal fun updateSources(replacement: Map<String, ModulePointer>) {
        sources = replacement.toMap()
        slots.forEach { slot -> slot.sharp.modules.apply { clear(); putAll(sources) } }
    }

    override fun close() {
        if (closed) return
        closed = true
        slots.forEach { it.retire() }
        slots.clear()
    }
}
