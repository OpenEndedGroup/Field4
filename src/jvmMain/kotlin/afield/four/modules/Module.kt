package afield.four.modules

import afield.Named
import afield.Rect
import afield.four.buffers.BufferParameters
import afield.four.Renderer
import afield.four.buffers.buffer
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.MissingRefException
import afield.four.buffers.Structure
import afield.four.device.*
import afield.four.device.DescriptorSetLayout.SimpleDescriptorSetLayout
import afield.four.buffers.linkBuffer
import afield.four.modules.Introspected.Bindable
import afield.four.targets.RenderToTexture
import afield.four.texture.ImageView
import afield.four.texture.TextureSampler
import afield.four.util.Options
import afield.four.util.AutoDisposer
import afield.four.util.vkCheck
import afield.logger.lprintln
import afield.stdlib.Vec4
import afield.stdlib.d
import afield.stdlib.f
import org.joml.primitives.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR
import org.lwjgl.vulkan.EXTExtendedDynamicState3
import java.nio.ByteBuffer
import kotlin.reflect.KProperty

data class Parameters(
    val renderer: Renderer,
    var vertex: String = "",
    var fragment: String = "",
    var compute: String = "",
    var mesh: String = "",
    var task: String = "",
    var color: Int = 1,
    var colorFormats: List<Int> = emptyList(),
    var depth: Int = 1,
    var depthFormat: Int = Options.defaultDepthFormat,
    var primitiveType: Int = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
    var textureResolver: (Introspected.Texture) -> Pair<Array<ImageView>, TextureSampler>? = {
        throw Exception("Cannot resolve texture ${it.name}")
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
        throw Exception("Cannot resolve image ${it.name}")
    },
    var name: String = "unnamed module",
    var errorHandler: (String, Throwable?) -> Unit = { desc, it ->
        println(" error $desc while creating Module:\n\t$it")
        it?.printStackTrace()
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
    var viewport: Rect? = null,
    var fillMode: Int = VK_POLYGON_MODE_FILL,
    var imageFormat: Int = renderer.swapChain.surfaceFormat.imageFormat,
    val labelColor: Vec4 = Vec4(0.15f, 0.45f, 0.85f, 1f)

) {
    val triangles = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
    val lines = VK_PRIMITIVE_TOPOLOGY_LINE_LIST
    val points = VK_PRIMITIVE_TOPOLOGY_POINT_LIST
    val triangle_strip = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP
    val line_strip = VK_PRIMITIVE_TOPOLOGY_LINE_STRIP
    val filled = VK_POLYGON_MODE_FILL
    val wireframe = VK_POLYGON_MODE_LINE



    fun RenderToTexture.apply() {
        this@Parameters.color = colorFormats.size
        this@Parameters.colorFormats = colorFormats
        this@Parameters.depth = if (parameters.useDepth) 1 else 0
        this@Parameters.depthFormat = parameters.depthFormat
    }
}

fun parameters(renderer: Renderer = fromContext(), build: Parameters.() -> Unit): Parameters {
    val p = Parameters(renderer)
    p.build()
    return p
}

interface Renderable {
    fun render(command: CommandBuffer, bounds: Rectanglef)
}

class FromUniform {
    operator fun getValue(inside: ModulePointer, property: KProperty<*>): Structure {
        return inside.current.liveUniformStructures[property.name]
            ?: throw IllegalArgumentException(
                " can't find '${property.name}' in this module, options are ${inside.current.liveUniformStructures.keys}"
            )
    }
}

class ModulePointer(var current: Module) : Renderable {
    override fun render(command: CommandBuffer, bounds: Rectanglef) {
        if (current.valid) current.render(command, bounds)
        else println("module '${current.parameters.name}' not valid / not rendering")
    }

    fun reload(
        p: Parameters,
        forceReresolve: Boolean = true,
        onError: (String, Throwable?) -> Unit = p.errorHandler
    ): Boolean {
        if (current.valid && p == current.parameters && !forceReresolve) return false
        p.errorHandler = onError

        val oUboResolver = p.uboResolver
        p.uboResolver = {
            val already = current.uniforms[it.name]
            if (already is Module.Uniform) already.source!! else oUboResolver(it)
        }

        val oSsboResolver = p.ssboResolver
        p.ssboResolver = { oSsboResolver(it) }

        val oTextureResolver = p.textureResolver
        p.textureResolver = {
            if (forceReresolve) oTextureResolver(it)
            else try {
                val tp = current.uniforms[it.name] as Module.TexturePointer
                arrayOf(tp.texture) to tp.sampler
            } catch (_: Exception) {
                lprintln(" couldn't transfer over texture ${it.name}, falling back to originating resolution")
                oTextureResolver(it)
            }
        }

        val m = Module(p)
        if (m.valid) {
            m.copyMeshesFrom(current)
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

    override fun toString(): String = if (!current.valid) "Invalid Module" else "Module"
}

fun Module.pointer() = ModulePointer(this)

class Module(val parameters: Parameters) : Renderable {
    var valid = false
    var meshes = mutableMapOf<String, Mesh>()

    val vi = Introspection(parameters.vertex, ShaderKind.VERTEX_SHADER, errorHandler = parameters.errorHandler)
    val fi = Introspection(parameters.fragment, ShaderKind.FRAGMENT_SHADER, errorHandler = parameters.errorHandler)

    val namedInputs = vi.inputs.associateBy { it.name }
    val pushConstantSize =
        (vi.pushConstants.associateBy { it.name } + fi.pushConstants.associateBy { it.name }).values.sumOf { it.size }

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
            command: CommandBuffer,
            pipelineLayout: Long,
            bindPoint: Int
        ) {
            MemoryStack.stackPush().use { stack ->
                val targetBuffer = buffer.get()
                val bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(targetBuffer.buffer)
                    .offset(0)
                    .range(targetBuffer.requestedSize)

                val write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(binding.binding)
                    .dstArrayElement(0)
                    .descriptorType(kind)
                    .descriptorCount(1)
                    .pBufferInfo(bufferInfo)

                vkCmdPushDescriptorSetKHR(
                    command.vkCommandBuffer,
                    bindPoint,
                    pipelineLayout,
                    binding.set,
                    write
                )
            }
        }
    }

    class TexturePointer(
        val name: String,
        override val binding: Bindable,
        override var source: BufferParameters? = null,
        val texture: ImageView,
        val sampler: TextureSampler,
    ) : UpdatesDescriptorSet {
        override fun pushDescriptorSet(
            command: CommandBuffer,
            pipelineLayout: Long,
            bindPoint: Int
        ) {
            MemoryStack.stackPush().use { stack ->
                val imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(texture.vkImageView)
                    .sampler(sampler.vkSampler)

                val write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(binding.binding)
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo)

                vkCmdPushDescriptorSetKHR(
                    command.vkCommandBuffer,
                    bindPoint,
                    pipelineLayout,
                    binding.set,
                    write
                )
            }
        }
    }

    class TextureArrayPointer(
        val name: String,
        override val binding: Bindable,
        override var source: BufferParameters? = null,
        val texture: Array<ImageView>,
        val sampler: TextureSampler,
    ) : UpdatesDescriptorSet {
        override fun pushDescriptorSet(
            command: CommandBuffer,
            pipelineLayout: Long,
            bindPoint: Int
        ) {
            MemoryStack.stackPush().use { stack ->
                val infos = VkDescriptorImageInfo.calloc(texture.size, stack)
                for (i in texture.indices) {
                    infos[i]
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .imageView(texture[i].vkImageView)
                        .sampler(sampler.vkSampler)
                }

                val write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(binding.binding)
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(texture.size)
                    .pImageInfo(infos)

                vkCmdPushDescriptorSetKHR(
                    command.vkCommandBuffer,
                    bindPoint,
                    pipelineLayout,
                    binding.set,
                    write
                )
            }
        }
    }

    class ImagePointer(
        val name: String,
        override val binding: Bindable,
        override var source: BufferParameters? = null,
        val texture: ImageView,
    ) : UpdatesDescriptorSet {
        override fun pushDescriptorSet(
            command: CommandBuffer,
            pipelineLayout: Long,
            bindPoint: Int
        ) {
            MemoryStack.stackPush().use { stack ->
                val imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .imageView(texture.vkImageView)

                val write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(binding.binding)
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo)

                vkCmdPushDescriptorSetKHR(
                    command.vkCommandBuffer,
                    bindPoint,
                    pipelineLayout,
                    binding.set,
                    write
                )
            }
        }
    }

    lateinit var vertexInputAttributes: VkVertexInputAttributeDescription.Buffer
    lateinit var vertexInputBindings: VkVertexInputBindingDescription.Buffer
    lateinit var vertexInputState: VkPipelineVertexInputStateCreateInfo

    var pipelineLayout: Long = 0L
    var pipeline: Long = 0L
    private val pipelineDisposeToken = AutoDisposer.register(this, "ModulePipeline(${parameters.name})") { }

    var vsm: Long = 0L
    var fsm: Long = 0L

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

            val layouts = mutableMapOf<Int, SimpleDescriptorSetLayout>()

            // Create layouts/sets for each set >= 1
            setsNeeded.entries.forEach { (set, bindingList) ->
                var where = 0
                val tag = mutableListOf<Introspected.Bindable>()
                val kinds = mutableListOf<Int>()
                val lens = mutableListOf<Int>()

                bindingList.forEach { bind ->
                    var kind = 0

                    vi.ubos.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_VERTEX_BIT
                            kind = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                            tag.add(it); lens.add(1)
                        }
                    }
                    fi.ubos.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                            kind = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                            tag.add(it); lens.add(1)
                        }
                    }

                    vi.ssbo.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_VERTEX_BIT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                            tag.add(it); lens.add(1)
                        }
                    }
                    fi.ssbo.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                            tag.add(it); lens.add(1)
                        }
                    }

                    vi.textures.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_VERTEX_BIT
                            kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                            tag.add(it); lens.add(maxOf(1, it.arrayCount))
                        }
                    }
                    fi.textures.forEach {                      // fix #1
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                            kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                            tag.add(it); lens.add(maxOf(1, it.arrayCount))
                        }
                    }

                    vi.images.forEach {                        // fix #1
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_VERTEX_BIT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                            tag.add(it); lens.add(1)
                        }
                    }
                    fi.images.forEach {                        // fix #1
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                            tag.add(it); lens.add(1)
                        }
                    }

                    if (kind == 0) throw IllegalArgumentException(" couldn't find binding $bind for set $set in $bindingList")
                    kinds.add(kind)
                }

                val layout = DescriptorSetLayout.SimpleDescriptorSetLayout(
                    parameters.renderer.device, kinds, set, bindingList, where, lens, pushDescriptor = true
                )
                layouts[set] = layout
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
                    val buf = parameters.uboResolver(bp)
                        ?: throw IllegalArgumentException("can't link UBO ${it.name}")
                    try {
                        uniforms[it.name] = Uniform(it.name, it, source = bp, buf.toBuffer(), kind)
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
                        uniforms[it.name] = Uniform(it.name, it, source = bp, buf.buffer, kind)
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
                    if (it.arrayCount == 0) {
                        uniforms[it.name] =
                            TexturePointer(it.name, it, source = null, texture[0], sampler)
                    } else {
                        require(texture.size == it.arrayCount) {
                            "can't link texture resource ${it.name} of length ${texture.size} but the shader expects ${it.arrayCount}"
                        }
                        uniforms[it.name] =
                            TextureArrayPointer(it.name, it, source = null, texture, sampler)
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
                vertexInputAttributes[index]
                    .binding(index)
                    .location(input.location)
                    .format(vi.inputs[index].type.leftOrNull()!!.bufferFormat)
                    .offset(0)
                vertexInputBindings[index]
                    .binding(index)
                    .stride(vi.inputs[index].sizeInBytes.toInt())
                    .inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
            }

            vertexInputState = VkPipelineVertexInputStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                .pVertexBindingDescriptions(vertexInputBindings)
                .pVertexAttributeDescriptions(vertexInputAttributes)

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
                    VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN,
                    VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP,
                    VK_PRIMITIVE_TOPOLOGY_LINE_STRIP,
                    VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY,
                    VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP_WITH_ADJACENCY -> ia.primitiveRestartEnable(true)
                }

                val vp = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1).scissorCount(1)

                val rs = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .polygonMode(parameters.fillMode)
                    .cullMode(VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_CLOCKWISE)
                    .lineWidth(1.0f)

                val ms = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)

                var ds: VkPipelineDepthStencilStateCreateInfo? = null
                if (parameters.depth > 0) {
                    ds = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                        .depthTestEnable(true).depthWriteEnable(true)
                        .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                        .depthBoundsTestEnable(false).stencilTestEnable(false)
                }

                val colorFormats = if (parameters.colorFormats.isNotEmpty()) parameters.colorFormats
                else List(parameters.color) { parameters.imageFormat }
                require(colorFormats.isNotEmpty()) { "Must specify at least one color attachment" }
                val colorCount = colorFormats.size

                val blendAtt = VkPipelineColorBlendAttachmentState.calloc(colorCount, stack)
                for (i in 0 until colorCount) {
                    blendAtt[i]
                        .colorWriteMask(
                            (if (parameters.colorMask[0]) VK_COLOR_COMPONENT_R_BIT else 0) or
                                    (if (parameters.colorMask[1]) VK_COLOR_COMPONENT_G_BIT else 0) or
                                    (if (parameters.colorMask[2]) VK_COLOR_COMPONENT_B_BIT else 0) or
                                    (if (parameters.colorMask[3]) VK_COLOR_COMPONENT_A_BIT else 0)
                        )
                        .blendEnable(parameters.blend)
                        .srcColorBlendFactor(parameters.srcColor)
                        .dstColorBlendFactor(parameters.dstColor)
                        .colorBlendOp(parameters.colorBlendOp)
                        .srcAlphaBlendFactor(parameters.srcAlpha)
                        .dstAlphaBlendFactor(parameters.dstAlpha)   // fix #3
                        .alphaBlendOp(parameters.alphaBlendOp)
                }
                val cb = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .pAttachments(blendAtt)

                val dynList = mutableListOf(
                    VK_DYNAMIC_STATE_VIEWPORT,
                    VK_DYNAMIC_STATE_SCISSOR,
                    VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE
                )
                if (parameters.renderer.device.supportsExtendedDynamicState) {
                    dynList += listOf(
                        VK_DYNAMIC_STATE_PRIMITIVE_TOPOLOGY,
                        VK_DYNAMIC_STATE_CULL_MODE,
                        VK_DYNAMIC_STATE_FRONT_FACE
                    )
                }
                if (parameters.renderer.device.supportsExtendedDynamicState3) {
                    dynList += EXTExtendedDynamicState3.VK_DYNAMIC_STATE_DEPTH_CLAMP_ENABLE_EXT
                }
                val dynStates = stack.ints(*dynList.toIntArray())

                val dyn = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(dynStates)

                var vpcr: VkPushConstantRange.Buffer? = null
                if (pushConstantSize > 0) {
                    vpcr = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT)
                        .offset(0).size(pushConstantSize)
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

                val pli = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                ppLayout?.let { pli.pSetLayouts(it) }
                vpcr?.let { pli.pPushConstantRanges(it) }

                val lp = stack.mallocLong(1)
                vkCheck(
                    vkCreatePipelineLayout(parameters.renderer.device.vkDevice, pli, null, lp),
                    "Failed to create pipeline layout"
                )
                pipelineLayout = lp[0]
                parameters.renderer.device.nameObject(
                    pipelineLayout,
                    VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT,
                    "${parameters.name} PipelineLayout"
                )

                val renderInfo = VkPipelineRenderingCreateInfoKHR.calloc(1, stack)
                    .sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR)

                val colorAttachmentFormats = stack.callocInt(colorCount)
                colorFormats.forEach { colorAttachmentFormats.put(it) }
                colorAttachmentFormats.flip()

                renderInfo
                    .colorAttachmentCount(colorCount)
                    .pColorAttachmentFormats(colorAttachmentFormats)
                    .depthAttachmentFormat(if (parameters.depth > 0) parameters.depthFormat else VK_FORMAT_UNDEFINED)
                    .stencilAttachmentFormat(0)

                val gp = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(shaderStages)
                    .pVertexInputState(vertexInputState)
                    .pInputAssemblyState(ia)
                    .pViewportState(vp)
                    .pRasterizationState(rs)
                    .pMultisampleState(ms)
                    .pColorBlendState(cb)
                    .pDynamicState(dyn)
                    .layout(pipelineLayout)
                    .renderPass(0)
                    .pNext(renderInfo.address())

                ds?.let { gp.pDepthStencilState(it) }

                vkCheck(
                    vkCreateGraphicsPipelines(
                        parameters.renderer.device.vkDevice,
                        parameters.renderer.pipelineCache.vkPipelineCache,
                        gp, null, lp
                    ),
                    "Error creating graphics pipeline"
                )
                this.pipeline = lp[0]
                pipelineDisposeToken.updateCleanup {
                    if (pipeline != 0L) {
                        VK10.vkDestroyPipeline(parameters.renderer.device.vkDevice, pipeline, null)
                    }
                    if (pipelineLayout != 0L) {
                        VK10.vkDestroyPipelineLayout(parameters.renderer.device.vkDevice, pipelineLayout, null)
                    }
                    if (vsm != 0L) {
                        VK10.vkDestroyShaderModule(parameters.renderer.device.vkDevice, vsm, null)
                    }
                    if (fsm != 0L) {
                        VK10.vkDestroyShaderModule(parameters.renderer.device.vkDevice, fsm, null)
                    }
                }

                parameters.renderer.device.nameObject(
                    pipeline,
                    VK10.VK_OBJECT_TYPE_PIPELINE,
                    "${parameters.name} Pipeline"
                )

                liveUniformStructures.forEach { (name, structure) ->
                    val target = (uniforms[name] as Uniform).buffer
                    structure.proxying = target
                }
            }

            valid = true
        } catch (e: Exception) {
            parameters.errorHandler("Module error: ${e.message}", e)
        }
    }

    override fun render(command: CommandBuffer, bounds: Rectanglef) {
        if (!valid) return
        MemoryStack.stackPush().use { stack ->
            val cmd = command.vkCommandBuffer
            val renderer = parameters.renderer
            val frameIndex = renderer.currentImage
            val profiler = renderer.getProfiler()

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

                val pushConstantBuffer = if (pushConstantSize > 0) stack.malloc(pushConstantSize) else stack.malloc(0)

                uniforms.values.forEach { upd ->
                    upd.pushDescriptorSet(command, pipelineLayout, VK_PIPELINE_BIND_POINT_GRAPHICS)
                }

                liveUniformStructures.forEach { (name, structure) ->
                    val target = (uniforms[name] as Uniform).buffer
                    structure.proxying = target
                    if (structure.dirty) {
                        target.get().mapAndWrite_bytes {
                            structure.writeToBuffer(this, command) {
                                throw MissingRefException("risk of GPU null ref `$it`")
                            }
                        }
                    }
                }

                meshes.forEach { (_, mesh) ->
                    mesh.draw(this, command, pushConstantBuffer)
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

    fun copyMeshesFrom(old: Module) {
        old.meshes.entries.removeIf {
            meshes[it.key] = it.value
            it.value.resize(it.value.numVert, it.value.numTriangle, inside = this.pointer())
            true
        }
    }

    fun cleanup() {
        pipelineDisposeToken.disable()
        if (pipeline != 0L) {
            VK10.vkDestroyPipeline(parameters.renderer.device.vkDevice, pipeline, null)
            pipeline = 0L
        }
        if (pipelineLayout != 0L) {
            VK10.vkDestroyPipelineLayout(parameters.renderer.device.vkDevice, pipelineLayout, null)
            pipelineLayout = 0L
        }
        if (vsm != 0L) {
            VK10.vkDestroyShaderModule(parameters.renderer.device.vkDevice, vsm, null)
            vsm = 0L
        }
        if (fsm != 0L) {
            VK10.vkDestroyShaderModule(parameters.renderer.device.vkDevice, fsm, null)
            fsm = 0L
        }
    }

    private fun createShaderModule(code: ByteBuffer): Long {
        MemoryStack.stackPush().use { stack ->
            val pCode = stack.malloc(code.limit()).put(code).flip()
            val ci = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(pCode)
            val lp = stack.mallocLong(1)
            vkCheck(
                vkCreateShaderModule(parameters.renderer.device.vkDevice, ci, null, lp),
                "Failed to create shader module"
            )
            return lp[0]
        }
    }

    operator fun plusAssign(m: Pair<String, Mesh>) {
        meshes[m.first] = m.second
        m.second.inside = this.pointer()
    }

}

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
