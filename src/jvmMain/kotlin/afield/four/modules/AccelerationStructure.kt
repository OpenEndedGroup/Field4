package afield.four.modules

import afield.four.Renderer
import afield.four.buffers.HasBuffer
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.device.nameObject
import afield.four.util.vkCheck
import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.KHRAccelerationStructure.*
import org.lwjgl.vulkan.KHRSynchronization2.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.*
import kotlin.math.max

interface HasAccelerationStructure {
    val vkAccelerationStructure: Long
    fun deviceAddress(): Long
}

data class AccelerationStructureTriangles(
    val vertexBuffer: HasBuffer,
    val vertexFormat: Int = VK_FORMAT_R32G32B32_SFLOAT,
    val vertexStride: Long = 12L,
    val maxVertex: Int,
    val primitiveCount: Int,
    val indexBuffer: HasBuffer? = null,
    val indexType: Int = VK_INDEX_TYPE_UINT32,
    val transformBuffer: HasBuffer? = null,
    val flags: Int = VK_GEOMETRY_OPAQUE_BIT_KHR,
    val primitiveOffset: Int = 0,
    val firstVertex: Int = 0,
    val transformOffset: Int = 0
)

data class AccelerationStructureAabbs(
    val aabbBuffer: HasBuffer,
    val stride: Long = 24L,
    val primitiveCount: Int,
    val flags: Int = VK_GEOMETRY_OPAQUE_BIT_KHR,
    val primitiveOffset: Int = 0,
    val firstVertex: Int = 0,
    val transformOffset: Int = 0
)

data class RayTracingInstance(
    val accelerationStructure: HasAccelerationStructure,
    val transform: FloatArray = identityTransform3x4(),
    val customIndex: Int = 0,
    val mask: Int = 0xff,
    val shaderBindingTableRecordOffset: Int = 0,
    val flags: Int = VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR
) {
    init {
        require(transform.size == 12) { "RayTracingInstance transform must be a row-major 3x4 float array." }
    }
}

fun identityTransform3x4(): FloatArray = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f
)

