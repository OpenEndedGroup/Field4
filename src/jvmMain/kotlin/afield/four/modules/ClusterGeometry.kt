package afield.four.modules

import afield.four.Renderer
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.lighting.LightingGpuTimer
import afield.stdlib.linalg.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.NVClusterAccelerationStructure.*
import org.lwjgl.vulkan.KHRAccelerationStructure.*
import org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
import org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR
import org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR
import org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR
import org.lwjgl.vulkan.VK13.*
import java.nio.ByteBuffer

/** One Field cluster description, eight uint32 words / 32 bytes, usable by ordinary compute shaders.
 * Positions are vec4 (xyz in world/object units); indices are uint32 LOCAL to this cluster's
 * vertex slice. A cluster may change counts, positions and indices on every build. IDs stay yours.
 * Invalid GPU records become degenerate geometry and set [ClusterGeometry.status] rather than
 * passing unchecked addresses/counts to Vulkan. No host readback occurs during builds.
 */
data class TriangleCluster(val firstVertex: Int,val firstIndex: Int,val vertexCount: Int,val triangleCount: Int,
    val clusterId: Int,val geometryId: Int=0) {
    init { require(firstVertex>=0 && firstIndex>=0 && vertexCount>=3 && triangleCount>=1 && geometryId>=0) }
    fun write(out: ByteBuffer) {
        require(out.order()==java.nio.ByteOrder.nativeOrder()) { "Cluster descriptions need a native-order ByteBuffer" }
        out.putInt(firstVertex).putInt(firstIndex).putInt(vertexCount).putInt(triangleCount)
        out.putInt(clusterId).putInt(geometryId).putInt(0).putInt(0) }
    companion object { const val STRIDE=32 }
}

/** Borrowed producer buffers. Keep alive through build completion; synchronize any subsequent writes.
 * [clusters] contains [TriangleCluster] records. Buffer sizes, not arbitrary user-supplied addresses,
 * determine bounds in the GPU validation/conversion pass. */
data class ClusterGeometryInput(val positions: VulkanBuffer,val indices: VulkanBuffer,val clusters: VulkanBuffer)

/** General NV Mega Geometry builder: Field compute -> validated clusters -> CLAS -> one cluster BLAS.
 * It owns bounded build/storage/scratch resources, never producer buffers. Single graphics queue;
 * call [build] explicitly after a producer changes geometry. Static geometry needs only one build.
 * Recreate to increase capacity. A rebuild changes this BLAS's bounds: rebuild the containing TLAS.
 * Cluster BLAS has an address but no VkAccelerationStructure handle; use it in [RayTracingInstance].
 */
