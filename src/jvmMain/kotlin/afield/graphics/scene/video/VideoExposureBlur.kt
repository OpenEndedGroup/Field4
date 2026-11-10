package afield.graphics.scene.video

import afield.graphics.render.*
import afield.graphics.shader.fromViews
import afield.graphics.texture.HasImageView
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.RenderToTexture
import afield.math.linalg.Vector4f
import afield.math.linalg.vec
import org.lwjgl.vulkan.VK10.*

/** A held full-resolution exposure and a half-resolution separable Gaussian, prepared before either eye. */
internal class VideoExposureBlur(
    private val renderer: Renderer,
    source: HasImageView,
    width: Int,
    height: Int,
) : AutoCloseable {
    private val sampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = false)
    private val targets = ArrayList<RenderToTexture>()
    private val modules = ArrayList<ModulePointer>()

    private fun target(w: Int, h: Int, label: String) = RenderToTexture(renderer) {
        this.width = w
        this.height = h
        format = VK_FORMAT_R16G16B16A16_SFLOAT
        useDepth = false
        colorSampled = true
        clearColor = vec(0f, 0f, 0f, 0f)
        debugName = label
    }.also { targets.add(it) }

    // Keep the packed width even at every level: the centre seam always separates two whole eyes.
    val held = target(width, height, "video-exposure-held")
    private val horizontal = target(2 * ((width / 2 + 1) / 2), height, "video-exposure-blur-h")
    val blurred = target(horizontal.width, ((height + 1) / 2).coerceAtLeast(1), "video-exposure-blur-v")

    private fun pass(destination: RenderToTexture, input: HasImageView): Mesh {
        val pointer = Module(parameters(renderer) {
            name = destination.parameters.debugName
            +destination
            depthTest = false
            depthWrite = false
            blend = false
            vertex = resource("accumulation_vertex")
            fragment = resource("exposure_blur_fragment")
            textureResolver = { request ->
                if (request.name == "sourceImage") fromViews(listOf(input), request.name, sampler) else null
            }
        }).pointer()
        check(pointer.current.valid) { "Cannot build ${destination.parameters.debugName}" }
        val mesh = Mesh(renderer, pointer).resize(3, 0)
        mesh.push["blur"] = Vector4f(0f) // source-pixel sigma X/Y; zero copies the image
        pointer += "fullscreen" to mesh
        destination += "prepare" to pointer
        modules.add(pointer)
        return mesh
    }

    private val copy = pass(held, source)
    private val horizontalMesh = pass(horizontal, held)
    private val verticalMesh = pass(blurred, horizontal)
    var valid = false
        private set

    fun clear() { valid = false }

    /** Record only when an exposure changes, or its blur radius is edited; no wait/readback or extra frame. */
    fun render(command: CommandBuffer, index: Int, sigma: Float, refreshExposure: Boolean) {
        require(sigma.isFinite() && sigma in 0f..16f)
        if (refreshExposure || !valid) held.render(command, index)
        horizontalMesh.push["blur"] = Vector4f(sigma, 0f, 0f, 0f)
        verticalMesh.push["blur"] = Vector4f(0f, sigma, 0f, 0f)
        horizontal.render(command, index)
        blurred.render(command, index)
        valid = true
    }

    override fun close() {
        modules.forEach { it.current.cleanup() }
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
            VideoExposureBlur::class.java.getResource("/afield/stereovideo/$stage.glsl")!!.readText()
    }
}
