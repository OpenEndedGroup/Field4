package afield.four.modules

import afield.Named
import afield.Rect
import afield.four.BufferParameters
import afield.four.Renderer
import afield.four.buffer
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.MissingRefException
import afield.four.buffers.Structure
import afield.four.device.CommandBuffer
import afield.four.device.DescriptorSet.SimpleDescriptorSet
import afield.four.device.DescriptorSet.UpdatesDescriptorSet
import afield.four.device.DescriptorSetLayout
import afield.four.device.DescriptorSetLayout.SimpleDescriptorSetLayout
import afield.four.device.GpuProfiler
import afield.four.linkBuffer
import afield.four.modules.Introspected.Bindable
import afield.four.texture.ImageView
import afield.four.texture.TextureSampler
import afield.four.util.Options
import afield.four.util.vkCheck
import afield.logger.lprintln
import afield.stdlib.d
import afield.stdlib.f
import org.joml.primitives.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*
import java.nio.ByteBuffer
import kotlin.reflect.KProperty

/**
 * all descriptor sets here are bound and scoped to a particular model. This is (I hear) inefficient, in the sense.
 *
 * The general idea is that you could have a scope that Module's are in that contain uniforms, textures and images and, inside that scope, you'd magically
 * get a descriptor set that was already set up and bound. This, presumably, is better than merely sharing the underlying buffer. Is this worth it?
 *
 * Right now this graphics system is targeting the one mesh per shader world so I don't understand the optimization here.
 *
 * You could imagine a GPU-vertex-pulled custom mesh subclass that binds its own descriptor set (just one) prior to its draw call? That
 * set would be special (and the uniforms would be juggled per-mesh I suppose)
 */

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
    var ssboSizer: (Introspected.UBO) -> Int? = {
        0
    },
    var imageResolver: (Introspected.Image) -> ImageView? = {
        throw Exception("Cannot resolve image ${it.name}")
    },
    var name: String = "unnamed module",
    var errorHandler: (String, Throwable?) -> Unit = { desc, it ->
        println(" error ${desc} while creating Module:\n\t${it}")
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
    var imageFormat: Int = renderer.swapChain.surfaceFormat.imageFormat
) {

    // todo, a few of these parameters that you can dynamically swap
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
            ?: throw IllegalArgumentException(" can't find '${property.name}' in this module, options are ${inside.current.liveUniformStructures.keys}")
//        return (inside.current.uniforms[property.name] as Module.Uniform).buffer
    }
}

class ModulePointer(var current: Module) : Renderable {
    override fun render(command: CommandBuffer, bounds: Rectanglef) {
        if (current.valid) current.render(command, bounds)
        else {
            println("module '${current.parameters.name}' not valid / not rendering")
        }
    }

