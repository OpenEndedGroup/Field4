package afield.four.modules

import afield.four.buffer
import afield.four.buffers.MissingRefException
import afield.four.buffers.Structure
import afield.four.device.CommandBuffer
import afield.four.device.DescriptorSet
import afield.four.device.DescriptorSet.SimpleDescriptorSet
import afield.four.device.DescriptorSetLayout
import afield.four.device.DescriptorSetLayout.SimpleDescriptorSetLayout
import afield.four.modules.Module.*
import afield.four.util.vkCheck
import afield.logger.lprintln
import org.joml.primitives.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*
import java.nio.ByteBuffer
import java.nio.LongBuffer


class ComputeModulePointer(var current: ComputeModule) : Renderable {
    override fun render(command: CommandBuffer, bounds: Rectanglef) {
        if (current.valid) current.render(command, bounds)
        else {
            println("module '${current.parameters.name}' not valid / not rendering")
        }
    }

    fun reload(p: Parameters, onError: (String, Throwable?) -> Unit = p.errorHandler): Boolean {
        if (current.valid && p.equals(current.parameters)) return false

        var previous = current

        p.errorHandler = onError

        val oUboResolver = p.uboResolver
        p.uboResolver = {
            oUboResolver(it)
        }

        // the goal here would be to marry the existing contents of current.uniform with the incoming parameter and see if it's compatible
        // if so, then just copy it across. If not, then we need to re-resolve it
        // (potentially severing any connection we have established elsewhere with this buffer)

        // TODO: this currently scales O(N) with the number of reloads
        val oSsboResolver = p.ssboResolver
        p.ssboResolver = {
            val previous = current.uniforms[it.name]
            if (previous == null)
                oSsboResolver(it)
            else {
                val previousSource = previous.source
                if (previousSource == null)
                    oSsboResolver(it)
                else {

                    // previousSource is a 'stored' version of the original creation parameters
                    if (previousSource.checkCompatibility(it) == false) {
                        oSsboResolver(it)
                    } else {
                        oSsboResolver(previousSource)
                    }
                }
            }
        }

        val oTextureResolver = p.textureResolver
        p.textureResolver = {
            try {
                (current.uniforms[it.name] as TexturePointer).texture to (current.uniforms[it.name] as TexturePointer).sampler
            } catch (e: Exception) {
                lprintln(" couldn't transfer over texture ${it.name}, falling back to originating resolution")
                oTextureResolver(it)
            }
        }

        val m = ComputeModule(p)
        if (m.valid) {
            m.copyMeshesFrom(current)
            current = m
            return true
        }
        return false
    }

    operator fun plusAssign(m: Pair<String, ComputeTask>) {
        current.meshes[m.first] = m.second
        (m.second as? ComputeMesh)?.let {
            it.inside = this
        }
    }
}

fun ComputeModule.pointer() = ComputeModulePointer(this)


class ComputeModule(val parameters: Parameters) : Renderable {
    var valid = false

    val meshes = mutableMapOf<String, ComputeTask>()

    val ci = Introspection(
        parameters.compute, ShaderKind.COMPUTE_SHADER, errorHandler = parameters.errorHandler
    )

    val csm = createShaderModule(ci.code.bytecode()!!)

    val pushConstantSize = (ci.pushConstants.associateBy { it.name }).values.sumOf { it.size }

    var layouts: List<Pair<DescriptorSetLayout.SimpleDescriptorSetLayout, MutableList<Introspected.Bindable>>> =
        listOf()

    var uniforms: MutableMap<String, DescriptorSet.UpdatesDescriptorSet> = mutableMapOf()
    var liveUniformStructures: MutableMap<String, Structure> = mutableMapOf()

    var pipelineLayout: Long = 0L
    var pipeline: Long = 0L

    init {


        try {

            if (!ci.valid) throw IllegalStateException("Can't make a module without valid programs")


            val setsNeeded = mutableMapOf<Int, MutableList<Int>>()

            ci.ubos.forEach {
                setsNeeded.computeIfAbsent(it.set) { mutableListOf<Int>() }.add(it.binding)
            }

            ci.ssbo.forEach {
                setsNeeded.computeIfAbsent(it.set) { mutableListOf<Int>() }.add(it.binding)
            }

            ci.textures.forEach {
                setsNeeded.computeIfAbsent(it.set) { mutableListOf<Int>() }.add(it.binding)
            }

            ci.images.forEach {
                setsNeeded.computeIfAbsent(it.set) { mutableListOf<Int>() }.add(it.binding)
            }

            uniforms = mutableMapOf<String, DescriptorSet.UpdatesDescriptorSet>()
            liveUniformStructures = mutableMapOf<String, Structure>()

            ci.ubos.forEach {
                liveUniformStructures[it.name] = Structure().setMembers(it.members)
            }

            ci.ssbo.forEach {
                liveUniformStructures[it.name] = Structure().setMembers(it.members)
            }

            val layouts = mutableMapOf<Int, Pair<SimpleDescriptorSetLayout, SimpleDescriptorSet>>()

            setsNeeded.entries.forEach { (set, bindingList) ->
                var where = 0
                var tag = mutableListOf<Introspected.Bindable>()
                var kinds = mutableListOf<Int>()
                bindingList.forEach { bind ->
                    var kind = 0
                    ci.ubos.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_COMPUTE_BIT
                            kind = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                            tag.add(it)
                        }
                    }
                    ci.ssbo.forEach {
                        if (it.set == set && it.binding == bind) {
                            where = where or VK_SHADER_STAGE_COMPUTE_BIT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                            tag.add(it)
                        }
                    }
                    ci.textures.forEach {
                        if (it.set == set) {
                            where = where or VK_SHADER_STAGE_COMPUTE_BIT
                            kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                            tag.add(it)
                        }
                    }
                    ci.images.forEach {
                        if (it.set == set) {
                            where = where or VK_SHADER_STAGE_VERTEX_BIT
                            kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                            tag.add(it)
                        }
                    }
                    if (kind == 0) throw IllegalArgumentException(" couldn't find ${bind} for ${bindingList}")
                    kinds.add(kind)
                }


                val layout = DescriptorSetLayout.SimpleDescriptorSetLayout(
                    parameters.renderer.device, kinds, set, bindingList, where
                )

                val descriptorSet = layout.makeDescriptorSet(parameters.renderer.device.pool)

                layouts.put(set, layout to descriptorSet)
            }



