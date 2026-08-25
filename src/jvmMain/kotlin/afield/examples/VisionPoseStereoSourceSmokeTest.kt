package afield.examples

import afield.four.targets.OpenXRHmdTarget
import afield.four.util.Options
import afield.graphics.VISION_POSE_CHANNEL
import afield.graphics.VISION_POSE_VERSION
import afield.graphics.VisionPoseStereoSource
import afield.graphics.VisionPoseStream
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import kotlin.math.abs

/*
FIELD_TEST
id: visionpose.stereo-source
runner: jvm-main
entrypoint: afield.examples.VisionPoseStereoSourceSmokeTest
tags: [smoke, visionos, pose, stereo]
timeoutSec: 90
expect:
  exitCode: 0
  stdoutContains:
    - "VisionPoseStereoSourceSmokeTest ok"
*/

/**
 * Guards the step where a headset's pose becomes something Field can render from:
 * [VisionPoseStereoSource], the third pose source on `SideBySideStereoTarget` alongside OpenXR and
 * the desktop mock.
 *
 * The load-bearing part is the projection, because it is **rebuilt, not forwarded** — the head's
 * matrix carries its compositor's near/far and depth range, and Field's pipelines want
 * [Options.zNear]/[Options.zFar] over a 0..1 depth range. So the frustum is checked *geometrically*:
 * a point sitting exactly on each frustum edge must land exactly on the corresponding clip boundary.
 * Comparing matrix entries against a hand-copied literal would pass just as happily with the near
 * plane wrong or the depth range inverted, and neither is visible in a screenshot — an inverted depth
 * range renders a plausible image with the wrong things in front.
 */
object VisionPoseStereoSourceSmokeTest {

    private const val TAN_LEFT = -1.0f
    private const val TAN_RIGHT = 0.8f
    private const val TAN_DOWN = -0.9f
    private const val TAN_UP = 1.1f

    private const val NEAR = 0.05f
    private const val FAR = 100f

