import SwiftUI
import RealityKit

/// The present layer: a world-locked screen in a dimmed `ImmersiveSpace` showing the decoded RTT
/// stream. `WIRE_FORMAT.md` step 4 — copy the decoded frame into a `LowLevelTexture` (done in
/// `PixelBufferTextureConverter`) sampled by a material here.
///
/// Mono vs stereo is driven by `stream.isStereo` (the wire STEREO_SBS flag, set by the sender):
///
/// - **mono** → one quad, whole frame, `UnlitMaterial` sampling the texture.
/// - **stereo (SBS)** → the decoded frame is a double-wide left|right pair. We present it per-eye
///   with a `ShaderGraphMaterial` whose **Camera Index Switch** node samples the left half for eye 0
///   and the right half for eye 1, and size the quad to a single eye's aspect (half the frame width).
///
/// ### The stereo material asset (Reality Composer Pro)
/// RealityKit has no per-eye entity visibility and no Metal surface shaders on visionOS, so per-eye
/// sampling must come from a `ShaderGraphMaterial`. Author `SBSMaterial.usda` in RCP (reference:
/// `~/temp/ShaderGraphByExamples` Ex06) as:
///   - `Image` (`ND_image_color3`) ×2 sampling a **promoted texture parameter** `sbsTexture`, one at
///     texcoord `(u*0.5, v)` (left half), one at `(u*0.5 + 0.5, v)` (right half);
///   - `CameraIndexSwitch` (`ND_realitykit_geometry_switch_cameraindex_color3`): `left` ← left Image,
///     `right` ← right Image, `mono` ← left Image; `out` → `UnlitSurface` (`ND_realitykit_unlit_surfaceshader`) `color`.
/// Add it to the app target (a RealityKitContent package or as a bundle resource). Until it's present
/// this view falls back to mono automatically, so nothing breaks. Verify stereo separation on device.
struct ImmersiveStreamView: View {
    @Environment(RTTStream.self) private var stream

    @State private var screen = ModelEntity()
    @State private var boundTextureID: ObjectIdentifier?
    @State private var boundStereo: Bool?
    /// Camera Index Switch material, loaded once if the RCP asset is present (nil → mono fallback).
    @State private var sbsMaterial: ShaderGraphMaterial?

    /// Screen height in metres; width tracks the frame's aspect ratio.
    private let screenHeight: Float = 1.0

    var body: some View {
        // Observe the decoded-frame counter so SwiftUI re-runs `update` on every new frame. The
        // converter updates the LowLevelTexture in place, but RealityKit only re-samples it when the
        // scene actually redraws — and a static immersive scene doesn't redraw on its own (starkly so
        // in the simulator, which idle-throttles). Without this the quad shows a stale frame until
        // some interaction (dragging a window) forces a redraw. Reading the counter here + nudging
        // the entity below makes each decoded frame drive a redraw.
        let tick = stream.decodedFrameCount

        return RealityView { content in
            screen.model = ModelComponent(
                mesh: .generatePlane(width: 16.0 / 9.0 * screenHeight, height: screenHeight),
                materials: [UnlitMaterial(color: .black)])
            screen.position = [0, 1.4, -2.0]
            content.add(screen)
        } update: { _ in
            guard let texture = stream.displayTexture else { return }
            let stereo = stream.isStereo

            // Imperceptible per-frame transform change → forces RealityKit to redraw + re-sample the
            // in-place-updated texture. (~0.1 mm of z jitter, keyed to the live frame counter.)
            screen.position.z = -2.0 + (tick % 2 == 0 ? 0.0 : 0.0001)

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
        .preferredSurroundingsEffect(.systemDark)   // dim passthrough → theatre surround
        .task {
            // Load the Camera Index Switch material if the asset shipped with the app; nil otherwise.
            sbsMaterial = try? await ShaderGraphMaterial(named: "/Root/SBSMaterial",
                                                         from: "SBSMaterial",
                                                         in: .main)
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
