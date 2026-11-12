/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
A view modifier for tracking the state of a window.
*/

import SwiftUI

extension View {
    func trackWindowState(_ windowState: Binding<AppModel.WindowState>) -> some View {
        modifier(TrackWindowStateViewModifier(windowState: windowState))
    }
}

struct TrackWindowStateViewModifier: ViewModifier {
    @Environment(\.scenePhase) var scenePhase
    @Binding var windowState: AppModel.WindowState

    func body(content: Content) -> some View {
        content
            .onAppear() {
                windowState = .open(scenePhase: scenePhase)
            }
            .onDisappear() {
                windowState = .closed
            }
            .onChange(of: scenePhase) {
                windowState = .open(scenePhase: scenePhase)
            }
    }
}
