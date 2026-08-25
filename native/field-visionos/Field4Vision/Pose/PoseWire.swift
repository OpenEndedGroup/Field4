import CompositorServices
import Foundation
import simd

/// The `__visionpose__` channel: head and per-eye viewing geometry sent **up** to the Mac, so the
/// Vulkan renderer there can draw the frame this headset is about to display.
///
/// Every other visionOS channel in Field runs the other way — `__visionmetal__` pushes a program down
/// and this device renders it; the RTT stream pushes encoded pixels down
/// (`../field-encode/WIRE_FORMAT.md`). Without a pose going up, a streamed image can only be a
/// world-locked surface. This is the channel that makes it a view.
///
/// The authoritative spec is the Kotlin end, `afield/graphics/VisionPose.kt` — read that first. This
/// file is its hand-written mirror, and `visionpose.uplink` (a jvm-main smoke over there) pins the
/// field names on that side so a rename here shows up as a test failure rather than as silence.
enum PoseWire {
    static let channel = "__visionpose__"

    /// Bump together with `VISION_POSE_VERSION` in the Kotlin. A mismatched version is *dropped* by
    /// the receiver, not half-read, so an old Field build reads as no tracking rather than as a
    /// headset that has lost its mind.
    static let version = 1

    /// Column-major, `m[column * 4 + row]` — `simd_float4x4`'s own storage order and the Kotlin
    /// `Matrix4f`'s, so the array crosses the wire untransposed. Do not "fix" this into row-major:
    /// a transposed rigid transform still looks like a plausible pose, which is the worst kind of bug
    /// to have in a tracking path.
    static func floats(_ m: simd_float4x4) -> [Float] {
        [m.columns.0, m.columns.1, m.columns.2, m.columns.3].flatMap { [$0.x, $0.y, $0.z, $0.w] }
    }
}

/// Sent once per connection, so a listener can size itself before the first pose arrives.
struct PoseHello: Encodable {
    let channel = PoseWire.channel
    let type = "visionpose/hello"
    let version = PoseWire.version
    let device: String
    let name: String
    let viewCount: Int
    let refreshHz: Double
}

/// One eye, exactly as this drawable stated it — no reinterpretation on the way out.
struct PoseWireView: Encodable {
    /// device→eye: `LayerRenderer.Drawable.View.transform`.
    let transform: [Float]
    /// `drawable.computeProjection(viewIndex:)`. Its near/far and depth range are the compositor's
    /// convention; the Mac takes the frustum tangents out of it and builds its own.
    let projection: [Float]
}

/// One tracked controller. Field maps this straight onto the `ControllerState` its OpenXR path
/// already produces, so interaction code cannot tell which backend it is running on.
struct PoseWireController: Encodable {
    /// "left" | "right" | "unspecified" — *held* chirality. An accessory whose inherent handedness is
    /// known but whose holder is not reports unspecified, and the host must not guess: driving the
    /// wrong hand is worse than driving neither.
    let chirality: String
    /// Position *and* orientation are tracked. Orientation-only reports false, because a ray cast
    /// from a position we do not have looks plausible and is wrong.
    let tracked: Bool
    /// world→grip, column-major.
    let originFromGrip: [Float]
    var select: Bool = false
    var trigger: Float = 0
    var triggerPressed: Bool = false
    var squeeze: Float = 0
    var squeezePressed: Bool = false
    var trackpadX: Float = 0
    var trackpadY: Float = 0
    var trackpadTouched: Bool = false
}

/// One compositor frame's worth of tracking.
struct PoseWireFrame: Encodable {
    let channel = PoseWire.channel
    let type = "visionpose/frame"
    let version = PoseWire.version
    let device: String
    let frameIndex: UInt64

    /// This head's clock (`LayerRenderer.Clock`, seconds): when *this* head displays its next frame.
    ///
    /// Not when a frame rendered on the Mac could arrive — that is later by the whole
    /// render→encode→network→decode chain. Carried faithfully rather than adjusted, so the forward
    /// shift stays one measured constant in `PoseUplink` once there is a measurement to shift by.
    let predictedDisplayTime: Double

    /// Same clock, at hand-off to the socket. `sentAt - predictedDisplayTime` is this head's own
    /// contribution to the loop and needs no clock agreement with the Mac to be meaningful.
    let sentAt: Double

    /// False when ARKit had no device anchor for `predictedDisplayTime`; the matrices are then
    /// identity and rendering from them would snap the world to the origin.
    let trackingValid: Bool

    /// world→head: `DeviceAnchor.originFromAnchorTransform`.
    let originFromDevice: [Float]

    let views: [PoseWireView]

    /// Controllers as of this pose, predicted to the same `predictedDisplayTime`. Additive and
    /// optional on the wire: an omitted or empty list simply means no tracked controllers, which is
    /// why this needed no version bump — on a JSON channel an optional field is compatible both ways.
    let controllers: [PoseWireController]
}

/// The launch-time media-wire parser verdict, reported over this channel because it is the only one
/// that reaches the Mac from a simulator or a device. `detail` is empty when `ok`.
struct PoseSelfCheck: Encodable {
    let channel = PoseWire.channel
    let type = "visionpose/selfcheck"
    let version = PoseWire.version
    let device: String
    let name: String
    let ok: Bool
    let detail: String
}

/// Sent on a clean immersive-space exit, so the Mac stops rendering for a head that has gone.
struct PoseBye: Encodable {
    let channel = PoseWire.channel
    let type = "visionpose/bye"
    let version = PoseWire.version
    let device: String
}
