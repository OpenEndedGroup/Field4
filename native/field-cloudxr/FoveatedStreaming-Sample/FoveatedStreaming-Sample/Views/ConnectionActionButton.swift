/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
A view displaying a button that starts a streaming connection action.
*/

import SwiftUI

struct ConnectionActionButton: View {
    @ScaledMetric var scaledButtonWidth = 40
    
    var isLoading: Bool
    var systemImage: String
    var help: String
    var action: () async throws -> Void
    
    var body: some View {
        Button(action: {
            Task { @MainActor in
                try await action()
            }
        }) {
            Image(systemName: systemImage)
                .font(.title.scaled(by: 2))
                .padding()
                .opacity(isLoading ? 0 : 1)
                .overlay(alignment: .center) {
                    if isLoading {
                        ProgressView()
                            .tint(.white)
                    }
                }
                .frame(width: scaledButtonWidth)
        }
        .disabled(isLoading)
        .help(help)
        .animation(.easeInOut, value: isLoading)
    }
}

#Preview {
    @Previewable @State var isLoading = false

    ConnectionActionButton(
        isLoading: isLoading,
        systemImage: "stop.fill",
        help: "Disconnect"
    ) {
        isLoading = true
        try? await Task.sleep(for: .seconds(1))
        isLoading = false
    }
}
