import SwiftUI
import RealityKit

/// The present layer: a world-locked screen in a dimmed `ImmersiveSpace` showing the decoded RTT
/// stream. `WIRE_FORMAT.md` step 4 — copy the decoded frame into a `LowLevelTexture` (done in
/// `PixelBufferTextureConverter`) sampled by a material here.
///
/// This is the flat / mono screen: the whole frame on one quad. The **stereo** dock is the next
/// step — swap `UnlitMaterial` for a `ShaderGraphMaterial` whose Camera Index Switch node samples
/// the SBS left/right halves per eye (authored in Reality Composer Pro; the texture from the
/// converter is already the full SBS frame, so only the material changes). Marked TODO below.
struct ImmersiveStreamView: View {
    @Environment(RTTStream.self) private var stream

    @State private var screen = ModelEntity()
    @State private var boundTextureID: ObjectIdentifier?

    /// Screen height in metres; width tracks the frame's aspect ratio.
    private let screenHeight: Float = 1.0

    var body: some View {
        RealityView { content in
            // Start with a neutral dark quad ~2 m in front at seated eye height.
            screen.model = ModelComponent(
                mesh: .generatePlane(width: 16.0 / 9.0 * screenHeight, height: screenHeight),
                materials: [UnlitMaterial(color: .black)])
            screen.position = [0, 1.4, -2.0]
            content.add(screen)
        } update: { _ in
            guard let texture = stream.displayTexture else { return }

            // Rebuild the material only when the texture resource identity changes (it updates in
            // place otherwise — RealityKit keeps sampling the latest contents).
            let id = ObjectIdentifier(texture)
            if id != boundTextureID {
                var material = UnlitMaterial()
                material.color = .init(tint: .white, texture: .init(texture))
                screen.model?.materials = [material]
                boundTextureID = id
            }

            // Match the quad's aspect to the decoded frame (SBS frames are ~double-wide).
            let size = stream.frameSize
            if size.width > 0, size.height > 0 {
                let aspect = Float(size.width / size.height)
                screen.model?.mesh = .generatePlane(width: aspect * screenHeight, height: screenHeight)
            }
        }
        // Dim passthrough to a theatre-like surround — the "dimmed ImmersiveSpace" from the spec.
        .preferredSurroundingsEffect(.systemDark)
    }
}