    @JvmStatic
    fun main(args: Array<String>) {
        VisionPoseStream.reset()
        val source = VisionPoseStereoSource(zNear = NEAR, zFar = FAR)

        // ---- nothing latched yet -----------------------------------------------------------------
        require(!source.latch()) { "a latch with no pose must fail" }
        require(source.latchedFrameId == -1L) { "an empty source must report no frame" }
        require(source.eyeCount == 0) { "an empty source must report no eyes" }
        require(!source.headPose()) { "an empty source has no head pose" }
        // And it must still answer, rather than throw, if something renders before a pose arrives.
        val beforeAnyPose = source.eyeViewMatrix(0)
        for (i in 0 until 16) {
            require(beforeAnyPose[i] == Matrix4f()[i]) { "an empty source must answer identity" }
        }

        // ---- a good pose -------------------------------------------------------------------------
        require(VisionPoseStream.accept(poseJson(frameIndex = 7, tracking = true))) { "pose refused" }
        require(source.latch()) { "a tracked pose must latch" }
        require(source.latchedFrameId == 7L) { "latched the wrong frame: ${source.latchedFrameId}" }
        require(source.latchedDisplayTime == 555.25) { "the display time was lost" }
        require(source.eyeCount == 2) { "expected two eyes, got ${source.eyeCount}" }

        // The view matrix is forwarded untouched — the wire already carries world→eye.
        val expectedView = VisionPoseStream.latest!!.eyes[1].viewMatrix
        val actualView = source.eyeViewMatrix(1)
        for (i in 0 until 16) {
            require(abs(actualView[i] - expectedView[i]) <= 1e-6f) {
                "eye 1 view matrix component $i: ${actualView[i]} != ${expectedView[i]}"
            }
        }

        // ---- the projection, checked as a frustum rather than as sixteen numbers ------------------
        val p = source.eyeProjectionMatrix(0)
        val d = 3f   // any distance: an edge is an edge at every depth
        assertNdc("right edge", p, TAN_RIGHT * d, 0f, -d, x = 1f)
        assertNdc("left edge", p, TAN_LEFT * d, 0f, -d, x = -1f)
        assertNdc("top edge", p, 0f, TAN_UP * d, -d, y = 1f)
        assertNdc("bottom edge", p, 0f, TAN_DOWN * d, -d, y = -1f)
        // Vulkan's 0..1 depth range, near at 0. An inverted range is the classic silent failure.
        assertNdc("near plane", p, 0f, 0f, -NEAR, z = 0f)
        assertNdc("far plane", p, 0f, 0f, -FAR, z = 1f)
        // Asymmetry must survive: the centre of the image is not the centre of the frustum.
        val centre = ndc(p, 0f, 0f, -d)
        require(abs(centre[0]) > 0.05f) {
            "an asymmetric frustum must not put the eye axis at NDC x=0, got ${centre[0]}"
        }

        // ---- losing tracking holds the last pose rather than snapping to the origin ---------------
        val heldFrame = source.latchedFrameId
        require(VisionPoseStream.accept(poseJson(frameIndex = 8, tracking = false))) {
            "an untracked pose is still a well-formed message"
        }
        require(!source.latch()) { "an untracked pose must not latch" }
        require(source.latchedFrameId == heldFrame) {
            "losing tracking replaced the held pose with ${source.latchedFrameId}"
        }
        require(source.staleLatchCount == 2L) {
            "expected two stale latches, got ${source.staleLatchCount}"
        }

        // ---- one view is a simulator, not an error -----------------------------------------------
        require(VisionPoseStream.accept(poseJson(frameIndex = 9, tracking = true, views = 1))) {
            "a single-view pose refused"
        }
        require(source.latch()) { "a single-view pose must latch" }
        require(source.eyeCount == 1) { "expected one eye, got ${source.eyeCount}" }
        val mono0 = source.eyeViewMatrix(0)
        val mono1 = source.eyeViewMatrix(1)
        for (i in 0 until 16) {
            require(mono0[i] == mono1[i]) {
                "a one-view head must draw both eyes from view 0; component $i differs"
            }
        }

        // ---- controllers ------------------------------------------------------------------------
        // A pose carrying two controllers plus one whose held hand the head could not determine.
        require(VisionPoseStream.accept(poseJson(frameIndex = 20, tracking = true, controllers = true))) {
            "a pose with controllers refused"
        }
        require(source.latch()) { "a pose with controllers must latch" }
        require(source.latchedControllers.size == 3) {
            "expected three controllers, got ${source.latchedControllers.size}"
        }

        val right = OpenXRHmdTarget.ControllerState()
        require(source.controller(OpenXRHmdTarget.Hand.RIGHT, right)) { "no right controller" }
        require(right.poseValid && right.active) { "the right controller should be tracked" }
        assertVec("right grip position", right.position, 0.3f, 1.1f, -0.5f)
        assertClose("right trigger", right.triggerValue, 0.75f)
        require(right.triggerPressed) { "the right trigger should read pressed" }
        require(right.selectPressed) { "select should read pressed" }
        assertClose("right trackpadX", right.trackpadX, -0.25f)
        require(right.trackpadTouched) { "trackpad should read touched" }

        val left = OpenXRHmdTarget.ControllerState()
        require(source.controller(OpenXRHmdTarget.Hand.LEFT, left)) { "no left controller" }
        assertVec("left grip position", left.position, -0.3f, 1.1f, -0.5f)
        // Untracked: still reported, but the pose must not read as valid — an interaction that
        // ray-casts from an untracked grip would aim at a stale position with no way to notice.
        require(!left.poseValid) { "the untracked left controller must not report a valid pose" }
        require(left.squeezePressed && !left.triggerPressed) { "left button mapping crossed over" }

        // An unknown-handed controller must never be handed out as a hand. Hardware reports inherent
        // chirality without held chirality, so guessing would silently drive the wrong hand.
        require(source.latchedControllers.count { it.hand == null } == 1) {
            "the unknown-handed controller was assigned a hand"
        }

        // A pose without controllers must clear them, not keep the last frame's.
        require(VisionPoseStream.accept(poseJson(frameIndex = 21, tracking = true))) { "pose refused" }
        require(source.latch()) { "must latch" }
        require(source.latchedControllers.isEmpty()) { "controllers outlived the pose that carried them" }
        require(!source.controller(OpenXRHmdTarget.Hand.RIGHT, right)) {
            "a controller-less pose still reported a controller"
        }

        // ---- the defaults come from Field's own convention, not a local guess --------------------
        val defaults = VisionPoseStereoSource()
        require(defaults.zNear == Options.zNear.toFloat() && defaults.zFar == Options.zFar.toFloat()) {
            "the default near/far must be Options.zNear/zFar, got ${defaults.zNear}..${defaults.zFar}"
        }

        println("source: $source")
        println("VisionPoseStereoSourceSmokeTest ok")
    }

