import SwiftUI
import RealityKit

/// The RTT stream *screen* — a textured quad sampling the live decoded frame — factored out of
/// `ImmersiveStreamView` so it can be presented in two homes that differ only in chrome:
///
/// - the dimmed, world-locked `ImmersiveSpace` theatre (`ImmersiveStreamView`), and
/// - a movable/resizable **volumetric window** (`RTTStreamWindow`), which the OS gives a grabber bar
///   and resize handles for free.
///
/// Mono vs stereo (SBS) handling is identical in both — see the material notes below. The only
/// difference the caller controls is `screenHeight` (metres) and `centerPosition` (where the quad
/// sits): the theatre parks it a couple of metres out at eye height; the window centres it at the
/// volume origin.
struct RTTScreenContent: View {
    @Environment(RTTStream.self) private var stream

    /// Screen height in metres; width tracks the frame's aspect ratio.
    var screenHeight: Float = 1.0
    /// Where the quad's centre sits in the enclosing space (volume origin, or out in front in the theatre).
    var centerPosition: SIMD3<Float> = [0, 1.4, -2.0]

    /// Per-frame presentation state. Deliberately a plain reference type held in `@State`, NOT a set
    /// of `@State` values and NOT `@Observable`: the identity is stable so SwiftUI keeps it across
    /// updates, but mutating its fields is invisible to SwiftUI. That matters because this state is
    /// touched on every decoded frame, and writing real `@State` at that point produced a flood of
    /// "Modifying state during view update, this will cause undefined behavior" — ~30 per second.
    @State private var scene = ScreenScene()

    @MainActor
    final class ScreenScene {
        let screen = ModelEntity()
        var boundTextureID: ObjectIdentifier?
        var boundStereo: Bool?
        var boundAspect: Float?
        /// Camera Index Switch material, loaded once if the RCP asset is present (nil → mono fallback).
        var sbsMaterial: ShaderGraphMaterial?
        var tick: UInt64 = 0
        var subscription: EventSubscription?
    }

    var body: some View {
        // NOTE: body deliberately does NOT read `stream.decodedFrameCount`.
        //
        // It used to, so that a new frame invalidated the view and re-ran `update:`. That put
        // SwiftUI in the per-frame path: a full view update 30 times a second, each one writing
        // @State from inside the update closure (the warning above) and regenerating the plane mesh
        // from scratch. The decode counters were clean the whole time — the hitch was presentation
        // churn, not lost frames.
        //
        // Instead we subscribe once to RealityKit's own per-frame event. It fires at the display's
        // rate, entirely outside SwiftUI, which is where a 90Hz redraw belongs.
        RealityView { content in
            scene.screen.model = ModelComponent(
                mesh: .generatePlane(width: 16.0 / 9.0 * screenHeight, height: screenHeight),
                materials: [UnlitMaterial(color: .black)])
            scene.screen.position = centerPosition
            content.add(scene.screen)

            // Call straight through — do NOT `Task { @MainActor in … }` here. RealityKit already
            // delivers this on the main thread, and hopping through a Task per frame does not
            // reliably yield one execution per display refresh. Since `presentDue` releases at most
            // one frame per call, a hop rate below the frame rate becomes a hard ceiling on
            // presented fps (observed: pinned at ~25fps, buffer filling until it hit the cap).
            scene.subscription = content.subscribe(to: SceneEvents.Update.self) { _ in
                MainActor.assumeIsolated { refreshScreen() }
            }
        }
        .task {
            // Load the Camera Index Switch material if the asset shipped with the app; nil otherwise.
            // NB: loading/parsing works in the simulator; only per-eye *separation* needs a device.
            do {
                scene.sbsMaterial = try await ShaderGraphMaterial(named: "/Root/SBSMaterial",
                                                                  from: "SBSMaterial",
                                                                  in: .main)
                shellLog.notice("RTT stereo material loaded (Camera Index Switch) — sbsMaterial present")
            } catch {
                scene.sbsMaterial = nil
                shellLog.error("RTT stereo material load failed: \(error, privacy: .public) — mono fallback")
            }
            // Force a rebind so a material that finished loading after the first frames is picked up.
            scene.boundTextureID = nil
        }
    }

    /// Runs once per RealityKit frame, off the SwiftUI update path. Everything here is idempotent
    /// and change-gated: the texture updates in place, so the steady state is a single transform
    /// nudge and nothing else.
    @MainActor
    private func refreshScreen() {
        // Release whichever buffered frame is due. This runs once per RealityKit frame, so the
        // presentation clock is the display clock — the point of the jitter buffer is that a frame
        // appears on the refresh its own timestamp asks for, not on the refresh it happened to
        // arrive during.
        // Pass the display's own cadence so the release clock can snap to whole refreshes.
        stream.presentDue(now: CACurrentMediaTime())

        guard let texture = stream.displayTexture else { return }
        let stereo = stream.isStereo

        // Imperceptible per-frame transform change → forces RealityKit to redraw + re-sample the
        // in-place-updated texture. (~0.1 mm of z jitter.) A static scene won't redraw on its own.
        scene.tick &+= 1
        scene.screen.position.z = centerPosition.z + (scene.tick % 2 == 0 ? 0.0 : 0.0001)

        // Rebuild the material only when the texture identity or the mono/stereo mode changes — the
        // texture updates in place otherwise, so RealityKit keeps sampling the latest contents.
        let id = ObjectIdentifier(texture)
        if id != scene.boundTextureID || stereo != scene.boundStereo {
            scene.screen.model?.materials = [buildMaterial(texture: texture, stereo: stereo)]
            scene.boundTextureID = id
            scene.boundStereo = stereo
        }

        // Aspect: mono → the whole frame; stereo → one eye (half the decoded, double-wide width).
        // Gated on an actual change: this was regenerating a plane mesh every single frame.
        let size = stream.frameSize
        guard size.width > 0, size.height > 0 else { return }
        let frameAspect = Float(size.width / size.height)
        let eyeAspect = stereo ? frameAspect / 2 : frameAspect
        if scene.boundAspect != eyeAspect {
            scene.screen.model?.mesh = .generatePlane(width: eyeAspect * screenHeight,
                                                      height: screenHeight)
            scene.boundAspect = eyeAspect
            // Say what the geometry actually is. `width` on the sender is the ENCODED width
            // covering BOTH eyes, so a 1920-wide SBS frame is 960 per eye — a near-square panel,
            // which is easy to mistake for a receiver bug. Print the per-eye pixels so the sender's
            // misconfiguration is legible instead of inferred from the shape on the wall.
            let eyeW = stereo ? size.width / 2 : size.width
            shellLog.notice("""
                RTT screen \(Int(eyeW))×\(Int(size.height)) per eye, aspect \
                \(String(format: "%.2f", eyeAspect))\(stereo ? " (SBS)" : " (mono)") — \
                encoded frame \(Int(size.width))×\(Int(size.height))
                """)
        }
    }

    /// Stereo → the Camera Index Switch material (texture bound to its `sbsTexture` parameter); mono,
    /// or if the stereo asset isn't present / the parameter can't be set → a plain unlit material.
    private func buildMaterial(texture: TextureResource, stereo: Bool) -> RealityKit.Material {
        if stereo, var sbs = scene.sbsMaterial {
            do {
                try sbs.setParameter(name: "sbsTexture", value: .textureResource(texture))
                return sbs
            } catch {
                shellLog.error("RTT stereo: setParameter(sbsTexture) failed (\(error)); mono fallback")
            }
        }
        var material = UnlitMaterial()
        material.color = .init(tint: .white, texture: .init(texture))
        return material
    }
}
