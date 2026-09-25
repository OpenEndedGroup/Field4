package afield.four.lighting

import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f

/** Stable light identity, independent of order. Opaque one-sided area emitters only. */
data class IdentifiedAreaLight(val id: Int, val emitter: AreaEmitter)

/** Live RTXDI controls. Commit lights and edit these values between frames on Field's render thread.
 * Current/previous camera matrices use Field's Vulkan projection (the raster viewport flips Y).
 * Geometry commits invalidate all history; material semantics edits must call resetHistory.
 */
class RtxdiSettings(val view: () -> Matrix4f, val projection: () -> Matrix4f) {
    /** Surface-guided spatial reconstruction, separate from reservoir reuse. Zero preserves raw output.
     * No color history is stored, so moving lights and camera cuts cannot leave color trails.
     * Each pass doubles the pixel spacing; three passes have a 14-pixel support radius.
     */
    var denoisePasses = 0
        set(value) { require(value in 0..4); field=value }
    var temporal = true
    var spatialSamples = 4
        set(value) { require(value in 0..32);field=value }
    var maxHistory = 8
        set(value) { require(value in 1..32);field=value }
    var spatialRadius = 12f
        set(value) { require(value.isFinite() && value in 0f..64f);field=value }
    var normalThreshold = 0.95f
        set(value) { require(value.isFinite() && value in 0f..1f);field=value }
    var depthThreshold = 0.02f
        set(value) { require(value.isFinite() && value in 0f..0.2f);field=value }
    var shadows = true
    var animateSeed = true
    internal var epoch = 0L
    internal var lights = emptyList<IdentifiedAreaLight>()
    internal var lightRevision = 0L
    var resetReason = "initial"; private set
    fun resetHistory(reason: String = "explicit cut or edit") { epoch++;resetReason=reason }
    /** Transactional snapshot. Moving/reordered lights retain their IDs; deleted IDs map to -1. */
    fun commitLights(values: List<IdentifiedAreaLight>) {
        require(values.size<=4096)
        require(values.all { it.id>=0 } && values.map { it.id }.distinct().size==values.size) { "Light IDs must be unique nonnegative integers" }
        val copy=values.map { v ->
            v.emitter.validate()
            IdentifiedAreaLight(v.id,AreaEmitter(Vector3f(v.emitter.center),Vector3f(v.emitter.edgeU),Vector3f(v.emitter.edgeV),Vector3f(v.emitter.radiance)))
        }
        lights=copy;lightRevision++
    }
}
