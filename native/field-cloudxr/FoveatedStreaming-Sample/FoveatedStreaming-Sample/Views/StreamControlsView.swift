/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
A view that displays controls for managing an active foveated streaming session.
*/

import SwiftUI
#if targetEnvironment(simulator)
import FoveatedStreamingSimulator
#else
import FoveatedStreaming
#endif

struct StreamControlsView: View {
    @Environment(StreamActions.self) var streamActions
    
    @ScaledMetric var scaledControlsWidth = 480
    
    @State var isSettingsVisible = false
    
    let status: FoveatedStreamingSession.Status
    
    var isSessionStatusUpdating: Bool {
        status == .pausing || status == .resuming || status == .disconnecting
    }

    var isSessionPaused: Bool {
        status == .paused
    }
    
    var isSessionDisconnecting: Bool {
        status == .disconnecting
    }
    
    var isSessionPausingOrResuming: Bool {
        status == .pausing || status == .resuming
    }
    
    var isDisplayingFullControls: Bool {
        isSettingsVisible || isSessionPaused || status == .pausing
    }
    
    var body: some View {
        VStack {
            HStack {
                ConnectionActionButton(
                    isLoading: isSessionDisconnecting,
                    systemImage: "stop.fill",
                    help: "Disconnect",
                    action: streamActions.disconnect
                )
                .disabled(isSessionStatusUpdating)
                
                Button {
                    withAnimation(.easeInOut) {
                        isSettingsVisible.toggle()
                    }
                } label: {
                    Image(systemName: isSettingsVisible ? "arrow.down.right.and.arrow.up.left.circle.fill" : "gearshape.circle.fill")
                        .font(.title.scaled(by: 4))
                        .contentTransition(.symbolEffect(.replace, options: .speed(2.0)))
                }
                .buttonBorderShape(.circle)
                .buttonStyle(.borderless)
                .help(isSettingsVisible ? "Close Settings" : "Open Settings")
                
                ConnectionActionButton(
                    isLoading: isSessionPausingOrResuming,
                    systemImage: isSessionPaused ? "play.fill" : "pause.fill",
                    help: isSessionPaused ? "Resume" : "Pause",
                    action: isSessionPaused ? streamActions.resume : streamActions.pause
                )
                .disabled(isSessionStatusUpdating)
            }
            .padding(.horizontal, 12)
            .glassBackgroundEffect()
            .hoverEffect { effect, isActive, proxy in
                effect.animation(.default.delay(isActive ? 0.0 : 0.2)) {
                    $0.clipShape(.capsule.size(
                        width: (isActive || isDisplayingFullControls) ? proxy.size.width : proxy.size.height,
                        height: proxy.size.height,
                        anchor: .center
                    ))
                }
            }
            
            if isSettingsVisible {
                DeveloperSettingsView()
                    .transition(.scale)
            }
        }
        .frame(width: scaledControlsWidth, height: isSettingsVisible ? 850 : nil)
    }
}

#Preview(windowStyle: .plain, traits: .fixedLayout(width: 1000, height: 850)) {
    @Previewable @State var status: FoveatedStreamingSession.Status = .connected

    VStack {
        StreamControlsView(status: status)
            .environment(
                StreamActions(
                    connect: { _ in },
                    pause: {
                        status = .pausing
                        try await Task.sleep(for: .seconds(1))
                        status = .paused
                    },
                    resume: {
                        status = .resuming
                        try await Task.sleep(for: .seconds(1))
                        status = .connected
                    },
                    disconnect: {
                        status = .disconnecting
                        try? await Task.sleep(for: .seconds(1))
                        status = .disconnected(.appInitiatedDisconnect)
                    }
                )
            )
            .environment(AppModel())
    }
}
