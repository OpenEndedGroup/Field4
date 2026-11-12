/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
An immersive view that displays a button to reopen the main window
  if the person has closed it.
*/

import SwiftUI
import RealityKit

struct ReopenMainWindowView: View {
    @Environment(AppModel.self) private var appModel
    @Environment(\.openWindow) private var openWindow

    let reopenMainWindowEntity = Entity()
    
    var body: some View {
        RealityView { content in
            reopenMainWindowEntity.components.set(ViewAttachmentComponent(rootView:
                Button("Open Controls", systemImage: "arrow.up.left.and.arrow.down.right") {
                    openWindow(id: appModel.mainWindowId)
                }
                .breakthroughEffect(.subtle)
            ))
            reopenMainWindowEntity.components.set(OpacityComponent(opacity: 0))
            reopenMainWindowEntity.position = [0, 1, -1]
            content.add(reopenMainWindowEntity)
        }
        .onChange(of: appModel.mainWindowState.isVisible, initial: true) {
            // Show the reopen button when the main window isn't visible and hide it otherwise.
            Entity.animate(appModel.mainWindowState.isVisible ? .easeOut(duration: 0.25) : .easeIn(duration: 0.25)) {
                reopenMainWindowEntity.components[OpacityComponent.self]?.opacity = appModel.mainWindowState.isVisible ? 0 : 1
            }
        }
    }
}

#Preview(immersionStyle: .mixed) {
    ReopenMainWindowView()
        .environment(AppModel())
}
