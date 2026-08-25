package afield.four.targets

import afield.stdlib.Quat
import afield.stdlib.Vec3
import afield.stdlib.linalg.Matrix4f

/**
 * A source of stereo viewing geometry that is neither OpenXR nor a desktop mock — a head somewhere
 * else, reporting where it is.
 *
 * [SideBySideStereoTarget] already chose between an attached [OpenXRHmdTarget] and its internal
 * `stereoCamera`; this is the third case, and it exists because a *remote* head differs from both in
 * one way that matters: its pose arrives asynchronously, so it has to be **latched**.
 *
 * OpenXR is queried synchronously inside the frame it renders, so reading it twice — once per eye —
 * can only ever give one answer. A remote pose can change between the two eye passes, which would
 * render the left eye from one head position and the right eye from another. That does not look like
 * latency; it looks like the two eyes disagreeing, and it is very hard to recognise from inside a
 * headset. So the contract here is explicit: [latch] once per frame, and every accessor answers from
 * that latched pose until the next [latch].
 *
 * The other half of the contract is [latchedFrameId]. Whatever renders from this source can record
 * *which* pose the image was drawn from, which is what lets a later stage reproject it correctly
 * rather than guess.
 */
interface StereoPoseSource {

    /**
     * Take the newest usable pose as the one to render this frame from.
     *
     * Returns false when there is nothing usable — no pose has arrived, or the head reported that
     * tracking was invalid. On false the previously latched pose is **kept**, so the view freezes
     * where it was instead of snapping to the origin. A frozen image is a recognisable "tracking
     * dropped"; a snap to origin looks like a bug in the scene.
     */
    fun latch(): Boolean

    /** Identity of the latched pose, or -1 when nothing has ever been latched. */
    val latchedFrameId: Long

    /**
     * The head's own predicted display time for the latched pose, in the head's clock (seconds).
     * Meaningless against this machine's clock; carried so a consumer can hand it back to the head.
     */
    val latchedDisplayTime: Double

    /** Views the latched pose carried. One on the visionOS simulator, two on hardware. */
    val eyeCount: Int

    /** World→eye for the latched pose. Indices past [eyeCount] clamp to the last view. */
    fun eyeViewMatrix(eyeIndex: Int, out: Matrix4f = Matrix4f()): Matrix4f

    /** Projection for the latched pose, in this renderer's own depth convention. */
    fun eyeProjectionMatrix(eyeIndex: Int, out: Matrix4f = Matrix4f()): Matrix4f

    /** Head pose of the latched frame. False when nothing is latched. */
    fun headPose(outPosition: Vec3 = Vec3(), outOrientation: Quat = Quat()): Boolean

    /**
     * Fill [out] with [hand]'s controller as of the latched pose; false when this source has none.
     *
     * Defaulted off so a pose-only source stays a two-method implementation. It reports into
     * [OpenXRHmdTarget.ControllerState] — the same type the OpenXR path fills — because the point of
     * this interface is that Field's interaction code cannot tell which backend it is running on.
     * Controllers come from the *latched* frame for the same reason the eyes do: input sampled at a
     * different instant from the image it acts on is a subtle, permanent source of mis-aimed rays.
     */
    fun controller(hand: OpenXRHmdTarget.Hand,
                   out: OpenXRHmdTarget.ControllerState): Boolean = false
}
