package afield.four.modules

import afield.four.buffers.buffer
import afield.four.buffers.MissingRefException
import afield.four.buffers.Structure
import afield.four.device.CommandBuffer
import afield.four.device.DescriptorSet
import afield.four.device.DescriptorSet.SimpleDescriptorSet
import afield.four.device.DescriptorSetLayout
import afield.four.device.DescriptorSetLayout.SimpleDescriptorSetLayout
import afield.four.device.beginLabel
import afield.four.device.endLabel
import afield.four.device.nameObject
import afield.four.modules.Module.ImagePointer
import afield.four.modules.Module.TextureArrayPointer
import afield.four.modules.Module.TexturePointer
import afield.four.modules.Module.Uniform
import afield.four.util.vkCheck
import afield.logger.lprintln
import afield.stdlib.f
import org.joml.primitives.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*
import java.nio.ByteBuffer
import kotlin.math.max

class ComputeModulePointer(var current: ComputeModule) : Renderable {
    override fun render(command: CommandBuffer, bounds: Rectanglef) {
        if (current.valid) current.render(command, bounds)
        else println("module '${current.parameters.name}' not valid / not rendering")
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
                arrayOf(tp.texture) to tp.sampler
            } catch (_: Exception) {
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
        (m.second as? ComputeMesh)?.let { it.inside = this }
    }
}

fun ComputeModule.pointer() = ComputeModulePointer(this)

class ComputeModule(val parameters: Parameters) : Renderable {
    var valid = false

    val meshes = mutableMapOf<String, ComputeTask>()

    val ci = Introspection(parameters.compute, ShaderKind.COMPUTE_SHADER, errorHandler = parameters.errorHandler)

    val csm: Long = createShaderModule(ci.code.bytecode()!!).also {
        // Name the compute shader module for tooling (no-op if EXT_debug_utils unavailable)
        parameters.renderer.device.nameObject(it, VK10.VK_OBJECT_TYPE_SHADER_MODULE, "${parameters.name} CS")
    }

    val pushConstantSize = (ci.pushConstants.associateBy { it.name }).values.sumOf { it.size }

    // (class-level 'layouts' list unused; keep API parity with graphics modules if needed)
    var layouts: List<Pair<DescriptorSetLayout.SimpleDescriptorSetLayout, MutableList<Introspected.Bindable>>> = listOf()
    var uniforms: MutableMap<String, DescriptorSet.UpdatesDescriptorSet> = mutableMapOf()
    var liveUniformStructures: MutableMap<String, Structure> = mutableMapOf()

    var pipelineLayout: Long = 0L
    var pipeline: Long = 0L