class AccelerationStructure(
    val renderer: Renderer = fromContext(),
    private val debugName: String = "acceleration-structure"
) : HasAccelerationStructure {
    override var vkAccelerationStructure: Long = 0L
        private set

    private var storage: VulkanBuffer? = null
    private var scratch: VulkanBuffer? = null
    private var instancesBuffer: VulkanBuffer? = null
    private var type: Int = VK_ACCELERATION_STRUCTURE_TYPE_GENERIC_KHR
    private var cachedDeviceAddress: Long = 0L

    override fun deviceAddress(): Long {
        if (cachedDeviceAddress != 0L) return cachedDeviceAddress
        require(vkAccelerationStructure != 0L) { "Acceleration structure '$debugName' has not been built yet." }
        MemoryStack.stackPush().use { stack ->
            val info = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR)
                .accelerationStructure(vkAccelerationStructure)
            cachedDeviceAddress = vkGetAccelerationStructureDeviceAddressKHR(renderer.device.vkDevice, info)
            return cachedDeviceAddress
        }
    }

    fun buildBottomLevel(
        command: CommandBuffer,
        triangles: List<AccelerationStructureTriangles> = emptyList(),
        aabbs: List<AccelerationStructureAabbs> = emptyList(),
        flags: Int = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
    ): AccelerationStructure {
        require(renderer.device.supportsRayTracing) { "Device does not support VK_KHR_ray_tracing_pipeline." }
        require(triangles.isNotEmpty() || aabbs.isNotEmpty()) { "BLAS build needs at least one triangles or AABB geometry." }

        MemoryStack.stackPush().use { stack ->
            val count = triangles.size + aabbs.size
            val geometries = VkAccelerationStructureGeometryKHR.calloc(count, stack)
            val ranges = VkAccelerationStructureBuildRangeInfoKHR.calloc(count, stack)
            val primitiveCounts = stack.mallocInt(count)

            var i = 0
            triangles.forEach { spec ->
                geometries[i]
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                    .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .flags(spec.flags)
                    .geometry { data ->
                        data.triangles { tri ->
                            tri.sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR)
                                .vertexFormat(spec.vertexFormat)
                                .vertexData { it.deviceAddress(spec.vertexBuffer.getGPUAddress(command)) }
                                .vertexStride(spec.vertexStride)
                                .maxVertex(spec.maxVertex)
                                .indexType(if (spec.indexBuffer == null) VK_INDEX_TYPE_NONE_KHR else spec.indexType)
                                .indexData { it.deviceAddress(spec.indexBuffer?.getGPUAddress(command) ?: 0L) }
                                .transformData { it.deviceAddress(spec.transformBuffer?.getGPUAddress(command) ?: 0L) }
                        }
                    }
                ranges[i]
                    .primitiveCount(spec.primitiveCount)
                    .primitiveOffset(spec.primitiveOffset)
                    .firstVertex(spec.firstVertex)
                    .transformOffset(spec.transformOffset)
                primitiveCounts.put(i, spec.primitiveCount)
                i++
            }
            aabbs.forEach { spec ->
                geometries[i]
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                    .geometryType(VK_GEOMETRY_TYPE_AABBS_KHR)
                    .flags(spec.flags)
                    .geometry { data ->
                        data.aabbs { aabb ->
                            aabb.sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_AABBS_DATA_KHR)
                                .data { it.deviceAddress(spec.aabbBuffer.getGPUAddress(command)) }
                                .stride(spec.stride)
                        }
                    }
                ranges[i]
                    .primitiveCount(spec.primitiveCount)
                    .primitiveOffset(spec.primitiveOffset)
                    .firstVertex(spec.firstVertex)
                    .transformOffset(spec.transformOffset)
                primitiveCounts.put(i, spec.primitiveCount)
                i++
            }

            build(command, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, flags, geometries, primitiveCounts, ranges)
        }
        return this
    }

    fun buildTopLevel(
        command: CommandBuffer,
        instances: List<RayTracingInstance>,
        flags: Int = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
    ): AccelerationStructure {
        require(renderer.device.supportsRayTracing) { "Device does not support VK_KHR_ray_tracing_pipeline." }
        require(instances.isNotEmpty()) { "TLAS build needs at least one instance." }

        val byteSize = instances.size * VkAccelerationStructureInstanceKHR.SIZEOF.toLong()
        val buffer = instancesBuffer
            ?.takeIf { it.requestedSize >= byteSize }
            ?: VulkanBuffer(
                renderer,
                byteSize,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                needsDeviceAddress = true
            ).also {
                instancesBuffer?.cleanup()
                instancesBuffer = it
            }

        buffer.mapAndWrite_bytes {
            val records = VkAccelerationStructureInstanceKHR.Buffer(this)
            records.limit(instances.size)
            instances.forEachIndexed { index, instance ->
                val record = records[index]
                for (n in 0 until 12) record.transform().matrix(n, instance.transform[n])
                record.instanceCustomIndex(instance.customIndex)
                    .mask(instance.mask)
                    .instanceShaderBindingTableRecordOffset(instance.shaderBindingTableRecordOffset)
                    .flags(instance.flags)
                    .accelerationStructureReference(instance.accelerationStructure.deviceAddress())
            }
        }

        MemoryStack.stackPush().use { stack ->
            val geometries = VkAccelerationStructureGeometryKHR.calloc(1, stack)
            geometries[0]
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR)
                .geometry { data ->
                    data.instances { inst ->
                        inst.sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR)
                            .arrayOfPointers(false)
                            .data { it.deviceAddress(buffer.getGPUAddress(command)) }
                    }
                }

            val ranges = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack)
            ranges[0].primitiveCount(instances.size).primitiveOffset(0).firstVertex(0).transformOffset(0)
            val primitiveCounts = stack.mallocInt(1)
            primitiveCounts.put(0, instances.size)

            build(command, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, flags, geometries, primitiveCounts, ranges)
        }
        return this
    }

    fun buildBottomLevelNow(
        triangles: List<AccelerationStructureTriangles> = emptyList(),
        aabbs: List<AccelerationStructureAabbs> = emptyList(),
        flags: Int = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
    ): AccelerationStructure = submitBuildNow { buildBottomLevel(it, triangles, aabbs, flags) }

    fun buildTopLevelNow(
        instances: List<RayTracingInstance>,
        flags: Int = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
    ): AccelerationStructure = submitBuildNow { buildTopLevel(it, instances, flags) }

    private fun submitBuildNow(build: (CommandBuffer) -> Unit): AccelerationStructure {
        val cmd = CommandBuffer(renderer.commandPool, primary = true, oneTimeSubmit = true)
        val fence = Fence(renderer.device, signaled = true)
        cmd.beginRecording()
        build(cmd)
        cmd.endRecording()
        fence.reset()
        MemoryStack.stackPush().use { stack ->
            renderer.graphQueue.submit(stack.pointers(cmd.vkCommandBuffer), null, null, null, fence)
        }
        fence.fenceWait()
        cmd.cleanup()
        fence.cleanup()
        return this
    }

    private fun build(
        command: CommandBuffer,
        buildType: Int,
        flags: Int,
        geometries: VkAccelerationStructureGeometryKHR.Buffer,
        primitiveCounts: java.nio.IntBuffer,
        ranges: VkAccelerationStructureBuildRangeInfoKHR.Buffer
    ) {
        type = buildType
        val info = VkAccelerationStructureBuildGeometryInfoKHR.calloc()
            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
            .type(buildType)
            .flags(flags)
            .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
            .geometryCount(geometries.remaining())
            .pGeometries(geometries)

        try {
            val sizes = MemoryStack.stackPush().use { stack ->
                val out = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR)
                vkGetAccelerationStructureBuildSizesKHR(
                    renderer.device.vkDevice,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    info,
                    primitiveCounts,
                    out
                )
                BuildSizes(out.accelerationStructureSize(), out.buildScratchSize())
            }

            ensureStorage(sizes.accelerationStructureSize)
            val scratchAlignment = max(1, renderer.device.rayTracingLimits?.minAccelerationStructureScratchOffsetAlignment ?: 1)
            ensureScratch(sizes.buildScratchSize + scratchAlignment)

            val scratchAddress = alignUp(scratch!!.getGPUAddress(command), scratchAlignment.toLong())
            info.dstAccelerationStructure(vkAccelerationStructure)
                .scratchData { it.deviceAddress(scratchAddress) }

            MemoryStack.stackPush().use { stack ->
                val infos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack)
                infos[0].set(info)
                val rangePointers: PointerBuffer = stack.mallocPointer(1)
                rangePointers.put(0, ranges.address())
                vkCmdBuildAccelerationStructuresKHR(command.vkCommandBuffer, infos, rangePointers)
            }
        } finally {
            info.free()
        }
        accelerationStructureBuildBarrier(command)
    }

    private fun ensureStorage(size: Long) {
        if (storage != null && storage!!.requestedSize >= size && vkAccelerationStructure != 0L) return
        destroyHandle()
        storage?.cleanup()
        storage = VulkanBuffer(
            renderer,
            size,
            VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            needsDeviceAddress = true
        )
        MemoryStack.stackPush().use { stack ->
            val createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                .type(type)
                .size(size)
                .buffer(storage!!.buffer)
            val out = stack.mallocLong(1)
            vkCheck(
                vkCreateAccelerationStructureKHR(renderer.device.vkDevice, createInfo, null, out),
                "Failed to create acceleration structure"
            )
            vkAccelerationStructure = out[0]
            cachedDeviceAddress = 0L
            renderer.device.nameObject(
                vkAccelerationStructure,
                VK_OBJECT_TYPE_ACCELERATION_STRUCTURE_KHR,
                debugName
            )
        }
    }

    private fun ensureScratch(size: Long) {
        if (scratch != null && scratch!!.requestedSize >= size) return
        scratch?.cleanup()
        scratch = VulkanBuffer(
            renderer,
            size,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            needsDeviceAddress = true
        )
    }

    private fun destroyHandle() {
        if (vkAccelerationStructure != 0L) {
            vkDestroyAccelerationStructureKHR(renderer.device.vkDevice, vkAccelerationStructure, null)
            vkAccelerationStructure = 0L
            cachedDeviceAddress = 0L
        }
    }

    fun cleanup() {
        destroyHandle()
        storage?.cleanup()
        scratch?.cleanup()
        instancesBuffer?.cleanup()
        storage = null
        scratch = null
        instancesBuffer = null
    }

    private data class BuildSizes(val accelerationStructureSize: Long, val buildScratchSize: Long)
}

fun accelerationStructureBuildBarrier(command: CommandBuffer) {
    MemoryStack.stackPush().use { stack ->
        val barrier = VkMemoryBarrier2.calloc(1, stack)
        barrier[0]
            .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
            .srcStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
            .srcAccessMask(VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
            .dstStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR or VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR or VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
            .dstAccessMask(VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR or VK_ACCESS_2_SHADER_READ_BIT)

        val dep = VkDependencyInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
            .pMemoryBarriers(barrier)
        vkCmdPipelineBarrier2(command.vkCommandBuffer, dep)
    }
}

fun rayTracingMemoryBarrier(command: CommandBuffer) {
    MemoryStack.stackPush().use { stack ->
        val barrier = VkMemoryBarrier2.calloc(1, stack)
        barrier[0]
            .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
            .srcStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
            .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
            .dstStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR or VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT or VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT)
            .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)

        val dep = VkDependencyInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
            .pMemoryBarriers(barrier)
        vkCmdPipelineBarrier2(command.vkCommandBuffer, dep)
    }
}

private fun alignUp(value: Long, alignment: Long): Long {
    if (alignment <= 1L) return value
    return ((value + alignment - 1L) / alignment) * alignment
}
