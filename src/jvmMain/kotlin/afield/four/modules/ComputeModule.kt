package afield.four.modules

import afield.four.buffers.MissingRefException
import afield.four.buffers.Structure
import afield.four.buffers.buffer
import afield.four.device.*
import afield.four.device.DescriptorSetLayout.SimpleDescriptorSetLayout
import afield.four.modules.Module.*
import afield.four.util.AutoDisposer
import afield.four.util.vkCheck
import afield.logger.lprintln
import afield.stdlib.f
import org.joml.primitives.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*
import java.nio.ByteBuffer

class ComputeModulePointer(var current: ComputeModule) : Renderable {
    override fun render(command: CommandBuffer, bounds: Rectanglef) {
        if (current.valid) current.render(command, bounds)
        else {
//            println("module '${current.parameters.name}' not valid / not rendering")
        }
    }

    fun reload(p: Parameters, onError: (String, Throwable?) -> Unit = p.errorHandler): Boolean {
        if (current.valid && p == current.parameters) return false

        p.errorHandler = onError

        val oUboResolver = p.uboResolver
        p.uboResolver = { oUboResolver(it) }

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
            try {
                val tp = current.uniforms[it.name] as TexturePointer
                tp.texture to tp.sampler
            } catch (_: Exception) {
                lprintln("warning,graphics", "couldn't transfer over texture ${it.name}, falling back to originating resolution")
                oTextureResolver(it)
            }
        }

        val m = ComputeModule(p)
        if (m.valid) {
            m.copyMeshesFrom(current)
            m.enabled = current.enabled
            current = m
            return true
        }
        return false
    }

    operator fun plusAssign(m: Pair<String, ComputeTask>) {
        current.meshes[m.first] = m.second
        (m.second as? ComputeMesh)?.let { it.inside = this }
    }

    fun enable() {
        current.enable()
    }

    fun disable() {
        current.disable()
    }
}

fun ComputeModule.pointer() = ComputeModulePointer(this)

class ComputeModule(val parameters: Parameters) : Renderable {
    var valid = false
    var enabled = true

    val meshes = mutableMapOf<String, ComputeTask>()

    val ci = Introspection(parameters.compute, ShaderKind.COMPUTE_SHADER, errorHandler = parameters.errorHandler)

    var csm: Long = createShaderModule(ci.code.bytecode()!!).also {
        parameters.renderer.device.nameObject(it, VK10.VK_OBJECT_TYPE_SHADER_MODULE, "${parameters.name} CS")
    }

    val pushConstantSize = (ci.pushConstants.associateBy { it.name }).values.sumOf { it.size }

    var uniforms: MutableMap<String, UpdatesDescriptorSet> = mutableMapOf()
    var liveUniformStructures: MutableMap<String, Structure> = mutableMapOf()

    var pipelineLayout: Long = 0L
    var pipeline: Long = 0L
    private val pipelineDisposeToken = AutoDisposer.register(this, "ComputePipeline(${parameters.name})") { }