            setsNeeded.entries.forEach { (set, bindingList) ->
                var where = 0
                var kind = 0
                var tag = mutableListOf<Introspected.Bindable>()
                ci.ubos.forEach {
                    if (it.set == set) {
                        where = where or VK_SHADER_STAGE_COMPUTE_BIT
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
                            uniforms[it.name] = Uniform(it.name, it, descriptorSet, source = buf, buf.toBuffer(), kind)
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
                ci.ssbo.forEach {
                    if (it.set == set) {
                        where = where or VK_SHADER_STAGE_COMPUTE_BIT
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
                            uniforms[it.name] = Uniform(it.name, it, descriptorSet, source = buf, buf.buffer, kind)
                        } catch (e: NullPointerException) {
                            throw IllegalArgumentException("buffer ${it.name} resolved to null")
                        }
                    } else
                        throw IllegalArgumentException("can't link SSBO buffer ${it.name}, resolver returned null")

                }
            }


            setsNeeded.entries.forEach { (set, bindingList) ->
                var where = 0
                var kind = 0
                var tag = mutableListOf<Introspected.Bindable>()
                ci.textures.forEach {
                    if (it.set == set) {
                        where = where or VK_SHADER_STAGE_COMPUTE_BIT
                        kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                        tag.add(it)
                    }
                }

                if (kind == 0) return@forEach

                val (layout, descriptorSet) = layouts.get(set)!!

                tag.forEach {
                    println(" resolving texture resource $it")
                    val (texture, sampler) = parameters.textureResolver(it as Introspected.Texture)!!
                    uniforms[it.name] = TexturePointer(
                        it.name, it as Introspected.Texture, descriptorSet, source = null, texture, sampler
                    )
                }
            }

            setsNeeded.entries.forEach { (set, bindingList) ->
                var where = 0
                var kind = 0
                var tag = mutableListOf<Introspected.Bindable>()
                ci.images.forEach {
                    if (it.set == set) {
                        where = where or VK_SHADER_STAGE_COMPUTE_BIT
                        kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                        tag.add(it)
                    }
                }


                if (kind == 0) return@forEach

                val (layout, descriptorSet) = layouts.get(set)!!

                tag.forEach {
                    println(" resolving image resource $it")
                    val image = parameters.imageResolver(it as Introspected.Image)!!
                    uniforms[it.name] = ImagePointer(it.name, it, descriptorSet, source = null, image)
                }

            }

            uniforms.forEach {
                it.value.updateDescriptorSet()
            }


            MemoryStack.stackPush().use { stack ->

                val shaderStages = VkPipelineShaderStageCreateInfo.calloc(1, stack)

                shaderStages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).pName(stack.UTF8("main")).module(csm)


                var vpcr: VkPushConstantRange.Buffer? = null
                if (pushConstantSize > 0) {
                    vpcr = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0)
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


                val pipeline =
                    VkComputePipelineCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                        .stage(shaderStages[0]).layout(pipelineLayout)

                vkCheck(
                    vkCreateComputePipelines(
                        parameters.renderer.device.vkDevice,
                        parameters.renderer.pipelineCache.vkPipelineCache,
                        pipeline,
                        null,
                        lp
                    ), "Error creating compute pipeline"
                )

                liveUniformStructures.forEach { (name, structure) ->
                    val target = (uniforms[name] as Uniform).buffer
                    structure.proxying = target
                }

                this.pipeline = lp[0]
            }


            valid = true
        } catch (e: Exception) {
            parameters.errorHandler("error `${e.message}`", e)
        }
    }

    override fun render(command: CommandBuffer, bounds: Rectanglef) {
        if (!valid) return
        MemoryStack.stackPush().use { stack ->

//            println(" -- rendering ${parameters.name} >>>>>>>>>>>>>>>>>>>>>>>>>>")

            // RENDER ----------------------------------------------------------

            val cmdHandle = command.vkCommandBuffer

            VK10.vkCmdBindPipeline(cmdHandle, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline)

            val offsets = stack.mallocLong(1)
            offsets.put(0, 0L)
            val pushConstantBuffer = stack.malloc(pushConstantSize)

            val allSets = uniforms.map { it.value.binding.set }
            val numSets = allSets.distinct().count()
            val descriptorSets: LongBuffer = stack.mallocLong(numSets)

            uniforms.forEach {
                descriptorSets.put(it.value.binding.set, it.value.target.vkDescriptorSet)
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
                target.get().clean(false, command)
            }

            if (descriptorSets.limit() > 0) vkCmdBindDescriptorSets(
                cmdHandle, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, descriptorSets, null
            );

            meshes.values.forEach { mesh ->
                mesh.compute(this, command, pushConstantBuffer)
            }

            liveUniformStructures.forEach { (name, structure) ->
                val target = (uniforms[name] as Uniform).buffer
                target.get().clean(true, command)
            }
        }
    }

    fun copyMeshesFrom(old: ComputeModule) {
        old.meshes.entries.removeIf {
            this.meshes.put(it.key, it.value)
            (it.value as? ComputeMesh)?.let {
                it.inside = this.pointer()
            }
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


}