    fun reload(
        p: Parameters, forceReresolve: Boolean = true, onError: (String, Throwable?) -> Unit = p.errorHandler
    ): Boolean {
        if (current.valid && p.equals(current.parameters) && !forceReresolve) return false
        p.errorHandler = onError

        // should we copy the buffers that back other uniforms as well
        // if they have the correct size?

        val oUboResolver = p.uboResolver
        p.uboResolver = {
            oUboResolver(it)
        }

        val oSsboResolver = p.ssboResolver
        p.ssboResolver = {
            oSsboResolver(it)
        }

        val oTextureResolver = p.textureResolver
        p.textureResolver = {
            if (forceReresolve) oTextureResolver(it)
            else try {
                arrayOf((current.uniforms[it.name] as Module.TexturePointer).texture) to (current.uniforms[it.name] as Module.TexturePointer).sampler
            } catch (e: Exception) {
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

    override fun toString(): String {
        if (!current.valid) return "Invalid Module"
        return "Module"
    }
}

fun Module.pointer() = ModulePointer(this)

class Module(val parameters: Parameters) : Renderable {
    var valid = false

//    var depthTestEnable = parameters.depthTest

    var meshes = mutableMapOf<String, Mesh>()

    val vi = Introspection(
        parameters.vertex, ShaderKind.VERTEX_SHADER, errorHandler = parameters.errorHandler
    )
    val fi = Introspection(
        parameters.fragment, ShaderKind.FRAGMENT_SHADER, errorHandler = parameters.errorHandler
    )


    val namedInputs = vi.inputs.map { it.name to it }.toMap()

    val pushConstantSize =
        (vi.pushConstants.associateBy { it.name } + fi.pushConstants.associateBy { it.name }).values.sumOf { it.size }

    var layouts: List<Pair<DescriptorSetLayout.SimpleDescriptorSetLayout, MutableList<Introspected.Bindable>>> =
        listOf()

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

            vsm = createShaderModule(vi.code.bytecode()!!)
            fsm = createShaderModule(fi.code.bytecode()!!)


            val setsNeeded = mutableMapOf<Int, MutableList<Int>>()

            vi.ubos.forEach {
                setsNeeded.computeIfAbsent(it.set) { mutableListOf<Int>() }.add(it.binding)
            }
            fi.ubos.forEach {
                setsNeeded.computeIfAbsent(it.set) { mutableListOf<Int>() }.add(it.binding)
            }

            vi.ssbo.forEach {
                setsNeeded.computeIfAbsent(it.set) { mutableListOf<Int>() }.add(it.binding)
            }
            fi.ssbo.forEach {
                setsNeeded.computeIfAbsent(it.set) { mutableListOf<Int>() }.add(it.binding)
            }

            vi.textures.forEach {
                setsNeeded.computeIfAbsent(it.set) { mutableListOf<Int>() }.add(it.binding)
            }
            fi.textures.forEach {
                setsNeeded.computeIfAbsent(it.set) { mutableListOf<Int>() }.add(it.binding)
            }

            vi.images.forEach {
                setsNeeded.computeIfAbsent(it.set) { mutableListOf<Int>() }.add(it.binding)
            }
            fi.images.forEach {
                setsNeeded.computeIfAbsent(it.set) { mutableListOf<Int>() }.add(it.binding)
            }

            uniforms = mutableMapOf<String, UpdatesDescriptorSet>()

            liveUniformStructures = mutableMapOf<String, Structure>()

            vi.ubos.forEach {
                liveUniformStructures[it.name] = Structure().setMembers(it.members)
            }
            fi.ubos.forEach {
                liveUniformStructures[it.name] = Structure().setMembers(it.members)
            }

            vi.ssbo.forEach {
                liveUniformStructures[it.name] = Structure().setMembers(it.members) // TODO: array types
            }
            fi.ssbo.forEach {
                liveUniformStructures[it.name] = Structure().setMembers(it.members) // TODO: array types
            }

            val layouts = mutableMapOf<Int, Pair<SimpleDescriptorSetLayout, SimpleDescriptorSet>>()

            setsNeeded.entries.forEach { (set, bindingList) ->
                var where = 0
                var tag = mutableListOf<Introspected.Bindable>()
                var kinds = mutableListOf<Int>()
                var lens = mutableListOf<Int>()
                bindingList.forEach { bind ->
                    var kind = 0
                    vi.ubos.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_VERTEX_BIT
                            kind = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                            tag.add(it)
                            lens.add(1)
                        }
                    }
                    fi.ubos.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                            kind = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                            tag.add(it)
                            lens.add(1)
                        }
                    }

                    vi.ssbo.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_VERTEX_BIT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                            tag.add(it)
                            lens.add(1)
                        }
                    }
                    fi.ssbo.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                            tag.add(it)
                            lens.add(1)
                        }
                    }
                    vi.textures.forEach {
                        if (it.set == set) {
                            where = where or VK_SHADER_STAGE_VERTEX_BIT
                            kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                            tag.add(it)
                            lens.add(Math.max(1, it.arrayCount))
                        }
                    }
                    fi.textures.forEach {
                        if (it.set == set) {
                            where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                            kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                            tag.add(it)
                            lens.add(Math.max(1, it.arrayCount))
                        }
                    }
                    vi.images.forEach {
                        if (it.set == set) {
                            where = where or VK_SHADER_STAGE_VERTEX_BIT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                            tag.add(it)
                            lens.add(1)
                        }
                    }
                    fi.images.forEach {
                        if (it.set == set) {
                            where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                            tag.add(it)
                            lens.add(1)
                        }
                    }
                    if (kind == 0) throw IllegalArgumentException(" couldn't find ${bind} for ${bindingList}")
                    kinds.add(kind)
                }

                val layout = DescriptorSetLayout.SimpleDescriptorSetLayout(
                    parameters.renderer.device, kinds, set, bindingList, where, lens
                )

                val descriptorSet = layout.makeDescriptorSet(parameters.renderer.device.pool)

                layouts.put(set, layout to descriptorSet)
            }


            setsNeeded.entries.forEach { (set, bindingList) ->
                var where = 0
                var kind = 0
                var tag = mutableListOf<Introspected.Bindable>()
                vi.ubos.forEach {
                    if (it.set == set) {
                        where = where or VK_SHADER_STAGE_VERTEX_BIT
                        kind = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                        tag.add(it)
                    }
                }
                fi.ubos.forEach {
                    if (it.set == set) {
                        where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                        kind = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                        tag.add(it)
                    }
                }

                if (kind == 0) return@forEach

                val (layout, descriptorSet) = layouts.get(set)!!

                tag.forEach {
                    var sz = (it as Introspected.UBO).size.toLong()
                    var bp = buffer(it, it.name) {
                        size = sz
                        source = it
                        uniform()
                        hosted()
                    }

                    val buf = parameters.uboResolver(bp)

                    if (buf != null) {
                        try {
                            uniforms[it.name] = Uniform(it.name, it, descriptorSet, source = bp, buf.toBuffer(), kind)
                        } catch (n: NullPointerException) {
                            throw IllegalArgumentException("UBO ${it.name}, no longer resolves")
                        }
                    } else {
                        throw IllegalArgumentException("can't link UBO ${it.name}")
                    }
                }
            }

            setsNeeded.entries.forEach { (set, bindingList) ->
                var where = 0
                var kind = 0
                var tag = mutableListOf<Introspected.Bindable>()
                vi.ssbo.forEach {
                    if (it.set == set) {
                        where = where or VK_SHADER_STAGE_VERTEX_BIT
                        kind = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                        tag.add(it)
                    }
                }
                fi.ssbo.forEach {
                    if (it.set == set) {
                        where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                        kind = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                        tag.add(it)
                    }
                }

                if (kind == 0) return@forEach

                val (layout, descriptorSet) = layouts.get(set)!!

                tag.forEach {
                    var sz = (it as Introspected.UBO).size.toLong()
                    if (sz == 0L) sz = parameters.ssboSizer(it)?.toLong() ?: 0L

                    var bp = buffer(it, it.name) {
                        size = sz
                        source = it
                        storage()
                        hosted()
                    }

                    val buf = parameters.ssboResolver(bp)

                    if (buf != null) {

                        try {
                            uniforms[it.name] = Uniform(it.name, it, descriptorSet, source = bp, buf.buffer, kind)
                        } catch (e: NullPointerException) {
                            throw IllegalArgumentException("buffer ${it.name} resolved to null")
                        }
                    } else throw IllegalArgumentException("can't link SSBO buffer ${it.name}, resolver returned null")

                }
            }

            setsNeeded.entries.forEach { (set, bindingList) ->
                var where = 0
                var kind = 0
                var tag = mutableListOf<Introspected.Bindable>()
                vi.textures.forEach {
                    if (it.set == set) {
                        where = where or VK_SHADER_STAGE_VERTEX_BIT
                        kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                        tag.add(it)
                    }
                }
                fi.textures.forEach {
                    if (it.set == set) {
                        where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                        kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                        tag.add(it)
                    }
                }


                if (kind == 0) return@forEach

                val (layout, descriptorSet) = layouts.get(set)!!

                tag.forEach {
                    println(" resolving texture resource $it")
                    val (texture, sampler) = parameters.textureResolver(it as Introspected.Texture)!!

                    if (it.arrayCount == 0) {

                        uniforms[it.name] = TexturePointer(
                            it.name,
                            it as Introspected.Texture,
                            descriptorSet,
                            source = null /* todo */,
                            texture[0],
                            sampler
                        )
                    } else {

                        if (texture.size != it.arrayCount) throw IllegalArgumentException("can't link texture resource ${it.name} of length ${texture.size} but the shader is expecting ${it.arrayCount}")

                        uniforms[it.name] = TextureArrayPointer(
                            it.name,
                            it as Introspected.Texture,
                            descriptorSet,
                            source = null /* todo */,
                            texture,
                            sampler
                        )
                    }
                }
            }

            setsNeeded.entries.forEach { (set, bindingList) ->
                var where = 0
                var kind = 0
                var tag = mutableListOf<Introspected.Bindable>()
                vi.images.forEach {
                    if (it.set == set) {
                        where = where or VK_SHADER_STAGE_VERTEX_BIT
                        kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                        tag.add(it)
                    }
                }
                fi.images.forEach {
                    if (it.set == set) {
                        where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                        kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                        tag.add(it)
                    }
                }

                if (kind == 0) return@forEach

                val (layout, descriptorSet) = layouts.get(set)!!

                tag.forEach {
                    println(" resolving image resource $it")
                    val image = parameters.imageResolver(it as Introspected.Image)!!
                    uniforms[it.name] = ImagePointer(it.name, it, descriptorSet, source = null /* todo */, image)
                }

            }

            uniforms.forEach {
                it.value.updateDescriptorSet()
            }


            vertexInputAttributes = VkVertexInputAttributeDescription.calloc(vi.inputs.size)
            vertexInputBindings = VkVertexInputBindingDescription.calloc(vi.inputs.size)

            // we'll still attach variable amounts of geometry here
            // and, maybe eventually, we'll just be doing ssbo's instead

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

                // TODO: lots of these should be in parameters
                shaderStages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT).pName(stack.UTF8("main")).module(vsm)

                shaderStages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT).pName(stack.UTF8("main")).module(fsm)

                val vkPipelineInputAssemblyStateCreateInfo = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(parameters.primitiveType)
                when (parameters.primitiveType) {
                    VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP, VK_PRIMITIVE_TOPOLOGY_LINE_STRIP, VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP_WITH_ADJACENCY -> vkPipelineInputAssemblyStateCreateInfo.primitiveRestartEnable(
                        true
                    )
                }

                val vkPipelineViewportStateCreateInfo = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1)

                val vkPipelineRasterizationStateCreateInfo = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(parameters.fillMode)
                    .cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_CLOCKWISE).lineWidth(1.0f)

                val vkPipelineMultisampleStateCreateInfo = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)

                var ds: VkPipelineDepthStencilStateCreateInfo? = null
                if (parameters.depth > 0) {
                    ds = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(true)
                        .depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL).depthBoundsTestEnable(false)
                        .stencilTestEnable(false)
                }

                val blendAttState = VkPipelineColorBlendAttachmentState.calloc(parameters.color, stack)
                for (i in 0 until parameters.color) {
                    blendAttState[i].colorWriteMask((if (parameters.colorMask[0]) VK_COLOR_COMPONENT_R_BIT else 0) or (if (parameters.colorMask[1]) VK_COLOR_COMPONENT_G_BIT else 0) or (if (parameters.colorMask[2]) VK_COLOR_COMPONENT_B_BIT else 0) or (if (parameters.colorMask[3]) VK_COLOR_COMPONENT_A_BIT else 0))
                        .blendEnable(parameters.blend).srcColorBlendFactor(parameters.srcColor)
                        .dstColorBlendFactor(parameters.dstColor).colorBlendOp(parameters.colorBlendOp)
                        .srcAlphaBlendFactor(parameters.srcAlpha).dstAlphaBlendFactor(parameters.dstColor)
                        .alphaBlendOp(parameters.alphaBlendOp)
                }
                val colorBlendState = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(blendAttState)

                val vkPipelineDynamicStateCreateInfo = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(
                        stack.ints(
                            VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE
                        )
                    )

                var vpcr: VkPushConstantRange.Buffer? = null
                if (pushConstantSize > 0) {
                    vpcr = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT).offset(0)
                        .size(pushConstantSize)
                }

                val numLayouts = layouts.size
                val maxSet = if (numLayouts == 0) 0 else (layouts.values.maxOf { it.first.set } + 1)
                if (numLayouts != maxSet) throw IllegalArgumentException("layout for a module is missing a set ${layouts.keys}")
                val ppLayout = stack.callocLong(maxSet)

                for (i in 0 until numLayouts) {
                    if (layouts[i] == null) throw IllegalArgumentException("layout for a module is missing a set ${i}")
                    ppLayout.put(layouts[i]!!.first.set, layouts[i]!!.first.vkDescriptorLayout)
                }

                val pPipelineLayoutCreateInfo =
                    VkPipelineLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                        .pSetLayouts(ppLayout)
                vpcr?.let {
                    pPipelineLayoutCreateInfo.pPushConstantRanges(it) // work around IntelliJ's nullability issue
                }
                val lp = stack.mallocLong(1)

                vkCheck(
                    vkCreatePipelineLayout(parameters.renderer.device.vkDevice, pPipelineLayoutCreateInfo, null, lp),
                    "Failed to create pipeline layout"
                )
                pipelineLayout = lp[0]

                val renderInfo = VkPipelineRenderingCreateInfoKHR.calloc(1, stack)
                renderInfo.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR)

                val colorAttachmentFormats = stack.callocInt(parameters.color)
                (0 until parameters.color).forEach {
                    // this is only true if we are rendering to the screen, if we're RTT then we need to override this
                    colorAttachmentFormats.put(parameters.imageFormat)
//                    colorAttachmentFormats.put(VK_FORMAT_UNDEFINED)

                }
                colorAttachmentFormats.flip()
                renderInfo.colorAttachmentCount(parameters.color)
                renderInfo.pColorAttachmentFormats(colorAttachmentFormats)
                if (parameters.depth > 0) renderInfo.depthAttachmentFormat(Options.defaultDepthFormat)
                else renderInfo.depthAttachmentFormat(VK_FORMAT_UNDEFINED)
                renderInfo.stencilAttachmentFormat(0)

                val pipeline =
                    VkGraphicsPipelineCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                        .pStages(shaderStages).pVertexInputState(vertexInputState)
                        .pInputAssemblyState(vkPipelineInputAssemblyStateCreateInfo)
                        .pViewportState(vkPipelineViewportStateCreateInfo)
                        .pRasterizationState(vkPipelineRasterizationStateCreateInfo)
                        .pMultisampleState(vkPipelineMultisampleStateCreateInfo).pColorBlendState(colorBlendState)
                        .pDynamicState(vkPipelineDynamicStateCreateInfo).layout(pipelineLayout).renderPass(0)
                        .pNext(renderInfo.address())

                if (ds != null) {
                    pipeline.pDepthStencilState(ds)
                }
                vkCheck(
                    vkCreateGraphicsPipelines(
                        parameters.renderer.device.vkDevice,
                        parameters.renderer.pipelineCache.vkPipelineCache,
                        pipeline,
                        null,
                        lp
                    ), "Error creating graphics pipeline"
                )


                liveUniformStructures.forEach { (name, structure) ->
                    val target = (uniforms[name] as Uniform).buffer
                    structure.proxying = target
                }


                this.pipeline = lp[0]
            }

            valid = true
        } catch (e: Exception) {
            parameters.errorHandler("Module error: ${e.message}", e)
        }
    }

    override fun render(command: CommandBuffer, bounds: Rectanglef) {
        if (!valid) return
        MemoryStack.stackPush().use { stack ->

            val cmdHandle = command.vkCommandBuffer

            val renderer = parameters.renderer
            val frameIndex = renderer.currentImage
            val profiler = renderer.getProfiler()

            val marks = if (profiler != null) Renderer.ModuleMarks(parameters.name) else null
            var psScope: GpuProfiler.PipeStatScope? = null
            var perfScope: GpuProfiler.PerfScope? = null

            if (profiler != null) {
                marks!!.tsBegin = profiler.writeTimestamp(cmdHandle, frameIndex, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                psScope = profiler.beginPipelineStats(cmdHandle, frameIndex)
                perfScope = profiler.beginPerfCounters(cmdHandle, frameIndex)
            }

            VK10.vkCmdBindPipeline(cmdHandle, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline)

            val viewportNow = parameters.viewport ?: Rect(0.0, 0.0, bounds.lengthX().d, bounds.lengthY().d)
            val viewport = VkViewport.calloc(1, stack).x(viewportNow.x.f).y(viewportNow.y.f + viewportNow.h.f)
                .height(-viewportNow.h.f)   // flip Y
                .width(viewportNow.w.f).minDepth(0.0f).maxDepth(1.0f)
            VK10.vkCmdSetViewport(cmdHandle, 0, viewport)

            val scissor = VkRect2D.calloc(1, stack)
                .extent(VkExtent2D.calloc(stack).width(bounds.lengthX().toInt()).height(bounds.lengthY().toInt()))
                .offset(VkOffset2D.calloc(stack).x(0).y(0))
            VK10.vkCmdSetScissor(cmdHandle, 0, scissor)

            // dynamic depth test (requires EXT_extended_dynamic_state enabled at device creation)
            EXTExtendedDynamicState.vkCmdSetDepthTestEnableEXT(
                cmdHandle, if (parameters.depth == 0) false else parameters.depthTest
            )

            // Push constant scratch buffer for meshes (stack-owned; valid for this call)
            val pushConstantBuffer = if (pushConstantSize > 0) stack.malloc(pushConstantSize) else stack.malloc(0)

            // --- Descriptor sets: build a dense window [firstSet .. lastSet] and bind once ---
            if (uniforms.isNotEmpty()) {
                val uniqueSets = uniforms.values.map { it.binding.set }.toSet()
                val firstSet = uniqueSets.minOrNull()!!
                val lastSet = uniqueSets.maxOrNull()!!
                val setCount = lastSet - firstSet + 1

                val descriptorSets = stack.mallocLong(setCount)
                // default to 0 for gaps (valid "null" set)
                for (i in 0 until setCount) descriptorSets.put(i, 0L)



                uniforms.values.forEach { upd ->
                    val slot = upd.binding.set - firstSet
                    descriptorSets.put(slot, upd.target.vkDescriptorSet)
                }

                vkCmdBindDescriptorSets(
                    cmdHandle, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, firstSet, descriptorSets, null
                )
            }

            // Update any dirty UBO/SSBO structures before drawing
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

            // Issue draws for all meshes (each writes its own push constants into the shared buffer)
            meshes.forEach { (_, mesh) ->
                mesh.draw(this, command, pushConstantBuffer)
            }


            if (profiler != null) {
                profiler.endPipelineStats(cmdHandle, psScope)
                profiler.endPerfCounters(cmdHandle, frameIndex, perfScope)
                marks!!.tsEnd =
                    profiler.writeTimestamp(cmdHandle, frameIndex, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT)
                if (psScope != null) marks.pipeStatsIndex = psScope.index
                if (perfScope != null) marks.perfIndex = perfScope.index
                renderer.registerModuleMarks(frameIndex, marks)
            }
        }


    }

    fun copyMeshesFrom(old: Module) {
        old.meshes.entries.removeIf {
            this.meshes.put(it.key, it.value)
            it.value.resize(it.value.numVert, it.value.numTriangle, inside = this.pointer())
            true
        }
    }


    fun cleanup() {

    }

    private fun createShaderModule(code: ByteBuffer): Long {
        MemoryStack.stackPush().use { stack ->
            val pCode = stack.malloc(code.limit()).put(code).flip()
            val moduleCreateInfo =
                VkShaderModuleCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(pCode)


            val lp = stack.mallocLong(1)
            vkCheck(
                vkCreateShaderModule(parameters.renderer.device.vkDevice, moduleCreateInfo, null, lp),
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

fun parseCompilerException(exception: CompilerError, accept: (String, Int, String) -> Unit) {
    val message = exception.message

    val regex = """:(\d+):""".toRegex() // Matches a number surrounded by ':' and puts the number in group 1
    message.split("\n").forEach {
        val f = regex.find(it)
        if (f != null) {
            val line = f.groupValues[1].toInt()

            val (file, message) = it.split(regex)
            accept(file.split("/").last(), line, message)
        } else {
            if (it.trim().length > 0) accept("", -1, it)
        }
    }
}
