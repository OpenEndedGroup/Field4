package afield.four.modules

import afield.Named
import afield.four.Target
import afield.four.buffers.BufferParameters
import afield.four.buffers.MissingRefException
import afield.four.buffers.Structure
import afield.four.buffers.VulkanBuffer
import afield.four.buffers.bindPushConstant
import afield.four.buffers.buffer
import afield.four.buffers.getBoundPushConstant
import afield.four.buffers.getPushConstant
import afield.four.buffers.setPushConstant
import afield.four.device.CommandBuffer
import afield.four.device.DescriptorSetLayout
import afield.four.device.UpdatesDescriptorSet
import afield.four.device.beginLabel
import afield.four.device.endLabel
import afield.four.device.nameObject
import afield.four.texture.HasImageViewArray
import afield.four.texture.TextureSampler
import afield.four.util.AutoDisposer
import afield.four.util.FIELD_PROBE_BUFFER_NAME
import afield.four.util.vkCheck
import afield.logger.lprintln
import afield.stdlib.f
import afield.stdlib.linalg.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.KHRAccelerationStructure.*
import org.lwjgl.vulkan.KHRRayTracingPipeline.*
import org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.*
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.reflect.KProperty

data class RayTracingShaderStage(
    val name: String,
    val kind: ShaderKind,
    val source: String,
    val introspection: Introspection? = null,
    val sourceName: String = "$name.glsl",
    val includeResolver: ShaderIncludeResolver? = null
)

data class RayTracingHitGroup(
    val name: String,
    val closestHit: RayTracingShaderStage? = null,
    val anyHit: RayTracingShaderStage? = null,
    val intersection: RayTracingShaderStage? = null
)

data class RayTracingGroups(
    var raygen: RayTracingShaderStage? = null,
    val miss: MutableList<RayTracingShaderStage> = mutableListOf(),
    val hit: MutableList<RayTracingHitGroup> = mutableListOf(),
    val callable: MutableList<RayTracingShaderStage> = mutableListOf()
) {
    companion object {
        fun fromParameters(parameters: Parameters): RayTracingGroups {
            val groups = RayTracingGroups()
            if (parameters.raygen.isNotBlank()) {
                groups.raygen = RayTracingShaderStage(
                    "raygen",
                    ShaderKind.RAYGEN_SHADER,
                    parameters.raygen,
                    parameters.raygenIntrospection,
                    parameters.raygenSourceName,
                    parameters.raygenIncludeResolver
                )
            }
            if (parameters.miss.isNotBlank()) {
                groups.miss += RayTracingShaderStage(
                    "miss",
                    ShaderKind.MISS_SHADER,
                    parameters.miss,
                    parameters.missIntrospection,
                    parameters.missSourceName,
                    parameters.missIncludeResolver
                )
            }
            val hasHitGroup =
                parameters.closesthit.isNotBlank() ||
                    parameters.anyhit.isNotBlank() ||
                    parameters.intersection.isNotBlank()
            if (hasHitGroup) {
                groups.hit += RayTracingHitGroup(
                    name = "hit",
                    closestHit = parameters.closesthit.takeIf { it.isNotBlank() }?.let {
                        RayTracingShaderStage(
                            "closesthit",
                            ShaderKind.CLOSEST_HIT_SHADER,
                            it,
                            parameters.closesthitIntrospection,
                            parameters.closesthitSourceName,
                            parameters.closesthitIncludeResolver
                        )
                    },
                    anyHit = parameters.anyhit.takeIf { it.isNotBlank() }?.let {
                        RayTracingShaderStage(
                            "anyhit",
                            ShaderKind.ANY_HIT_SHADER,
                            it,
                            parameters.anyhitIntrospection,
                            parameters.anyhitSourceName,
                            parameters.anyhitIncludeResolver
                        )
                    },
                    intersection = parameters.intersection.takeIf { it.isNotBlank() }?.let {
                        RayTracingShaderStage(
                            "intersection",
                            ShaderKind.INTERSECTION_SHADER,
                            it,
                            parameters.intersectionIntrospection,
                            parameters.intersectionSourceName,
                            parameters.intersectionIncludeResolver
                        )
                    }
                )
            }
            if (parameters.callable.isNotBlank()) {
                groups.callable += RayTracingShaderStage(
                    "callable",
                    ShaderKind.CALLABLE_SHADER,
                    parameters.callable,
                    parameters.callableIntrospection,
                    parameters.callableSourceName,
                    parameters.callableIncludeResolver
                )
            }
            return groups
        }
    }
}

