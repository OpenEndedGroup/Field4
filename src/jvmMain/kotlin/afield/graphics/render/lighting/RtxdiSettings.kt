/**
 * Live controls for the RTXDI resampling path of [DirectLighting].
 *
 * [RtxdiSettings] holds the camera matrices, temporal and spatial reuse parameters, shadowing and an
 * optional spatial denoise, and takes the light list as [IdentifiedAreaLight]s through
 * [RtxdiSettings.commitLights], so a light keeps its history across reordering. Edit it on the render
 * thread between frames; [RtxdiSettings.resetHistory] discards history after a cut or material edit.
 */

package afield.graphics.render.lighting

import afield.math.linalg.Matrix4f
import afield.math.linalg.Vector3f

/** Stable light identity, independent of order. Opaque one-sided area emitters only. */
data class IdentifiedAreaLight(val id: Int, val emitter: AreaEmitter)

/** Live RTXDI controls. Commit lights and edit these values between frames on Field's render thread.
 * Current/previous camera matrices use Field's Vulkan projection (the raster viewport flips Y).
 * Geometry commits invalidate all history; material semantics edits must call resetHistory.
 */
class RtxdiSettings(
    /** The camera's view matrix, read once per frame rather than stored, so a camera that moves between frames needs no further bookkeeping here. */
    val view: () -> Matrix4f,
    /** The camera's projection matrix, read per frame like [view]. Field's Vulkan convention, where the raster viewport flips Y. */
    val projection: () -> Matrix4f
) {
    /** Surface-guided spatial reconstruction, separate from reservoir reuse. Zero preserves raw output.
     * No color history is stored, so moving lights and camera cuts cannot leave color trails.
     * Each pass doubles the pixel spacing; three passes have a 14-pixel support radius.
     */
    var denoisePasses = 0
        set(value) { require(value in 0..4); field=value }
    /** Whether each pixel may reuse the reservoir its surface carried last frame. Off, every frame resamples from scratch: noisier, and the only setting under which a still camera does not keep improving. */
    var temporal = true

    /** How many neighbouring pixels' reservoirs each pixel resamples, 0 to 32. This is the reuse that cleans up a single frame, and the cost that scales with it. */
    var spatialSamples = 4
        set(value) { require(value in 0..32);field=value }

    /** The largest number of frames' worth of samples a reservoir may claim to hold, 1 to 32. Capping it is what lets a reservoir forget light that has moved; raising it trades responsiveness for a quieter image. */
    var maxHistory = 8
        set(value) { require(value in 1..32);field=value }

    /** The radius in pixels that spatial neighbours are drawn from, 0 to 64. */
    var spatialRadius = 12f
        set(value) { require(value.isFinite() && value in 0f..64f);field=value }

    /** How closely two surface normals must agree before one's reservoir may be reused for the other, as a dot product from 0 to 1 — the default admits normals within about 18° of each other, which is what keeps light from bleeding around a crease. Handed to both the temporal and the spatial resampling pass. */
    var normalThreshold = 0.95f
        set(value) { require(value.isFinite() && value in 0f..1f);field=value }

    /** The companion depth test, from 0 to 0.2, so that a pixel sitting on a surface behind this one is not reused for it. Handed to the same two passes as [normalThreshold]. */
    var depthThreshold = 0.02f
        set(value) { require(value.isFinite() && value in 0f..0.2f);field=value }

    /** Whether a shadow ray is traced towards the chosen light. Off, every sample is treated as visible, which is faster and wrong wherever anything occludes anything. */
    var shadows = true

    /** Whether the sampling seed advances with the frame counter. Off, every frame draws the same sequence, which is what makes two renders of one scene comparable pixel for pixel. */
    var animateSeed = true
    internal var epoch = 0L
    internal var lights = emptyList<IdentifiedAreaLight>()
    internal var lightRevision = 0L
    /**
     * The reason last passed to [resetHistory], or `initial` before any call. The renderer reads it
     * when it notices the reset and reports it as its own reason for that frame, which is how a
     * deliberate cut can be told apart from the resets it detects for itself — a geometry commit, a
     * change of projection, a large camera move, or an edit to [temporal], [spatialSamples],
     * [spatialRadius], [maxHistory] or [shadows], which are the settings that invalidate history.
     */
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
