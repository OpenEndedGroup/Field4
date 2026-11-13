/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
The SwiftUI `App` structure, which acts as the entry point of the app. Defines the windows and spaces the app uses as well as global state.
*/

import SwiftUI
import RealityKit

#if targetEnvironment(simulator)
@main
struct FoveatedStreamingSampleApp: App {
    var body: some SwiftUI.Scene {
        WindowGroup() {
            Text("FoveatedStreamingSampleApp is unsupported on the simulator. Please use a device to run this sample.")
        }
    }
}
#else

import FoveatedStreaming

@main
struct FoveatedStreamingSampleApp: App {
    private let session = FoveatedStreamingSession()
    private let appModel = AppModel()
    private let messageChannelModel: MessageChannelModel
    
    init() {
        // visionOS 27 forwards these inputs only after the user authorizes them.
        if #available(visionOS 27.0, *) {
            session.requestedInputCapabilities = [.handTracking, .accessoryTracking]
        }
        messageChannelModel = MessageChannelModel(session: session)
    }

    var body: some SwiftUI.Scene {
        Window("Main", id: appModel.mainWindowId) {
            ContentView(session: session)
                .environment(appModel)
                .environment(messageChannelModel)
                .environment(session)
                .environment(
                    StreamActions(
                        connect: { endpoint in
                            try await session.connect(endpoint: endpoint)
                        },
                        pause: {
                            try await session.pause()
                        },
                        resume: {
                            try await session.resume()
                        },
                        disconnect: {
                            await session.disconnect()
                        }
                    )
                )
        }
        .defaultLaunchBehavior(.presented)
        .windowStyle(.plain)
        .windowResizability(.contentSize)

        Window("Field IDE", id: "field-browser") {
            FieldBrowserView()
        }
        .defaultLaunchBehavior(.suppressed)
        .defaultSize(width: 1440, height: 1000)
        .windowResizability(.automatic)

        ImmersiveSpace(foveatedStreaming: session) {
            SpatialContainer {
                ReopenMainWindowView()
                    .environment(appModel)
                TransformStreamWidgetView()
                    .environment(session)
            }
        }
        .immersionStyle(selection: .constant(.progressive), in: .progressive)
        .upperLimbVisibility(.hidden)
    }
}
#endif