class RayTracingModulePointer(var current: RayTracingModule) : Renderable {
    /** What the last successful [reload] did to the interface and which values it could not carry. */
    var lastReloadReport: ModuleReloadReport? = null
        internal set

    override fun render(command: CommandBuffer, bounds: Rectanglef) {
        if (current.valid && current.enabled) current.render(command, bounds)
    }

    fun reload(
        p: Parameters,
        forceReresolve: Boolean = true,
        onError: (String, Throwable?) -> Unit = p.errorHandler
    ): Boolean {
        if (current.valid && p == current.parameters && !forceReresolve) return false
        p.errorHandler = onError

        p.ssboResolver = reloadWrap(p.ssboResolver) { oldSsboResolver, it ->
            val prev = current.uniforms[it.name]
            val prevSrc = prev?.source
            if (prevSrc != null && prevSrc.checkCompatibility(it) != false) oldSsboResolver(prevSrc)
            else oldSsboResolver(it)
        }

        p.textureResolver = reloadWrap(p.textureResolver) { oldTextureResolver, it ->
            if (forceReresolve) {
                oldTextureResolver(it) ?: current.textureUniform(it.name) ?: oldTextureResolver(it)
            } else {
                current.textureUniform(it.name) ?: oldTextureResolver(it)
            }
        }

        val m = RayTracingModule(p)
        if (m.valid) {
            val previous = current
            m.copyTasksFrom(previous)
            m.enabled = previous.enabled
            val transition = runCatching { shaderInterfaceTransition(previous.stages(), m.stages()) }.getOrNull()
            lastReloadReport = ModuleReloadReport(
                transition,
                ReloadCarryOver.finish(transition, previous.uniforms.values, m.uniforms, previous.liveUniformStructures, m.liveUniformStructures)
            )
            current = m
            // Retired through the renderer's deferred queue, as in ModulePointer.reload.
            previous.cleanup()
            return true
        }
        m.cleanup()
        return false
    }

    operator fun plusAssign(task: Pair<String, RayTracingTask>) {
        current.tasks[task.first] = task.second
        (task.second as? RayTracingDispatch)?.inside = this
    }

    operator fun plusAssign(task: Named<RayTracingTask>) {
        current.tasks[task.name] = task.value
        (task.value as? RayTracingDispatch)?.inside = this
    }

    @JvmName("plusAssign_target")
    operator fun plusAssign(m: Pair<String, Target>) {
        current.after[m.first] = m.second
    }

    fun enable() { current.enable() }
    fun disable() { current.disable() }

    override fun toString(): String = if (!current.valid) "Invalid RayTracingModule" else "RayTracingModule"
}

class RayTracingFromUniform {
    operator fun getValue(inside: RayTracingModulePointer, property: KProperty<*>): Structure {
        return inside.current.liveUniformStructures[property.name]
            ?: throw IllegalArgumentException("can't find '${property.name}' in this ray tracing module, options are ${inside.current.liveUniformStructures.keys}")
    }
}

fun RayTracingModule.pointer() = RayTracingModulePointer(this)

class RayTracingModule(val parameters: Parameters) : Renderable {
    // Volatile: cleanup() clears it on a box thread while render workers read it.
    @Volatile
    var valid = false
    var enabled = true

    val tasks = linkedMapOf<String, RayTracingTask>()
    val before = linkedMapOf<String, Target>()
    val after = linkedMapOf<String, Target>()

    val groups: RayTracingGroups = parameters.rayTracingGroups ?: RayTracingGroups.fromParameters(parameters)
    val stageRecords = mutableListOf<StageRecord>()

    /** The module's stages, named (`raygen`, `miss#2`, ...), for [shaderInterfaceTransition]. */
    internal fun stages(): List<Pair<String, Introspection>> {
        val seen = mutableMapOf<String, Int>()
        return stageRecords.map { r ->
            val n = seen.merge(r.stage.name, 1, Int::plus)!!
            (if (n == 1) r.stage.name else "${r.stage.name}#$n") to r.introspection
        }
    }

    var uniforms: MutableMap<String, UpdatesDescriptorSet> = mutableMapOf()
    var liveUniformStructures: MutableMap<String, Structure> = mutableMapOf()

    var pipelineLayout: Long = 0L
    var pipeline: Long = 0L
    private val pipelineDisposeToken = AutoDisposer.register(this, "RayTracingPipeline(${parameters.name})", owner = parameters.renderer.device) { }
    internal var sbt: ShaderBindingTable? = null
    private var descriptorLayouts: Map<Int, DescriptorSetLayout.SimpleDescriptorSetLayout> = emptyMap()

