package afield.four.modules

import afield.Named
import afield.Rect
import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.*
import afield.four.device.*
import afield.four.device.DescriptorSetLayout.SimpleDescriptorSetLayout
import afield.four.modules.Introspected.Bindable
import afield.four.targets.MultiPingPongRenderToTexture
import afield.four.targets.RenderToTexture
import afield.four.texture.HasDefaultTextureSampler
import afield.four.texture.HasImageView
import afield.four.texture.HasImageViewArray
import afield.four.texture.ImageView
import afield.four.texture.PreparesSampledImages
import afield.four.texture.TextureSampler
import afield.four.util.AutoDisposer
import afield.four.util.FIELD_PROBE_BUFFER_NAME
import afield.four.util.Options
import afield.four.util.ShaderFieldStatsBinding
import afield.four.util.ShaderFieldProbe
import afield.four.util.vkCheck
import afield.logger.lprintln
import afield.stdlib.Vec4
import afield.stdlib.f
import afield.stdlib.linalg.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR
import org.lwjgl.vulkan.VK13.*
import java.nio.ByteBuffer
import kotlin.reflect.KProperty

data class Parameters(
    val renderer: Renderer,
    var vertex: String = "",
    var fragment: String = "",
    var compute: String = "",
    var mesh: String = "",
    var task: String = "",
    var raygen: String = "",
    var miss: String = "",
    var closesthit: String = "",
    var anyhit: String = "",
    var intersection: String = "",
    var callable: String = "",
    var vertexIntrospection: Introspection? = null,
    var fragmentIntrospection: Introspection? = null,
    var computeIntrospection: Introspection? = null,
    var meshIntrospection: Introspection? = null,
    var taskIntrospection: Introspection? = null,
    var raygenIntrospection: Introspection? = null,
    var missIntrospection: Introspection? = null,
    var closesthitIntrospection: Introspection? = null,
    var anyhitIntrospection: Introspection? = null,
    var intersectionIntrospection: Introspection? = null,
    var callableIntrospection: Introspection? = null,
    var vertexSourceName: String = "vertex.glsl",
    var fragmentSourceName: String = "fragment.glsl",
    var computeSourceName: String = "compute.glsl",
    var meshSourceName: String = "mesh.glsl",
    var taskSourceName: String = "task.glsl",
    var raygenSourceName: String = "raygen.rgen",
    var missSourceName: String = "miss.rmiss",
    var closesthitSourceName: String = "closesthit.rchit",
    var anyhitSourceName: String = "anyhit.rahit",
    var intersectionSourceName: String = "intersection.rint",
    var callableSourceName: String = "callable.rcall",
    var vertexIncludeResolver: ShaderIncludeResolver? = null,
    var fragmentIncludeResolver: ShaderIncludeResolver? = null,
    var computeIncludeResolver: ShaderIncludeResolver? = null,
    var meshIncludeResolver: ShaderIncludeResolver? = null,
    var taskIncludeResolver: ShaderIncludeResolver? = null,
    var raygenIncludeResolver: ShaderIncludeResolver? = null,
    var missIncludeResolver: ShaderIncludeResolver? = null,
    var closesthitIncludeResolver: ShaderIncludeResolver? = null,
    var anyhitIncludeResolver: ShaderIncludeResolver? = null,
    var intersectionIncludeResolver: ShaderIncludeResolver? = null,
    var callableIncludeResolver: ShaderIncludeResolver? = null,
    var color: Int = 1,
    var colorFormats: List<Int> = emptyList(),
    var depth: Int = 1,
    var depthFormat: Int = Options.defaultDepthFormat,
    var primitiveType: Int = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
    var textureResolver: (Introspected.Texture) -> Pair<HasImageViewArray, TextureSampler>? = {
        lprintln("warning,graphics", "Cannot resolve texture ${it.name}")
        null
    },
    var uboResolver: (BufferParameters) -> BufferParameters? = {
        val lb = linkBuffer(it.name, it)
        lb.second
    },
    var ssboResolver: (BufferParameters) -> BufferParameters? = {
        val lb = linkBuffer(it.name, it)
        lb.second
    },
    var ssboSizer: (Introspected.UBO) -> Int? = { 0 },
    var imageResolver: (Introspected.Image) -> ImageView? = {
        lprintln("warning,graphics", "Cannot resolve image ${it.name}")
//        throw Exception("Cannot resolve image ${it.name}")
        null
    },
    var accelerationStructureResolver: (Introspected.AccelerationStructure) -> HasAccelerationStructure? = {
        val found = fromContextWithNameOrNull<HasAccelerationStructure>(it.name)
        if (found == null) lprintln("warning,graphics", "Cannot resolve acceleration structure ${it.name}")
        found
    },
    var rayTracingGroups: RayTracingGroups? = null,
    var maxRayRecursionDepth: Int = 1,
    var rayTracingPipelineFlags: Int = 0,
    /** Explicit pipeline opt-in for NV cluster geometry. Baseline pipelines need no extension. */
    var allowClusterAccelerationStructure: Boolean = false,
    var name: String = "unnamed module",
    var errorHandler: (String, Throwable?) -> Unit = { desc, it ->
        lprintln("error,graphics", "error $desc while creating Module: $it")
        it?.let { ex -> lprintln("error,graphics", ex.stackTraceToString()) }
    },
    var colorMask: Array<Boolean> = arrayOf(true, true, true, true),
    var blend: Boolean = true,
    var srcColor: Int = VK_BLEND_FACTOR_SRC_ALPHA,
    var dstColor: Int = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
    var colorBlendOp: Int = VK_BLEND_OP_ADD,
    var srcAlpha: Int = VK_BLEND_FACTOR_SRC_ALPHA,
    var dstAlpha: Int = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
    var alphaBlendOp: Int = VK_BLEND_OP_ADD,
    var depthTest: Boolean = true,
    var depthWrite: Boolean = true,
    var depthCompareOp: Int = VK_COMPARE_OP_LESS_OR_EQUAL,
    var viewport: Rect? = null,
    var viewportCount: Int = 1,
    var viewMask: Int = 0,
    var fillMode: Int = VK_POLYGON_MODE_FILL,
    var imageFormat: Int = renderer.swapChain.surfaceFormat.imageFormat,
    val labelColor: Vec4 = Vec4(0.15f, 0.45f, 0.85f, 1f),
    var fieldProbe: ShaderFieldProbe? = null,
    var fieldStats: ShaderFieldStatsBinding? = null

) {
    val triangles = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
    val lines = VK_PRIMITIVE_TOPOLOGY_LINE_LIST
    val points = VK_PRIMITIVE_TOPOLOGY_POINT_LIST
    val triangle_strip = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP
    val line_strip = VK_PRIMITIVE_TOPOLOGY_LINE_STRIP
    val filled = VK_POLYGON_MODE_FILL
    val wireframe = VK_POLYGON_MODE_LINE
    val depth_never = VK_COMPARE_OP_NEVER
    val depth_less = VK_COMPARE_OP_LESS
    val depth_equal = VK_COMPARE_OP_EQUAL
    val depth_less_or_equal = VK_COMPARE_OP_LESS_OR_EQUAL
    val depth_greater = VK_COMPARE_OP_GREATER
    val depth_not_equal = VK_COMPARE_OP_NOT_EQUAL
    val depth_greater_or_equal = VK_COMPARE_OP_GREATER_OR_EQUAL
    val depth_always = VK_COMPARE_OP_ALWAYS


    fun RenderToTexture.apply() {
        this@Parameters.color = colorFormats.size
        this@Parameters.colorFormats = colorFormats
        this@Parameters.depth = if (parameters.useDepth) 1 else 0
        this@Parameters.depthFormat = if (parameters.useDepth) parameters.depthFormat else VK_FORMAT_UNDEFINED
        this@Parameters.viewportCount = parameters.viewports.size.coerceAtLeast(1)
        this@Parameters.viewMask = parameters.multiviewViewMask
    }

    fun MultiPingPongRenderToTexture.apply() {
        val resolvedColorFormat = if (this.parameters.colorFormats.isNotEmpty()) this.parameters.colorFormats.first() else this.parameters.format
        this@Parameters.color = 1
        this@Parameters.colorFormats = listOf(resolvedColorFormat)
        this@Parameters.depth = if (parameters.useDepth) 1 else 0
        this@Parameters.depthFormat = if (parameters.useDepth) parameters.depthFormat else VK_FORMAT_UNDEFINED
        this@Parameters.viewportCount = parameters.viewports.size.coerceAtLeast(1)
        this@Parameters.viewMask = parameters.multiviewViewMask
    }

    fun textures(vararg t: Pair<String, HasImageView>) {
        val m = t.associate { it }
        this.textureResolver = {
            val found = m[it.name]
            if (found != null) {
                val provider = object : HasImageViewArray {
                    override val imageViewArray = listOf(found)
                }
                val sampler = (found as? HasDefaultTextureSampler)?.defaultSampler ?: TextureSampler(
                    renderer = fromContext(), sampledTextureMipLevels(found), true
                )
                provider to sampler
            } else null
        }
    }

    /**
     * Bind providers whose image views can change between frames (video ring buffers, ping-pong
     * targets, and similar resources). Unlike [textures], this keeps the provider itself live
     * instead of capturing one of its current image views while the module is configured.
     */
    fun textureArrays(vararg t: Pair<String, HasImageViewArray>) {
        val m = t.associate { it }
        this.textureResolver = { texture ->
            val provider = m[texture.name]
            if (provider != null) {
                val first = provider.imageViewArray.firstOrNull()
                    ?: error("Texture provider '${texture.name}' has no image views")
                val sampler = (provider as? HasDefaultTextureSampler)?.defaultSampler ?: TextureSampler(
                    renderer = fromContext(), sampledTextureMipLevels(first), true
                )
                provider to sampler
            } else null
        }
    }
}

