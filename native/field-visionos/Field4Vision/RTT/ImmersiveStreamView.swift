import SwiftUI
import RealityKit

/// The **immersive theatre** presentation of the RTT stream: the shared `RTTScreenContent` quad,
/// parked a couple of metres out at eye height in a dimmed (`.systemDark`) `ImmersiveSpace` so the
/// passthrough recedes into a cinema surround.
///
/// The actual screen (mesh, live texture, mono/stereo material, per-frame redraw nudge) lives in
/// `RTTScreenContent`, shared with the windowed `RTTStreamWindow`. This view only adds the theatre's
/// world-locked placement and dimming; everything visual is the same in both homes.
///
/// ### Stereo (recap — the detail lives on `RTTScreenContent.buildMaterial`)
/// Mono → one quad, whole frame. Stereo (SBS) → a `ShaderGraphMaterial` whose Camera Index Switch
/// node samples the left half for eye 0 and the right half for eye 1, with the quad sized to a single
/// eye's aspect. Until the RCP `SBSMaterial.usda` asset ships, it falls back to mono automatically.
struct ImmersiveStreamView: View {
    var body: some View {
        // World-locked at eye height, ~2 m out; dimmed surroundings turn the passthrough into a theatre.
        RTTScreenContent(screenHeight: 1.0, centerPosition: [0, 1.4, -2.0])
            .preferredSurroundingsEffect(.systemDark)
    }
}