    val pushConstantSize: Int

    init {
        var computedPushConstantSize = 0
        try {
            require(parameters.renderer.device.supportsRayTracing) {
                "Device does not support VK_KHR_ray_tracing_pipeline."
            }

            val raygen = groups.raygen ?: throw IllegalStateException("Ray tracing pipeline needs a raygen shader.")
            addStage(raygen)
            groups.miss.forEach(::addStage)
            groups.hit.forEach { group ->
                group.closestHit?.let(::addStage)
                group.anyHit?.let(::addStage)
                group.intersection?.let(::addStage)
            }
            groups.callable.forEach(::addStage)

            if (stageRecords.isEmpty()) throw IllegalStateException("Ray tracing pipeline has no shader stages.")
            computedPushConstantSize = stageRecords.flatMap { it.introspection.pushConstants }.maxOfOrNull { it.size } ?: 0

            buildResources()
            createPipeline(computedPushConstantSize)
            sbt = ShaderBindingTable(parameters.renderer, pipeline, groupCount(), groupRegions())
            pipelineDisposeToken.updateCleanup(snapshotPipelineCleanup())

            valid = true
        } catch (e: Exception) {
            parameters.errorHandler("Ray tracing module error: ${e.message}", e)
        }
        pushConstantSize = computedPushConstantSize
    }

    private fun addStage(stage: RayTracingShaderStage) {
        val introspection = stage.introspection ?: Introspection(
            stage.source,
            stage.kind,
            errorHandler = parameters.errorHandler,
            sourceName = stage.sourceName,
            includeResolver = stage.includeResolver
        )
        if (!introspection.valid) throw IllegalStateException("Invalid ${stage.name} shader")
        val shaderModule = createShaderModule(introspection.code.bytecode()!!)
        parameters.renderer.device.nameObject(
            shaderModule,
            VK_OBJECT_TYPE_SHADER_MODULE,
            "${parameters.name} ${stage.name}"
        )
        stageRecords += StageRecord(stage, introspection, shaderModule, stage.kind.toVulkan())
    }

