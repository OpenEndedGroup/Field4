/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
A view modifier for setting the immersive presentation behavior of a foveated streaming session.
*/

import SwiftUI
#if !targetEnvironment(simulator)
import FoveatedStreaming
#endif

extension View {
    func setImmersivePresentationBehaviors() -> some View {
#if !targetEnvironment(simulator)
        modifier(SetImmersivePresentationBehaviorsViewModifier())
#else
        self
#endif
    }
}

#if !targetEnvironment(simulator)
struct SetImmersivePresentationBehaviorsViewModifier: ViewModifier {
    @Environment(\.openImmersiveSpace) var openImmersiveSpace
    @Environment(\.dismissImmersiveSpace) var dismissImmersiveSpace
    @Environment(FoveatedStreamingSession.self) var session

    func body(content: Content) -> some View {
        content
            .task {
                setImmersivePresentationBehavior()
            }
    }

    private func setImmersivePresentationBehavior() {
        // Automatically present the foveated streaming space when the session connects or resumes,
        // and hide it when the session pauses or disconnects.
        session.immersivePresentationBehaviors = .automatic(openImmersiveSpace, dismissImmersiveSpace)
    }
}
#endif
