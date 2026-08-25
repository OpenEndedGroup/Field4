package afield.examples

import afield.Handled
import afield.graphics.VISION_POSE_CHANNEL
import afield.graphics.VISION_POSE_VERSION
import afield.graphics.VisionPoseStream
import afield.graphics.VisionPoseWireFrame
import afield.graphics.VisionPoseWireView
import afield.graphics.installVisionPoseHandler
import afield.graphics.visionPoseRawHandler
import afield.graphics.visionPoseWireJson
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import kotlin.math.abs

/*
FIELD_TEST
id: visionpose.uplink
runner: jvm-main
entrypoint: afield.examples.VisionPoseUplinkSmokeTest
tags: [smoke, visionos, pose]
timeoutSec: 90
expect:
  exitCode: 0
  stdoutContains:
    - "VisionPoseUplinkSmokeTest ok"
*/

/**
 * Guards the pose uplink's arithmetic, which is the half of the head-tracking milestone that a device
 * cannot check for you.
 *
 * With a headset on, a wrong handedness, a transposed matrix or a swapped eye all look the same —
 * "the world is wrong somehow" — and none of them look wrong in a screenshot. So the composition from
 * the wire (`(originFromDevice * deviceFromEye).inverse`) and the tangent extraction out of the
 * compositor's projection are pinned here against hand-computed values, and the *hand-written* JSON
 * below is what the visionOS mirror is checked against: it is the format as documented, not as the
 * Kotlin serializer happens to spell it today.
 */
object VisionPoseUplinkSmokeTest {

    /** Head at (1, 1.5, -2), yawed 90° about +Y. Column-major, as the wire carries it. */
    private val originFromDevice = floatArrayOf(
        0f, 0f, -1f, 0f,
        0f, 1f, 0f, 0f,
        1f, 0f, 0f, 0f,
        1f, 1.5f, -2f, 1f,
    )

