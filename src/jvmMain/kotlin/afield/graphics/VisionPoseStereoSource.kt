package afield.graphics

import afield.four.targets.OpenXRHmdTarget
import afield.four.targets.StereoPoseSource
import afield.four.util.Options
import afield.stdlib.Quat
import afield.stdlib.Vec3
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.prettySummary

/**
 * Drives Field's stereo rendering from a visionOS head's uplinked pose — the consumer end of
 * [VisionPoseStream].
 *
 * Attach it to a [afield.four.targets.SideBySideStereoTarget] and the eye passes render from the
 * headset rather than from a desktop mock:
 *
 * ```kotlin
 * val sbs = SideBySideStereoTarget(renderer)
 * sbs.poseSource = VisionPoseStereoSource()
 * ```
 *
 * The projection is **rebuilt** rather than forwarded. The head sends the matrix its compositor
 * computed, but that carries the compositor's near/far and depth range; Field's Vulkan pipelines use
 * [Options.zNear]/[Options.zFar] with a 0..1 depth range. So this takes the frustum *tangents* — the
 * convention-free part — and builds a projection exactly the way
 * [afield.four.targets.OpenXRHmdTarget] builds one from `XrFovf`'s angles. That is the whole reason
 * [VisionEyeView] carries tangents at all.
 *
 * The view matrix, by contrast, is forwarded untouched: the wire already carries world→eye, composed
 * on arrival the same way the head composes its own, so there is nothing to reinterpret.
 */
class VisionPoseStereoSource(
    /** Where a fresh pose comes from. Injectable so the arithmetic can be tested without a head. */
    private val newest: () -> VisionPoseFrame? = { VisionPoseStream.latest },
    var zNear: Float = Options.zNear.toFloat(),
    var zFar: Float = Options.zFar.toFloat(),
) : StereoPoseSource {

    /** The pose the current frame is being rendered from, or null before the first successful latch. */
    var latched: VisionPoseFrame? = null
        private set

    /** Latches that found nothing usable, so the view held its last pose. */
    var staleLatchCount: Long = 0L
        private set

    override val latchedFrameId: Long get() = latched?.frameIndex ?: -1L
    override val latchedDisplayTime: Double get() = latched?.predictedDisplayTime ?: 0.0
    override val eyeCount: Int get() = latched?.eyes?.size ?: 0

    override fun latch(): Boolean {
        val fresh = newest()
        // An untracked pose carries identity matrices. Rendering from those would snap the world to
        // the origin, which reads as a broken scene rather than as lost tracking — so hold the last
        // good pose instead and count it.
        if (fresh == null || !fresh.trackingValid || fresh.eyes.isEmpty()) {
            staleLatchCount++
            return false
        }
        latched = fresh
        return true
    }

    override fun eyeViewMatrix(eyeIndex: Int, out: Matrix4f): Matrix4f {
        val eye = eyeFor(eyeIndex) ?: return out.identity()
        return out.set(eye.viewMatrix)
    }

    override fun eyeProjectionMatrix(eyeIndex: Int, out: Matrix4f): Matrix4f {
        val eye = eyeFor(eyeIndex) ?: return out.identity()
        // Same construction as the OpenXR path, one `tan` later: it works from angles, we already
        // have the tangents.
        return out.identity().frustum(
            eye.tanLeft * zNear, eye.tanRight * zNear,
            eye.tanDown * zNear, eye.tanUp * zNear,
            zNear, zFar, true,
        )
    }

    override fun controller(hand: OpenXRHmdTarget.Hand,
                            out: OpenXRHmdTarget.ControllerState): Boolean {
        val source = latched?.controller(hand)?.state ?: return false
        out.active = source.active
        out.poseValid = source.poseValid
        out.position = source.position
        out.rotation = source.rotation
        out.selectPressed = source.selectPressed
        out.triggerValue = source.triggerValue
        out.triggerPressed = source.triggerPressed
        out.squeezeValue = source.squeezeValue
        out.squeezePressed = source.squeezePressed
        out.trackpadX = source.trackpadX
        out.trackpadY = source.trackpadY
        out.trackpadTouched = source.trackpadTouched
        return true
    }

    /** Controllers carried by the latched pose, whichever hand — including unknown-handed ones. */
    val latchedControllers: List<VisionController> get() = latched?.controllers ?: emptyList()

    override fun headPose(outPosition: Vec3, outOrientation: Quat): Boolean {
        val frame = latched ?: return false
        outPosition.set(frame.headPosition)
        outOrientation.set(frame.headOrientation)
        return true
    }

    /**
     * The view to render this eye from, clamping past the end.
     *
     * The visionOS *simulator* reports a single view, so a two-eye render there draws both eyes from
     * view 0 — monoscopic, and correct for what the head actually said. Hardware reports two. Failing
     * instead would make the simulator useless for everything else in the pipeline.
     */
    private fun eyeFor(eyeIndex: Int): VisionEyeView? {
        val eyes = latched?.eyes ?: return null
        if (eyes.isEmpty()) return null
        return eyes[eyeIndex.coerceIn(0, eyes.size - 1)]
    }

    override fun toString(): String = prettySummary(
        "VisionPoseStereoSource",
        "frame" to latchedFrameId,
        "eyes" to eyeCount,
        "controllers" to latchedControllers.size,
        "staleLatches" to staleLatchCount,
        "zNear" to zNear,
        "zFar" to zFar,
    )
}