    private fun buildResources() {
        val bindingUses = linkedMapOf<BindingKey, BindingUse>()

        fun note(bindable: Introspected.Bindable, descriptorType: Int, stage: Int, count: Int = 1) {
            val key = BindingKey(bindable.set, bindable.binding)
            val use = bindingUses.getOrPut(key) { BindingUse(descriptorType, 0, 1) }
            require(use.descriptorType == descriptorType) {
                "binding ${bindable.binding} in set ${bindable.set} resolves to multiple descriptor types"
            }
            use.stageFlags = use.stageFlags or stage
            use.descriptorCount = maxOf(use.descriptorCount, count)
        }

        stageRecords.forEach { record ->
            val i = record.introspection
            i.ubos.forEach { note(it, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, record.stageFlag) }
            i.ssbo.forEach { note(it, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, record.stageFlag) }
            i.textures.forEach { note(it, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, record.stageFlag, maxOf(1, it.arrayCount)) }
            i.images.forEach { note(it, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, record.stageFlag) }
            i.accelerationStructures.forEach { note(it, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, record.stageFlag) }
            i.ubos.forEach { liveUniformStructures[it.name] = Structure().setMembers(it.members).reportingTo(parameters) }
            i.ssbo.forEach { liveUniformStructures[it.name] = Structure().setMembers(it.members).reportingTo(parameters) }
        }

        val layoutMap = mutableMapOf<Int, DescriptorSetLayout.SimpleDescriptorSetLayout>()
        bindingUses.entries
            .groupBy { it.key.set }
            .forEach { (set, entries) ->
                val sorted = entries.sortedBy { it.key.binding }
                layoutMap[set] = DescriptorSetLayout.SimpleDescriptorSetLayout(
                    parameters.renderer.device,
                    descriptorType = sorted.map { it.value.descriptorType },
                    set = set,
                    binding = sorted.map { it.key.binding },
                    stage = sorted.fold(0) { acc, entry -> acc or entry.value.stageFlags },
                    descriptorCount = sorted.map { it.value.descriptorCount },
                    pushDescriptor = true
                )
            }
        descriptorLayouts = layoutMap

        stageRecords.flatMap { it.introspection.ubos }.forEach { ubo ->
            val bp = buffer(ubo, ubo.name) {
                size = ubo.size.toLong()
                source = ubo
                uniform()
                hosted()
            }
            val buf = ReloadCarryOver.resolve(bp, parameters.uboResolver) ?: throw IllegalArgumentException("can't link UBO ${ubo.name}")
            uniforms[ubo.name] = Module.Uniform(ubo.name, ubo, source = buf, buf.toBuffer(parameters.renderer), VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
        }

        stageRecords.flatMap { it.introspection.ssbo }.forEach { ssbo ->
            var sz = ssbo.size.toLong()
            if (sz == 0L) sz = parameters.ssboSizer(ssbo)?.toLong() ?: 0L
            val bp = buffer(ssbo, ssbo.name) {
                size = sz
                source = ssbo
                storage()
                hosted()
            }
            val buf = ReloadCarryOver.resolve(bp, parameters.ssboResolver)
                ?: throw IllegalArgumentException("can't link SSBO buffer ${ssbo.name}, resolver returned null")
            uniforms[ssbo.name] = Module.Uniform(ssbo.name, ssbo, source = buf, buf.toBuffer(parameters.renderer), VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
        }

        stageRecords.flatMap { it.introspection.textures }.forEach { texture ->
            val (resolved, sampler) = parameters.textureResolver(texture)
                ?: throw IllegalArgumentException("can't link texture ${texture.name}")
            val views = resolved.imageViewArray
            require(views.isNotEmpty()) { "Texture ${texture.name} resolved to empty image view array" }
            uniforms[texture.name] = if (texture.arrayCount == 0) {
                Module.TexturePointer(texture.name, texture, source = null, resolved, sampler)
            } else {
                require(views.size == texture.arrayCount) {
                    "can't link texture resource ${texture.name} of length ${views.size} but the shader expects ${texture.arrayCount}"
                }
                Module.TextureArrayPointer(texture.name, texture, source = null, resolved, sampler)
            }
        }

        stageRecords.flatMap { it.introspection.images }.forEach { image ->
            val resolved = parameters.imageResolver(image)
                ?: throw IllegalArgumentException("can't link storage image ${image.name}")
            uniforms[image.name] = Module.ImagePointer(image.name, image, source = null, resolved)
        }

        stageRecords.flatMap { it.introspection.accelerationStructures }.forEach { accel ->
            val resolved = parameters.accelerationStructureResolver(accel)
                ?: throw IllegalArgumentException("can't link acceleration structure ${accel.name}")
            uniforms[accel.name] = AccelerationStructurePointer(accel.name, accel, resolved)
        }

        liveUniformStructures.forEach { (name, structure) ->
            (uniforms[name] as? Module.Uniform)?.let { structure.proxying = it.buffer }
        }
    }

    private fun createPipeline(pushConstantSize: Int) {
        MemoryStack.stackPush().use { stack ->
            val shaderStages = VkPipelineShaderStageCreateInfo.calloc(stageRecords.size, stack)
            stageRecords.forEachIndexed { index, record ->
                shaderStages[index]
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(record.stageFlag)
                    .pName(stack.UTF8("main"))
                    .module(record.shaderModule)
            }

            val shaderGroups = VkRayTracingShaderGroupCreateInfoKHR.calloc(groupCount(), stack)
            var groupIndex = 0
            fun generalGroup(stageIndex: Int) {
                shaderGroups[groupIndex]
                    .sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
                    .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                    .generalShader(stageIndex)
                    .closestHitShader(VK_SHADER_UNUSED_KHR)
                    .anyHitShader(VK_SHADER_UNUSED_KHR)
                    .intersectionShader(VK_SHADER_UNUSED_KHR)
                groupIndex++
            }

            generalGroup(indexOf(groups.raygen!!))
            groups.miss.forEach { generalGroup(indexOf(it)) }
            groups.hit.forEach { group ->
                shaderGroups[groupIndex]
                    .sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
                    .type(if (group.intersection == null) VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR else VK_RAY_TRACING_SHADER_GROUP_TYPE_PROCEDURAL_HIT_GROUP_KHR)
                    .generalShader(VK_SHADER_UNUSED_KHR)
                    .closestHitShader(group.closestHit?.let(::indexOf) ?: VK_SHADER_UNUSED_KHR)
                    .anyHitShader(group.anyHit?.let(::indexOf) ?: VK_SHADER_UNUSED_KHR)
                    .intersectionShader(group.intersection?.let(::indexOf) ?: VK_SHADER_UNUSED_KHR)
                groupIndex++
            }
            groups.callable.forEach { generalGroup(indexOf(it)) }

            val highestSet = descriptorLayouts.keys.maxOrNull() ?: -1
            val ppLayout = if (highestSet >= 0) stack.callocLong(highestSet + 1) else null
            if (highestSet >= 0) {
                for (set in 0..highestSet) {
                    val layout = descriptorLayouts[set]
                        ?: throw IllegalArgumentException("Pipeline layout is missing descriptor set $set. Make sets contiguous starting at set=0.")
                    ppLayout!!.put(set, layout.vkDescriptorLayout)
                }
            }

            var pushRanges: VkPushConstantRange.Buffer? = null
            if (pushConstantSize > 0) {
                pushRanges = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(rayTracingStageFlags())
                    .offset(0)
                    .size(pushConstantSize)
            }

            val layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
            ppLayout?.let { layoutInfo.pSetLayouts(it) }
            pushRanges?.let { layoutInfo.pPushConstantRanges(it) }

            val out = stack.mallocLong(1)
            vkCheck(
                vkCreatePipelineLayout(parameters.renderer.device.vkDevice, layoutInfo, null, out),
                "Failed to create ray tracing pipeline layout"
            )
            pipelineLayout = out[0]
            parameters.renderer.device.nameObject(
                pipelineLayout,
                VK_OBJECT_TYPE_PIPELINE_LAYOUT,
                "${parameters.name} RayTracingPipelineLayout"
            )

            val maxDepth = min(
                parameters.maxRayRecursionDepth.coerceAtLeast(1),
                parameters.renderer.device.rayTracingLimits?.maxRayRecursionDepth ?: parameters.maxRayRecursionDepth.coerceAtLeast(1)
            )

            val createInfo = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_KHR)
                .flags(parameters.rayTracingPipelineFlags)
                .pStages(shaderStages)
                .pGroups(shaderGroups)
                .maxPipelineRayRecursionDepth(maxDepth)
                .layout(pipelineLayout)

            if (parameters.allowClusterAccelerationStructure) {
                require(parameters.renderer.device.supportsClusterAccelerationStructure) { "Cluster acceleration structures were not enabled" }
                createInfo.pNext(org.lwjgl.vulkan.VkRayTracingPipelineClusterAccelerationStructureCreateInfoNV.calloc(stack)
                    .`sType$Default`().allowClusterAccelerationStructure(true).address())
            }

            vkCheck(
                vkCreateRayTracingPipelinesKHR(
                    parameters.renderer.device.vkDevice,
                    0L,
                    parameters.renderer.pipelineCache.vkPipelineCache,
                    createInfo,
                    null,
                    out
                ),
                "Error creating ray tracing pipeline"
            )
            pipeline = out[0]
            parameters.renderer.device.nameObject(
                pipeline,
                VK_OBJECT_TYPE_PIPELINE,
                "${parameters.name} RayTracingPipeline"
            )

            pipelineDisposeToken.updateCleanup(snapshotPipelineCleanup())
        }
    }

    override fun render(command: CommandBuffer, bounds: Rectanglef) {
        if (!valid || !enabled) return
        MemoryStack.stackPush().use { stack ->
            val labelColor = stack.floats(
                parameters.labelColor.x.f,
                parameters.labelColor.y.f,
                parameters.labelColor.z.f,
                parameters.labelColor.w.f
            )
            command.beginLabel(parameters.name, labelColor)
            val cmd = command.vkCommandBuffer
            val frameIndex = parameters.renderer.currentImage
            val fieldProbe = parameters.fieldProbe?.takeIf { FIELD_PROBE_BUFFER_NAME in uniforms }
            fieldProbe?.beforeShaderUse(parameters.renderer, frameIndex)

            before.values.forEach { it.render(command, frameIndex) }

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline)

            val pushConstantBuffer = if (pushConstantSize > 0) stack.calloc(pushConstantSize) else stack.malloc(0)

            liveUniformStructures.forEach { (name, structure) ->
                val target = (uniforms[name] as Module.Uniform).buffer
                structure.proxying = target
                structure.writeIfStale(target, command) {
                    throw MissingRefException("risk of GPU null ref `$it`")
                }
                target.clean(false, command)
            }

            uniforms.values.forEach { upd ->
                upd.pushDescriptorSet(command, pipelineLayout, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR)
            }

            tasks.values.forEach { it.trace(this, command, pushConstantBuffer) }

            after.values.forEach { it.render(command, frameIndex) }

            fieldProbe?.let {
                rayTracingHostReadBarrier(command, it.bufferForImage(parameters.renderer, frameIndex))
                it.afterShaderUse(parameters.renderer, frameIndex)
            }
            command.endLabel()
        }
    }

