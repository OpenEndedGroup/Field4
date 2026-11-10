package afield.graphics.scene.video

import afield.graphics.render.*
import afield.graphics.shader.fromViews
import afield.graphics.texture.LoadedTexture
import afield.graphics.texture.HasImageView
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.RenderToTexture
import afield.math.linalg.Vector4f
import afield.math.linalg.vec
import org.lwjgl.vulkan.VK10.*

/** One full-resolution, video-local RGBA16F pass per selected source frame, independent of eye poses. */
internal class VideoFrameAccumulator(
    private val renderer: Renderer,
    private val source: VideoSourceFrame,
    factory: ((Parameters) -> ModulePointer)?,
) : AutoCloseable, HasImageView {
    private val targets = List(2) { i ->
        RenderToTexture(renderer) {
            width = source.width
            height = source.height
            format = VK_FORMAT_R16G16B16A16_SFLOAT
            useDepth = false
            colorSampled = true
            clearColor = vec(0f, 0f, 0f, 0f)
            debugName = "video-accumulation-$i"
        }
    }
    private val sampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = false)
    private var written = 1
    private val previous = object : HasImageView {
        override val imageView get() = if (valid) targets[written].imageView else LoadedTexture.stubTexture(renderer).imageView
    }
    override val imageView get() = if (valid) targets[written].imageView else LoadedTexture.stubTexture(renderer).imageView
    private val weights = VideoAccumulationWeights()
    private var lastTime = 0L
    var valid = false
        private set
    var samples = 0L
        private set

    private val configuration = parameters(renderer) {
        name = "video-accumulation"
        +targets[0]
        depthTest = false
        depthWrite = false
        blend = false
        textureResolver = { request ->
            val view = when (request.name) {
                "luma" -> object : HasImageView { override val imageView get() = source.imageViewArray[0].imageView }
                "chroma" -> object : HasImageView { override val imageView get() = source.imageViewArray[1].imageView }
                "previousImage" -> previous
                else -> null
            }
            view?.let { fromViews(listOf(it), request.name, sampler) }
        }
    }
    private val module = factory?.invoke(configuration) ?: Module(configuration.apply {
        vertex = resource("accumulation_vertex")
        fragment = resource("accumulation_fragment")
    }).pointer()
    private val mesh = Mesh(renderer, module).resize(3, 0)

    init {
        check(module.current.valid) { "Cannot build video accumulation shaders" }
        mesh.push["image"] = Vector4f(source.width.toFloat(), source.height.toFloat(), 0f, 0f)
        mesh.push["colorimetry"] = StereoVideoColorimetry.parameters(source.colorSpace, source.colorRange)
        mesh.push["weights"] = Vector4f(1f, 0f, 0f, 0f)
        module += "accumulate" to mesh
        targets.forEach { it += "accumulate" to module }
    }

    fun clear() {
        valid = false
        samples = 0
        weights.clear()
        lastTime = 0
    }

    fun render(command: CommandBuffer, index: Int, halfLife: Double, incoming: Double) {
        val now = System.nanoTime()
        val elapsed = if (lastTime == 0L) 0.0 else (now - lastTime) / 1e9
        val fraction = weights.add(elapsed, halfLife, incoming)
        mesh.push["weights"] = Vector4f(fraction, if (valid) 1f else 0f, 0f, 0f)
        mesh.push["colorimetry"] = StereoVideoColorimetry.parameters(source.colorSpace, source.colorRange)
        val destination = 1 - written
        targets[destination].render(command, index)
        written = destination
        lastTime = now
        valid = weights.mass > 0
        samples++
    }

    override fun close() {
        module.current.cleanup()
        // Descriptor/image destruction follows the renderer's in-flight retirement boundary.
        renderer.cleanup.add {
            sampler.cleanup()
            targets.forEach { target ->
                target.imageViewArray.forEach { it.imageView.cleanup() }
                target.colorImageRefs.filterNotNull().forEach { it.cleanup() }
            }
        }
    }

    companion object {
        private fun resource(stage: String) =
            requireNotNull(VideoFrameAccumulator::class.java.getResource("/afield/stereovideo/$stage.glsl")).readText()
    }
}
