package afield.graphics

import afield.Handled
import afield.RunsAt
import afield.installHandlers
import afield.rawHandlers
import afield.four.targets.OpenXRHmdTarget
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector3f
import afield.stdlib.prettySummary
import io.ktor.websocket.WebSocketSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * The pose **uplink**: head and per-eye viewing geometry travelling *from* a visionOS head *to* this
 * Field process, so that the Vulkan renderer on this machine can draw the frame the headset is about
 * to display.
 *
 * This is the reverse of every other visionOS channel in Field. [VisionMetal] pushes a program down
 * to the head and the head renders it; the RTT stream pushes encoded pixels down
 * (`native/field-encode/WIRE_FORMAT.md`). Neither tells this process where the wearer is looking, so
 * the streamed image can only ever be a world-locked surface — a plane in space. Head-locked,
 * full-field VR needs the pose to come *up* first, which is what this file receives.
 *
 * ## Wire format (`__visionpose__`, version 1)
 *
 * JSON over the existing `/ws` bus, device → here. Three message types:
 *
 * | `type` | when | carries |
 * |---|---|---|
 * | `visionpose/hello` | once per connection | `device`, `name`, the head's view count and refresh rate |
 * | `visionpose/frame` | once per compositor frame | the pose, below |
 * | `visionpose/bye`   | on a clean immersive-space exit | `device` |
 *
 * A `visionpose/frame` is:
 *
 * ```json
 * {"channel":"__visionpose__","type":"visionpose/frame","version":1,
 *  "device":"<uuid>","frameIndex":1842,
 *  "predictedDisplayTime":123456.789,"sentAt":123456.778,
 *  "trackingValid":true,
 *  "originFromDevice":[16 floats],
 *  "views":[{"transform":[16 floats],"projection":[16 floats]}, …]}
 * ```
 *
 * Every matrix is **16 floats, column-major** — `m[column * 4 + row]`, which is both `simd_float4x4`'s
 * own storage order on the device and [Matrix4f]'s here, so the array crosses the wire untransposed.
 *
 * `originFromDevice` is ARKit's `DeviceAnchor.originFromAnchorTransform`: the head's pose in the
 * world origin the head established. `views[i].transform` is CompositorServices'
 * `LayerRenderer.Drawable.View.transform`, i.e. device→eye, and `views[i].projection` is that
 * drawable's `computeProjection(viewIndex:)`. Both timestamps are in the head's own clock (seconds,
 * `LayerRenderer.Clock`), so differences *within* one head are meaningful and comparisons against
 * this machine's clock are not — see [VisionPoseFrame.receivedAtNanos].
 *
 * ## Deliberately not solved here
 *
 * - **Prediction.** `predictedDisplayTime` is when the *head's own* next frame displays, which is
 *   earlier than when a frame rendered here could arrive. Shifting the query forward by the measured
 *   render→encode→network→decode latency is the third milestone's job; this file only has to carry
 *   the timestamp faithfully so that shift is a one-line change on the head.
 * - **Transport.** JSON over the WebSocket reuses the bus, the dev-certificate trust and the
 *   reconnect logic the other native clients already have, at the cost of TCP in the tracking path.
 *   A pose is ~700 bytes; at 90 Hz that is ~60 kB/s, which is not the reason to move it to UDP. The
 *   reason will be latency, measured, not assumed.
 */
const val VISION_POSE_CHANNEL = "__visionpose__"

/**
 * Wire version 1. Bump when the *shape* changes; a head sending another version is dropped rather
 * than half-read, so an old Field4Vision build reports as silence rather than as garbage poses.
 */
const val VISION_POSE_VERSION = 1