    /** Project an eye-space point and check whichever NDC components the caller named. */
    private fun assertNdc(
        what: String, p: Matrix4f, ex: Float, ey: Float, ez: Float,
        x: Float? = null, y: Float? = null, z: Float? = null,
    ) {
        val n = ndc(p, ex, ey, ez)
        x?.let { require(abs(n[0] - it) <= 1e-3f) { "$what: NDC x = ${n[0]}, expected $it" } }
        y?.let { require(abs(n[1] - it) <= 1e-3f) { "$what: NDC y = ${n[1]}, expected $it" } }
        z?.let { require(abs(n[2] - it) <= 1e-3f) { "$what: NDC z = ${n[2]}, expected $it" } }
    }

    private fun assertClose(what: String, actual: Float, expected: Float, tolerance: Float = 1e-4f) {
        require(abs(actual - expected) <= tolerance) { "$what = $actual, expected $expected" }
    }

    private fun assertVec(what: String, v: Vector3f, x: Float, y: Float, z: Float) {
        assertClose("$what.x", v.x, x); assertClose("$what.y", v.y, y); assertClose("$what.z", v.z, z)
    }

    private fun ndc(p: Matrix4f, x: Float, y: Float, z: Float): FloatArray {
        val clip: Vector4f = p * Vector4f(x, y, z, 1f)
        require(abs(clip.w) > 1e-9f) { "degenerate clip w for ($x, $y, $z)" }
        return floatArrayOf(clip.x / clip.w, clip.y / clip.w, clip.z / clip.w)
    }

    /** A head one metre up, looking down -Z, with the eyes offset ±32 mm. */
    private fun poseJson(frameIndex: Int, tracking: Boolean, views: Int = 2,
                         controllers: Boolean = false): String {
        val origin = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )
        val m00 = 2f / (TAN_RIGHT - TAN_LEFT)
        val m20 = (TAN_RIGHT + TAN_LEFT) / (TAN_RIGHT - TAN_LEFT)
        val m11 = 2f / (TAN_UP - TAN_DOWN)
        val m21 = (TAN_UP + TAN_DOWN) / (TAN_UP - TAN_DOWN)
        // A deliberately different depth convention from Field's, so a forwarded matrix would fail.
        val projection = floatArrayOf(
            m00, 0f, 0f, 0f,
            0f, m11, 0f, 0f,
            m20, m21, 0f, -1f,
            0f, 0f, 0.007f, 0f,
        )
        fun eye(offsetX: Float) = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            offsetX, 0f, 0f, 1f,
        )
        val offsets = if (views == 1) listOf(0f) else listOf(-0.032f, 0.032f)
        val viewJson = offsets.joinToString(",") { off ->
            """{"transform":${eye(off).joinToString(",", "[", "]")},""" +
                """"projection":${projection.joinToString(",", "[", "]")}}"""
        }
        val controllerJson = if (!controllers) "" else {
            fun grip(x: Float) = floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                x, 1.1f, -0.5f, 1f,
            ).joinToString(",", "[", "]")
            ",\"controllers\":[" +
                """{"chirality":"right","tracked":true,"originFromGrip":${grip(0.3f)},
                     "select":true,"trigger":0.75,"triggerPressed":true,
                     "trackpadX":-0.25,"trackpadY":0.5,"trackpadTouched":true},""" +
                """{"chirality":"left","tracked":false,"originFromGrip":${grip(-0.3f)},
                     "squeeze":0.9,"squeezePressed":true},""" +
                """{"chirality":"unknown","tracked":true,"originFromGrip":${grip(0f)}}""" +
                "]"
        }
        return """
            {"channel":"$VISION_POSE_CHANNEL","type":"visionpose/frame","version":$VISION_POSE_VERSION,
             "device":"stereo-head","frameIndex":$frameIndex,
             "predictedDisplayTime":555.25,"sentAt":555.26,"trackingValid":$tracking,
             "originFromDevice":${origin.joinToString(",", "[", "]")},
             "views":[$viewJson]$controllerJson}
        """.trimIndent()
    }
}
