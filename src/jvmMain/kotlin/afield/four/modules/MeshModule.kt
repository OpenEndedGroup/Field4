package afield.four.modules

import org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT
import org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT

import afield.Named
import afield.Rect
import afield.four.buffers.buffer
import afield.four.buffers.MissingRefException
import afield.four.buffers.Structure
import afield.four.device.CommandBuffer
import afield.four.device.DescriptorSetLayout
import afield.four.device.DescriptorSetLayout.SimpleDescriptorSetLayout
import afield.four.device.UpdatesDescriptorSet
import afield.four.device.beginLabel
import afield.four.device.endLabel
import afield.four.device.nameObject
import afield.four.util.Options
import afield.four.util.AutoDisposer
import afield.four.util.vkCheck
import afield.logger.lprintln
import afield.stdlib.d
import afield.stdlib.f
import org.joml.primitives.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.EXTExtendedDynamicState3
import java.nio.ByteBuffer

class MeshModulePointer(var current: MeshModule) : Renderable {
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
        p.uboResolver = { oUboResolver(it) }

        val oSsboResolver = p.ssboResolver
        p.ssboResolver = { oSsboResolver(it) }

        val oTextureResolver = p.textureResolver
        p.textureResolver = {
            if (forceReresolve) oTextureResolver(it) else try {
                val tp = current.uniforms[it.name] as Module.TexturePointer
                arrayOf(tp.texture) to tp.sampler
            } catch (_: Exception) {
                lprintln(" couldn't transfer over texture ${it.name}, falling back to originating resolution")
                oTextureResolver(it)
            }
        }

        val m = MeshModule(p)
        if (m.valid) {
            m.copyMeshesFrom(current)
            current = m
            return true
        }
        return false
    }

    operator fun plusAssign(m: Pair<String, MeshMesh>) {
        current.meshes[m.first] = m.second
        m.second.inside = this
    }

    operator fun plusAssign(m: Named<MeshMesh>) {
        current.meshes[m.name] = m.value
        m.value.inside = this
    }

    override fun toString(): String = if (!current.valid) "Invalid Module" else "Module"
}

fun MeshModule.pointer() = MeshModulePointer(this)

class MeshModule(val parameters: Parameters) : Renderable {
    var valid = false

    var meshes = mutableMapOf<String, MeshMesh>()

    val ti = Introspection(parameters.task, ShaderKind.TASK_SHADER, errorHandler = parameters.errorHandler)
    val mi = Introspection(parameters.mesh, ShaderKind.MESH_SHADER, errorHandler = parameters.errorHandler)
    val fi = Introspection(parameters.fragment, ShaderKind.FRAGMENT_SHADER, errorHandler = parameters.errorHandler)

    val pushConstantSize =
        (ti.pushConstants.associateBy { it.name } + mi.pushConstants.associateBy { it.name }).values.sumOf { it.size }

    var uniforms: MutableMap<String, UpdatesDescriptorSet> = mutableMapOf()
    var liveUniformStructures: MutableMap<String, Structure> = mutableMapOf()

    var pipelineLayout: Long = 0L
    var pipeline: Long = 0L
    private val pipelineDisposeToken = AutoDisposer.register(this, "MeshPipeline(${parameters.name})") { }

    var tsm: Long = 0L
    var msm: Long = 0L
    var fsm: Long = 0L

