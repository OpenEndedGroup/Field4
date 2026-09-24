package afield.four.lighting

import afield.four.Renderer
import afield.four.buffers.VulkanBuffer
import afield.four.modules.*
import afield.stdlib.linalg.Matrix4f
import org.lwjgl.vulkan.KHRAccelerationStructure.*
import org.lwjgl.vulkan.VK12.*

/** Immutable indexed XYZ triangles. Opaque, double sided visibility only; no alpha or transmission. */
class OpaqueTriangles(positions: FloatArray, indices: IntArray) {
    internal val vertexData = positions.copyOf()
    internal val indexData = indices.copyOf()
    val positions get() = vertexData.copyOf()
    val indices get() = indexData.copyOf()
    init {
        require(positions.isNotEmpty() && positions.size % 3 == 0 && positions.all { it.isFinite() })
        require(indices.isNotEmpty() && indices.size % 3 == 0 && indices.all { it in 0 until positions.size / 3 })
    }
    companion object {
        fun box(x: Float, y: Float, z: Float): OpaqueTriangles {
            require(listOf(x,y,z).all { it.isFinite() && it > 0 })
            return OpaqueTriangles(floatArrayOf(-x,-y,-z, x,-y,-z, x,y,-z, -x,y,-z, -x,-y,z, x,-y,z, x,y,z, -x,y,z),
                intArrayOf(0,2,1,0,3,2, 4,5,6,4,6,7, 0,1,5,0,5,4, 3,7,6,3,6,2, 0,4,7,0,7,3, 1,2,6,1,6,5))
        }
    }
}

/** Stable object id is independent of list position. Transform is copied at [OpaqueRayScene.commit]. */
data class OpaqueInstance(val id: Int, val geometry: OpaqueTriangles, val transform: Matrix4f = Matrix4f())

/**
 * Static BLAS per geometry identity; a new TLAS for each explicit instance edit. No refit is claimed.
 * Commit on the render thread between frames. Builds submit on Field's graphics queue and wait for
 * their fence; removed resources retire via Field's frames-in-flight queue. This intentionally
 * conservative first slice makes edits safe, at the cost of a stall on instance/topology edits.
 */
class OpaqueRayScene(val renderer: Renderer) : HasAccelerationStructure, AutoCloseable {
    private data class Geometry(val vertices: VulkanBuffer, val indices: VulkanBuffer, val blas: AccelerationStructure) {
        fun close() { blas.cleanup(); indices.cleanup(); vertices.cleanup() }
    }
    private var geometry = mapOf<OpaqueTriangles, Geometry>()
    private var top: AccelerationStructure? = null
    private var signature = emptyList<Pair<Int, List<Float>>>()
    private var order = emptyList<OpaqueTriangles>()
    var blasBuilds = 0; private set
    var tlasBuilds = 0; private set
    var closed = false; private set
    val available get() = renderer.device.supportsRayQuery
    override val vkAccelerationStructure get() = top?.vkAccelerationStructure ?: 0L
    override fun deviceAddress(): Long = checkNotNull(top).deviceAddress()

    /** Returns false when unchanged or unavailable. Empty scenes are represented by a masked instance. */
    fun commit(instances: List<OpaqueInstance>): Boolean {
        check(!closed)
        require(instances.map { it.id }.distinct().size == instances.size) { "Duplicate object identity" }
        require(instances.all { it.id in 0..0xffffff })
        val actual = if (instances.isEmpty()) listOf(OpaqueInstance(0, emptyGeometry)) else instances
        val transforms = actual.map { rowMajor3x4(it.transform) }
        val nextSignature = actual.mapIndexed { i, v -> v.id to transforms[i].toList() } + (-1 to listOf(instances.size.toFloat()))
        val nextOrder = actual.map { it.geometry }
        if (!available || (nextSignature == signature && nextOrder == order)) return false
        val created = mutableMapOf<OpaqueTriangles, Geometry>()
        var candidate: AccelerationStructure? = null
        try {
            nextOrder.distinct().filter { it !in geometry }.forEach { mesh ->
                val flags = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                val vertices = VulkanBuffer(renderer, mesh.vertexData.size * 4L, flags,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, needsDeviceAddress = true)
                var indices: VulkanBuffer? = null
                var blas: AccelerationStructure? = null
                try {
                    vertices.mapAndWrite_bytes { asFloatBuffer().put(mesh.vertexData) }
                    indices = VulkanBuffer(renderer, mesh.indexData.size * 4L, flags,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, needsDeviceAddress = true)
                    indices.mapAndWrite_bytes { asIntBuffer().put(mesh.indexData) }
                    blas = AccelerationStructure(renderer, "direct-light-blas")
                    blas.buildBottomLevelNow(listOf(AccelerationStructureTriangles(vertices, maxVertex = mesh.vertexData.size / 3 - 1,
                        primitiveCount = mesh.indexData.size / 3, indexBuffer = indices)))
                    created[mesh] = Geometry(vertices, indices, blas)
                    blasBuilds++
                } catch (t: Throwable) { blas?.cleanup(); indices?.cleanup(); vertices.cleanup(); throw t }
            }
            val nextGeometry = nextOrder.distinct().associateWith { geometry[it] ?: created.getValue(it) }
            candidate = AccelerationStructure(renderer, "direct-light-tlas")
            candidate.buildTopLevelNow(actual.mapIndexed { i, instance -> RayTracingInstance(nextGeometry.getValue(instance.geometry).blas,
                transforms[i], customIndex = instance.id, mask = if (instances.isEmpty()) 0 else 255) })
            tlasBuilds++
            val oldTop = top
            val removed = geometry.filterKeys { it !in nextGeometry }.values.toList()
            top = candidate; geometry = nextGeometry; signature = nextSignature; order = nextOrder
            renderer.cleanup += Runnable { oldTop?.cleanup(); removed.forEach { it.close() } }
            return true
        } catch (t: Throwable) { candidate?.cleanup(); created.values.forEach { it.close() }; throw t }
    }

    override fun close() {
        if (closed) return
        closed = true
        val oldTop = top; val oldGeometry = geometry.values.toList()
        top = null; geometry = emptyMap()
        renderer.cleanup += Runnable { oldTop?.cleanup(); oldGeometry.forEach { it.close() } }
    }
    companion object {
        private val emptyGeometry = OpaqueTriangles.box(0.01f,0.01f,0.01f)
        fun rowMajor3x4(m: Matrix4f): FloatArray {
            require(m.m03() == 0f && m.m13() == 0f && m.m23() == 0f && m.m33() == 1f) { "Instance transform must be affine" }
            require(m.determinant().isFinite() && kotlin.math.abs(m.determinant()) > 1e-12f) { "Singular instance transform" }
            return floatArrayOf(m.m00(),m.m10(),m.m20(),m.m30(), m.m01(),m.m11(),m.m21(),m.m31(), m.m02(),m.m12(),m.m22(),m.m32()).also {
                require(it.all { v -> v.isFinite() })
            }
        }
    }
}
