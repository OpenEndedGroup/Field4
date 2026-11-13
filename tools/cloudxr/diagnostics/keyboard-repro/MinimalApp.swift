// Standalone reproduction entrypoint; build in a separate visionOS app target.
// Requires com.apple.developer.foveated-streaming-session and the same pairing setup.
import SwiftUI
import FoveatedStreaming
import Network

@main
struct KeyboardReproApp: App {
    private let session = FoveatedStreamingSession()

    init() {
        session.requestedInputCapabilities = [.handTracking]
        // Do not open any immersive space on connection.
        session.immersivePresentationBehaviors = []
    }

    var body: some Scene {
        WindowGroup {
            KeyboardReproView(session: session)
        }
    }
}

struct KeyboardReproView: View {
    let session: FoveatedStreamingSession
    @State private var text = ""
    @State private var error: String?

    var body: some View {
        VStack(spacing: 20) {
            Text("Type before and after connecting")
            TextEditor(text: $text).frame(height: 240).border(.secondary)
            Text("Characters: \(text.count)")
            Text(String(describing: session.status))
            Button("Connect without presenting stream") {
                Task {
                    do {
                        try await session.connect(endpoint: .local(
                            ipAddress: IPv4Address("10.0.0.37")!, port: 55000))
                    } catch { self.error = error.localizedDescription }
                }
            }
            Button("Disconnect") { Task { await session.disconnect() } }
            if let error { Text(error) }
        }
        .padding(24)
        .frame(width: 700, height: 500)
    }
}
