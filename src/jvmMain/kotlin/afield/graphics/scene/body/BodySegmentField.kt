package afield.graphics.scene.body

import afield.graphics.render.ComputeModulePointer
import afield.graphics.render.Parameters
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.buffers.Structure
import afield.graphics.vulkan.buffers.defaultBuffer
import afield.math.linalg.Matrix4f
import afield.math.linalg.Vector4f

/**
 * Small pose upload and persistent amortized nearest-segment choices for a GPU particle pass.
 * Include [shaderHeader] and call `bodySegmentForce(id, activeTick, localPosition)` in the existing
 * physics shader. [update] must run after the current XR poses are located, before that pass.
 * There is no dispatch, image, readback or queue submission owned by this adapter.
 */
class BodySegmentField(renderer: Renderer, val particleCount: Int) : AutoCloseable {
    init { require(particleCount > 0) }

    var enabled = false
    var pull = 1f
    var push = 4f
    var reach = .25f
    var insideReach = 0f // zero keeps repulsion throughout the interior
    var falloff = 1f
    var gap = .03f
    var maximumForce = 2f
    var snapshot: BodySegments = BodySegments.from(null)
        private set
    private val worldFromLocal = Matrix4f()
    private val localFromWorld = Matrix4f()
    private var closed = false
    private val upload = defaultBuffer {
        size = 64L * 32 + 16 * 16
        perFrame = true
        hosted() // Fully overwritten by the host; no deferred GPU zero may follow that upload.
    }.toBuffer(renderer)
    private val choices = defaultBuffer {
        size = particleCount.toLong() * 4
        perFrame = false
        zeroInit()
    }.toBuffer(renderer)

    /** Copy and upload only this frame's valid world observations, preserving stable IDs. */
    fun update(observations: BodySegments, model: Matrix4f) {
        check(!closed)
        require(finite(model)) { "Body interaction cloud transform must be finite" }
        val inverse = Matrix4f(model).invert()
        require(finite(inverse)) { "Body interaction cloud transform must be invertible" }
        worldFromLocal.set(model)
        localFromWorld.set(inverse)
        snapshot = observations
        upload.mapAndWrite_bytes {
            for (id in 0..63) {
                val segment = observations[id]
                val a = segment?.start
                val b = segment?.end
                putFloat(a?.x ?: 0f); putFloat(a?.y ?: 0f); putFloat(a?.z ?: 0f); putFloat(segment?.radius ?: 0f)
                putFloat(b?.x ?: 0f); putFloat(b?.y ?: 0f); putFloat(b?.z ?: 0f); putFloat(if (segment == null) 0f else 1f)
            }
            for (i in 0..63) putInt(observations.active.getOrNull(i)?.id ?: -1)
        }
    }

    /** Reserve the per-frame BodyField block; binding 20 must be free in the consumer. */
    fun configure(parameters: Parameters) {
        val previous = parameters.uboResolver
        parameters.uboResolver = { requested ->
            if (requested.name == "BodyField") requested.also { it.perFrame = true; it.hosted() }
            else previous(requested)
        }
    }

    /** Supply the live upload and controls whenever the consumer records its existing dispatch. */
    fun bind(structures: Map<String, Structure>) {
        val target = structures["BodyField"] ?: return
        require(listOf(pull, push, reach, falloff, maximumForce, insideReach).all { it.isFinite() && it >= 0f } && gap.isFinite())
        target["worldFromLocal"] = worldFromLocal
        target["localFromWorld"] = localFromWorld
        target["geometry"] = upload
        target["choices"] = choices
        target["state"] = Vector4f(particleCount.toFloat(), if (enabled && !closed) 1f else 0f, snapshot.active.size.toFloat(), 0f)
        target["gains"] = Vector4f(pull, push, reach, falloff)
        target["options"] = Vector4f(gap, maximumForce, insideReach, 0f)
    }

    fun attach(module: ComputeModulePointer) {
        bind(module.current.liveUniformStructures)
        module.current.before["tracked-body-field"] = Target { _, _ -> bind(module.current.liveUniformStructures) }
    }

    /** Stop/detach consumers before closing their field; ordinary tracking loss never frees buffers. */
    override fun close() {
        if (closed) return
        closed = true
        enabled = false
        upload.buffers.forEach { it.cleanup() }
        choices.buffers.forEach { it.cleanup() }
    }

    private fun finite(m: Matrix4f) = listOf(m.m00, m.m01, m.m02, m.m03, m.m10, m.m11, m.m12, m.m13, m.m20, m.m21, m.m22, m.m23, m.m30, m.m31, m.m32, m.m33).all { it.isFinite() }

    companion object {
        /** Shared GLSL source, also used by the GPU reference fixture. */
        val shaderHeader: String by lazy {
            BodySegmentField::class.java.getResource("/afield/shaders/body-force.glsl")!!.readText()
        }
    }
}