class ClusterGeometry(val renderer: Renderer,val capacity: Int,val verticesPerCluster: Int=128,
    val trianglesPerCluster: Int=128,val maxGeometryId: Int=0) : AccelerationStructureAddress,AutoCloseable {
    init {
        require(renderer.device.supportsClusterAccelerationStructure) { "VK_NV_cluster_acceleration_structure is not enabled" }
        require(capacity in 1..1_000_000 && verticesPerCluster>=3 && trianglesPerCluster>=1 && maxGeometryId>=0)
    }
    data class Limits(val vertices: Int,val triangles: Int,val scratchAlignment: Int,val clusterAlignment: Int,
        val blasAlignment: Int,val maxGeometryId: Int)
    val limits: Limits
    private val owned=mutableListOf<VulkanBuffer>()
    private val timer=LightingGpuTimer(renderer)
    val gpuMilliseconds get()=timer.samples.toList()
    private val positions: VulkanBuffer
    private val indices: VulkanBuffer
    internal val records: VulkanBuffer
    private val addresses: VulkanBuffer
    private val sizes: VulkanBuffer
    private val blasRecord: VulkanBuffer
    private val blasAddress: VulkanBuffer
    private val clas: VulkanBuffer
    private val blas: VulkanBuffer
    private val scratch: VulkanBuffer
    /** One uint per cluster: 0 valid, 1 invalid descriptor, 2 invalid index, 4 nonfinite vertex. */
    val status: VulkanBuffer
    private val convert: ComputeModule
    private val task: ComputeMesh
    private var closed=false
    private var built=false
    val allocatedBytes get()=owned.sumOf { it.requestedSize }
    init {
        require(renderer.device.supportsClusterAccelerationStructure) { "VK_NV_cluster_acceleration_structure is not enabled" }
        limits=MemoryStack.stackPush().use { s ->
            val p=VkPhysicalDeviceClusterAccelerationStructurePropertiesNV.calloc(s).`sType$Default`()
            VK11.vkGetPhysicalDeviceProperties2(renderer.device.physicalDevice.vkPhysicalDevice,
                VkPhysicalDeviceProperties2.calloc(s).`sType$Default`().pNext(p.address()))
            Limits(p.maxVerticesPerCluster(),p.maxTrianglesPerCluster(),p.clusterScratchByteAlignment(),p.clusterByteAlignment(),
                p.clusterBottomLevelByteAlignment(),p.maxClusterGeometryIndex())
        }
        require(capacity in 1..1_000_000 && verticesPerCluster in 3..limits.vertices && trianglesPerCluster in 1..limits.triangles && maxGeometryId in 0..limits.maxGeometryId)
        check(VkClusterAccelerationStructureBuildTriangleClusterInfoNV.SIZEOF==64 && VkClusterAccelerationStructureBuildClustersBottomLevelInfoNV.SIZEOF==16)
        try {
            positions=allocate(capacity.toLong()*verticesPerCluster*16)
            indices=allocate(capacity.toLong()*trianglesPerCluster*12)
            records=allocate(capacity.toLong()*64);addresses=allocate(capacity.toLong()*8);sizes=allocate(capacity.toLong()*4)
            status=allocate(capacity.toLong()*4);blasRecord=allocate(16);blasAddress=allocate(8)
            val requirements=MemoryStack.stackPush().use { s ->
                fun size(bottom: Boolean): Pair<Long,Long> {val p=VkAccelerationStructureBuildSizesInfoKHR.calloc(s).`sType$Default`()
                    vkGetClusterAccelerationStructureBuildSizesNV(renderer.device.vkDevice,inputInfo(s,bottom,capacity),p)
                    return p.accelerationStructureSize() to p.buildScratchSize() }
                size(false) to size(true)
            }
            clas=allocate(requirements.first.first+limits.clusterAlignment,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR)
            blas=allocate(requirements.second.first+limits.blasAlignment,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR)
            scratch=allocate(maxOf(requirements.first.second,requirements.second.second,1)+limits.scratchAlignment)
            convert=ComputeModule(parameters(renderer){name="cluster-input-validation";compute=SOURCE})
            check(convert.valid) { "Cluster input converter did not compile" }
            val pointer=convert.pointer();task=ComputeMesh(renderer,pointer);pointer+="convert" to task
            task.push.setMembers(convert.ci.pushConstants.single().members)
        } catch(t: Throwable) { owned.asReversed().forEach { it.cleanup() };timer.close();throw t }
    }
    private fun allocate(bytes: Long,extra: Int=0)=VulkanBuffer(renderer,bytes,
        extra or VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT or VK_BUFFER_USAGE_TRANSFER_SRC_BIT or
            VK_BUFFER_USAGE_TRANSFER_DST_BIT or VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,needsDeviceAddress=true).also { owned+=it }
    private fun address(b: VulkanBuffer,alignment: Int=1): Long {
        check(b.gpuAddress>0);return (b.gpuAddress+alignment-1)/alignment*alignment
    }
    private fun inputInfo(s: MemoryStack,bottom: Boolean,count: Int): VkClusterAccelerationStructureInputInfoNV {
        val info=VkClusterAccelerationStructureInputInfoNV.calloc(s).`sType$Default`()
            .maxAccelerationStructureCount(if(bottom)1 else count).flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
            .opMode(if(bottom)VK_CLUSTER_ACCELERATION_STRUCTURE_OP_MODE_EXPLICIT_DESTINATIONS_NV else VK_CLUSTER_ACCELERATION_STRUCTURE_OP_MODE_IMPLICIT_DESTINATIONS_NV)
            .opType(if(bottom)VK_CLUSTER_ACCELERATION_STRUCTURE_OP_TYPE_BUILD_CLUSTERS_BOTTOM_LEVEL_NV else VK_CLUSTER_ACCELERATION_STRUCTURE_OP_TYPE_BUILD_TRIANGLE_CLUSTER_NV)
        if(bottom)info.opInput().pClustersBottomLevel(VkClusterAccelerationStructureClustersBottomLevelInputNV.calloc(s).`sType$Default`()
            .maxTotalClusterCount(count).maxClusterCountPerAccelerationStructure(count))
        else info.opInput().pTriangleClusters(VkClusterAccelerationStructureTriangleClusterInputNV.calloc(s).`sType$Default`()
            .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT).maxGeometryIndexValue(maxGeometryId).maxClusterUniqueGeometryCount(1)
            .maxClusterTriangleCount(trianglesPerCluster).maxClusterVertexCount(verticesPerCluster)
            .maxTotalTriangleCount(count*trianglesPerCluster).maxTotalVertexCount(count*verticesPerCluster).minPositionTruncateBitCount(0))
        return info
    }
    /** Record outside a render pass on the graphics queue. The pipeline must opt into cluster AS;
     * Vulkan requires it bound during cluster builds. Field rebinds descriptors/state when tracing. */
    fun build(command: CommandBuffer,pipeline: RayTracingModule,input: ClusterGeometryInput,count: Int=capacity) {
        check(!closed && !command.inRenderPassInstance)
        require(count in 1..capacity && pipeline.valid && pipeline.parameters.allowClusterAccelerationStructure)
        require(pipeline.parameters.renderer===renderer)
        listOf(input.positions,input.indices,input.clusters).forEach { require(it.renderer===renderer && it.gpuAddress>0) }
        require(input.positions.requestedSize%16==0L && input.indices.requestedSize%4==0L && input.clusters.requestedSize>=count.toLong()*32)
        require(input.positions.requestedSize/16<=Int.MAX_VALUE && input.indices.requestedSize/4<=Int.MAX_VALUE)
        barrier(command,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT or VK_PIPELINE_STAGE_2_TRANSFER_BIT,VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT or VK_ACCESS_2_TRANSFER_WRITE_BIT)
        timer.begin(command,renderer.currentImage)
        task.x=(count+63)/64
        for((key,buffer) in mapOf("sourcePositions" to input.positions,"sourceIndices" to input.indices,"sourceClusters" to input.clusters,
            "positions" to positions,"indices" to indices,"records" to records,"status" to status))task.push[key]=buffer
        task.push["count"]=count;task.push["maxVertices"]=verticesPerCluster;task.push["maxTriangles"]=trianglesPerCluster
        task.push["positionCapacity"]=(input.positions.requestedSize/16).toInt();task.push["indexCapacity"]=(input.indices.requestedSize/4).toInt()
        task.push["maxGeometry"]=maxGeometryId
        convert.render(command,Rectanglef())
        MemoryStack.stackPush().use { s ->
            vkCmdUpdateBuffer(command.vkCommandBuffer,blasRecord.buffer,0,s.malloc(16).putInt(count).putInt(8).putLong(address(addresses)).flip())
            vkCmdUpdateBuffer(command.vkCommandBuffer,blasAddress.buffer,0,s.longs(address(blas,limits.blasAlignment)))
        }
        val stage=VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
        val access=VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR or VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR or VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT
        barrier(command,VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT or VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            VK_ACCESS_2_SHADER_WRITE_BIT or VK_ACCESS_2_TRANSFER_WRITE_BIT,stage,access)
        vkCmdBindPipeline(command.vkCommandBuffer,VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,pipeline.pipeline)
        MemoryStack.stackPush().use { s ->
            fun record(bottom: Boolean) {
                val c=VkClusterAccelerationStructureCommandsInfoNV.calloc(s).`sType$Default`().input(inputInfo(s,bottom,count))
                    .scratchData(address(scratch,limits.scratchAlignment))
                c.srcInfosArray().deviceAddress(address(if(bottom)blasRecord else records)).stride(if(bottom)16 else 64).size(if(bottom)16 else count.toLong()*64)
                c.dstAddressesArray().deviceAddress(address(if(bottom)blasAddress else addresses)).stride(8).size(if(bottom)8 else count.toLong()*8)
                if(!bottom){c.dstImplicitData(address(clas,limits.clusterAlignment));c.dstSizesArray().deviceAddress(address(sizes)).stride(4).size(count.toLong()*4)}
                vkCmdBuildClusterAccelerationStructureIndirectNV(command.vkCommandBuffer,c)
            }
            record(false);barrier(command,stage,access,stage,access);record(true)
        }
        barrier(command,stage,access,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_READ_BIT)
        timer.end(command,renderer.currentImage);built=true
    }
    override fun deviceAddress(): Long { check(built && !closed);return address(blas,limits.blasAlignment) }
    override fun close() { if(closed)return;closed=true;renderer.cleanup+=Runnable { convert.cleanup();owned.asReversed().forEach { it.cleanup() };timer.close() } }
    companion object {
        private fun barrier(c: CommandBuffer,src: Long,srcAccess: Long,dst: Long,dstAccess: Long)=MemoryStack.stackPush().use { s ->
            vkCmdPipelineBarrier2(c.vkCommandBuffer,VkDependencyInfo.calloc(s).`sType$Default`().pMemoryBarriers(
                VkMemoryBarrier2.calloc(1,s).`sType$Default`().srcStageMask(src).srcAccessMask(srcAccess).dstStageMask(dst).dstAccessMask(dstAccess)))
        }
        private val SOURCE get()=ClusterGeometry::class.java.getResource("/afield/four/geometry/clusters.comp")!!.readText()
    }
}
