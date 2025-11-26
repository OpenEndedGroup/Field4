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
import afield.four.device.DescriptorSet.SimpleDescriptorSet
import afield.four.device.DescriptorSet.UpdatesDescriptorSet
import afield.four.device.DescriptorSetLayout.SimpleDescriptorSetLayout
import afield.four.buffers.linkBuffer
import afield.four.modules.Introspected.Bindable
import afield.four.texture.ImageView
import afield.four.texture.TextureSampler
import afield.four.util.Options
import afield.four.util.vkCheck
import afield.logger.lprintln
import afield.stdlib.Vec4
import afield.stdlib.d
import afield.stdlib.f
import org.joml.primitives.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.reflect.KProperty

data class Parameters(
    val renderer: Renderer,
    var vertex: String = "",
    var fragment: String = "",
    var compute: String = "",
    var mesh: String = "",
    var task: String = "",
    var color: Int = 1,
    var depth: Int = 1,
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
        override var target: SimpleDescriptorSet,
        override var source: BufferParameters? = null,
        val buffer: FrameBufferGroup,
        val kind: Int = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
    ) : UpdatesDescriptorSet {
        override fun updateDescriptorSet() {
            target.updateBuffer(binding.binding, buffer.get(), kind)
        }
    }

    class TexturePointer(
        val name: String,
        override val binding: Bindable,
        override var target: SimpleDescriptorSet,
        override var source: BufferParameters? = null,
        val texture: ImageView,
        val sampler: TextureSampler,
    ) : UpdatesDescriptorSet {
        override fun updateDescriptorSet() {
            target.updateTexture(binding.binding, texture, sampler)
        }
    }

    class TextureArrayPointer(
        val name: String,
        override val binding: Bindable,
        override var target: SimpleDescriptorSet,
        override var source: BufferParameters? = null,
        val texture: Array<ImageView>,
        val sampler: TextureSampler,
    ) : UpdatesDescriptorSet {
        override fun updateDescriptorSet() {
            target.updateTextures(binding.binding, texture, sampler)
        }
    }

    class ImagePointer(
        val name: String,
        override val binding: Bindable,
        override var target: SimpleDescriptorSet,
        override var source: BufferParameters? = null,
        val texture: ImageView,
    ) : UpdatesDescriptorSet {
        override fun updateDescriptorSet() {
            target.updateImage(binding.binding, texture)
        }
    }

    lateinit var vertexInputAttributes: VkVertexInputAttributeDescription.Buffer
    lateinit var vertexInputBindings: VkVertexInputBindingDescription.Buffer
    lateinit var vertexInputState: VkPipelineVertexInputStateCreateInfo

    var pipelineLayout: Long = 0L
    var pipeline: Long = 0L

    var vsm: Long = 0L
    var fsm: Long = 0L

    init {
        try {
            if (!vi.valid || !fi.valid) throw IllegalStateException("Can't make a module without valid programs")

            // Reserve set=0 for the global bindless sampled-image array (+ sampler). Validate shaders.
            validateAndReserveSet0(vi, fi)

            vsm = createShaderModule(vi.code.bytecode()!!)
            fsm = createShaderModule(fi.code.bytecode()!!)

            // Name objects for tooling (if EXT_debug_utils is present)
            parameters.renderer.device.nameObject(vsm, VK10.VK_OBJECT_TYPE_SHADER_MODULE, "${parameters.name} VS")
            parameters.renderer.device.nameObject(fsm, VK10.VK_OBJECT_TYPE_SHADER_MODULE, "${parameters.name} FS")

            val setsNeeded = mutableMapOf<Int, MutableList<Int>>()

            // Build per-module sets for set >= 1 only (set=0 is global)
            vi.ubos.forEach { if (it.set != 0) setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            fi.ubos.forEach { if (it.set != 0) setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            vi.ssbo.forEach { if (it.set != 0) setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            fi.ssbo.forEach { if (it.set != 0) setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            vi.textures.forEach {
                if (it.set != 0) setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding)
            }
            fi.textures.forEach {
                if (it.set != 0) setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding)
            }
            vi.images.forEach {
                if (it.set != 0) setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding)
            }
            fi.images.forEach {
                if (it.set != 0) setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding)
            }

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

            val layouts = mutableMapOf<Int, Pair<SimpleDescriptorSetLayout, SimpleDescriptorSet>>()

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

                    vi.textures.forEach {                      // fix #1: match by set & binding
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
                    parameters.renderer.device, kinds, set, bindingList, where, lens
                )
                val descriptorSet = layout.makeDescriptorSet(parameters.renderer.device.pool)
                layouts[set] = layout to descriptorSet
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

                val (_, descriptorSet) = layouts.getValue(set)
                tag.forEach {
                    var sz = (it as Introspected.UBO).size.toLong()
                    val bp = buffer(it, it.name) {
                        size = sz; source = it; uniform(); hosted()
                    }
                    val buf = parameters.uboResolver(bp)
                        ?: throw IllegalArgumentException("can't link UBO ${it.name}")
                    try {
                        uniforms[it.name] = Uniform(it.name, it, descriptorSet, source = bp, buf.toBuffer(), kind)
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

                val (_, descriptorSet) = layouts.getValue(set)
                tag.forEach {
                    var sz = (it as Introspected.UBO).size.toLong()
                    if (sz == 0L) sz = parameters.ssboSizer(it)?.toLong() ?: 0L
                    val bp = buffer(it, it.name) {
                        size = sz; source = it; storage(); hosted()
                    }
                    val buf = parameters.ssboResolver(bp)
                        ?: throw IllegalArgumentException("can't link SSBO buffer ${it.name}, resolver returned null")
                    try {
                        uniforms[it.name] = Uniform(it.name, it, descriptorSet, source = bp, buf.buffer, kind)
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

                val (_, descriptorSet) = layouts.getValue(set)
                tag.forEach {
                    val (texture, sampler) = parameters.textureResolver(it as Introspected.Texture)!!
                    if (it.arrayCount == 0) {
                        uniforms[it.name] =
                            TexturePointer(it.name, it, descriptorSet, source = null, texture[0], sampler)
                    } else {
                        require(texture.size == it.arrayCount) {
                            "can't link texture resource ${it.name} of length ${texture.size} but the shader expects ${it.arrayCount}"
                        }
                        uniforms[it.name] =
                            TextureArrayPointer(it.name, it, descriptorSet, source = null, texture, sampler)
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

                val (_, descriptorSet) = layouts.getValue(set)
                tag.forEach {
                    val image = parameters.imageResolver(it as Introspected.Image)!!
                    uniforms[it.name] = ImagePointer(it.name, it, descriptorSet, source = null, image)
                }
            }

            uniforms.values.forEach { it.updateDescriptorSet() }

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

                val blendAtt = VkPipelineColorBlendAttachmentState.calloc(parameters.color, stack)
                for (i in 0 until parameters.color) {
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

                val dyn = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(
                        stack.ints(
                            VK_DYNAMIC_STATE_VIEWPORT,
                            VK_DYNAMIC_STATE_SCISSOR,
                            VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE
                        )
                    )

                var vpcr: VkPushConstantRange.Buffer? = null
                if (pushConstantSize > 0) {
                    vpcr = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT)
                        .offset(0).size(pushConstantSize)
                }

                // ---- Build pipeline layout including global set=0
                val moduleHighestSet = if (layouts.isEmpty()) 0 else layouts.keys.maxOrNull()!!
                val highestSet = max(0, moduleHighestSet) // ensure room for set 0

                val ppLayout = stack.callocLong(highestSet + 1)
                // Always put the global set 0 at index 0
                ppLayout.put(0, parameters.renderer.set0Layout.vkDescriptorLayout)

                // Require contiguous sets from 1..highestSet for module-local sets
                for (s in 1..highestSet) {
                    val pair = layouts[s]
                        ?: throw IllegalArgumentException(
                            "Pipeline layout is missing descriptor set $s (set 0 is reserved globally). " +
                                    "Make your module sets contiguous starting at set=1."
                        )
                    ppLayout.put(s, pair.first.vkDescriptorLayout)
                }

                val pli = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(ppLayout)
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

                val colorAttachmentFormats = stack.callocInt(parameters.color)
                repeat(parameters.color) { colorAttachmentFormats.put(parameters.imageFormat) }
                colorAttachmentFormats.flip()

                renderInfo
                    .colorAttachmentCount(parameters.color)
                    .pColorAttachmentFormats(colorAttachmentFormats)
                    .depthAttachmentFormat(if (parameters.depth > 0) Options.defaultDepthFormat else VK_FORMAT_UNDEFINED)
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


                // Bind the global set=0 (sampled-image array + sampler) once for this pipeline layout
                renderer.bindGlobalSet0(command, pipelineLayout)

                val viewportNow = parameters.viewport ?: Rect(0.0, 0.0, bounds.lengthX().d, bounds.lengthY().d)
                val viewport = VkViewport.calloc(1, stack)
                    .x(viewportNow.x.f)
                    .y(viewportNow.y.f + viewportNow.h.f)
                    .height(-viewportNow.h.f)   // flip Y
                    .width(viewportNow.w.f)
                    .minDepth(0.0f).maxDepth(1.0f)
                VK10.vkCmdSetViewport(cmd, 0, viewport)

                val scissor = VkRect2D.calloc(1, stack)
                    .extent(VkExtent2D.calloc(stack).width(bounds.lengthX().toInt()).height(bounds.lengthY().toInt()))
                    .offset(VkOffset2D.calloc(stack).x(0).y(0))
                VK10.vkCmdSetScissor(cmd, 0, scissor)

                VK13.vkCmdSetDepthTestEnable(cmd, if (parameters.depth == 0) false else parameters.depthTest)

                val pushConstantBuffer = if (pushConstantSize > 0) stack.malloc(pushConstantSize) else stack.malloc(0)

                if (uniforms.isNotEmpty()) {
                    val uniqueSets = uniforms.values.map { it.binding.set }.toSet()
                    val firstSet = uniqueSets.minOrNull()!!
                    val lastSet = uniqueSets.maxOrNull()!!
                    val setCount = lastSet - firstSet + 1

                    // zeroed + absolute puts (fix #4)
                    val descriptorSets = stack.callocLong(setCount)
                    uniforms.values.forEach { upd ->
                        val slot = upd.binding.set - firstSet
                        descriptorSets.put(slot, upd.target.vkDescriptorSet)
                    }
                    descriptorSets.position(0)

                    vkCmdBindDescriptorSets(
                        cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, firstSet, descriptorSets, null
                    )
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

    fun cleanup() { /* pipeline and shaders are owned by renderer lifetime; no-op here */
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

    /**
     * set=0 is reserved for: sampled-image array (binding=0) and a single sampler binding (binding=1).
     * No UBO/SSBO/storage image in set=0.
     */
    private fun validateAndReserveSet0(vi: Introspection, fi: Introspection) {
        val illegalSet0 = mutableListOf<String>()

        // Disallow anything but sampled images / sampler at set=0
        vi.ubos.filter { it.set == 0 }.forEach { illegalSet0 += "UBO '${it.name}' (binding ${it.binding})" }
        fi.ubos.filter { it.set == 0 }.forEach { illegalSet0 += "UBO '${it.name}' (binding ${it.binding})" }
        vi.ssbo.filter { it.set == 0 }.forEach { illegalSet0 += "SSBO '${it.name}' (binding ${it.binding})" }
        fi.ssbo.filter { it.set == 0 }.forEach { illegalSet0 += "SSBO '${it.name}' (binding ${it.binding})" }
        vi.images.filter { it.set == 0 }.forEach { illegalSet0 += "storage image '${it.name}' (binding ${it.binding})" }
        fi.images.filter { it.set == 0 }.forEach { illegalSet0 += "storage image '${it.name}' (binding ${it.binding})" }

        if (illegalSet0.isNotEmpty()) {
            val msg = buildString {
                appendLine("set=0 is reserved for the global bindless sampled-image array (binding 0) and a single sampler binding (binding 1).")
                appendLine("These declarations in set=0 are not allowed:")
                illegalSet0.forEach { appendLine("  - $it") }
                appendLine("Fix: move them to set>=1.")
            }
            throw IllegalArgumentException(msg)
        }

        // Sampled images in set=0 must be binding 0
        val set0Textures = (vi.textures + fi.textures).filter { it.set == 0 }
        if (set0Textures.isNotEmpty()) {
            val distinctBindings = set0Textures.map { it.binding }.toSet()
            if (distinctBindings.size != 1 || distinctBindings.first() != 0) {
                val msg = buildString {
                    appendLine("set=0 sampled images must use **binding 0** (global array).")
                    appendLine("Found bindings: ${distinctBindings.sorted()}")
                    appendLine("Fix: declare textures at set=0,binding=0 (the sampler is set=0,binding=1).")
                }
                throw IllegalArgumentException(msg)
            }
        }

        // Separate samplers in set=0 must be binding 1
        val set0Samplers = (vi.samplers + fi.samplers).filter { it.set == 0 }
        if (set0Samplers.isNotEmpty()) {
            val samplerBindings = set0Samplers.map { it.location }.toSet()
            if (samplerBindings.size != 1 || samplerBindings.first() != 1) {
                val msg = buildString {
                    appendLine("set=0 separate samplers must use **binding 1** (single global sampler binding).")
                    appendLine("Found sampler bindings: ${samplerBindings.sorted()}")
                    appendLine("Fix: declare the sampler at set=0,binding=1 (textures at set=0,binding=0).")
                }
                throw IllegalArgumentException(msg)
            }
        }
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
