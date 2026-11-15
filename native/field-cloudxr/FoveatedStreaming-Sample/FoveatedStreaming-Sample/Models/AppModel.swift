/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
An observable model that maintains the app's state.
*/

import SwiftUI

@MainActor
@Observable
class AppModel {
    enum WindowState {
        case open(scenePhase: ScenePhase)
        case closed
        
        var isVisible: Bool {
            switch self {
                case .open(let scenePhase):
                    scenePhase != .background
                case .closed:
                    false
            }
        }
    }
    
    var mainWindowState = WindowState.closed
    let mainWindowId = "main"
}