    /** device→eye: a 64 mm interpupillary offset, no rotation. */
    private fun eyeTransform(offsetX: Float) = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        offsetX, 0f, 0f, 1f,
    )

    // Deliberately asymmetric, the way a real HMD's frustum is; a symmetric one would pass even with
    // left and right confused.
    private const val TAN_LEFT = -1.0f
    private const val TAN_RIGHT = 0.8f
    private const val TAN_DOWN = -0.9f
    private const val TAN_UP = 1.1f

    /** A projection carrying those tangents, in a reverse-Z convention the extraction must ignore. */
    private val projection = run {
        val m00 = 2f / (TAN_RIGHT - TAN_LEFT)
        val m20 = (TAN_RIGHT + TAN_LEFT) / (TAN_RIGHT - TAN_LEFT)
        val m11 = 2f / (TAN_UP - TAN_DOWN)
        val m21 = (TAN_UP + TAN_DOWN) / (TAN_UP - TAN_DOWN)
        floatArrayOf(
            m00, 0f, 0f, 0f,
            0f, m11, 0f, 0f,
            m20, m21, 0f, -1f,
            0f, 0f, 0.05f, 0f,
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        installVisionPoseHandler()
        val handler = visionPoseRawHandler()
            ?: error("installVisionPoseHandler registered nothing on $VISION_POSE_CHANNEL")

        VisionPoseStream.reset()
        var seen = 0
        VisionPoseStream.onPose { seen++ }

        // ---- a head announcing itself -----------------------------------------------------------
        require(handler(hello()) == Handled.yes) { "a hello must be handled" }
        val head = VisionPoseStream.heads["test-head"] ?: error("hello did not register the head")
        require(head.viewCount == 2 && head.refreshHz == 90.0) { "hello fields lost: $head" }

        // ---- one pose ---------------------------------------------------------------------------
        require(handler(poseJson(frameIndex = 1)) == Handled.yes) { "a pose frame must be handled" }
        val frame = VisionPoseStream.latest ?: error("a handled pose frame did not become latest")
        require(seen == 1) { "the listener saw $seen poses, expected 1" }
        require(frame.device == "test-head" && frame.frameIndex == 1L) { "identity lost: $frame" }
        require(frame.trackingValid) { "trackingValid lost" }
        require(frame.predictedDisplayTime == 123456.789) { "predictedDisplayTime lost" }
        require(frame.sentAt == 123456.778) { "sentAt lost" }
        require(frame.receivedAtNanos != 0L) { "arrival was not stamped" }

        // The head's own pose, straight off the transform's fourth column.
        assertVec("head position", frame.headPosition, 1f, 1.5f, -2f)
        // 90° about +Y is (0, sin45, 0, cos45).
        val q = frame.headOrientation
        assertClose("head qx", q.x, 0f); assertClose("head qy", q.y, 0.70710678f)
        assertClose("head qz", q.z, 0f); assertClose("head qw", q.w, 0.70710678f)

        require(frame.eyes.size == 2) { "expected two eyes, got ${frame.eyes.size}" }

        // A left eye 32 mm along the *head's* -X, which a 90° yaw points at world +Z: the eye must
        // land at z = -1.968, not at x = 0.968. Getting this wrong is the classic symptom where the
        // stereo pair looks fine standing still and shears when you turn your head.
        val left = frame.eyes[0]
        assertVec("left eye position", Matrix4f(left.viewMatrix).invert().getTranslation(),
                  1f, 1.5f, -1.968f)
        assertVec("right eye position", Matrix4f(frame.eyes[1].viewMatrix).invert().getTranslation(),
                  1f, 1.5f, -2.032f)

        // And the view matrix must actually be world→eye: the eye's own world position maps to zero.
        val leftEyeWorld = Matrix4f(left.viewMatrix).invert().getTranslation()
        assertVec("world→eye origin", left.viewMatrix * leftEyeWorld, 0f, 0f, 0f)

        // Tangents recovered from the projection, depth convention ignored.
        assertClose("tanLeft", left.tanLeft, TAN_LEFT)
        assertClose("tanRight", left.tanRight, TAN_RIGHT)
        assertClose("tanDown", left.tanDown, TAN_DOWN)
        assertClose("tanUp", left.tanUp, TAN_UP)
        // The compositor's own matrix rides through untouched, for anything that wants it verbatim.
        assertClose("projection m32 carried", left.projection.m32, 0.05f)

        // ---- statistics -------------------------------------------------------------------------
        require(handler(poseJson(frameIndex = 2)) == Handled.yes) { "a second pose must be handled" }
        require(VisionPoseStream.frameCount == 2L) { "frameCount = ${VisionPoseStream.frameCount}" }
        require(VisionPoseStream.regressedCount == 0L) { "an advancing frameIndex is not a regression" }
        require(VisionPoseStream.intervalMillis > 0.0) { "arrival interval was never measured" }

        require(handler(poseJson(frameIndex = 2)) == Handled.yes) { "a repeat must still be handled" }
        require(VisionPoseStream.regressedCount == 1L) {
            "a repeated frameIndex must count as a regression, got ${VisionPoseStream.regressedCount}"
        }

        // Latest-wins has to survive out-of-order arrival, because it happens: Field's /ws reader
        // launches a coroutine per raw message, so two poses decode concurrently and finish in
        // either order. Measured at 25 in 968 against the simulator. An older pose applied over a
        // newer one is the "world swims when I turn my head" failure — invisible in a screenshot and
        // not what anyone would look for in the renderer.
        require(handler(poseJson(frameIndex = 1)) == Handled.yes) { "a late pose is still understood" }
        require(VisionPoseStream.latest?.frameIndex == 2L) {
            "a late pose overwrote a newer one: latest is ${VisionPoseStream.latest?.frameIndex}"
        }
        require(VisionPoseStream.regressedCount == 2L) { "the late pose was not counted" }

        // A renderer restart counts from zero again, and treating that as permanently stale would
        // wedge the stream in silence. Under the same device id the only signal is the size of the
        // jump, so this has to be a *large* step back — a two-frame one is reordering, and asserting
        // on that would be asserting the rule away.
        require(handler(poseJson(frameIndex = 1000)) == Handled.yes) { "a normal advance" }
        require(VisionPoseStream.latest?.frameIndex == 1000L) { "frame 1000 did not land" }
        require(handler(poseJson(frameIndex = 0)) == Handled.yes) { "a restart must be handled" }
        require(VisionPoseStream.latest?.frameIndex == 0L) {
            "a restarted head never recovered: latest is ${VisionPoseStream.latest?.frameIndex}"
        }
        // A short step back, at the same distance from a *low* index, is still reordering.
        require(handler(poseJson(frameIndex = 5)) == Handled.yes) { "frame 5 advances" }
        require(handler(poseJson(frameIndex = 4)) == Handled.yes) { "frame 4 is still understood" }
        require(VisionPoseStream.latest?.frameIndex == 5L) { "a one-frame step back was applied" }

        // ---- what must be refused ---------------------------------------------------------------
        val rejectedBefore = VisionPoseStream.rejectedCount
        val stale = poseJson(frameIndex = 9).replace(
            "\"version\":$VISION_POSE_VERSION", "\"version\":${VISION_POSE_VERSION + 1}")
        require(handler(stale) == Handled.no) { "a future wire version must not be handled" }
        require(handler("""{"channel":"$VISION_POSE_CHANNEL", not json""") == Handled.no) {
            "malformed JSON must not be handled"
        }
        require(handler("""{"channel":"$VISION_POSE_CHANNEL","type":"visionpose/frame",""" +
                        """"version":$VISION_POSE_VERSION,"device":"x"}""") == Handled.no) {
            "a frame missing its matrices must not be handled"
        }
        require(VisionPoseStream.latest?.frameIndex == 5L) { "a refused message changed the pose" }
        require(VisionPoseStream.rejectedCount == rejectedBefore + 3) {
            "expected three rejections, got ${VisionPoseStream.rejectedCount - rejectedBefore}"
        }
        // Another channel's traffic is not ours to reject — it must fall through unclaimed. Same for
        // anything that never names the channel: the handler is offered every unrecognised /ws
        // message and takes a substring shortcut before parsing, so junk it never looked at must not
        // land in our statistics. Pinned because that shortcut is an optimisation, and an
        // optimisation that starts swallowing our own traffic would look exactly like silence.
        require(handler("""{"channel":"__visionmetal__","type":"subscribe-all","version":2}""")
                    == Handled.no) { "another channel's message must fall through" }
        require(handler("{not json at all") == Handled.no) { "unrelated junk must fall through" }
        require(VisionPoseStream.rejectedCount == rejectedBefore + 3) {
            "traffic that never names the channel must not be counted as a rejection"
        }

        // ---- the field names the visionOS mirror encodes -----------------------------------------
        assertWireNamesMatchTheHead()

        println("pose summary: ${VisionPoseStream.summary()}")
        println("VisionPoseUplinkSmokeTest ok")
    }

    /**
     * The Swift `PoseWire` mirror is hand-written, so nothing but a test stops the two spellings
     * drifting. Encoding the Kotlin types and comparing the *key set* against the documented format
     * catches a renamed or dropped field on this side; `Pose/PoseWire.swift` carries the same list.
     */
    private fun assertWireNamesMatchTheHead() {
        val wire = VisionPoseWireFrame(
            device = "test-head", frameIndex = 500L,
            predictedDisplayTime = 1.0, sentAt = 1.0, trackingValid = true,
            originFromDevice = originFromDevice,
            views = listOf(VisionPoseWireView(eyeTransform(-0.032f), projection)),
        )
        val encoded = visionPoseWireJson.encodeToString(VisionPoseWireFrame.serializer(), wire)
        val expected = listOf(
            "channel", "type", "version", "device", "frameIndex",
            "predictedDisplayTime", "sentAt", "trackingValid", "originFromDevice", "views",
            "transform", "projection",
        )
        expected.forEach { name ->
            require(encoded.contains("\"$name\"")) { "the wire lost the '$name' field: $encoded" }
        }
        // And the round trip has to survive our own encoder, not just the hand-written JSON.
        require(VisionPoseStream.accept(encoded)) { "the encoder emitted something the decoder refuses" }
        require(VisionPoseStream.latest?.frameIndex == 500L) { "the round-tripped pose was not published" }
    }

    private fun hello() = """
        {"channel":"$VISION_POSE_CHANNEL","type":"visionpose/hello","version":$VISION_POSE_VERSION,
         "device":"test-head","name":"Smoke","viewCount":2,"refreshHz":90.0}
    """.trimIndent()

    /** Hand-written, so the decoder is checked against the documented format rather than its sibling. */
    private fun poseJson(frameIndex: Int) = """
        {"channel":"$VISION_POSE_CHANNEL","type":"visionpose/frame","version":$VISION_POSE_VERSION,
         "device":"test-head","frameIndex":$frameIndex,
         "predictedDisplayTime":123456.789,"sentAt":123456.778,"trackingValid":true,
         "originFromDevice":${originFromDevice.joinToString(",", "[", "]")},
         "views":[{"transform":${eyeTransform(-0.032f).joinToString(",", "[", "]")},
                   "projection":${projection.joinToString(",", "[", "]")}},
                  {"transform":${eyeTransform(0.032f).joinToString(",", "[", "]")},
                   "projection":${projection.joinToString(",", "[", "]")}}]}
    """.trimIndent()

    private fun assertClose(what: String, actual: Float, expected: Float, tolerance: Float = 1e-4f) {
        require(abs(actual - expected) <= tolerance) { "$what = $actual, expected $expected" }
    }

    private fun assertVec(what: String, v: Vector3f, x: Float, y: Float, z: Float) {
        assertClose("$what.x", v.x, x); assertClose("$what.y", v.y, y); assertClose("$what.z", v.z, z)
    }
}