    fun enable() { enabled = true }
    fun disable() { enabled = false }

    fun copyTasksFrom(old: RayTracingModule) {
        old.tasks.entries.removeIf {
            tasks[it.key] = it.value
            (it.value as? RayTracingDispatch)?.inside = this.pointer()
            true
        }
        before.putAll(old.before)
        after.putAll(old.after)
    }

    fun textureUniform(name: String): Pair<HasImageViewArray, TextureSampler>? {
        return when (val uniform = uniforms[name]) {
            is Module.TexturePointer -> uniform.texture to uniform.sampler
            is Module.TextureArrayPointer -> uniform.texture to uniform.sampler
            else -> null
        }
    }

    private fun snapshotPipelineCleanup(): () -> Unit {
        val dev = parameters.renderer.device.vkDevice
        val p = pipeline
        val layout = pipelineLayout
        val shaders = stageRecords.map { it.shaderModule }
        val table = sbt
        val sets = descriptorLayouts.values.toList()
        return {
            table?.cleanup()
            if (p != 0L) vkDestroyPipeline(dev, p, null)
            if (layout != 0L) vkDestroyPipelineLayout(dev, layout, null)
            shaders.forEach { if (it != 0L) vkDestroyShaderModule(dev, it, null) }
            sets.forEach { it.cleanup() }
        }
    }