/** Per-eye viewing geometry exactly as the head's compositor stated it. */
@Serializable
data class VisionPoseWireView(
    /** device→eye, column-major. */
    val transform: FloatArray,
    /** The compositor's projection for this eye, column-major. */
    val projection: FloatArray,
) {
    // FloatArray members give a data class reference equality. Pose frames are never compared, so
    // say so rather than leaving a default that looks meaningful.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * One tracked controller, as the head reports it.
 *
 * Fields mirror [afield.four.targets.OpenXRHmdTarget.ControllerState] deliberately, so this maps onto
 * the shape Field's interaction code is *already* written against rather than introducing a second
 * vocabulary for the same thing. A Vision Pro's accessories are read from ARKit's
 * `AccessoryTrackingProvider` (pose, chirality) and GameController (buttons, axes); an OpenXR runtime
 * reports the same quantities under different names. Field should not be able to tell.
 */
@Serializable
data class VisionPoseWireController(
    /** "left" | "right" | "unknown" — the head's *held* chirality, not the device's inherent one. */
    val chirality: String,
    /** The accessory is present and its pose is being tracked. */
    val tracked: Boolean,
    /** world→grip, column-major. Grip, not aim: it is the pose a hand-held object should follow. */
    val originFromGrip: FloatArray,
    val select: Boolean = false,
    val trigger: Float = 0f,
    val triggerPressed: Boolean = false,
    val squeeze: Float = 0f,
    val squeezePressed: Boolean = false,
    val trackpadX: Float = 0f,
    val trackpadY: Float = 0f,
    val trackpadTouched: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** One frame of head tracking, as it arrives. [VisionPoseFrame] is the form Field consumes. */
@Serializable
data class VisionPoseWireFrame(
    val channel: String = VISION_POSE_CHANNEL,
    val type: String = "visionpose/frame",
    val version: Int = VISION_POSE_VERSION,
    val device: String,
    val frameIndex: Long,
    val predictedDisplayTime: Double,
    val sentAt: Double,
    val trackingValid: Boolean,
    /** world→head (ARKit `originFromAnchorTransform`), column-major. */
    val originFromDevice: FloatArray,
    val views: List<VisionPoseWireView>,
    /**
     * Tracked controllers as of this pose. Additive and optional: a head that reports none, or an
     * older head that does not know the field, simply yields no controllers. That is why adding this
     * did **not** need a [VISION_POSE_VERSION] bump — on a JSON channel with `ignoreUnknownKeys`, an
     * optional field is compatible in both directions. Bump the version only for a change that would
     * *misread* an old message.
     */
    val controllers: List<VisionPoseWireController> = emptyList(),
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = frameIndex.hashCode()
}

/** A head announcing itself, so a listener can size buffers before the first frame arrives. */
@Serializable
data class VisionPoseHello(
    val channel: String = VISION_POSE_CHANNEL,
    val type: String = "visionpose/hello",
    val version: Int = VISION_POSE_VERSION,
    val device: String,
    val name: String = "",
    val viewCount: Int = 2,
    val refreshHz: Double = 90.0,
)

val visionPoseWireJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/**
 * One eye, ready to render with.
 *
 * [viewMatrix] is world→eye, composed here the same way the head composes its own —
 * `(originFromDevice * transform).inverse` — so a frame rendered from it lines up with what the head
 * would have drawn for that timestamp.
 *
 * [projection] is carried through untouched, but Field's Vulkan renderer does not share the
 * compositor's depth convention, so the useful members are the four **tangents**: the half-extents of
 * the frustum at unit distance, signed, left/down negative. They let the renderer build a projection
 * in whatever near/far and depth range it already uses. This is the same decomposition
 * [afield.four.targets.OpenXRHmdTarget] does from `XrFovf`'s angles, one `tan` earlier.
 */
class VisionEyeView(
    val viewMatrix: Matrix4f,
    val projection: Matrix4f,
    val tanLeft: Float,
    val tanRight: Float,
    val tanDown: Float,
    val tanUp: Float,
) {
    override fun toString(): String = prettySummary(
        "VisionEyeView",
        "eyePosition" to Matrix4f(viewMatrix).invert().getTranslation(),
        "tangents" to "l=%.3f r=%.3f d=%.3f u=%.3f".format(tanLeft, tanRight, tanDown, tanUp),
    )
}

/**
 * One controller, ready to use.
 *
 * [state] is the very type the OpenXR path produces, so a Field interaction written against
 * `SideBySideStereoTarget.controllerState(...)` works unchanged on a Vision Pro. [hand] is null when
 * the head could not say which hand is holding the accessory — which is a real state on hardware that
 * reports inherent-but-not-held chirality, not an error.
 */
class VisionController(
    val hand: OpenXRHmdTarget.Hand?,
    val state: OpenXRHmdTarget.ControllerState,
) {
    override fun toString(): String = prettySummary(
        "VisionController",
        "hand" to (hand?.name ?: "unknown"),
        "tracked" to state.poseValid,
        "position" to state.position,
        "trigger" to state.triggerValue,
    )
}

/**
 * One arrived pose. Immutable: [VisionPoseStream] republishes a whole new frame rather than mutating
 * the last one, so a renderer that captured [VisionPoseStream.latest] at the top of its frame keeps a
 * consistent head and eyes all the way through it — the association between a pose and the image
 * drawn from it is the whole point of the milestone.
 */
class VisionPoseFrame(
    val device: String,
    val frameIndex: Long,
    /** Head clock, seconds: when the head expects to display its own next frame. */
    val predictedDisplayTime: Double,
    /** Head clock, seconds: when the head handed this to its socket. */
    val sentAt: Double,
    /** This machine's [System.nanoTime] on arrival. Not comparable with the two above. */
    val receivedAtNanos: Long,
    val trackingValid: Boolean,
    /** world→head. */
    val originFromDevice: Matrix4f,
    val headPosition: Vector3f,
    val headOrientation: Quaternionf,
    val eyes: List<VisionEyeView>,
    val controllers: List<VisionController> = emptyList(),
) {
    /** The controller in [hand], or null. Unknown-handed controllers never match. */
    fun controller(hand: OpenXRHmdTarget.Hand): VisionController? =
        controllers.firstOrNull { it.hand == hand }

    /** Head clock, seconds: how long the head spent between sampling and sending. */
    val sendLatencySeconds: Double get() = sentAt - predictedDisplayTime

    override fun toString(): String = prettySummary(
        "VisionPoseFrame",
        "device" to device,
        "frame" to frameIndex,
        "tracking" to trackingValid,
        "position" to headPosition,
        "orientation" to headOrientation,
        "eyes" to eyes.size,
        "controllers" to controllers.size,
    )
}

/**
 * Everything known about the heads currently reporting pose.
 *
 * Latest-wins by design: a pose is only useful while it is the newest one, so there is no queue to
 * fall behind. The counters exist because the third milestone is a latency measurement and, per the
 * plan, the instrumentation should predate the optimisation rather than be archaeology after it.
 */
object VisionPoseStream {
    @Volatile
    var latest: VisionPoseFrame? = null
        private set

    /** The most recent `visionpose/hello`, per device. */
    val heads: MutableMap<String, VisionPoseHello> = ConcurrentHashMap()

    private val frames = AtomicLong(0)
    private val regressions = AtomicLong(0)
    private val rejected = AtomicLong(0)
    private val listeners = CopyOnWriteArrayList<(VisionPoseFrame) -> Unit>()
    private val sessions = CopyOnWriteArraySet<WebSocketSession>()

    private val publishLock = Any()
    @Volatile private var lastDevice: String? = null
    @Volatile private var lastFrameIndex = Long.MIN_VALUE
    @Volatile private var intervalMillisEwma = 0.0
    @Volatile private var lastLogNanos = 0L

    /**
     * Print a pose summary at this rate. Zero is silent; the first pose of a run always prints one
     * line whatever this says, because "did anything arrive at all" is the question a fresh headset
     * raises and grepping for silence is not an answer.
     *
     * Set with `-Dfield.visionpose.logHz=10` or from a box. Tracking is consumed at full rate either
     * way — this only throttles the console.
     */
    @Volatile
    var logHz: Double = System.getProperty("field.visionpose.logHz")?.toDoubleOrNull() ?: 0.0

    /** Poses accepted since startup. */
    val frameCount: Long get() = frames.get()

    /**
     * Poses dropped for arriving out of order, rather than applied over a newer one.
     *
     * Some of these are *normal and expected*, and the cause is on this side: Field's `/ws` reader
     * dispatches each raw message in its own coroutine, so two poses decoded concurrently can finish
     * in either order. The head sends strictly in order over one socket; what arrives here is not.
     *
     * Compare it against [frameCount], not against a sampled rate — both are cumulative since
     * startup, and comparing a total to a windowed delta says nothing.
     */
    val regressedCount: Long get() = regressions.get()

    /** Messages on the channel that were not usable: wrong version, malformed, wrong shape. */
    val rejectedCount: Long get() = rejected.get()

    /** Smoothed arrival interval in milliseconds — the honest answer to "is this really 90 Hz?". */
    val intervalMillis: Double get() = intervalMillisEwma

    /** Heads that have said hello on a still-open session. */
    val connectedHeadCount: Int get() = sessions.size

    fun onPose(listener: (VisionPoseFrame) -> Unit): VisionPoseStream {
        listeners += listener
        return this
    }

    fun removePoseListener(listener: (VisionPoseFrame) -> Unit) {
        listeners -= listener
    }

    /**
     * Decode and publish one message from the channel. The handler is a thin wrapper over this, so a
     * test can drive the real path — decode, matrix composition, tangent extraction, statistics —
     * without a socket.
     *
     * Returns true if the message was understood.
     */
    fun accept(text: String, session: WebSocketSession? = null): Boolean {
        val payload = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return reject()
        if (payload["channel"]?.jsonPrimitive?.contentOrNull != VISION_POSE_CHANNEL) return false
        if (payload["version"]?.jsonPrimitive?.content?.toIntOrNull() != VISION_POSE_VERSION) return reject()

        return when (payload["type"]?.jsonPrimitive?.contentOrNull) {
            "visionpose/hello" -> {
                val hello = runCatching {
                    visionPoseWireJson.decodeFromJsonElement<VisionPoseHello>(payload)
                }.getOrNull() ?: return reject()
                heads[hello.device] = hello
                session?.let { sessions += it }
                println("[vision-pose] head '${hello.name}' ${hello.device} attached: " +
                        "${hello.viewCount} views @ ${hello.refreshHz} Hz")
                true
            }
            "visionpose/frame" -> {
                val wire = runCatching {
                    visionPoseWireJson.decodeFromJsonElement<VisionPoseWireFrame>(payload)
                }.getOrNull() ?: return reject()
                val frame = runCatching { wire.toPoseFrame() }.getOrNull() ?: return reject()
                publish(frame)
                true
            }
            "visionpose/selfcheck" -> {
                // The head's launch-time media-wire parser verdict, relayed here because neither the
                // unified log nor `simctl --console-pty` reaches the Mac from a simulator or device.
                val ok = payload["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: return reject()
                val name = payload["name"]?.jsonPrimitive?.contentOrNull ?: "?"
                val detail = payload["detail"]?.jsonPrimitive?.contentOrNull ?: ""
                if (ok) println("[vision-pose/selfcheck] $name: rtt-wire-selfcheck ok (v1 + v2 pose tail)")
                else println("[vision-pose/selfcheck] $name: rtt-wire-selfcheck FAILED: $detail")
                true
            }
            "visionpose/bye" -> {
                val device = payload["device"]?.jsonPrimitive?.contentOrNull
                if (device != null) heads.remove(device)
                session?.let { sessions -= it }
                println("[vision-pose] head ${device ?: "?"} detached")
                true
            }
            else -> reject()
        }
    }

    private fun reject(): Boolean {
        rejected.incrementAndGet()
        return false
    }

    private fun publish(frame: VisionPoseFrame) {
        val previous = synchronized(publishLock) {
            // Latest-wins has to mean it. Field's `/ws` reader launches a coroutine per raw message,
            // so poses decode concurrently and finish in either order; without this an older pose
            // overwrites a newer one a few percent of the time, which is the "world swims when I turn
            // my head" failure rather than anything visible as lag.
            if (!supersedes(frame)) {
                regressions.incrementAndGet()
                return
            }
            lastDevice = frame.device
            lastFrameIndex = frame.frameIndex
            val previous = latest
            latest = frame
            previous
        }
        if (previous != null) {
            val deltaMillis = (frame.receivedAtNanos - previous.receivedAtNanos) / 1.0e6
            // Ignore the gap across a reconnect: a ten-second hole would poison the average for
            // hundreds of frames and hide the rate we actually care about.
            if (deltaMillis in 0.0..250.0) {
                intervalMillisEwma =
                    if (intervalMillisEwma == 0.0) deltaMillis else intervalMillisEwma * 0.9 + deltaMillis * 0.1
            }
        }
        val count = frames.incrementAndGet()
        if (count == 1L) println("[vision-pose] first pose: ${summary()}")
        else logPeriodically(frame)
        listeners.forEach { runCatching { it(frame) } }
    }

    /**
     * Whether this frame is newer than what we hold. Called under [publishLock].
     *
     * A different head is a new sequence outright, and that is the usual restart signal: the device
     * id is minted per `PoseUplink`, so a renderer that comes back brings a new one. The jump rule
     * below is the backstop for the same uplink seeing the compositor's `frameIndex` reset — two
     * seconds at 60 Hz is well past any plausible reordering window and well short of a restart.
     * Without it a reset would wedge the stream in silence until the process died.
     */
    private fun supersedes(frame: VisionPoseFrame): Boolean {
        if (frame.device != lastDevice) return true
        if (frame.frameIndex > lastFrameIndex) return true
        return frame.frameIndex + 120 < lastFrameIndex
    }

    private fun logPeriodically(frame: VisionPoseFrame) {
        val hz = logHz
        if (hz <= 0.0) return
        val intervalNanos = (1.0e9 / hz).toLong()
        if (frame.receivedAtNanos - lastLogNanos < intervalNanos) return
        lastLogNanos = frame.receivedAtNanos
        println("[vision-pose] ${summary()}")
    }

    /** Forget a head whose session has gone, called from the `/ws` teardown. */
    fun detach(session: WebSocketSession) {
        sessions -= session
    }

    /** One line for a log, a box output or a smoke test. */
    fun summary(): String {
        val f = latest ?: return "no pose yet (heads=${heads.size}, rejected=${rejectedCount})"
        val hz = if (intervalMillis > 0) 1000.0 / intervalMillis else 0.0
        return ("frame %d @ %.1f Hz  pos=(%.3f, %.3f, %.3f)  quat=(%.3f, %.3f, %.3f, %.3f)  " +
                "t=%.3f  eyes=%d  tracking=%s").format(
            f.frameIndex, hz,
            f.headPosition.x, f.headPosition.y, f.headPosition.z,
            f.headOrientation.x, f.headOrientation.y, f.headOrientation.z, f.headOrientation.w,
            f.predictedDisplayTime, f.eyes.size, f.trackingValid,
        )
    }

    /** Test seam: drop every accumulated statistic and pose. */
    fun reset() {
        latest = null
        heads.clear()
        sessions.clear()
        listeners.clear()
        frames.set(0)
        regressions.set(0)
        rejected.set(0)
        lastDevice = null
        lastFrameIndex = Long.MIN_VALUE
        intervalMillisEwma = 0.0
        lastLogNanos = 0L
    }
}

/** Read a column-major 16-float wire matrix. */
internal fun matrixFromWire(values: FloatArray): Matrix4f {
    require(values.size == 16) { "a wire matrix is 16 floats, got ${values.size}" }
    val out = Matrix4f()
    for (i in 0 until 16) out[i] = values[i]
    return out
}

/**
 * Recover the frustum tangents from a perspective projection.
 *
 * For a projection built from signed tangents the two rows that matter are
 * `m00 = 2/(r-l)`, `m20 = (r+l)/(r-l)` and `m11 = 2/(u-d)`, `m21 = (u+d)/(u-d)`, so
 * `r = (m20+1)/m00` and `l = (m20-1)/m00`. Independent of the depth convention, which is exactly
 * why this is the part worth extracting: the near/far and z-range in the compositor's matrix are
 * its business, and the tangents are ours.
 */
internal fun tangentsFromProjection(p: Matrix4f): FloatArray {
    val a = p.m00
    val c = p.m11
    require(a != 0f && c != 0f) { "projection has no horizontal or vertical extent" }
    val b = p.m20
    val d = p.m21
    return floatArrayOf((b - 1f) / a, (b + 1f) / a, (d - 1f) / c, (d + 1f) / c)
}

private fun VisionPoseWireFrame.toPoseFrame(): VisionPoseFrame {
    require(views.isNotEmpty()) { "a pose frame needs at least one view" }
    val origin = matrixFromWire(originFromDevice)
    val eyes = views.map { view ->
        val deviceFromEye = matrixFromWire(view.transform)
        val projection = matrixFromWire(view.projection)
        val tangents = tangentsFromProjection(projection)
        VisionEyeView(
            // world→eye, the composition the head's own renderer performs.
            viewMatrix = Matrix4f(origin).mul(deviceFromEye).invert(),
            projection = projection,
            tanLeft = tangents[0], tanRight = tangents[1], tanDown = tangents[2], tanUp = tangents[3],
        )
    }
    val controllerStates = controllers.map { it.toController() }
    return VisionPoseFrame(
        device = device,
        frameIndex = frameIndex,
        predictedDisplayTime = predictedDisplayTime,
        sentAt = sentAt,
        receivedAtNanos = System.nanoTime(),
        trackingValid = trackingValid,
        originFromDevice = origin,
        headPosition = origin.getTranslation(),
        headOrientation = origin.getUnnormalizedRotation(Quaternionf()).normalize(),
        eyes = eyes,
        controllers = controllerStates,
    )
}

private fun VisionPoseWireController.toController(): VisionController {
    val grip = matrixFromWire(originFromGrip)
    val state = OpenXRHmdTarget.ControllerState(
        active = true,
        poseValid = tracked,
        position = grip.getTranslation(),
        rotation = grip.getUnnormalizedRotation(Quaternionf()).normalize(),
        selectPressed = select,
        triggerValue = trigger,
        triggerPressed = triggerPressed,
        squeezeValue = squeeze,
        squeezePressed = squeezePressed,
        trackpadX = trackpadX,
        trackpadY = trackpadY,
        trackpadTouched = trackpadTouched,
    )
    val hand = when (chirality) {
        "left" -> OpenXRHmdTarget.Hand.LEFT
        "right" -> OpenXRHmdTarget.Hand.RIGHT
        else -> null
    }
    return VisionController(hand, state)
}

@RunsAt(installHandlers)
fun installVisionPoseHandler() {
    rawHandlers.getOrPut(VISION_POSE_CHANNEL) { mutableSetOf() }.add { text ->
        // Every raw handler is offered every unrecognised /ws message, so this one runs on all of
        // Field's other channel traffic too. A substring scan first keeps that cost a scan rather
        // than a full parse — this handler is on the 90 Hz path and does not need to make everyone
        // else's messages more expensive.
        if (!text.contains(VISION_POSE_CHANNEL)) return@add Handled.no
        // `thisSession` is a thread local of the /ws reader; absent when a test drives the handler
        // directly, which the hello/bye bookkeeping tolerates.
        val session = runCatching { afield.thisSession.get() }.getOrNull()
        if (VisionPoseStream.accept(text, session)) Handled.yes else Handled.no
    }
}

/**
 * The registered raw handler for this channel, for a test that wants to drive the real dispatch path
 * rather than a lookalike.
 *
 * It lives here, beside [VISION_POSE_CHANNEL], rather than being indexed out of `rawHandlers` at the
 * call site: `dev_docs/server_surface_inventory.md`'s ledger discovers `/ws` channels textually and
 * can only resolve a channel key that is a string literal or a `const val` in the same file. A
 * reference from elsewhere reads to it as a channel registered under an unresolvable name, which is
 * exactly the thing that table exists to refuse.
 */
internal fun visionPoseRawHandler(): ((String) -> Handled)? =
    rawHandlers[VISION_POSE_CHANNEL]?.firstOrNull()

/** The newest pose from any head, or null if none has ever arrived. */
fun visionPoseLatest(): VisionPoseFrame? = VisionPoseStream.latest

/** Heads currently publishing pose. */
fun visionPoseConnectedHeadCount(): Int = VisionPoseStream.connectedHeadCount
