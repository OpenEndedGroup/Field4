package afield.graphics.render.neural

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.CommandBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import org.lwjgl.vulkan.VK13.*

/**
 * Editable source -> native NR -> graded comparison. Source mode works without the optional library. The current box
 * run owns the study: stopping or rerunning it closes the old resources. The renderer also closes the study before
 * destroying its device. The comparison and capture use the very same targets as the live Starting Point.
 */
class OpenDlssNrStudy(
    val renderer: Renderer,
    val size: Int = 768,
    val name: String = "nr-study",
    imagePath: String? = null,
    val geometry: Boolean = false,
    vertex: String = if (geometry) resource("cubes.vert") else defaultVertex,
    fragment: String = if (geometry) resource("cubes.frag") else defaultFragment,
    attach: Boolean = true,
) : Target, AutoCloseable {
    init {
        require(!geometry || imagePath == null) { "The geometry study needs shaders rather than an input image" }
    }

    val availability = OpenDlssNrSupport.availability(renderer)
    private val image = imagePath?.let { LoadedTexture.fromFile(it, renderer, VK_FORMAT_R8G8B8A8_UNORM) }
    val source = RenderToTexture(renderer) {
        width = size
        height = size
        useDepth = geometry
        format = VK_FORMAT_R16G16B16A16_SFLOAT
        colorTransferSrc = true
        debugName = "$name-source"
    }
    private val imageProvider = image?.let { texture ->
        object : HasImageViewArray, PreparesSampledImages {
            override val imageViewArray = [texture]

            override fun prepareSampledImagesFor(command: CommandBuffer) = texture.prepareSampledImagesFor(command)
        }
    }
    private val sampler = TextureSampler(renderer, 1, false, VK_FILTER_LINEAR)
    private val sourceModule =
        Module(
            parameters(renderer) {
                this.name = "$name-source"
                source.apply()
                depthTest = geometry
                depthWrite = geometry
                blend = false
                textureResolver = {
                    if (it.name == "image" && imageProvider != null) imageProvider to sampler else null
                }
                this.vertex = vertex
                this.fragment =
                    if (image == null) fragment
                    else
                        """
                        #version 460
                        layout(location=0) in vec2 uv;
                        layout(location=0) out vec4 color;
                        layout(binding=0) uniform sampler2D image;
                        layout(push_constant) uniform Study { vec4 frame; } pc;
                        void main() {
                            vec3 c=texture(image,uv).rgb;
                            c=mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));
                            color=vec4(c+pc.frame.w,1);
                        }
                        """
                            .trimIndent()
            },
        )
    private val sourceMesh = triangle(sourceModule, if (geometry) 3 + 39 * 36 else 3)
    val pass = if (availability.ready) OpenDlssNrPass(renderer, size, size) else null
    val output = RenderToTexture(renderer) {
        width = size * 3
        height = size + 64
        useDepth = false
        format = VK_FORMAT_R16G16B16A16_SFLOAT
        colorTransferSrc = true
        debugName = "$name-comparison"
    }
    private val compareModule = Module(
        parameters(renderer) {
            this.name = "$name-comparison"
            output.apply()
            depthTest = false
            depthWrite = false
            blend = false
            this.vertex = defaultVertex
            this.fragment = resource("compare.frag")
            textureResolver = {
                when (it.name) {
                    "source" -> source to sampler
                    "neural" -> (pass?.output ?: source) to sampler
                    else -> null
                }
            }
        },
    )
    private val compareMesh = triangle(compareModule)
    private var screen: ModulePointer? = null
    private var frame = 0L
    private var closed = false
    private val ownerScope = RunScope.current()
    /** Closes this study when its renderer tears its device down, so the boxes using it need not stop first. */
    private val closeWithRenderer = Runnable { close() }
    /** Whether [close] has run, by the box or by the renderer going away. */
    val isClosed: Boolean
        get() = closed

    var time = 0f
    var animate = true
    /** The editable capture box reserves scene time while recording. */
    var recording = false
    var controls = OpenDlssNrControls(style = 1, tone = 0f, structure = 10f)
    var grade = Vector4f(.65f, 1.1f, 1.4f, 1f)

    init {
        check(sourceModule.valid && compareModule.valid) { "NR study shader compilation failed" }
        source.modules["source"] = sourceModule.pointer()
        output.modules["compare"] = compareModule.pointer()
        renderer.beforeDeviceCleanup += closeWithRenderer
        if (attach) attach()
        ownerScope?.own(this)
    }

    private fun triangle(module: Module, vertices: Int = 3): Mesh = Mesh(renderer, module.pointer()).also {
        it.resize(vertices, 0, module.pointer())
        it.push.setMembers(module.fi.pushConstants.single().members)
        module.meshes["triangle"] = it
    }

    fun attach(): OpenDlssNrStudy {
        check(!closed)
        renderer.renderList.preTargets[name] = this
        screen = output.blitToScreen(name = name)
        return this
    }

    /** A compatible shader edit replaces the source at a frame boundary. Invalid edits retain it. */
    fun reload(
        vertex: String = if (geometry) resource("cubes.vert") else defaultVertex,
        fragment: String = if (geometry) resource("cubes.frag") else defaultFragment,
    ): Result<Unit> = runCatching {
        check(!closed && image == null) { "Shader reload requires the procedural source" }
        val errors = mutableListOf<String>()
        val v = Introspection(vertex, ShaderKind.VERTEX_SHADER, errorHandler = { message, _ -> errors += message })
        val f = Introspection(fragment, ShaderKind.FRAGMENT_SHADER, errorHandler = { message, _ -> errors += message })
        check(v.valid && f.valid) { errors.joinToString("\n") }
        val abi = graphicsShaderAbiCheck(sourceModule.vi, sourceModule.fi, v, f)
        check(abi.compatible) { abi.message }
        sourceModule.installPipelineUpdate(sourceModule.buildPipelineUpdate(v, f))
        pass?.reset("source shader edit")
    }

    /** Queue the next comparison frame for a lossless PNG after its GPU fence completes. */
    fun saveFrame(path: Path): CompletableFuture<Path> {
        check(!closed)
        return output.readPixelsAsync().thenApplyAsync { bytes ->
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            val pixels =
                FloatArray(output.width * output.height * 4) { java.lang.Float.float16ToFloat(buffer.getShort(it * 2)) }
            OpenDlssNrDiagnostics.png(pixels, output.width, output.height, path)
            path
        }
    }

    override fun render(command: CommandBuffer, index: Int) {
        if (closed) return
        if (geometry) {
            sourceMesh.push["sizeJitter"] = Vector4f(size.toFloat(), size.toFloat(), 0f, 0f)
            sourceMesh.push["times"] = Vector4f(time, time, 0f, 0f)
            sourceMesh.push["settings"] = Vector4f(0f, 1f, 0f, 0f)
        } else sourceMesh.push["frame"] = Vector4f(time, size.toFloat(), size.toFloat(), 0f)
        source.render(command, index)
        pass?.let {
            it.controls = controls
            it.evaluate(command, OpenDlssNrInputs(source, OpenDlssNrColorEncoding.LINEAR_LDR), OpenDlssNrFrame(frame))
        }
        compareMesh.push["grade"] = grade
        compareMesh.push["dimensions"] =
            Vector4f(output.width.toFloat(), output.height.toFloat(), 64f, if (pass == null) 0f else 1f)
        output.render(command, index)
        if (animate) time += 1f / 30f
        frame++
    }

    override fun close() {
        if (closed) return
        closed = true
        ownerScope?.disown(this)
        renderer.beforeDeviceCleanup.remove(closeWithRenderer)
        renderer.renderList.preTargets.remove(name, this)
        screen?.let { renderer.renderList.modules.remove(name, it) }
        pass?.close()
        renderer.cleanup += Runnable {
            screen?.current?.cleanup()
            sourceModule.cleanup()
            compareModule.cleanup()
            for (target in [source, output]) {
                target.imageViewArray.forEach { it.imageView.cleanup() }
                target.colorImageRefs.forEach { it?.cleanup() }
                target.depthImageView?.cleanup()
                target.depthImage?.cleanup()
                target.defaultSampler?.cleanup()
            }
            image?.cleanup()
            sampler.cleanup()
        }
    }

    companion object {
        private fun resource(file: String) =
            OpenDlssNrStudy::class.java.getResource("/afield/four/neuralrendering/$file")!!.readText()

        val defaultVertex: String
            get() = resource("study.vert")

        val defaultFragment: String
            get() = resource("study.frag")
    }
}