    init {
        try {
            if (!ci.valid) throw IllegalStateException("Can't make a module without valid programs")

            // --- Reserve & validate set=0 usage (global bindless sampled-image array + sampler)
            validateAndReserveSet0(ci)

            // Build per-module sets for set >= 1 (set 0 is global)
            val setsNeeded = mutableMapOf<Int, MutableList<Int>>()
            ci.ubos.forEach     { if (it.set != 0) setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            ci.ssbo.forEach     { if (it.set != 0) setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            ci.textures.forEach { if (it.set != 0) setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }
            ci.images.forEach   { if (it.set != 0) setsNeeded.computeIfAbsent(it.set) { mutableListOf() }.add(it.binding) }

            // Dedup + sort bindings per set to ensure deterministic, contiguous binding lists (Fix #2)
            setsNeeded.keys.toList().forEach { s ->
                setsNeeded[s] = setsNeeded[s]!!.distinct().sorted().toMutableList()
            }

            uniforms = mutableMapOf()
            liveUniformStructures = mutableMapOf()

            ci.ubos.forEach  { liveUniformStructures[it.name] = Structure().setMembers(it.members) }
            ci.ssbo.forEach  { liveUniformStructures[it.name] = Structure().setMembers(it.members) }

            // Use a distinct name to avoid shadowing the class property 'layouts'
            val layoutMap = mutableMapOf<Int, Pair<SimpleDescriptorSetLayout, SimpleDescriptorSet>>()

            // Build descriptor set layouts (sets >= 1)
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
                    parameters.renderer.device, kinds, set, bindingList, stages, lens
                )
                val descriptorSet = layout.makeDescriptorSet(parameters.renderer.device.pool)
                layoutMap[set] = layout to descriptorSet
            }

            // UBOs (sets >= 1)
            setsNeeded.entries.forEach { (set, _) ->
                var kind = 0
                val tag  = mutableListOf<Introspected.Bindable>()
                ci.ubos.forEach { if (it.set == set) { kind = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER; tag.add(it) } }
                if (kind == 0) return@forEach

                val (_, descriptorSet) = layoutMap.getValue(set)
                tag.forEach {
                    var sz = (it as Introspected.UBO).size.toLong()
                    val bp = buffer(it, it.name) { size = sz; source = it; uniform(); hosted() }
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
                val tag  = mutableListOf<Introspected.Bindable>()
                ci.ssbo.forEach { if (it.set == set) { kind = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; tag.add(it) } }
                if (kind == 0) return@forEach

                val (_, descriptorSet) = layoutMap.getValue(set)
                tag.forEach {
                    var sz = (it as Introspected.UBO).size.toLong()
                    if (sz == 0L) sz = parameters.ssboSizer(it)?.toLong() ?: 0L

                    val bp = buffer(it, it.name) { size = sz; source = it; storage(); hosted() }
                    val buf = parameters.ssboResolver(bp)
                        ?: throw IllegalArgumentException("can't link SSBO buffer ${it.name}, resolver returned null")
                    try {
                        uniforms[it.name] = Uniform(it.name, it, descriptorSet, source = bp, buf.buffer, kind)
                    } catch (_: NullPointerException) {
                        throw IllegalArgumentException("buffer ${it.name} resolved to null")
                    }
                }
            }

            // Sampled textures (sets >= 1)
            setsNeeded.entries.forEach { (set, _) ->
                var kind = 0
                val tag  = mutableListOf<Introspected.Bindable>()
                ci.textures.forEach { if (it.set == set) { kind = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; tag.add(it) } }
                if (kind == 0) return@forEach

                val (_, descriptorSet) = layoutMap.getValue(set)
                tag.forEach {
                    val (texture, sampler) = parameters.textureResolver(it as Introspected.Texture)!!
                    if (it.arrayCount == 0) {
                        uniforms[it.name] = TexturePointer(it.name, it, descriptorSet, source = null, texture[0], sampler)
                    } else {
                        require(texture.size == it.arrayCount) {
                            "can't link texture resource ${it.name} of length ${texture.size} but the shader is expecting ${it.arrayCount}"
                        }
                        uniforms[it.name] = TextureArrayPointer(it.name, it, descriptorSet, source = null, texture, sampler)
                    }
                }
            }

            // Storage images (sets >= 1)
            setsNeeded.entries.forEach { (set, _) ->
                var kind = 0
                val tag  = mutableListOf<Introspected.Bindable>()
                ci.images.forEach { if (it.set == set) { kind = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; tag.add(it) } }
                if (kind == 0) return@forEach

                val (_, descriptorSet) = layoutMap.getValue(set)
                tag.forEach {
                    val image = parameters.imageResolver(it as Introspected.Image)!!
                    uniforms[it.name] = ImagePointer(it.name, it, descriptorSet, source = null, image)
                }
            }

            uniforms.values.forEach { it.updateDescriptorSet() }

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

                // Always include renderer's set-0 layout first so global bindless set can be bound.
                val highestUsedSet = max(0, layoutMap.keys.maxOrNull() ?: 0)
                val ppLayout = stack.callocLong(highestUsedSet + 1)
                // index 0: global layout (sampled-image array @0 + sampler @1)
                ppLayout.put(0, parameters.renderer.set0Layout.vkDescriptorLayout)

                // Fill remaining sets; require contiguity (1..highest)
                for (s in 1..highestUsedSet) {
                    val pair = layoutMap[s] ?: throw IllegalArgumentException(
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
                parameters.renderer.device.nameObject(
                    pipeline, VK10.VK_OBJECT_TYPE_PIPELINE, "${parameters.name} Pipeline"
                )

                // Hook live structures to their backing buffers
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
        if (!valid) return
        MemoryStack.stackPush().use { stack ->
            // Label the entire compute dispatch region
            val labelColor = stack.floats(
                parameters.labelColor.x.f, parameters.labelColor.y.f,
                parameters.labelColor.z.f, parameters.labelColor.w.f
            )
            command.beginLabel(parameters.name, labelColor)

            val cmd = command.vkCommandBuffer
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline)

            // Bind global set=0 (sampled-image array + sampler) for compute
            parameters.renderer.bindGlobalSet0(command, pipelineLayout, VK10.VK_PIPELINE_BIND_POINT_COMPUTE)

            val pushConstantBuffer = if (pushConstantSize > 0) stack.malloc(pushConstantSize) else stack.malloc(0)

            // Update UBO/SSBO contents before dispatch
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

            // Bind per-module sets densely in [firstSet..lastSet] (set 0 is global and bound separately)
            if (uniforms.isNotEmpty()) {
                val uniqueSets = uniforms.values.map { it.binding.set }.toSet()
                val firstSet = uniqueSets.minOrNull()!!
                val lastSet  = uniqueSets.maxOrNull()!!
                val setCount = lastSet - firstSet + 1

                // Allocate zeroed buffer without advancing position
                val descriptorSets = stack.callocLong(setCount)

                // Absolute writes do not change position
                uniforms.values.forEach { upd ->
                    val slot = upd.binding.set - firstSet
                    descriptorSets.put(slot, upd.target.vkDescriptorSet)
                }

                // Ensure the buffer position is 0 for the JNI call
                descriptorSets.position(0)

                vkCmdBindDescriptorSets(
                    cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, firstSet, descriptorSets, null
                )
            }

            meshes.values.forEach { mesh ->
                mesh.compute(this, command, pushConstantBuffer)
            }

            // Mark SSBOs clean post-dispatch — keep as-needed depending on your write tracking
            liveUniformStructures.forEach { (_, _) -> }

            command.endLabel()
        }
    }

    fun copyMeshesFrom(old: ComputeModule) {
        old.meshes.entries.removeIf {
            meshes[it.key] = it.value
            (it.value as? ComputeMesh)?.let { cm -> cm.inside = this.pointer() }
            true
        }
    }

    fun cleanup() {
        // pipelines/shader modules are managed at renderer lifetime; no-op here
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

    /**
     * set=0 is reserved for: sampled-image array (binding=0) and a single sampler binding (binding=1).
     * No UBO/SSBO/storage image in set=0.
     */
    private fun validateAndReserveSet0(ci: Introspection) {
        val illegal = mutableListOf<String>()

        ci.ubos  .filter { it.set == 0 }.forEach { illegal += "UBO '${it.name}' (binding ${it.binding})" }
        ci.ssbo  .filter { it.set == 0 }.forEach { illegal += "SSBO '${it.name}' (binding ${it.binding})" }
        ci.images.filter { it.set == 0 }.forEach { illegal += "storage image '${it.name}' (binding ${it.binding})" }

        if (illegal.isNotEmpty()) {
            val msg = buildString {
                appendLine("set=0 is reserved for the global bindless sampled-image array (binding 0) and a single sampler binding (binding 1).")
                appendLine("These declarations in set=0 are not allowed:")
                illegal.forEach { appendLine("  - $it") }
                appendLine("Fix: move them to set>=1, or convert sampled textures in set=0 to use the global array.")
            }
            throw IllegalArgumentException(msg)
        }

        val set0Textures = ci.textures.filter { it.set == 0 }
        if (set0Textures.isNotEmpty()) {
            val distinctBindings = set0Textures.map { it.binding }.toSet()
            if (distinctBindings.size != 1 || distinctBindings.first() != 0) {
                val msg = buildString {
                    appendLine("set=0 sampled images must use **binding 0** (global array).")
                    appendLine("Found bindings: ${distinctBindings.sorted()}")
                    appendLine("Fix: declare textures at set=0,binding=0 (sampler is set=0,binding=1).")
                }
                throw IllegalArgumentException(msg)
            }
        }

        val set0Samplers = ci.samplers.filter { it.set == 0 }
        if (set0Samplers.isNotEmpty()) {
            val samplerBindings = set0Samplers.map { it.location }.toSet()
            if (samplerBindings.size != 1 || samplerBindings.first() != 1) {
                val msg = buildString {
                    appendLine("set=0 separate samplers must use **binding 1** (single global sampler binding).")
                    appendLine("Found sampler bindings: ${samplerBindings.sorted()}")
                    appendLine("Fix: declare the sampler at set=0,binding=1.")
                }
                throw IllegalArgumentException(msg)
            }
        }
    }

}
