package afield.examples.four

import afield.four.Renderer
import afield.four.targets.SideBySideStereoTarget
import afield.four.util.Window
import afield.stdlib.linalg.Vector3f
import kotlin.math.abs

/*
FIELD_TEST
id: examples.four.imgui-vr-panel-smoke
runner: jvm-main
entrypoint: afield.examples.four.ImGuiVrPanelSmokeTest
timeoutSec: 60
expect:
  stdout:
    - "ImGuiVrPanelSmokeTest ok"
*/

/**
 * End-to-end smoke for the ImGui-as-a-VR-panel pipeline: builds the ImGui -> RTT -> stereo-quad ->
 * side-by-side pipeline exactly as [ImGuiVrPanelDemo], renders a warm-up of frames on a real
 * Vulkan window with the mock controller aimed at the panel centre, and asserts the controller
 * ray -> quad hit-test round-trips to panel-centre UV.
 *
 * This is the desktop (non-headset) path: the packed side-by-side image is what OpenXR copies into
 * the eye swapchains on Windows/Linux (SideBySideStereoTarget.openXR == null here, since OpenXR
 * natives are excluded on macOS). The assertion is on the raycast math + the fact that the whole
 * per-eye render graph builds and runs without error; it does not depend on window compositing.
 */
class ImGuiVrPanelSmokeTest {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val window = Window("ImGui VR Panel Smoke Test")
            val renderer = Renderer(window)
            val sbs = SideBySideStereoTarget(renderer)
            val demo = ImGuiVrPanelDemo(window, renderer, sbs)

            try {
                demo.build()

                val frames = 60
                repeat(frames) {
                    window.pollEvents()
                    demo.aimControllerAtPanelCentre()
                    renderer.render()
                    if (window.shouldClose()) return@repeat
                }

                require(demo.hitActive) {
                    "Controller ray never hit the panel after $frames frames"
                }
                require(abs(demo.hitU - 0.5f) < 0.02f && abs(demo.hitV - 0.5f) < 0.02f) {
                    "Centre-aimed ray expected UV (0.5, 0.5), got (${demo.hitU}, ${demo.hitV})"
                }
                val hit = requireNotNull(demo.lastHitWorld) { "No world hit recorded" }
                val expected = Vector3f(0f, 0f, 1.5f)
                require(hit.distance(expected) < 0.05f) {
                    "Expected world hit near $expected, got $hit"
                }

                println(
                    "ImGuiVrPanelSmokeTest ok: hitUV=(%.3f, %.3f) hitWorld=(%.3f, %.3f, %.3f)"
                        .format(demo.hitU, demo.hitV, hit.x, hit.y, hit.z)
                )
            } finally {
                demo.cleanup()
                renderer.cleanup()
                window.cleanup()
            }
        }
    }
}
