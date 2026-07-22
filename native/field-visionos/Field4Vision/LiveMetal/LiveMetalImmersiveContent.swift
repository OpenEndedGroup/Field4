import CompositorServices
import Metal
import SwiftUI

struct LiveMetalImmersiveContent: CompositorContent {
    let client: LiveMetalClient

    var body: some CompositorContent {
        CompositorLayer(configuration: LiveMetalLayerConfiguration()) { @MainActor layerRenderer in
            LiveMetalRenderer.start(layerRenderer, mailbox: client.mailbox) { uid, revision, phase, ok, message in
                Task { @MainActor in
                    client.report(uid: uid, revision: revision, phase: phase, ok: ok, message: message)
                }
            }
        }
    }
}

private struct LiveMetalLayerConfiguration: CompositorLayerConfiguration {
    func makeConfiguration(capabilities: LayerRenderer.Capabilities,
                           configuration: inout LayerRenderer.Configuration) {
        let foveated = capabilities.supportsFoveation
        configuration.isFoveationEnabled = foveated
        let options: LayerRenderer.Capabilities.SupportedLayoutsOptions = foveated ? [.foveationEnabled] : []
        let layouts = capabilities.supportedLayouts(options: options)
        configuration.layout = layouts.contains(.layered) ? .layered : .dedicated
    }
}
