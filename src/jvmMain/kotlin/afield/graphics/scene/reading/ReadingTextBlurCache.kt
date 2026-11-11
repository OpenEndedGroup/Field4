package afield.graphics.scene.reading

import afield.graphics.render.*
import afield.graphics.shader.fromViews
import afield.graphics.text.slug.SlugBuffer
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.*
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.MultiviewStereoTarget
import afield.graphics.vulkan.targets.RenderToTexture
import afield.math.linalg.*
import kotlin.math.max
import org.lwjgl.vulkan.VK10.*

/**
 * Two word-local masks use the multiview support already required by the reading renderer. The
 * cache never contains an eye image: current eye poses are applied only when sampling it. Gaussian
 * work records once before the scene, outside any active rendering pass.
 */
internal class ReadingTextBlurCache(
    private val renderer: Renderer,
    private val stereo: MultiviewStereoTarget,
    private val output: RenderToTexture,
    requested: List<Pair<ReadingText, Float>>,
    private val slugData: Map<ReadingText, SlugBuffer>,
    maximumLabels: Int,
    private val config: ReadingTextBlurConfig,
) : AutoCloseable {
    init {
        require(requested.size in 1..2)
    }

    private val items = if (requested.size == 1) requested + requested else requested
    private val capacity = items.map { max(.5f, it.second) }
    private val bounds = items.mapIndexed { i, (shape, _) ->
        val w = shape.slug.words
        val sigma = capacity[i] * (shape.layout?.typeface?.size?.toFloat() ?: 100f)
        require(sigma.isFinite()) { "Text blur reservation is too large" }
        Vector4f(
            Float.fromBits(w[8]) - max(4f, config.sigmaCutoff) * sigma,
            Float.fromBits(w[9]) - max(4f, config.sigmaCutoff) * sigma,
            Float.fromBits(w[10]) + max(4f, config.sigmaCutoff) * sigma,
            Float.fromBits(w[11]) + max(4f, config.sigmaCutoff) * sigma,
        ).also { b -> require(listOf(b.x,b.y,b.z,b.w).all { it.isFinite() } && b.z > b.x && b.w > b.y) }
    }
    private val entries =
        defaultBuffer(name = "reading-local-blur-entries") {
                size = 2L * 48
                hosted()
                addressable()
            }
            .toBuffer(renderer)
    private val sampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = false)
    private val targets = ArrayList<RenderToTexture>()
    private val preparationModules = ArrayList<ModulePointer>()

    private fun target(w: Int, h: Int, name: String) =
        RenderToTexture(renderer) {
                width = w
                height = h
                colorLayerCount = 2
                multiviewViewMask = 3
                format = VK_FORMAT_R16_SFLOAT
                useDepth = false
                colorSampled = true
                colorTransferSrc = true
                clearColor = vec(0f, 0f, 0f, 0f)
                debugName = name
            }
            .also { targets.add(it) }

    internal val levels = ArrayList<RenderToTexture>()

    private fun pass(
        target: RenderToTexture,
        vertex: String,
        fragment: String,
        source: RenderToTexture? = null,
    ): Mesh {
        val module =
            Module(
                    parameters(renderer) {
                        name = target.parameters.debugName
                        +target
                        this.vertex = vertex
                        this.fragment = fragment
                        depthTest = false
                        depthWrite = false
                        blend = false
                        textureResolver = { texture ->
                            if (texture.name == "source" && source != null)
                                fromViews(listOf(source), texture.name, sampler)
                            else null
                        }
                    },
                )
                .pointer()
        val mesh = Mesh(renderer, module).resize(3, 0)
        mesh.push["entries"] = entries
        module += "mask" to mesh
        target += "prepare" to module
        check(module.current.valid) { "Cannot prepare text blur: ${target.parameters.debugName}" }
        preparationModules.add(module)
        return mesh
    }

    val drawModule: ModulePointer
    private val meshes: List<Mesh>
    private val clipPlane = Vector4f()
    private val meshLevels = Array(maximumLabels) { Vector4f(1f, 1f, 0f, 0f) }
    private val meshInfo = Array(maximumLabels) { Vector4f() }
    private var prepared = false
    private var closed = false
    var preparationCount = 0
        private set

    val bytes: Long
        get() = targets.sumOf { it.width.toLong() * it.height * 2L * 2L }

    init {
        val base = target(config.width, config.height, "reading-text-mask")
        levels.add(base)
        pass(
            base,
            ReadingShaders.source("text_mask_vertex"),
            ReadingShaders.source("text_mask_fragment"),
        )
        var previous = base
        for (level in 1 until ReadingBlurLevels.count) {
            val width = ReadingBlurLevels.size(config.width, level, config.minimumSize, config.levelsPerHalving)
            val height = ReadingBlurLevels.size(config.height, level, config.minimumSize, config.levelsPerHalving)
            val horizontal = target(width, previous.height, "reading-text-gaussian-$level-h")
            val vertical = target(width, height, "reading-text-gaussian-$level-v")
            val sigma = ReadingBlurLevels.increment(level)
            val h =
                pass(
                    horizontal,
                    ReadingShaders.source("fullscreen_vertex"),
                    ReadingShaders.source("text_gaussian_fragment"),
                    previous,
                )
            h.push["pass"] =
                Vector4f(previous.width.toFloat(), previous.height.toFloat(), sigma, 0f)
            val v =
                pass(
                    vertical,
                    ReadingShaders.source("fullscreen_vertex"),
                    ReadingShaders.source("text_gaussian_fragment"),
                    horizontal,
                )
            v.push["pass"] =
                Vector4f(horizontal.width.toFloat(), horizontal.height.toFloat(), sigma, 1f)
            levels.add(vertical)
            previous = vertical
        }
        drawModule =
            Module(
                    parameters(renderer) {
                        name = "reading-local-text-blur"
                        stereo.configure(this)
                        +output
                        vertex = ReadingShaders.source("text_blur_vertex")
                        fragment = ReadingShaders.source("text_blur_fragment")
                        depthTest = false
                        depthWrite = false
                        blend = true
                        srcColor = VK_BLEND_FACTOR_ONE
                        dstColor = VK_BLEND_FACTOR_ONE
                        srcAlpha = VK_BLEND_FACTOR_ONE
                        dstAlpha = VK_BLEND_FACTOR_ONE
                        textureResolver = { texture ->
                            val n = texture.name.removePrefix("mask").toIntOrNull()
                            if (n != null && n in 1 until ReadingBlurLevels.count)
                                fromViews(listOf(levels[n]), texture.name, sampler)
                            else null
                        }
                    },
                )
                .pointer()
        check(drawModule.current.valid) { "Cannot build the cached text blur drawing module" }
        drawModule.current.liveUniformStructures.getValue("TextBlurTone").bind("appearance") { config.appearance }
        meshes = List(maximumLabels) { Mesh(renderer, drawModule) }

        for ((i, mesh) in meshes.withIndex()) {
            mesh.resize(0, 0)
            mesh.push["model"] = Matrix4f()
            mesh.push["bounds"] = bounds[0]
            mesh.push["levels"] = Vector4f(1f, 1f, 0f, 0f)
            mesh.push["tint"] = Vector4f(0f)
            mesh.push["info"] = Vector4f(0f)
            drawModule += "blurred-word-$i" to mesh
        }
    }

    fun mix(shape: ReadingText, radius: Float): ReadingBlurLevels.Mix? {
        val layer = items.indexOfFirst { it.first == shape }
        return if (layer < 0) null
        else ReadingBlurLevels.mix(radius.coerceAtMost(capacity[layer]), capacity[layer])
    }

    fun supports(shape: ReadingText, radius: Float): Boolean {
        val layer = items.indexOfFirst { it.first == shape }
        return layer >= 0 && radius <= capacity[layer]
    }

    fun prepare(command: CommandBuffer, index: Int) {
        if (prepared || closed) return
        entries.mapAndWrite_bytes {
            clear()
            for ((i, item) in items.withIndex()) {
                putLong(slugData.getValue(item.first).getGPUAddress(command))
                putLong(0L)
                val b = bounds[i]
                putFloat(b.x)
                putFloat(b.y)
                putFloat(b.z)
                putFloat(b.w)
                putFloat(capacity[i] * (item.first.layout?.typeface?.size?.toFloat() ?: 100f))
                putFloat(config.maximumRadius.toFloat())
                putFloat(config.sigmaCutoff)
                putFloat(0f)
            }
        }
        for (target in targets) target.render(command, index)
        prepared = true
        preparationCount++
    }

    fun update(labels: List<ReadingLabel>, model: Matrix4f, colour: Vector4f) {
        for ((i, mesh) in meshes.withIndex()) {
            val word = labels.getOrNull(i)
            val layer = if (word == null) -1 else items.indexOfFirst { it.first == word.shape }
            if (word == null || layer < 0 || word.blur <= 0f) {
                mesh.resize(0, 0)
                continue
            }
            val mix =
                ReadingBlurLevels.mix(word.blur.coerceAtMost(capacity[layer]), capacity[layer])
            mesh.resize(6, 0)
            mesh.push["model"] =
                Matrix4f(model).translate(word.position).rotate(word.orientation).rotateZ(word.rotation).scale(word.scale)
            mesh.push["bounds"] = bounds[layer]
            meshLevels[i].set(mix.low.toFloat(), mix.high.toFloat(), mix.fraction, clipPlane.w)
            mesh.push["levels"] = meshLevels[i]
            mesh.push["tint"] =
                Vector4f(colour).mul(word.color).apply { w *= word.opacity * mix.cached }
            meshInfo[i].set(layer.toFloat(), clipPlane.x, clipPlane.y, clipPlane.z)
            mesh.push["info"] = meshInfo[i]
        }
    }

    /** Keep the existing 128-byte ABI by using the four previously reserved components. */
    fun setClipPlane(plane: Vector4f) {
        clipPlane.set(plane)
        meshes.forEachIndexed { i, mesh ->
            meshLevels[i].w = plane.w
            meshInfo[i].y = plane.x
            meshInfo[i].z = plane.y
            meshInfo[i].w = plane.z
            mesh.push["levels"] = meshLevels[i]
            mesh.push["info"] = meshInfo[i]
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // The retired draw pipeline and descriptors can still be used by submitted eye frames.
        renderer.cleanup.add {
            preparationModules.forEach { it.current.cleanup() }
            drawModule.current.cleanup()
            entries.buffers.forEach { it.cleanup() }
            sampler.cleanup()
            targets.forEach { target ->
                target.imageViewArray.forEach { it.imageView.cleanup() }
                target.colorImageRefs.filterNotNull().forEach { it.cleanup() }
            }
        }
    }
}