private fun sampledTextureMipLevels(view: HasImageView): Int {
    return (view as? RenderToTexture)?.mipLevels
        ?: view.imageView.imageViewData.mipLevels
}

fun parameters(renderer: Renderer = fromContext(), build: Parameters.() -> Unit): Parameters {
    val p = Parameters(renderer)
    p.build()
    return p
}

internal fun Renderer.formatSupportsColorAttachmentBlend(format: Int): Boolean {
    MemoryStack.stackPush().use { stack ->
        val props = VkFormatProperties.calloc(stack)
        VK10.vkGetPhysicalDeviceFormatProperties(device.physicalDevice.vkPhysicalDevice, format, props)
        return (props.optimalTilingFeatures() and VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT) != 0
    }
}

interface Renderable {
    fun render(command: CommandBuffer, bounds: Rectanglef)
}

class FromUniform {
    operator fun getValue(inside: ModulePointer, property: KProperty<*>): Structure {
        return ModuleRebuild.access(inside, property.name, inside.current.liveUniformStructures[property.name] ?: throw IllegalArgumentException(
            " can't find '${property.name}' in this module, options are ${inside.current.liveUniformStructures.keys}"
        ))
    }
}

private fun sampledImageLayout(view: HasImageView): Int {
    val aspectMask = view.imageView.aspectMask
    val depthOrStencil = (aspectMask and (VK_IMAGE_ASPECT_DEPTH_BIT or VK_IMAGE_ASPECT_STENCIL_BIT)) != 0
    return if (depthOrStencil) VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL
    else VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
}

internal fun prepareSampledImagesForUniforms(
    uniforms: Iterable<UpdatesDescriptorSet>,
    command: CommandBuffer
) {
    uniforms.forEach { uniform ->
        when (uniform) {
            is Module.TexturePointer -> (uniform.texture as? PreparesSampledImages)?.prepareSampledImagesFor(command)
            is Module.TextureArrayPointer -> (uniform.texture as? PreparesSampledImages)?.prepareSampledImagesFor(command)
        }
    }
}

class ModulePointer(var current: Module) : Renderable {
    fun prepareSampledImagesFor(command: CommandBuffer) {
        if (current.valid && current.enabled) {
            prepareSampledImagesForUniforms(current.uniforms.values, command)
        }
    }

    override fun render(command: CommandBuffer, bounds: Rectanglef) {
        if (current.valid && current.enabled) current.render(command, bounds)
        else {
         //   println("module '${current.parameters.name}' not valid / not rendering")
        }
    }

    fun reload(
        p: Parameters, forceReresolve: Boolean = true, onError: (String, Throwable?) -> Unit = p.errorHandler
    ): Boolean {
        if (current.valid && p == current.parameters && !forceReresolve) return false
        p.errorHandler = onError

        val oUboResolver = p.uboResolver
        p.uboResolver = {
            val already = current.uniforms[it.name]
            if (already is Module.Uniform) already.source!! else oUboResolver(it)
        }

        val oSsboResolver = p.ssboResolver
        p.ssboResolver = {
            val prev = current.uniforms[it.name]
            if (prev == null) oSsboResolver(it) else {
                val prevSrc = prev.source
                if (prevSrc == null) oSsboResolver(it)
                else if (prevSrc.checkCompatibility(it) == false) oSsboResolver(it)
                else oSsboResolver(prevSrc)
            }
        }

        val oTextureResolver = p.textureResolver
        p.textureResolver = {
            if (forceReresolve) {
                val oo = oTextureResolver(it)
                if (oo != null) oo
                else when (val uniform = current.uniforms[it.name]) {
                    is Module.TexturePointer -> uniform.texture to uniform.sampler
                    is Module.TextureArrayPointer -> uniform.texture to uniform.sampler
                    else -> {
                        lprintln("warning,graphics", "couldn't transfer over texture ${it.name}, falling back to originating resolution")
                        oTextureResolver(it)
                    }
                }
            } else try {
                when (val uniform = current.uniforms[it.name]) {
                    is Module.TexturePointer -> uniform.texture to uniform.sampler
                    is Module.TextureArrayPointer -> uniform.texture to uniform.sampler
                    else -> throw IllegalStateException("unsupported texture uniform type ${uniform?.javaClass}")
                }
            } catch (_: Exception) {
                lprintln("warning,graphics", "couldn't transfer over texture ${it.name}, falling back to originating resolution")
                oTextureResolver(it)
            }
        }

        val m = Module(p)
        if (m.valid) {
            m.copyMeshesFrom(current)
            m.enabled = current.enabled
            ModuleRebuild.rebuilt(this, current.liveUniformStructures, m.liveUniformStructures)
            current = m
            return true
        }
        return false
    }