    /**
     * Release this module's pipeline, shader binding table, shader modules and descriptor layouts. It
     * stops tracing at once; while its renderer is alive the handles go through the retirement queue, as
     * for [Module.cleanup] — a full reload retires the module it replaces while frames that recorded it
     * may still be in flight — and at once after the renderer has closed.
     */
    fun cleanup() {
        if (!pipelineDisposeToken.disable()) return
        valid = false
        val renderer = parameters.renderer
        if (renderer.isClosed) destroyNow()
        else renderer.cleanup.add(Runnable { destroyNow() })
    }

    private fun destroyNow() {
        cleanupPipelineObjects()
        descriptorLayouts.values.forEach { it.cleanup() }
        descriptorLayouts = emptyMap()
    }

    private fun cleanupPipelineObjects() {
        sbt?.cleanup()
        sbt = null
        if (pipeline != 0L) {
            vkDestroyPipeline(parameters.renderer.device.vkDevice, pipeline, null)
            pipeline = 0L
        }
        if (pipelineLayout != 0L) {
            vkDestroyPipelineLayout(parameters.renderer.device.vkDevice, pipelineLayout, null)
            pipelineLayout = 0L
        }
        stageRecords.forEach {
            if (it.shaderModule != 0L) vkDestroyShaderModule(parameters.renderer.device.vkDevice, it.shaderModule, null)
        }
    }

    private fun createShaderModule(code: ByteBuffer): Long {
        MemoryStack.stackPush().use { stack ->
            val pCode = stack.malloc(code.limit()).put(code).flip()
            val ci = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(pCode)
            val out = stack.mallocLong(1)
            vkCheck(
                vkCreateShaderModule(parameters.renderer.device.vkDevice, ci, null, out),
                "Failed to create ray tracing shader module"
            )
            return out[0]
        }
    }

    private fun indexOf(stage: RayTracingShaderStage): Int =
        stageRecords.indexOfFirst { it.stage === stage }.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("No shader stage '${stage.name}' in ray tracing module")

    private fun groupCount(): Int = 1 + groups.miss.size + groups.hit.size + groups.callable.size

    private fun groupRegions(): ShaderBindingTable.GroupRegions =
        ShaderBindingTable.GroupRegions(
            raygenCount = 1,
            missCount = groups.miss.size,
            hitCount = groups.hit.size,
            callableCount = groups.callable.size
        )

    internal fun rayTracingStageFlags(): Int = stageRecords.fold(0) { acc, record -> acc or record.stageFlag }

    data class StageRecord(
        val stage: RayTracingShaderStage,
        val introspection: Introspection,
        val shaderModule: Long,
        val stageFlag: Int
    )

    private data class BindingKey(val set: Int, val binding: Int)
    private data class BindingUse(val descriptorType: Int, var stageFlags: Int, var descriptorCount: Int)

