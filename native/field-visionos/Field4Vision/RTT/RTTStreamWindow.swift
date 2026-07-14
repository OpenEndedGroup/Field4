import SwiftUI

/// The **windowed** presentation of the RTT stream: the shared `RTTScreenContent` quad centred in a
/// volumetric `WindowGroup`. Unlike the immersive theatre, a volumetric window comes with system
/// chrome — a grabber bar to move it and resize handles — so the stream is no longer pinned in space.
///
/// This is deliberately thin: the screen (mesh/texture/material/redraw) is all `RTTScreenContent`;
/// the volume style, default size, and resizability are declared on the scene in `Field4VisionApp`.
/// A small status ornament floats under the screen so fps/size stays visible while dragging.
struct RTTStreamWindow: View {
    @Environment(RTTStream.self) private var stream

    var body: some View {
        // Centre the quad at the volume origin. Height 0.5 m → ~0.89 m wide at 16:9, which sits
        // comfortably inside the default 1.0 × 0.6 × 0.2 m volume (content is clipped to the box).
        RTTScreenContent(screenHeight: 0.5, centerPosition: [0, 0, 0])
            .ornament(attachmentAnchor: .scene(.bottom)) {
                Text(stream.isRunning ? stream.status : "Stream idle")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .glassBackgroundEffect()
            }
            // Opening the window is enough to want the receiver running; closing it releases the
            // stream (which only actually stops if the immersive theatre isn't also open).
            .onAppear { stream.acquire() }
            .onDisappear { stream.release() }
    }
}