    operator fun plusAssign(m: Pair<String, Mesh>) {
        current.meshes[m.first] = m.second
        m.second.inside = this
    }

    operator fun plusAssign(m: Named<Mesh>) {
        current.meshes[m.name] = m.value
        m.value.inside = this
    }

    @JvmName("plusAssign_target")
    operator fun plusAssign(m: Pair<String, Target>) {
        current.after[m.first] = m.second
    }

    @JvmName("plusAssign_namedTarget")
    operator fun plusAssign(m: Named<Target>) {
        current.after[m.name] = m.value
    }

    @JvmName("plusAssign_beforeTarget")
    operator fun plusAssign(m: Pair<String, BeforeTarget>) {
        current.before[m.first] = m.second.target
    }

    @JvmName("plusAssign_namedBeforeTarget")
    operator fun plusAssign(m: Named<BeforeTarget>) {
        current.before[m.name] = m.value.target
    }

    fun enable() {
        current.enable()
    }

    fun disable() {
        current.disable()
    }

    override fun toString(): String = if (!current.valid) "Invalid Module" else "Module"
}

fun Module.pointer() = ModulePointer(this)

class Module(val parameters: Parameters) : Renderable {
    // Volatile: cleanup() clears it on a box thread while render workers read it.
    @Volatile
    var valid = false
    var enabled = true
    var meshes = linkedMapOf<String, Mesh>()
    var before = linkedMapOf<String, Target>()
    var after = linkedMapOf<String, Target>()

    val vi = parameters.vertexIntrospection
        ?: Introspection(
            parameters.vertex,
            ShaderKind.VERTEX_SHADER,
            errorHandler = parameters.errorHandler,
            sourceName = parameters.vertexSourceName,
            includeResolver = parameters.vertexIncludeResolver
        )
    val fi = parameters.fragmentIntrospection
        ?: Introspection(
            parameters.fragment,
            ShaderKind.FRAGMENT_SHADER,
            errorHandler = parameters.errorHandler,
            sourceName = parameters.fragmentSourceName,
            includeResolver = parameters.fragmentIncludeResolver
        )

    val namedInputs = vi.inputs.associateBy { it.name }
    val pushConstantSize = (vi.pushConstants + fi.pushConstants).maxOfOrNull { it.size } ?: 0

    var uniforms: MutableMap<String, UpdatesDescriptorSet> = mutableMapOf()
    var liveUniformStructures: MutableMap<String, Structure> = mutableMapOf()

    class Uniform(
        val name: String,
        override val binding: Bindable,
        override var source: BufferParameters? = null,
        val buffer: FrameBufferGroup,
        val kind: Int = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
    ) : UpdatesDescriptorSet {
        override fun pushDescriptorSet(
            command: CommandBuffer, pipelineLayout: Long, bindPoint: Int
        ) {
            buffer.clean(false, command)
            MemoryStack.stackPush().use { stack ->
                // One read of handle + range, refused by name if the buffer was released or its
                // renderer disposed: MoltenVK dereferences a pushed handle without validating it.
                val bound = buffer.get().descriptorBinding()
                val bufferInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(bound.buffer).offset(0)
                    .range(bound.range)

                val write = VkWriteDescriptorSet.calloc(1, stack).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(binding.binding).dstArrayElement(0).descriptorType(kind).descriptorCount(1)
                    .pBufferInfo(bufferInfo)

                vkCmdPushDescriptorSetKHR(
                    command.vkCommandBuffer, bindPoint, pipelineLayout, binding.set, write
                )
            }
        }
    }