    init {
        try {
            if (!ci.valid) throw IllegalStateException("Can't make a module without valid programs")

            // Build per-module sets for all shader-declared sets (including set 0)
            val setsNeeded = mutableMapOf<Int, MutableList<Int>>()
            ci.ubos.forEach     { setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            ci.ssbo.forEach     { setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            ci.textures.forEach { setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            ci.images.forEach   { setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }

            setsNeeded.keys.toList().forEach { s ->
                setsNeeded[s] = setsNeeded[s]!!.distinct().sorted().toMutableList()
            }

            uniforms = mutableMapOf()
            liveUniformStructures = mutableMapOf()

            ci.ubos.forEach  { liveUniformStructures[it.name] = Structure().setMembers(it.members) }
            ci.ssbo.forEach  { liveUniformStructures[it.name] = Structure().setMembers(it.members) }

            val layoutMap = mutableMapOf<Int, SimpleDescriptorSetLayout>()

            // Build push-descriptor set layouts
            setsNeeded.entries.forEach { (set, bindingList) ->
                var stages = 0
                val kinds = mutableListOf<Int>()
                val lens  = mutableListOf<Int>()

                bindingList.forEach { bind ->
                    var kind = 0
                    ci.ubos.forEach {
                        if (it.set == set && it.binding == bind) {
                            stages = stages or VK_SHADER_STAGE_COMPUTE_BIT
                            kind = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                            lens.add(1)
                        }
                    }
                    ci.ssbo.forEach {
                        if (it.set == set && it.binding == bind) {
                            stages = stages or VK_SHADER_STAGE_COMPUTE_BIT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                            lens.add(1)
                        }
                    }
                    ci.textures.forEach {
                        if (it.set == set && it.binding == bind) {
                            stages = stages or VK_SHADER_STAGE_COMPUTE_BIT
                            kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                            lens.add(maxOf(1, it.arrayCount))
                        }
                    }
                    ci.images.forEach {
                        if (it.set == set && it.binding == bind) {
                            stages = stages or VK_SHADER_STAGE_COMPUTE_BIT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                            lens.add(1)
                        }
                    }

                    if (kind == 0) throw IllegalArgumentException(" couldn't find binding $bind for set $set in $bindingList")
                    kinds.add(kind)
                }

                val layout = DescriptorSetLayout.SimpleDescriptorSetLayout(
                    parameters.renderer.device, kinds, set, bindingList, stages, lens, pushDescriptor = true
                )
                layoutMap[set] = layout
            }

            // UBOs
            setsNeeded.entries.forEach { (set, _) ->
                var kind = 0
                val tag  = mutableListOf<Introspected.Bindable>()
                ci.ubos.forEach { if (it.set == set) { kind = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER; tag.add(it) } }
                if (kind == 0) return@forEach

                tag.forEach {
                    var sz = (it as Introspected.UBO).size.toLong()
                    val bp = buffer(it, it.name) { size = sz; source = it; uniform(); hosted() }
                    val buf = parameters.uboResolver(bp)
                        ?: throw IllegalArgumentException("can't link UBO ${it.name}")
                    uniforms[it.name] = Uniform(it.name, it, source = bp, buf.toBuffer(), kind)
                }
            }

            // SSBOs
            setsNeeded.entries.forEach { (set, _) ->
                var kind = 0
                val tag  = mutableListOf<Introspected.Bindable>()
                ci.ssbo.forEach { if (it.set == set) { kind = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; tag.add(it) } }
                if (kind == 0) return@forEach

                tag.forEach {
                    var sz = (it as Introspected.UBO).size.toLong()
                    if (sz == 0L) sz = parameters.ssboSizer(it)?.toLong() ?: 0L

                    val bp = buffer(it, it.name) { size = sz; source = it; storage(); hosted() }
                    val buf = parameters.ssboResolver(bp)
                        ?: throw IllegalArgumentException("can't link SSBO buffer ${it.name}, resolver returned null")
                    uniforms[it.name] = Uniform(it.name, it, source = bp, buf.toBuffer(parameters.renderer), kind)
                }
            }

            // Sampled textures
            setsNeeded.entries.forEach { (set, _) ->
                var kind = 0
                val tag  = mutableListOf<Introspected.Bindable>()
                ci.textures.forEach { if (it.set == set) { kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; tag.add(it) } }
                if (kind == 0) return@forEach

                tag.forEach {
                    val (texture, sampler) = parameters.textureResolver(it as Introspected.Texture)!!
                    val views = texture.imageViewArray
                    require(views.isNotEmpty()) { "Texture ${it.name} resolved to empty image view array" }

                    if (it.arrayCount == 0) {
                        uniforms[it.name] = TexturePointer(it.name, it, source = null, texture, sampler)
                    } else {
                        require(views.size == it.arrayCount) {
                            "can't link texture resource ${it.name} of length ${views.size} but the shader is expecting ${it.arrayCount}"
                        }
                        uniforms[it.name] = TextureArrayPointer(it.name, it, source = null, texture, sampler)
                    }
                }
            }

            // Storage images
            setsNeeded.entries.forEach { (set, _) ->
                var kind = 0
                val tag  = mutableListOf<Introspected.Bindable>()
                ci.images.forEach { if (it.set == set) { kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; tag.add(it) } }
                if (kind == 0) return@forEach

                tag.forEach {
                    val image = parameters.imageResolver(it as Introspected.Image)!!
                    uniforms[it.name] = ImagePointer(it.name, it, source = null, image)
                }
            }

            // --- Pipeline creation
            MemoryStack.stackPush().use { stack ->
                val stage = VkPipelineShaderStageCreateInfo.calloc(1, stack)
                stage[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .pName(stack.UTF8("main"))
                    .module(csm)

                var vpcr: VkPushConstantRange.Buffer? = null
                if (pushConstantSize > 0) {
                    vpcr = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                        .offset(0)
                        .size(pushConstantSize)
                }

                val highestUsedSet = layoutMap.keys.maxOrNull() ?: -1
                val ppLayout = if (highestUsedSet >= 0) stack.callocLong(highestUsedSet + 1) else null
                if (highestUsedSet >= 0) {
                    ppLayout?.let { buf ->
                        for (s in 0..highestUsedSet) {
                            val layout = layoutMap[s] ?: throw IllegalArgumentException(
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
                    pipelineLayout, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, "${parameters.name} PipelineLayout"
                )

                val cp = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .stage(stage[0])
                    .layout(pipelineLayout)

                vkCheck(
                    vkCreateComputePipelines(
                        parameters.renderer.device.vkDevice,
                        parameters.renderer.pipelineCache.vkPipelineCache,
                        cp, null, lp
                    ),
                    "Error creating compute pipeline"
                )
                this.pipeline = lp[0]
                pipelineDisposeToken.updateCleanup {
                    if (pipeline != 0L) {
                        VK10.vkDestroyPipeline(parameters.renderer.device.vkDevice, pipeline, null)
                    }
                    if (pipelineLayout != 0L) {
                        VK10.vkDestroyPipelineLayout(parameters.renderer.device.vkDevice, pipelineLayout, null)
                    }
                    if (csm != 0L) {
                        VK10.vkDestroyShaderModule(parameters.renderer.device.vkDevice, csm, null)
                    }
                }
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
            parameters.errorHandler("error `${e.message}`", e)
        }
    }

    override fun render(command: CommandBuffer, bounds: Rectanglef) {
        if (!valid || !enabled) return
        MemoryStack.stackPush().use { stack ->
            val labelColor = stack.floats(
                parameters.labelColor.x.f, parameters.labelColor.y.f,
                parameters.labelColor.z.f, parameters.labelColor.w.f
            )
            command.beginLabel(parameters.name, labelColor)

            val cmd = command.vkCommandBuffer
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline)

            val pushConstantBuffer = if (pushConstantSize > 0) stack.malloc(pushConstantSize) else stack.malloc(0)

            liveUniformStructures.forEach { (name, structure) ->
                val target = (uniforms[name] as Uniform).buffer
                structure.proxying = target
                if (structure.dirty || structure.bindings.size>0) {
                    target.get().mapAndWrite_bytes {
                        structure.writeToBuffer(this, command) {
                            throw MissingRefException("risk of GPU null ref `$it`")
                        }
                    }
                }
                target.get().clean(false, command)
            }

            uniforms.values.forEach { upd ->
                upd.pushDescriptorSet(command, pipelineLayout, VK10.VK_PIPELINE_BIND_POINT_COMPUTE)
            }

            meshes.values.forEach { mesh ->
                mesh.compute(this, command, pushConstantBuffer)
            }



            command.endLabel()
        }
    }

    fun enable() {
        enabled = true
    }

    fun disable() {
        enabled = false
    }

    fun copyMeshesFrom(old: ComputeModule) {
        old.meshes.entries.removeIf {
            meshes[it.key] = it.value
            (it.value as? ComputeMesh)?.let { cm -> cm.inside = this.pointer() }
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
        if (csm != 0L) {
            VK10.vkDestroyShaderModule(parameters.renderer.device.vkDevice, csm, null)
            csm = 0L
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
}
