package afield.four.examples

import afield.Rect
import afield.four.Renderer
import afield.four.imgui.ImGuiRenderToTexture
import afield.four.modules.fromContext
import afield.four.targets.RenderTarget
import afield.four.util.Window
import afield.stdlib.f
import afield.stdlib.times
import afield.stdlib.vec
import imgui.ImGui.text

class ImGuiRTTExample(val window: Window = fromContext(), val renderer: Renderer = fromContext()) {

    fun go(overSample: Int = 2, fontSuperSample: Float = 4f) {
        val rt = RenderTarget(renderer)
        renderer.renderList = rt

        val imgui = ImGuiRenderToTexture(
            renderer = renderer,
            window = window,
            parameters = {
                width = window.width * overSample
                height = window.height * overSample
                mipLevels = 4
                generateColorMipmaps = true
                clearFirst = true
            },
            fontSupersample = fontSuperSample,
            displayScale = 1f//overSample.f
        )

        imgui.addUi("test") {
            window("ImGui → RenderToTexture") {
                text("Hello from imgui-java")
                text("Renderer: Vulkan → RTT → screen blit")
            }
        }

        imgui.loadFontFromResource("Iosevka-Light.ttf", sizePixels = 12*2*overSample.f)

        rt.preTargets["imgui-rtt"] = imgui

        imgui.blitToScreen(
            renderer = renderer,
            target = rt,
            name = "imgui-rtt-blit",
            sourceRect = Rect(0.0, 1.0, 1.0, -1.0)
        ) {
            offset = 0.01*vec(1,1,1,1)
            offset.w = 1.0f
        }
    }
}
