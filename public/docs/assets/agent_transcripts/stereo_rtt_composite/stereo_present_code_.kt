// The parent graph supplies both the renderer and the composite RTT.
import afield.document.context.fromContextWithNameOrNull
import afield.graphics.vulkan.targets.RenderToTexture

val presentedTexture: RenderToTexture =
    fromContextWithNameOrNull<RenderToTexture>("composite")
        ?: throw IllegalStateException("Launch the FXAA composite parent (3) first")
renderer.openXRHmdTarget?.copySideBySideToEyes(presentedTexture)
presentedTexture.blitToScreen(renderer = renderer, target = renderer.renderList, name = "stereo-mirror")
println("Eye texture: ${presentedTexture.parameters.width / 2} × ${presentedTexture.parameters.height}; FXAA in box 3")
// Keep start last: Field tracks the returned Frames as this box's running job.
val activeRenderer = renderer
start(activeRenderer::run)