    class AccelerationStructurePointer(
        val name: String,
        override val binding: Introspected.Bindable,
        val accelerationStructure: HasAccelerationStructure
    ) : UpdatesDescriptorSet {
        override var source: BufferParameters? = null

        override fun pushDescriptorSet(command: CommandBuffer, pipelineLayout: Long, bindPoint: Int) {
            MemoryStack.stackPush().use { stack ->
                val handles = stack.longs(accelerationStructure.vkAccelerationStructure)
                val accelInfo = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                    .pAccelerationStructures(handles)

                val write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .pNext(accelInfo.address())
                    .dstBinding(binding.binding)
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1)

                org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR(
                    command.vkCommandBuffer,
                    bindPoint,
                    pipelineLayout,
                    binding.set,
                    write
                )
            }
        }
    }

    internal class ShaderBindingTable(
        val renderer: afield.four.Renderer,
        val pipeline: Long,
        val groupCount: Int,
        val regions: GroupRegions
    ) {
        data class GroupRegions(
            val raygenCount: Int,
            val missCount: Int,
            val hitCount: Int,
            val callableCount: Int
        )

        private val handleSize = renderer.device.rayTracingLimits!!.shaderGroupHandleSize
        private val handleAlignment = renderer.device.rayTracingLimits!!.shaderGroupHandleAlignment
        private val baseAlignment = renderer.device.rayTracingLimits!!.shaderGroupBaseAlignment
        private val stride = alignUp(handleSize, handleAlignment)

        private val raygenOffset = 0L
        private val raygenSize = stride.toLong()
        private val missOffset = alignUp(raygenOffset + raygenSize, baseAlignment)
        private val missSize = regions.missCount * stride.toLong()
        private val hitOffset = alignUp(missOffset + missSize, baseAlignment)
        private val hitSize = regions.hitCount * stride.toLong()
        private val callableOffset = alignUp(hitOffset + hitSize, baseAlignment)
        private val callableSize = regions.callableCount * stride.toLong()
        private val totalSize = alignUp(callableOffset + callableSize, baseAlignment).coerceAtLeast(raygenSize)

        private val buffer = VulkanBuffer(
            renderer,
            totalSize,
            VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            needsDeviceAddress = true
        )

        init {
            val handles = MemoryUtil.memAlloc(groupCount * handleSize)
            try {
                vkCheck(
                    vkGetRayTracingShaderGroupHandlesKHR(
                        renderer.device.vkDevice,
                        pipeline,
                        0,
                        groupCount,
                        handles
                    ),
                    "Failed to fetch ray tracing shader group handles"
                )
                buffer.mapAndWrite_bytes {
                    var group = 0
                    fun writeRegion(offset: Long, count: Int) {
                        for (i in 0 until count) {
                            val oldPosition = handles.position()
                            val oldLimit = handles.limit()
                            handles.position(group * handleSize)
                            handles.limit((group + 1) * handleSize)
                            this.position((offset + i * stride).toInt())
                            this.put(handles)
                            handles.position(oldPosition)
                            handles.limit(oldLimit)
                            group++
                        }
                    }
                    writeRegion(raygenOffset, regions.raygenCount)
                    writeRegion(missOffset, regions.missCount)
                    writeRegion(hitOffset, regions.hitCount)
                    writeRegion(callableOffset, regions.callableCount)
                }
            } finally {
                MemoryUtil.memFree(handles)
            }
        }

        fun trace(command: CommandBuffer, width: Int, height: Int, depth: Int) {
            MemoryStack.stackPush().use { stack ->
                val base = buffer.getGPUAddress(command)
                val raygen = region(stack, base, raygenOffset, raygenSize, stride)
                val miss = region(stack, base, missOffset, missSize, stride)
                val hit = region(stack, base, hitOffset, hitSize, stride)
                val callable = region(stack, base, callableOffset, callableSize, stride)
                vkCmdTraceRaysKHR(command.vkCommandBuffer, raygen, miss, hit, callable, width, height, depth)
            }
        }

        private fun region(
            stack: MemoryStack,
            base: Long,
            offset: Long,
            size: Long,
            stride: Int
        ): VkStridedDeviceAddressRegionKHR {
            val r = VkStridedDeviceAddressRegionKHR.calloc(stack)
            if (size == 0L) return r
            return r.deviceAddress(base + offset)
                .stride(stride.toLong())
                .size(size)
        }

        fun cleanup() {
            buffer.cleanup()
        }
    }
}

interface RayTracingTask {
    fun trace(inside: RayTracingModule, cmd: CommandBuffer, pushConstantBuffer: ByteBuffer)
}

fun rayTracingTask(c: (inside: RayTracingModule, cmd: CommandBuffer, pushConstantBuffer: ByteBuffer) -> Unit): RayTracingTask {
    return object : RayTracingTask {
        override fun trace(inside: RayTracingModule, cmd: CommandBuffer, pushConstantBuffer: ByteBuffer) {
            c(inside, cmd, pushConstantBuffer)
        }
    }
}

