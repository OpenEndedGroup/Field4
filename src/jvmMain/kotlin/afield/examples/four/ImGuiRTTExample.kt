package afield.examples.four

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.imgui.ImGuiRenderToTexture
import afield.document.context.fromContext
import afield.graphics.vulkan.targets.RenderTarget
import afield.graphics.vulkan.util.Window

/**
 * Backwards-compatible shim for the old example helper.
 */
object ImGuiRTTExample {
    /**
     * Returns an [ImGuiRenderToTexture] configured through the old example helper API.
     *
     * Delegates to [ImGuiRenderToTexture.get], forwarding the renderer, window, sampling,
     * and target settings unchanged.
     */
    @JvmStatic
    fun get(
        renderer: Renderer = fromContext(),
        window: Window = renderer.window,
        name: String = "imgui-rtt",
        overSample: Int = 2,
        fontSuperSample: Float = 4f,
        target: RenderTarget = renderer.renderList
    ): ImGuiRenderToTexture = ImGuiRenderToTexture.get(renderer, window, name, overSample, fontSuperSample, target)

    operator fun invoke(
        renderer: Renderer = fromContext(),
        window: Window = renderer.window,
        name: String = "imgui-rtt",
        overSample: Int = 2,
        fontSuperSample: Float = 4f,
        target: RenderTarget = renderer.renderList
    ): ImGuiRenderToTexture = get(renderer, window, name, overSample, fontSuperSample, target)
}
