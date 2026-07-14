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

    @State private var screen = ModelEntity()
    @State private var boundTextureID: ObjectIdentifier?
    @State private var boundStereo: Bool?
    /// Camera Index Switch material, loaded once if the RCP asset is present (nil → mono fallback).
    @State private var sbsMaterial: ShaderGraphMaterial?

    var body: some View {
        // Observe the decoded-frame counter so SwiftUI re-runs `update` on every new frame. The
        // converter updates the LowLevelTexture in place, but RealityKit only re-samples it when the
        // scene actually redraws — and a static scene doesn't redraw on its own (starkly so in the
        // simulator, which idle-throttles). Reading the counter here + nudging the entity below makes
        // each decoded frame drive a redraw.
        let tick = stream.decodedFrameCount

        return RealityView { content in
            screen.model = ModelComponent(
                mesh: .generatePlane(width: 16.0 / 9.0 * screenHeight, height: screenHeight),
                materials: [UnlitMaterial(color: .black)])
            screen.position = centerPosition
            content.add(screen)
        } update: { _ in
            guard let texture = stream.displayTexture else { return }
            let stereo = stream.isStereo

            // Imperceptible per-frame transform change → forces RealityKit to redraw + re-sample the
            // in-place-updated texture. (~0.1 mm of z jitter, keyed to the live frame counter.)
            screen.position.z = centerPosition.z + (tick % 2 == 0 ? 0.0 : 0.0001)

            // Rebuild the material when the texture identity or the mono/stereo mode changes (the
            // texture updates in place otherwise — RealityKit keeps sampling the latest contents).
            let id = ObjectIdentifier(texture)
            if id != boundTextureID || stereo != boundStereo {
                screen.model?.materials = [buildMaterial(texture: texture, stereo: stereo)]
                boundTextureID = id
                boundStereo = stereo
            }

            // Aspect: mono → the whole frame; stereo → one eye (half the decoded, double-wide width).
            let size = stream.frameSize
            if size.width > 0, size.height > 0 {
                let frameAspect = Float(size.width / size.height)
                let eyeAspect = stereo ? frameAspect / 2 : frameAspect
                screen.model?.mesh = .generatePlane(width: eyeAspect * screenHeight, height: screenHeight)
            }
        }
        .task {
            // Load the Camera Index Switch material if the asset shipped with the app; nil otherwise.
            // NB: loading/parsing works in the simulator; only per-eye *separation* needs a device.
            do {
                sbsMaterial = try await ShaderGraphMaterial(named: "/Root/SBSMaterial",
                                                            from: "SBSMaterial",
                                                            in: .main)
                shellLog.notice("RTT stereo material loaded (Camera Index Switch) — sbsMaterial present")
            } catch {
                sbsMaterial = nil
                shellLog.error("RTT stereo material load failed: \(error, privacy: .public) — mono fallback")
            }
        }
    }

    /// Stereo → the Camera Index Switch material (texture bound to its `sbsTexture` parameter); mono,
    /// or if the stereo asset isn't present / the parameter can't be set → a plain unlit material.
    private func buildMaterial(texture: TextureResource, stereo: Bool) -> RealityKit.Material {
        if stereo, var sbs = sbsMaterial {
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
