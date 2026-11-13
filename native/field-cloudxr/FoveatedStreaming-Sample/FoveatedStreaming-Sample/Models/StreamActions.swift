/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
A model that contains stream actions such as connect, resume, pause, and disconnect.
*/

import SwiftUI
#if targetEnvironment(simulator)
import FoveatedStreamingSimulator
#else
import FoveatedStreaming
#endif

@MainActor
@Observable
class StreamActions {
    let connect: (FoveatedStreamingSession.Endpoint) async throws -> Void
    let pause: () async throws -> Void
    let resume: () async throws -> Void
    let disconnect: () async throws -> Void
    
    init(connect: @escaping (FoveatedStreamingSession.Endpoint) async throws -> Void,
         pause: @escaping () async throws -> Void,
         resume: @escaping () async throws -> Void,
         disconnect: @escaping () async -> Void) {
        self.connect = connect
        self.pause = pause
        self.resume = resume
        self.disconnect = disconnect
    }
}
