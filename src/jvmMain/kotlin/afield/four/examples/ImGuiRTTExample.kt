package afield.four.examples

import afield.four.Renderer
import afield.four.imgui.ImGuiRenderToTexture
import afield.four.modules.fromContext
import afield.four.targets.RenderTarget
import afield.four.util.Window

/**
 * Backwards-compatible shim for the old example helper.
 */
object ImGuiRTTExample {
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
