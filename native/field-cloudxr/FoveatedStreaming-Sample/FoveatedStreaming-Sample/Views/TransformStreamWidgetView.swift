/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
An immersive view that displays a widget that the person can drag to reposition
  the streamed content.
*/

import SwiftUI
import RealityKit
#if !targetEnvironment(simulator)
import FoveatedStreaming
#endif

struct TransformStreamWidgetView: View {
#if !targetEnvironment(simulator)
    @Environment(FoveatedStreamingSession.self) private var session
#endif
    
    let originPosition: SIMD3<Float> = [0, 0.75, -1]
    @State var dragStartPosition: SIMD3<Float> = .zero
    @State var isDragging: Bool = false
    
    var body: some View {
        RealityView { content in
            // Create an entity with a sphere model that can receive system input.
            let transformWidget = Entity(
                components: [ModelComponent(
                                 mesh: .generateSphere(radius: 0.025),
                                 materials: [UnlitMaterial(color: .white)]
                             ),
                             InputTargetComponent(),
                             CollisionComponent(shapes: [.generateSphere(radius: 0.025)], isStatic: false),
                             HoverEffectComponent()]
            )
            // Apply a drag gesture to the entity that moves it around the scene.
            transformWidget.components.set(GestureComponent(
                DragGesture(minimumDistance: 0).targetedToEntity(transformWidget)
                    .onChanged { value in
                        // Initialize the drag.
                        if !isDragging {
                            dragStartPosition = transformWidget.position
                            isDragging = true
                        }
                        // Update the entity position with the drag translation.
                        transformWidget.position = dragStartPosition + SIMD3<Float>(value.translation3D)
#if !targetEnvironment(simulator)
                        // Update the remote scene relative to the immersive space by the entity's total translation.
                        let translation = originPosition - transformWidget.position
                        session.immersiveSpaceFromRemoteSceneTransform = Transform(translation: translation).matrix
#endif
                    }.onEnded { value in
                        // End the drag.
                        isDragging = false
                    })
            )
            transformWidget.position = originPosition
            
            // Add an attachment view with instructions to the entity.
            let transformAttachment = Entity(
                components: [ViewAttachmentComponent(rootView: Text("Drag to Translate").padding().glassBackgroundEffect()),
                             BillboardComponent()]
            )
            transformAttachment.position = [0, 0.05, 0]
            transformWidget.addChild(transformAttachment)
            
            content.add(transformWidget)
        }
    }
}

#Preview(immersionStyle: .mixed) {
    TransformStreamWidgetView()
}