class RayTracingDispatch(var width: Int, var height: Int = 1, var depth: Int = 1) : RayTracingTask {
    constructor(pointer: RayTracingModulePointer = fromContext(), width: Int, height: Int = 1, depth: Int = 1) : this(width, height, depth) {
        inside = pointer
    }

    lateinit var inside: RayTracingModulePointer
    var push = Structure()

    class BoundFromStructure<T> {
        operator fun setValue(inside: RayTracingDispatch, property: KProperty<*>, any: () -> T) {
            if (inside.push.offsets.isEmpty()) inside.initializePushConstantsFromModule(inside.inside)
            inside.push.bindPushConstant(property.name, any) {
                throw IllegalArgumentException("can't find ${property.name} as a ray tracing push_constant")
            }
        }

        operator fun getValue(inside: RayTracingDispatch, property: KProperty<*>): () -> T {
            if (inside.push.offsets.isEmpty()) inside.initializePushConstantsFromModule(inside.inside)
            return inside.push.getBoundPushConstant(property.name) {
                throw IllegalArgumentException("can't find ${property.name} as a ray tracing push_constant")
            }
        }
    }

    class FromStructure<T> {
        operator fun setValue(inside: RayTracingDispatch, property: KProperty<*>, any: T) {
            if (inside.push.offsets.isEmpty()) inside.initializePushConstantsFromModule(inside.inside)
            inside.push.setPushConstant(property.name, any) {
                throw IllegalArgumentException("can't find ${property.name} as a ray tracing push_constant")
            }
        }

        operator fun getValue(inside: RayTracingDispatch, property: KProperty<*>): T {
            if (inside.push.offsets.isEmpty()) inside.initializePushConstantsFromModule(inside.inside)
            return inside.push.getPushConstant(property.name) {
                throw IllegalArgumentException("can't find ${property.name} as a ray tracing push_constant")
            }
        }
    }

    internal fun initializePushConstantsFromModule(inside: RayTracingModulePointer) {
        inside.current.stageRecords.forEach { record ->
            record.introspection.pushConstants.forEach { push.setMembers(it.members) }
        }
    }

    override fun trace(inside: RayTracingModule, cmd: CommandBuffer, pushConstantBuffer: ByteBuffer) {
        if (inside != this.inside.current) {
            lprintln("warning,graphics", "RayTracingDispatch built in a different module ${inside}!=${this.inside.current}")
        }
        if (width <= 0 || height <= 0 || depth <= 0) return

        fun pushConstantsForCurrentDispatch(): Boolean {
            if (pushConstantBuffer.limit() <= 0) return true
            inside.stageRecords.forEach { record ->
                record.introspection.pushConstants.forEach { push.setMembers(it.members) }
            }
            try {
                pushConstantBuffer.clear()
                push.writeToBuffer(pushConstantBuffer, cmd) {
                    throw MissingRefException("risk of GPU null ref `$it`")
                }
            } catch (e: Exception) {
                inside.parameters.errorHandler(e.message ?: "ray tracing push constant error", e)
                lprintln("error,graphics", "push constant error, not tracing rays : ${e.message}")
                return false
            }
            pushConstantBuffer.clear()
            vkCmdPushConstants(
                cmd.vkCommandBuffer,
                inside.pipelineLayout,
                inside.rayTracingStageFlags(),
                0,
                pushConstantBuffer
            )
            return true
        }

        if (pushConstantsForCurrentDispatch()) {
            inside.sbt?.trace(cmd, width, height, depth)
        }
    }
}

fun rayTracingHostReadBarrier(cmd: CommandBuffer, buffer: VulkanBuffer? = null) {
    MemoryStack.stackPush().use { stack ->
        val dep = VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
        if (buffer != null) {
            val b = VkBufferMemoryBarrier2.calloc(1, stack)
            b[0]
                .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
                .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT)
                .dstAccessMask(VK_ACCESS_2_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer.buffer)
                .offset(0L)
                .size(buffer.requestedSize)
            dep.pBufferMemoryBarriers(b)
        } else {
            val m = VkMemoryBarrier2.calloc(1, stack)
            m[0]
                .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
                .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT)
                .dstAccessMask(VK_ACCESS_2_HOST_READ_BIT)
            dep.pMemoryBarriers(m)
        }
        vkCmdPipelineBarrier2(cmd.vkCommandBuffer, dep)
    }
}

private fun alignUp(value: Int, alignment: Int): Int {
    if (alignment <= 1) return value
    return ((value + alignment - 1) / alignment) * alignment
}

private fun alignUp(value: Long, alignment: Int): Long {
    if (alignment <= 1) return value
    return ((value + alignment - 1L) / alignment) * alignment
}