    init {
        try {
            if (!ti.valid || !mi.valid || !fi.valid) throw IllegalStateException("Can't make a module without valid programs")

            tsm = createShaderModule(ti.code.bytecode()!!)
            msm = createShaderModule(mi.code.bytecode()!!)
            fsm = createShaderModule(fi.code.bytecode()!!)

            parameters.renderer.device.nameObject(tsm, VK10.VK_OBJECT_TYPE_SHADER_MODULE, "${parameters.name} TS")
            parameters.renderer.device.nameObject(msm, VK10.VK_OBJECT_TYPE_SHADER_MODULE, "${parameters.name} MS")
            parameters.renderer.device.nameObject(fsm, VK10.VK_OBJECT_TYPE_SHADER_MODULE, "${parameters.name} FS")

            // Collect sets -> bindings (may contain dups from multiple stages)
            val setsNeededRaw = mutableMapOf<Int, MutableList<Int>>()

            // Build required sets for set >= 1 only (set=0 is global)
            ti.ubos.forEach    { if (it.set != 0) setsNeededRaw.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            mi.ubos.forEach    { if (it.set != 0) setsNeededRaw.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            fi.ubos.forEach    { if (it.set != 0) setsNeededRaw.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }

            ti.ssbo.forEach    { if (it.set != 0) setsNeededRaw.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            mi.ssbo.forEach    { if (it.set != 0) setsNeededRaw.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            fi.ssbo.forEach    { if (it.set != 0) setsNeededRaw.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }

            ti.textures.forEach { if (it.set != 0) setsNeededRaw.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            mi.textures.forEach { if (it.set != 0) setsNeededRaw.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            fi.textures.forEach { if (it.set != 0) setsNeededRaw.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }

            ti.images.forEach   { if (it.set != 0) setsNeededRaw.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            mi.images.forEach   { if (it.set != 0) setsNeededRaw.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            fi.images.forEach   { if (it.set != 0) setsNeededRaw.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }

            // (2) Dedup + sort per-set binding lists so DescriptorSetLayout is deterministic & gap-free
            val setsNeeded = setsNeededRaw.mapValues { (_, v) -> v.distinct().sorted().toMutableList() }

            uniforms = mutableMapOf()
            liveUniformStructures = mutableMapOf()

            ti.ubos.forEach { liveUniformStructures[it.name] = Structure().setMembers(it.members) }
            mi.ubos.forEach { liveUniformStructures[it.name] = Structure().setMembers(it.members) }
            fi.ubos.forEach { liveUniformStructures[it.name] = Structure().setMembers(it.members) }

            ti.ssbo.forEach { liveUniformStructures[it.name] = Structure().setMembers(it.members) }
            mi.ssbo.forEach { liveUniformStructures[it.name] = Structure().setMembers(it.members) }
            fi.ssbo.forEach { liveUniformStructures[it.name] = Structure().setMembers(it.members) }

            val layouts = mutableMapOf<Int, SimpleDescriptorSetLayout>()

            // Build layouts for sets >= 1
            setsNeeded.entries.forEach { (set, bindingList) ->
                var where = 0
                val kinds = mutableListOf<Int>()
                val lens  = mutableListOf<Int>()

                bindingList.forEach { bind ->
                    var kind = 0

                    ti.ubos.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_TASK_BIT_EXT
                            kind = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                            lens.add(1)
                        }
                    }
                    mi.ubos.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_MESH_BIT_EXT
                            kind = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                            lens.add(1)
                        }
                    }
                    fi.ubos.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                            kind = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                            lens.add(1)
                        }
                    }

                    ti.ssbo.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_TASK_BIT_EXT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                            lens.add(1)
                        }
                    }
                    mi.ssbo.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_MESH_BIT_EXT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                            lens.add(1)
                        }
                    }
                    fi.ssbo.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                            lens.add(1)
                        }
                    }