    class TexturePointer(
        val name: String,
        override val binding: Bindable,
        override var source: BufferParameters? = null,
        val texture: HasImageViewArray,
        val sampler: TextureSampler,
    ) : UpdatesDescriptorSet {
        override fun pushDescriptorSet(
            command: CommandBuffer, pipelineLayout: Long, bindPoint: Int
        ) {
            if (!command.inRenderPassInstance) {
                (texture as? PreparesSampledImages)?.prepareSampledImagesFor(command)
            }
            MemoryStack.stackPush().use { stack ->
                val views = texture.imageViewArray
                require(views.isNotEmpty()) { "TexturePointer ${name} has no image views" }

                val imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(sampledImageLayout(views.first()))
                    .imageView(views.first().imageView.vkImageView)
                    .sampler(sampler.vkSampler)

                val write = VkWriteDescriptorSet.calloc(1, stack).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(binding.binding).dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imageInfo)

                vkCmdPushDescriptorSetKHR(
                    command.vkCommandBuffer, bindPoint, pipelineLayout, binding.set, write
                )
            }
        }
    }

    class TextureArrayPointer(
        val name: String,
        override val binding: Bindable,
        override var source: BufferParameters? = null,
        val texture: HasImageViewArray,
        val sampler: TextureSampler,
    ) : UpdatesDescriptorSet {
        override fun pushDescriptorSet(
            command: CommandBuffer, pipelineLayout: Long, bindPoint: Int
        ) {
            if (!command.inRenderPassInstance) {
                (texture as? PreparesSampledImages)?.prepareSampledImagesFor(command)
            }
            MemoryStack.stackPush().use { stack ->
                val views = texture.imageViewArray
                require(views.isNotEmpty()) { "TextureArrayPointer ${name} has no image views" }

                val infos = VkDescriptorImageInfo.calloc(views.size, stack)
                for (i in views.indices) {
                    infos[i].imageLayout(sampledImageLayout(views[i]))
                        .imageView(views[i].imageView.vkImageView).sampler(sampler.vkSampler)
                }

                val write = VkWriteDescriptorSet.calloc(1, stack).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(binding.binding).dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(views.size)
                    .pImageInfo(infos)

                vkCmdPushDescriptorSetKHR(
                    command.vkCommandBuffer, bindPoint, pipelineLayout, binding.set, write
                )
            }
        }
    }

    class ImagePointer(
        val name: String,
        override val binding: Bindable,
        override var source: BufferParameters? = null,
        val texture: HasImageView,
    ) : UpdatesDescriptorSet {
        override fun pushDescriptorSet(
            command: CommandBuffer, pipelineLayout: Long, bindPoint: Int
        ) {
            MemoryStack.stackPush().use { stack ->
                val imageInfo = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .imageView(texture.imageView.vkImageView)

                val write = VkWriteDescriptorSet.calloc(1, stack).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(binding.binding).dstArrayElement(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).pImageInfo(imageInfo)

                vkCmdPushDescriptorSetKHR(
                    command.vkCommandBuffer, bindPoint, pipelineLayout, binding.set, write
                )
            }
        }
    }

    lateinit var vertexInputAttributes: VkVertexInputAttributeDescription.Buffer
    lateinit var vertexInputBindings: VkVertexInputBindingDescription.Buffer
    lateinit var vertexInputState: VkPipelineVertexInputStateCreateInfo

    // Push descriptors still reference these layouts while the pipeline is in use.
    private val ownedDescriptorLayouts = mutableListOf<DescriptorSetLayout.SimpleDescriptorSetLayout>()
    var pipelineLayout: Long = 0L
    var pipeline: Long = 0L
    private val pipelineDisposeToken = AutoDisposer.register(this, "ModulePipeline(${parameters.name})", owner = parameters.renderer.device) { }

    var vsm: Long = 0L
    var fsm: Long = 0L

    data class PipelineUpdate(
        val vertexIntrospection: Introspection,
        val fragmentIntrospection: Introspection,
        val vertexShaderModule: Long,
        val fragmentShaderModule: Long,
        val pipeline: Long
    )

    init {
        try {
            if (!vi.valid || !fi.valid) throw IllegalStateException("Can't make a module without valid programs")

            vsm = createShaderModule(vi.code.bytecode()!!)
            fsm = createShaderModule(fi.code.bytecode()!!)

            // Name objects for tooling (if EXT_debug_utils is present)
            parameters.renderer.device.nameObject(vsm, VK10.VK_OBJECT_TYPE_SHADER_MODULE, "${parameters.name} VS")
            parameters.renderer.device.nameObject(fsm, VK10.VK_OBJECT_TYPE_SHADER_MODULE, "${parameters.name} FS")

            val setsNeeded = mutableMapOf<Int, MutableList<Int>>()

            // Build per-module sets for all sets used (including set 0)
            vi.ubos.forEach { setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            fi.ubos.forEach { setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            vi.ssbo.forEach { setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            fi.ssbo.forEach { setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            vi.textures.forEach { setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            fi.textures.forEach { setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            vi.images.forEach { setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            fi.images.forEach { setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }

            // --- Dedup and sort per-set bindings (fix #2)
            if (setsNeeded.isNotEmpty()) {
                val keys = setsNeeded.keys.toList()
                for (k in keys) {
                    setsNeeded[k] = setsNeeded.getValue(k).distinct().sorted().toMutableList()
                }
            }

            uniforms = mutableMapOf()
            liveUniformStructures = mutableMapOf()

            vi.ubos.forEach { liveUniformStructures[it.name] = Structure().setMembers(it.members) }
            fi.ubos.forEach { liveUniformStructures[it.name] = Structure().setMembers(it.members) }
            vi.ssbo.forEach { liveUniformStructures[it.name] = Structure().setMembers(it.members) }
            fi.ssbo.forEach { liveUniformStructures[it.name] = Structure().setMembers(it.members) }
            ModuleRebuild.created(liveUniformStructures)

            val layouts = mutableMapOf<Int, SimpleDescriptorSetLayout>()

            // Create layouts/sets for each set >= 1
            setsNeeded.entries.forEach { (set, bindingList) ->
                var where = 0
                val kinds = mutableListOf<Int>()
                val lens = mutableListOf<Int>()

                bindingList.forEach { bind ->
                    var kind = 0
                    var descriptorCount = 1

                    fun noteBinding(stageFlag: Int, descriptorType: Int, count: Int = 1) {
                        where = where or stageFlag
                        if (kind == 0) kind = descriptorType
                        else require(kind == descriptorType) {
                            "binding $bind in set $set resolves to multiple descriptor types"
                        }
                        descriptorCount = maxOf(descriptorCount, count)
                    }

                    vi.ubos.forEach {
                        if (it.set == set && it.binding == bind) {
                            noteBinding(VK_SHADER_STAGE_VERTEX_BIT, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        }
                    }
                    fi.ubos.forEach {
                        if (it.set == set && it.binding == bind) {
                            noteBinding(VK_SHADER_STAGE_FRAGMENT_BIT, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        }
                    }

                    vi.ssbo.forEach {
                        if (it.set == set && it.binding == bind) {
                            noteBinding(VK_SHADER_STAGE_VERTEX_BIT, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        }
                    }
                    fi.ssbo.forEach {
                        if (it.set == set && it.binding == bind) {
                            noteBinding(VK_SHADER_STAGE_FRAGMENT_BIT, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        }
                    }

                    vi.textures.forEach {
                        if (it.set == set && it.binding == bind) {
                            noteBinding(
                                VK_SHADER_STAGE_VERTEX_BIT,
                                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                maxOf(1, it.arrayCount)
                            )
                        }
                    }
                    fi.textures.forEach {                      // fix #1
                        if (it.set == set && it.binding == bind) {
                            noteBinding(
                                VK_SHADER_STAGE_FRAGMENT_BIT,
                                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                maxOf(1, it.arrayCount)
                            )
                        }
                    }

                    vi.images.forEach {                        // fix #1
                        if (it.set == set && it.binding == bind) {
                            noteBinding(VK_SHADER_STAGE_VERTEX_BIT, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        }
                    }
                    fi.images.forEach {                        // fix #1
                        if (it.set == set && it.binding == bind) {
                            noteBinding(VK_SHADER_STAGE_FRAGMENT_BIT, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        }
                    }

                    if (kind == 0) throw IllegalArgumentException(" couldn't find binding $bind for set $set in $bindingList")
                    kinds.add(kind)
                    lens.add(descriptorCount)
                }

                val layout = DescriptorSetLayout.SimpleDescriptorSetLayout(
                    parameters.renderer.device, kinds, set, bindingList, where, lens, pushDescriptor = true
                )
                layouts[set] = layout
                ownedDescriptorLayouts += layout
            }

            // UBOs (sets >= 1)
            setsNeeded.entries.forEach { (set, _) ->
                var kind = 0
                val tag = mutableListOf<Introspected.Bindable>()
                vi.ubos.forEach {
                    if (it.set == set) {
                        kind = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER; tag.add(it)
                    }
                }
                fi.ubos.forEach {
                    if (it.set == set) {
                        kind = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER; tag.add(it)
                    }
                }
                if (kind == 0) return@forEach

                tag.forEach {
                    var sz = (it as Introspected.UBO).size.toLong()
                    val bp = buffer(it, it.name) {
                        size = sz; source = it; uniform(); hosted()
                    }
                    val buf = parameters.uboResolver(bp) ?: throw IllegalArgumentException("can't link UBO ${it.name}")
                    try {
                        uniforms[it.name] = Uniform(it.name, it, source = bp, buf.toBuffer(parameters.renderer), kind)
                    } catch (_: NullPointerException) {
                        throw IllegalArgumentException("UBO ${it.name}, no longer resolves")
                    }
                }
            }

            // SSBOs (sets >= 1)
            setsNeeded.entries.forEach { (set, _) ->
                var kind = 0
                val tag = mutableListOf<Introspected.Bindable>()
                vi.ssbo.forEach {
                    if (it.set == set) {
                        kind = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; tag.add(it)
                    }
                }
                fi.ssbo.forEach {
                    if (it.set == set) {
                        kind = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; tag.add(it)
                    }
                }
                if (kind == 0) return@forEach

                tag.forEach {
                    var sz = (it as Introspected.UBO).size.toLong()
                    if (sz == 0L) sz = parameters.ssboSizer(it)?.toLong() ?: 0L
                    val bp = buffer(it, it.name) {
                        size = sz; source = it; storage(); hosted()
                    }
                    val buf = parameters.ssboResolver(bp)
                        ?: throw IllegalArgumentException("can't link SSBO buffer ${it.name}, resolver returned null")
                    try {
                        uniforms[it.name] = Uniform(it.name, it, source = bp, buf.toBuffer(parameters.renderer), kind)
                    } catch (_: NullPointerException) {
                        throw IllegalArgumentException("buffer ${it.name} resolved to null")
                    }
                }
            }

            // Sampled textures (sets >= 1, combined image sampler is fine for local sets)
            setsNeeded.entries.forEach { (set, _) ->
                var kind = 0
                val tag = mutableListOf<Introspected.Bindable>()
                vi.textures.forEach {
                    if (it.set == set) {
                        kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; tag.add(it)
                    }
                }
                fi.textures.forEach {
                    if (it.set == set) {
                        kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; tag.add(it)
                    }
                }
                if (kind == 0) return@forEach

                tag.forEach {
                    val (texture, sampler) = parameters.textureResolver(it as Introspected.Texture)!!
                    val views = texture.imageViewArray
                    require(views.isNotEmpty()) { "Texture ${it.name} resolved to empty image view array" }

                    if (it.arrayCount == 0) {
                        uniforms[it.name] = TexturePointer(it.name, it, source = null, texture, sampler)
                    } else {
                        require(views.size == it.arrayCount) {
                            "can't link texture resource ${it.name} of length ${views.size} but the shader expects ${it.arrayCount}"
                        }
                        uniforms[it.name] = TextureArrayPointer(it.name, it, source = null, texture, sampler)
                    }
                }
            }

            // Storage images (sets >= 1)
            setsNeeded.entries.forEach { (set, _) ->
                var kind = 0
                val tag = mutableListOf<Introspected.Bindable>()
                vi.images.forEach {
                    if (it.set == set) {
                        kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; tag.add(it)
                    }
                }
                fi.images.forEach {
                    if (it.set == set) {
                        kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; tag.add(it)
                    }
                }
                if (kind == 0) return@forEach

                tag.forEach {
                    val image = parameters.imageResolver(it as Introspected.Image)!!
                    uniforms[it.name] = ImagePointer(it.name, it, source = null, image)
                }
            }

            // --- Vertex input (unchanged)
            vertexInputAttributes = VkVertexInputAttributeDescription.calloc(vi.inputs.size)
            vertexInputBindings = VkVertexInputBindingDescription.calloc(vi.inputs.size)

            vi.inputs.forEachIndexed { index, input ->
                vertexInputAttributes[index].binding(index).location(input.location)
                    .format(vi.inputs[index].type.leftOrNull()!!.bufferFormat).offset(0)
                vertexInputBindings[index].binding(index).stride(vi.inputs[index].sizeInBytes.toInt())
                    .inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
            }

            vertexInputState = VkPipelineVertexInputStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                .pVertexBindingDescriptions(vertexInputBindings).pVertexAttributeDescriptions(vertexInputAttributes)

            MemoryStack.stackPush().use { stack ->
                val shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
                shaderStages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT).pName(stack.UTF8("main")).module(vsm)
                shaderStages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT).pName(stack.UTF8("main")).module(fsm)

                val ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(parameters.primitiveType)
                when (parameters.primitiveType) {
                    VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP, VK_PRIMITIVE_TOPOLOGY_LINE_STRIP, VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP_WITH_ADJACENCY -> ia.primitiveRestartEnable(
                        true
                    )
                }

                val dynamicViewportCount = parameters.viewportCount.coerceAtLeast(1)
                val vp = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(dynamicViewportCount)
                    .scissorCount(dynamicViewportCount)

                val rs = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(parameters.fillMode)
                    .cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_CLOCKWISE).lineWidth(1.0f)

                val ms = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)

                var ds: VkPipelineDepthStencilStateCreateInfo? = null
                if (parameters.depth > 0) {
                    ds = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(true)
                        .depthWriteEnable(parameters.depthWrite).depthCompareOp(parameters.depthCompareOp).depthBoundsTestEnable(false)
                        .stencilTestEnable(false)
                }

                val colorFormats = if (parameters.colorFormats.isNotEmpty()) parameters.colorFormats
                else List(parameters.color) { parameters.imageFormat }
                require(colorFormats.isNotEmpty()) { "Must specify at least one color attachment" }
                val colorCount = colorFormats.size

                val blendAtt = VkPipelineColorBlendAttachmentState.calloc(colorCount, stack)
                for (i in 0 until colorCount) {
                    val attachmentBlend = parameters.blend &&
                            parameters.renderer.formatSupportsColorAttachmentBlend(colorFormats[i])
                    blendAtt[i].colorWriteMask(
                            (if (parameters.colorMask[0]) VK_COLOR_COMPONENT_R_BIT else 0) or (if (parameters.colorMask[1]) VK_COLOR_COMPONENT_G_BIT else 0) or (if (parameters.colorMask[2]) VK_COLOR_COMPONENT_B_BIT else 0) or (if (parameters.colorMask[3]) VK_COLOR_COMPONENT_A_BIT else 0)
                        ).blendEnable(attachmentBlend).srcColorBlendFactor(parameters.srcColor)
                        .dstColorBlendFactor(parameters.dstColor).colorBlendOp(parameters.colorBlendOp)
                        .srcAlphaBlendFactor(parameters.srcAlpha).dstAlphaBlendFactor(parameters.dstAlpha)   // fix #3
                        .alphaBlendOp(parameters.alphaBlendOp)
                }
                val cb = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(blendAtt)

                val dynList = mutableListOf(
                    VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE
                )
                if (parameters.renderer.device.supportsExtendedDynamicState) {
                    dynList += listOf(
                        VK_DYNAMIC_STATE_PRIMITIVE_TOPOLOGY, VK_DYNAMIC_STATE_CULL_MODE, VK_DYNAMIC_STATE_FRONT_FACE
                    )
                }
                if (parameters.renderer.device.supportsExtendedDynamicState3) {
                    dynList += EXTExtendedDynamicState3.VK_DYNAMIC_STATE_DEPTH_CLAMP_ENABLE_EXT
                }
                val dynStates = stack.ints(*dynList.toIntArray())

                val dyn = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(dynStates)

                var vpcr: VkPushConstantRange.Buffer? = null
                if (pushConstantSize > 0) {
                    vpcr = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT).offset(0)
                        .size(pushConstantSize)
                }

                // ---- Build pipeline layout for module-owned sets (starting at 0)
                val highestSet = layouts.keys.maxOrNull() ?: -1
                val ppLayout = if (highestSet >= 0) stack.callocLong(highestSet + 1) else null
                if (highestSet >= 0) {
                    ppLayout?.let { buf ->
                        for (s in 0..highestSet) {
                            val layout = layouts[s] ?: throw IllegalArgumentException(
                                "Pipeline layout is missing descriptor set $s. Make sets contiguous starting at set=0."
                            )
                            buf.put(s, layout.vkDescriptorLayout)
                        }
                    }
                }

                val pli = VkPipelineLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                ppLayout?.let { pli.pSetLayouts(it) }
                vpcr?.let { pli.pPushConstantRanges(it) }

                val lp = stack.mallocLong(1)
                vkCheck(
                    vkCreatePipelineLayout(parameters.renderer.device.vkDevice, pli, null, lp),
                    "Failed to create pipeline layout"
                )
                pipelineLayout = lp[0]
                parameters.renderer.device.nameObject(
                    pipelineLayout, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, "${parameters.name} PipelineLayout"
                )

                val renderInfo = VkPipelineRenderingCreateInfoKHR.calloc(1, stack)
                    .sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR)

                val colorAttachmentFormats = stack.callocInt(colorCount)
                colorFormats.forEach { colorAttachmentFormats.put(it) }
                colorAttachmentFormats.flip()

                renderInfo.colorAttachmentCount(colorCount).pColorAttachmentFormats(colorAttachmentFormats)
                    .depthAttachmentFormat(parameters.depthFormat)
                    .stencilAttachmentFormat(0)
                    .viewMask(parameters.viewMask)

                val gp =
                    VkGraphicsPipelineCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                        .pStages(shaderStages).pVertexInputState(vertexInputState).pInputAssemblyState(ia)
                        .pViewportState(vp).pRasterizationState(rs).pMultisampleState(ms).pColorBlendState(cb)
                        .pDynamicState(dyn).layout(pipelineLayout).renderPass(0).pNext(renderInfo.address())

                ds?.let { gp.pDepthStencilState(it) }

                vkCheck(
                    vkCreateGraphicsPipelines(
                        parameters.renderer.device.vkDevice,
                        parameters.renderer.pipelineCache.vkPipelineCache,
                        gp,
                        null,
                        lp
                    ), "Error creating graphics pipeline"
                )
                this.pipeline = lp[0]
                pipelineDisposeToken.updateCleanup(snapshotPipelineCleanup())

                parameters.renderer.device.nameObject(
                    pipeline, VK10.VK_OBJECT_TYPE_PIPELINE, "${parameters.name} Pipeline"
                )

                liveUniformStructures.forEach { (name, structure) ->
                    val target = (uniforms[name] as Uniform).buffer
                    structure.proxying = target
                }
            }

            valid = true
        } catch (e: Exception) {
            // A Metal compile failure just before this is the real reason; say it here, where the box reads it.
            parameters.errorHandler("Module error: ${e.message}" + (afield.four.MetalShaderErrors.recent()?.let { "\n$it" } ?: ""), e)
        }
    }

    fun buildPipelineUpdate(
        newVertex: Introspection,
        newFragment: Introspection
    ): PipelineUpdate {
        var newVsm = 0L
        var newFsm = 0L
        var newPipeline = 0L
        try {
            newVsm = createShaderModule(newVertex.code.bytecode()!!)
            newFsm = createShaderModule(newFragment.code.bytecode()!!)

            parameters.renderer.device.nameObject(newVsm, VK10.VK_OBJECT_TYPE_SHADER_MODULE, "${parameters.name} VS update")
            parameters.renderer.device.nameObject(newFsm, VK10.VK_OBJECT_TYPE_SHADER_MODULE, "${parameters.name} FS update")

            MemoryStack.stackPush().use { stack ->
                val shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
                shaderStages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT).pName(stack.UTF8("main")).module(newVsm)
                shaderStages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT).pName(stack.UTF8("main")).module(newFsm)

                val ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(parameters.primitiveType)
                when (parameters.primitiveType) {
                    VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN,
                    VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP,
                    VK_PRIMITIVE_TOPOLOGY_LINE_STRIP,
                    VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY,
                    VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP_WITH_ADJACENCY -> ia.primitiveRestartEnable(true)
                }

                val dynamicViewportCount = parameters.viewportCount.coerceAtLeast(1)
                val vp = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(dynamicViewportCount)
                    .scissorCount(dynamicViewportCount)

                val rs = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(parameters.fillMode)
                    .cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_CLOCKWISE).lineWidth(1.0f)

                val ms = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)

                var ds: VkPipelineDepthStencilStateCreateInfo? = null
                if (parameters.depth > 0) {
                    ds = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(true)
                        .depthWriteEnable(parameters.depthWrite).depthCompareOp(parameters.depthCompareOp).depthBoundsTestEnable(false)
                        .stencilTestEnable(false)
                }

                val colorFormats = if (parameters.colorFormats.isNotEmpty()) parameters.colorFormats
                else List(parameters.color) { parameters.imageFormat }
                require(colorFormats.isNotEmpty()) { "Must specify at least one color attachment" }
                val colorCount = colorFormats.size

                val blendAtt = VkPipelineColorBlendAttachmentState.calloc(colorCount, stack)
                for (i in 0 until colorCount) {
                    val attachmentBlend = parameters.blend &&
                        parameters.renderer.formatSupportsColorAttachmentBlend(colorFormats[i])
                    blendAtt[i].colorWriteMask(
                        (if (parameters.colorMask[0]) VK_COLOR_COMPONENT_R_BIT else 0) or
                            (if (parameters.colorMask[1]) VK_COLOR_COMPONENT_G_BIT else 0) or
                            (if (parameters.colorMask[2]) VK_COLOR_COMPONENT_B_BIT else 0) or
                            (if (parameters.colorMask[3]) VK_COLOR_COMPONENT_A_BIT else 0)
                    ).blendEnable(attachmentBlend).srcColorBlendFactor(parameters.srcColor)
                        .dstColorBlendFactor(parameters.dstColor).colorBlendOp(parameters.colorBlendOp)
                        .srcAlphaBlendFactor(parameters.srcAlpha).dstAlphaBlendFactor(parameters.dstAlpha)
                        .alphaBlendOp(parameters.alphaBlendOp)
                }
                val cb = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(blendAtt)

                val dynList = mutableListOf(
                    VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE
                )
                if (parameters.renderer.device.supportsExtendedDynamicState) {
                    dynList += listOf(
                        VK_DYNAMIC_STATE_PRIMITIVE_TOPOLOGY, VK_DYNAMIC_STATE_CULL_MODE, VK_DYNAMIC_STATE_FRONT_FACE
                    )
                }
                if (parameters.renderer.device.supportsExtendedDynamicState3) {
                    dynList += EXTExtendedDynamicState3.VK_DYNAMIC_STATE_DEPTH_CLAMP_ENABLE_EXT
                }
                val dynStates = stack.ints(*dynList.toIntArray())

                val dyn = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(dynStates)

                val renderInfo = VkPipelineRenderingCreateInfoKHR.calloc(1, stack)
                    .sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR)

                val colorAttachmentFormats = stack.callocInt(colorCount)
                colorFormats.forEach { colorAttachmentFormats.put(it) }
                colorAttachmentFormats.flip()

                renderInfo.colorAttachmentCount(colorCount).pColorAttachmentFormats(colorAttachmentFormats)
                    .depthAttachmentFormat(parameters.depthFormat)
                    .stencilAttachmentFormat(0)
                    .viewMask(parameters.viewMask)

                val gp =
                    VkGraphicsPipelineCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                        .pStages(shaderStages).pVertexInputState(vertexInputState).pInputAssemblyState(ia)
                        .pViewportState(vp).pRasterizationState(rs).pMultisampleState(ms).pColorBlendState(cb)
                        .pDynamicState(dyn).layout(pipelineLayout).renderPass(0).pNext(renderInfo.address())

                ds?.let { gp.pDepthStencilState(it) }

                val lp = stack.mallocLong(1)
                synchronized(parameters.renderer.pipelineCache) {
                    vkCheck(
                        vkCreateGraphicsPipelines(
                            parameters.renderer.device.vkDevice,
                            parameters.renderer.pipelineCache.vkPipelineCache,
                            gp,
                            null,
                            lp
                        ), "Error creating graphics pipeline update"
                    )
                }
                newPipeline = lp[0]
                parameters.renderer.device.nameObject(
                    newPipeline, VK10.VK_OBJECT_TYPE_PIPELINE, "${parameters.name} Pipeline update"
                )
            }

            return PipelineUpdate(newVertex, newFragment, newVsm, newFsm, newPipeline)
        } catch (t: Throwable) {
            destroyPipelineHandles(newPipeline, 0L, newVsm, newFsm)
            throw t
        }
    }

    fun installPipelineUpdate(update: PipelineUpdate) {
        val oldPipeline = pipeline
        val oldVsm = vsm
        val oldFsm = fsm

        pipeline = update.pipeline
        vsm = update.vertexShaderModule
        fsm = update.fragmentShaderModule

        parameters.vertexIntrospection = update.vertexIntrospection
        parameters.fragmentIntrospection = update.fragmentIntrospection

        pipelineDisposeToken.updateCleanup(snapshotPipelineCleanup())

        parameters.renderer.cleanup.add(Runnable {
            destroyPipelineHandles(oldPipeline, 0L, oldVsm, oldFsm)
        })
    }

    fun destroyUnusedPipelineUpdate(update: PipelineUpdate) {
        destroyPipelineHandles(update.pipeline, 0L, update.vertexShaderModule, update.fragmentShaderModule)
    }

    override fun render(command: CommandBuffer, bounds: Rectanglef) {
        if (!valid || !enabled) return
        MemoryStack.stackPush().use { stack ->
            val cmd = command.vkCommandBuffer
            val renderer = parameters.renderer
            val frameIndex = renderer.currentImage
            val profiler = renderer.getProfiler()
            val fieldProbe = parameters.fieldProbe?.takeIf { FIELD_PROBE_BUFFER_NAME in uniforms }

            fieldProbe?.beforeShaderUse(renderer, frameIndex)

            // Begin label for the whole module render (if EXT_debug_utils is available)
            val labelColor = stack.floats(
                parameters.labelColor.x.f,
                parameters.labelColor.y.f,
                parameters.labelColor.z.f,
                parameters.labelColor.w.f
            )
            command.beginLabel(parameters.name, labelColor)

            try {
                val marks = if (profiler != null) Renderer.ModuleMarks(parameters.name) else null
                var psScope: GpuProfiler.PipeStatScope? = null
                var perfScope: GpuProfiler.PerfScope? = null

                if (profiler != null) {
                    marks!!.tsBegin = profiler.writeTimestamp2(cmd, frameIndex, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT)
                    psScope = profiler.beginPipelineStats(cmd, frameIndex)
                    perfScope = profiler.beginPerfCounters(cmd, frameIndex)
                }

                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline)

                VK13.vkCmdSetDepthTestEnable(cmd, if (parameters.depth == 0) false else parameters.depthTest)
                if (renderer.device.supportsExtendedDynamicState) {
                    VK13.vkCmdSetPrimitiveTopology(cmd, parameters.primitiveType)
                    VK13.vkCmdSetCullMode(cmd, VK_CULL_MODE_NONE)
                    VK13.vkCmdSetFrontFace(cmd, VK_FRONT_FACE_CLOCKWISE)
                }
                if (renderer.device.supportsExtendedDynamicState3) {
                    EXTExtendedDynamicState3.vkCmdSetDepthClampEnableEXT(cmd, false)
                }

                val pushConstantBuffer = if (pushConstantSize > 0) stack.calloc(pushConstantSize) else stack.malloc(0)

                uniforms.values.forEach { upd ->
                    upd.pushDescriptorSet(command, pipelineLayout, VK_PIPELINE_BIND_POINT_GRAPHICS)
                }

                liveUniformStructures.forEach { (name, structure) ->
                    val target = (uniforms[name] as Uniform).buffer
                    structure.proxying = target
                    if (structure.dirty || structure.bindings.size>0) {
                        target.mapAndWrite_bytes {
                            structure.writeToBuffer(this, command) {
                                throw MissingRefException("risk of GPU null ref `$it`")
                            }
                        }
                    }
                    target.clean(false, command)
                }

                meshes.forEach { (_, mesh) ->
                    mesh.draw(this, command, pushConstantBuffer)
                }

                after.forEach { (_, target) ->
                    target.render(command, renderer.currentImage)
                }

                fieldProbe?.let {
                    graphicsHostReadBarrier(command, it.bufferForImage(renderer, frameIndex))
                    it.afterShaderUse(renderer, frameIndex)
                }

                if (profiler != null) {
                    profiler.endPipelineStats(cmd, psScope)
                    profiler.endPerfCounters(cmd, frameIndex, perfScope)
                    marks!!.tsEnd = profiler.writeTimestamp2(cmd, frameIndex, VK_PIPELINE_STAGE_2_BOTTOM_OF_PIPE_BIT)
                    psScope?.let { marks.pipeStatsIndex = it.index }
                    perfScope?.let { marks.perfIndex = it.index }
                    renderer.registerModuleMarks(frameIndex, marks)
                }
            } finally {
                command.endLabel() // close the module render region
            }
        }
    }

    fun enable() {
        enabled = true
    }

    fun disable() {
        enabled = false
    }

    fun copyMeshesFrom(old: Module) {
        old.meshes.entries.removeIf {
            meshes[it.key] = it.value
            it.value.resize(it.value.numVert, it.value.numTriangle, inside = this.pointer())
            true
        }
        before.putAll(old.before)
        after.putAll(old.after)
    }

    private fun snapshotPipelineCleanup(): () -> Unit {
        val dev = parameters.renderer.device.vkDevice
        val p = pipeline
        val layout = pipelineLayout
        val sets = ownedDescriptorLayouts.toList()
        val vsmHandle = vsm
        val fsmHandle = fsm
        return {
            if (p != 0L) VK10.vkDestroyPipeline(dev, p, null)
            if (layout != 0L) VK10.vkDestroyPipelineLayout(dev, layout, null)
            if (vsmHandle != 0L) VK10.vkDestroyShaderModule(dev, vsmHandle, null)
            if (fsmHandle != 0L) VK10.vkDestroyShaderModule(dev, fsmHandle, null)
            sets.forEach { it.cleanup() }
        }
    }

    /**
     * Release this module's pipeline, pipeline layout, shader modules and descriptor layouts.
     *
     * The module stops rendering at once ([valid] goes false, so no *new* frame binds it), but
     * while its renderer is alive the handles are destroyed through the renderer's retirement
     * queue, after the frames that may have recorded or be executing them have retired. Destroying
     * them here, as this used to, was a SIGSEGV on a render worker still recording a bind
     * (`MVKCmdBindGraphicsPipeline`, reproduced 1 of 1), and a `VUID-vkDestroyPipeline-pipeline-00765`
     * for frames in flight. After the renderer has closed (its device idle) they go at once.
     * See `dev_docs/renderer_teardown_order.md`, "Immediate cleanup mid-frame".
     */
    fun cleanup() {
        if (!pipelineDisposeToken.disable()) return
        valid = false
        val renderer = parameters.renderer
        if (renderer.isClosed) destroyPipelineNow()
        else renderer.cleanup.add(Runnable { destroyPipelineNow() })
    }

    private fun destroyPipelineNow() {
        destroyPipelineHandles(pipeline, pipelineLayout, vsm, fsm)
        pipeline = 0L
        pipelineLayout = 0L
        vsm = 0L
        fsm = 0L
        ownedDescriptorLayouts.forEach { it.cleanup() }
        ownedDescriptorLayouts.clear()
    }

    private fun destroyPipelineHandles(pipelineHandle: Long, pipelineLayoutHandle: Long, vertexShaderModule: Long, fragmentShaderModule: Long) {
        if (pipelineHandle != 0L) {
            VK10.vkDestroyPipeline(parameters.renderer.device.vkDevice, pipelineHandle, null)
        }
        if (pipelineLayoutHandle != 0L) {
            VK10.vkDestroyPipelineLayout(parameters.renderer.device.vkDevice, pipelineLayoutHandle, null)
        }
        if (vertexShaderModule != 0L) {
            VK10.vkDestroyShaderModule(parameters.renderer.device.vkDevice, vertexShaderModule, null)
        }
        if (fragmentShaderModule != 0L) {
            VK10.vkDestroyShaderModule(parameters.renderer.device.vkDevice, fragmentShaderModule, null)
        }
    }

    /**
     * Hands SPIR-V to Vulkan without staging it on LWJGL's 64 KiB [MemoryStack].
     *
     * Generated shaders routinely cross that boundary. Shaderc already returns a
     * native, word-aligned buffer, so the common path can point Vulkan at it
     * directly. A non-direct caller is copied into explicitly managed native
     * memory; only the two small Vulkan structs remain on the stack.
     */
    private fun createShaderModule(code: ByteBuffer): Long {
        val direct = if (code.isDirect) null else MemoryUtil.memAlloc(code.remaining()).put(code.duplicate()).flip()
        try {
            MemoryStack.stackPush().use { stack ->
                val ci = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(direct ?: code)
                val lp = stack.mallocLong(1)
                vkCheck(
                    vkCreateShaderModule(parameters.renderer.device.vkDevice, ci, null, lp),
                    "Failed to create shader module"
                )
                return lp[0]
            }
        } finally {
            if (direct != null) MemoryUtil.memFree(direct)
        }
    }

    operator fun plusAssign(m: Pair<String, Mesh>) {
        meshes[m.first] = m.second
        m.second.inside = this.pointer()
    }

    @JvmName("plusAssign_target")
    operator fun plusAssign(m: Pair<String, Target>) {
        after[m.first] = m.second
    }

    @JvmName("plusAssign_beforeTarget")
    operator fun plusAssign(m: Pair<String, BeforeTarget>) {
        before[m.first] = m.second.target
    }

}

@JvmInline
value class BeforeTarget(val target: Target)

fun before(a: (CommandBuffer, Int) -> Unit) = BeforeTarget(object : Target {
    override fun render(command: CommandBuffer, index: Int) = a(command, index)
})

// ---------- helper (unchanged) ----------

fun parseCompilerException(exception: CompilerError, accept: (String, Int, String) -> Unit) {
    val message = exception.message
    val regex = """:(\d+):""".toRegex()
    message.split("\n").forEach {
        val f = regex.find(it)
        if (f != null) {
            val line = f.groupValues[1].toInt()
            val (file, msg) = it.split(regex)
            accept(file.split("/").last(), line, msg)
        } else {
            if (it.trim().isNotEmpty()) accept("", -1, it)
        }
    }
}