                    // (1) Match on binding for textures/images too
                    ti.textures.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_TASK_BIT_EXT
                            kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                            lens.add(maxOf(1, it.arrayCount))
                        }
                    }
                    mi.textures.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_MESH_BIT_EXT
                            kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                            lens.add(maxOf(1, it.arrayCount))
                        }
                    }
                    fi.textures.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                            kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                            lens.add(maxOf(1, it.arrayCount))
                        }
                    }

                    ti.images.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_TASK_BIT_EXT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                            lens.add(1)
                        }
                    }
                    mi.images.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_MESH_BIT_EXT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                            lens.add(1)
                        }
                    }
                    fi.images.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_FRAGMENT_BIT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                            lens.add(1)
                        }
                    }

                    if (kind == 0) throw IllegalArgumentException(" couldn't find $bind for $bindingList")
                    kinds.add(kind)
                }

                val layout = DescriptorSetLayout.SimpleDescriptorSetLayout(
                    parameters.renderer.device, kinds, set, bindingList, where, lens, pushDescriptor = true
                )
                layouts[set] = layout
            }

            // UBOs (sets >= 1)
            setsNeeded.entries.forEach { (set, _) ->
                var found = false
                ti.ubos.forEach { if (it.set == set) {
                    found = true
                    val sz = (it as Introspected.UBO).size.toLong()
                    val bp = buffer(it, it.name) { size = sz; source = it; uniform(); hosted() }
                    val buf = parameters.uboResolver(bp) ?: throw IllegalArgumentException("can't link UBO ${it.name}")
                    uniforms[it.name] = Module.Uniform(it.name, it, source = bp, buf.toBuffer(), VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                } }
                mi.ubos.forEach { if (it.set == set) {
                    found = true
                    val sz = (it as Introspected.UBO).size.toLong()
                    val bp = buffer(it, it.name) { size = sz; source = it; uniform(); hosted() }
                    val buf = parameters.uboResolver(bp) ?: throw IllegalArgumentException("can't link UBO ${it.name}")
                    uniforms[it.name] = Module.Uniform(it.name, it, source = bp, buf.toBuffer(), VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                } }
                fi.ubos.forEach { if (it.set == set) {
                    found = true
                    val sz = (it as Introspected.UBO).size.toLong()
                    val bp = buffer(it, it.name) { size = sz; source = it; uniform(); hosted() }
                    val buf = parameters.uboResolver(bp) ?: throw IllegalArgumentException("can't link UBO ${it.name}")
                    uniforms[it.name] = Module.Uniform(it.name, it, source = bp, buf.toBuffer(), VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                } }
                if (!found) return@forEach
            }

            // SSBOs (sets >= 1)
            setsNeeded.entries.forEach { (set, _) ->
                var found = false
                ti.ssbo.forEach { if (it.set == set) {
                    found = true
                    var sz = (it as Introspected.UBO).size.toLong()
                    if (sz == 0L) sz = parameters.ssboSizer(it)?.toLong() ?: 0L
                    val bp = buffer(it, it.name) { size = sz; source = it; storage(); hosted() }
                    val buf = parameters.ssboResolver(bp) ?: throw IllegalArgumentException("can't link SSBO buffer ${it.name}, resolver returned null")
                    uniforms[it.name] = Module.Uniform(it.name, it, source = bp, buf.buffer, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                } }
                mi.ssbo.forEach { if (it.set == set) {
                    found = true
                    var sz = (it as Introspected.UBO).size.toLong()
                    if (sz == 0L) sz = parameters.ssboSizer(it)?.toLong() ?: 0L
                    val bp = buffer(it, it.name) { size = sz; source = it; storage(); hosted() }
                    val buf = parameters.ssboResolver(bp) ?: throw IllegalArgumentException("can't link SSBO buffer ${it.name}, resolver returned null")
                    uniforms[it.name] = Module.Uniform(it.name, it, source = bp, buf.buffer, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                } }
                fi.ssbo.forEach { if (it.set == set) {
                    found = true
                    var sz = (it as Introspected.UBO).size.toLong()
                    if (sz == 0L) sz = parameters.ssboSizer(it)?.toLong() ?: 0L
                    val bp = buffer(it, it.name) { size = sz; source = it; storage(); hosted() }
                    val buf = parameters.ssboResolver(bp) ?: throw IllegalArgumentException("can't link SSBO buffer ${it.name}, resolver returned null")
                    uniforms[it.name] = Module.Uniform(it.name, it, source = bp, buf.buffer, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                } }
                if (!found) return@forEach
            }

            // Sampled textures (sets >= 1)
            setsNeeded.entries.forEach { (set, _) ->
                var found = false
                ti.textures.forEach { if (it.set == set) {
                    found = true
                    val (texture, sampler) = parameters.textureResolver(it as Introspected.Texture)!!
                    if (it.arrayCount == 0) {
                        uniforms[it.name] = Module.TexturePointer(it.name, it, source = null, texture[0], sampler)
                    } else {
                        require(texture.size == it.arrayCount) {
                            "can't link texture resource ${it.name} of length ${texture.size} but the shader is expecting ${it.arrayCount}"
                        }
                        uniforms[it.name] = Module.TextureArrayPointer(it.name, it, source = null, texture, sampler)
                    }
                } }
                mi.textures.forEach { if (it.set == set) {
                    found = true
                    val (texture, sampler) = parameters.textureResolver(it as Introspected.Texture)!!
                    if (it.arrayCount == 0) {
                        uniforms[it.name] = Module.TexturePointer(it.name, it, source = null, texture[0], sampler)
                    } else {
                        require(texture.size == it.arrayCount) {
                            "can't link texture resource ${it.name} of length ${texture.size} but the shader is expecting ${it.arrayCount}"
                        }
                        uniforms[it.name] = Module.TextureArrayPointer(it.name, it, source = null, texture, sampler)
                    }
                } }
                fi.textures.forEach { if (it.set == set) {
                    found = true
                    val (texture, sampler) = parameters.textureResolver(it as Introspected.Texture)!!
                    if (it.arrayCount == 0) {
                        uniforms[it.name] = Module.TexturePointer(it.name, it, source = null, texture[0], sampler)
                    } else {
                        require(texture.size == it.arrayCount) {
                            "can't link texture resource ${it.name} of length ${texture.size} but the shader is expecting ${it.arrayCount}"
                        }
                        uniforms[it.name] = Module.TextureArrayPointer(it.name, it, source = null, texture, sampler)
                    }
                } }
                if (!found) return@forEach
            }

            // Storage images (sets >= 1)
            setsNeeded.entries.forEach { (set, _) ->
                var found = false
                ti.images.forEach { if (it.set == set) {
                    found = true
                    val image = parameters.imageResolver(it as Introspected.Image)!!
                    uniforms[it.name] = Module.ImagePointer(it.name, it, source = null, image)
                } }
                mi.images.forEach { if (it.set == set) {
                    found = true
                    val image = parameters.imageResolver(it as Introspected.Image)!!
                    uniforms[it.name] = Module.ImagePointer(it.name, it, source = null, image)
                } }
                fi.images.forEach { if (it.set == set) {
                    found = true
                    val image = parameters.imageResolver(it as Introspected.Image)!!
                    uniforms[it.name] = Module.ImagePointer(it.name, it, source = null, image)
                } }
                if (!found) return@forEach
            }
            MemoryStack.stackPush().use { stack ->
                val shaderStages = VkPipelineShaderStageCreateInfo.calloc(3, stack)

                shaderStages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_TASK_BIT_EXT)
                    .pName(stack.UTF8("main"))
                    .module(tsm)

                shaderStages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_MESH_BIT_EXT)
                    .pName(stack.UTF8("main"))
                    .module(msm)

                shaderStages[2].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .pName(stack.UTF8("main"))
                    .module(fsm)

                val vp = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1)
                    .scissorCount(1)

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
                        .depthTestEnable(true)
                        .depthWriteEnable(true)
                        .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                        .depthBoundsTestEnable(false)
                        .stencilTestEnable(false)
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
                        // (3) BUGFIX: use dstAlpha, not dstColor
                        .dstAlphaBlendFactor(parameters.dstAlpha)
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
                        .stageFlags(VK_SHADER_STAGE_TASK_BIT_EXT or VK_SHADER_STAGE_MESH_BIT_EXT)
                        .offset(0)
                        .size(pushConstantSize)
                }

                // ---- Pipeline layout includes global set=0 at index 0
                val highestSet = layouts.keys.maxOrNull() ?: -1

                val ppLayout = if (highestSet >= 0) stack.callocLong(highestSet + 1) else null
                if (highestSet >= 0) {
                    ppLayout?.let { buf ->
                        for (s in 0..highestSet) {
                            val layout = layouts[s]
                                ?: throw IllegalArgumentException(
                                    "Pipeline layout is missing descriptor set $s. Make your module sets contiguous starting at set=0."
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
                    pipelineLayout, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, "${parameters.name} PipelineLayout"
                )

                val renderInfo = VkPipelineRenderingCreateInfoKHR.calloc(1, stack)
                    .sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR)

                val colorAttachmentFormats = stack.callocInt(colorCount)
                colorFormats.forEach { colorAttachmentFormats.put(it) }
                colorAttachmentFormats.flip()

                renderInfo
                    .colorAttachmentCount(colorCount)
                    .pColorAttachmentFormats(colorAttachmentFormats)
                    .depthAttachmentFormat(if (parameters.depth > 0) Options.defaultDepthFormat else VK_FORMAT_UNDEFINED)
                    .stencilAttachmentFormat(0)

                val gp = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(shaderStages)
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
                    if (msm != 0L) {
                        VK10.vkDestroyShaderModule(parameters.renderer.device.vkDevice, msm, null)
                    }
                    if (tsm != 0L) {
                        VK10.vkDestroyShaderModule(parameters.renderer.device.vkDevice, tsm, null)
                    }
                    if (fsm != 0L) {
                        VK10.vkDestroyShaderModule(parameters.renderer.device.vkDevice, fsm, null)
                    }
                }
                parameters.renderer.device.nameObject(
                    pipeline, VK10.VK_OBJECT_TYPE_PIPELINE, "${parameters.name} Pipeline"
                )

                // hook structures to backing buffers
                liveUniformStructures.forEach { (name, structure) ->
                    val target = (uniforms[name] as Module.Uniform).buffer
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
            val labelColor = stack.floats(
                parameters.labelColor.x.f, parameters.labelColor.y.f, parameters.labelColor.z.f, parameters.labelColor.w.f
            )
            command.beginLabel(parameters.name, labelColor)

            val cmd = command.vkCommandBuffer

            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline)

            VK13.vkCmdSetDepthTestEnable(cmd, if (parameters.depth == 0) false else parameters.depthTest)
            if (parameters.renderer.device.supportsExtendedDynamicState) {
                VK13.vkCmdSetPrimitiveTopology(cmd, parameters.primitiveType)
                VK13.vkCmdSetCullMode(cmd, VK_CULL_MODE_NONE)
                VK13.vkCmdSetFrontFace(cmd, VK_FRONT_FACE_CLOCKWISE)
            }
            if (parameters.renderer.device.supportsExtendedDynamicState3) {
                EXTExtendedDynamicState3.vkCmdSetDepthClampEnableEXT(cmd, false)
            }

            val pushConstantBuffer = if (pushConstantSize > 0) stack.malloc(pushConstantSize) else stack.malloc(0)

            uniforms.values.forEach { upd ->
                upd.pushDescriptorSet(command, pipelineLayout, VK_PIPELINE_BIND_POINT_GRAPHICS)
            }

            // Update dirty UBO/SSBOs
            liveUniformStructures.forEach { (name, structure) ->
                val target = (uniforms[name] as Module.Uniform).buffer
                structure.proxying = target
                if (structure.dirty) {
                    target.get().mapAndWrite_bytes {
                        structure.writeToBuffer(this, command) {
                            throw MissingRefException("risk of GPU null ref `$it`")
                        }
                    }
                }
            }

            // Dispatch per-mesh work
            meshes.forEach { (_, mesh) ->
                mesh.compute(this, command, pushConstantBuffer)
            }

            command.endLabel()
        }
    }

    fun copyMeshesFrom(old: MeshModule) {
        old.meshes.entries.removeIf {
            meshes[it.key] = it.value
            (it.value as? MeshMesh)?.let { mm -> mm.inside = this.pointer() }
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
        if (msm != 0L) {
            VK10.vkDestroyShaderModule(parameters.renderer.device.vkDevice, msm, null)
            msm = 0L
        }
        if (tsm != 0L) {
            VK10.vkDestroyShaderModule(parameters.renderer.device.vkDevice, tsm, null)
            tsm = 0L
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
            vkCheck(vkCreateShaderModule(parameters.renderer.device.vkDevice, ci, null, lp),
                "Failed to create shader module")
            return lp[0]
        }
    }

    operator fun plusAssign(m: Pair<String, MeshMesh>) {
        meshes[m.first] = m.second
        m.second.inside = this.pointer()
    }

}
