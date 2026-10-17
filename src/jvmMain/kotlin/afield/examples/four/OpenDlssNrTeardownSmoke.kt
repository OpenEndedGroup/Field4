package afield.examples.four

import afield.graphics.render.neural.OpenDlssNrStudy
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Window
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.open-dlss-nr.teardown
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.OpenDlssNrTeardownSmoke
tags: [smoke, four, gpu, neural]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "OpenDlssNr teardown PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/

/**
 * The renderer goes away while an [OpenDlssNrStudy] is still open on it, which is what quitting or
 * disposing the graphics context does to a box that was never stopped. The study is closed by the
 * renderer, before its device, and the box's own later close is harmless. Without the native library
 * the study runs in source mode, so this needs a GPU but not NVIDIA.
 */
object OpenDlssNrTeardownSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        val window = Window("OpenDlssNr teardown")
        val renderer = Renderer(window)
        val study = OpenDlssNrStudy(renderer, size = 128)
        try {
            repeat(6) { window.pollEvents(); renderer.render() }
        } finally {
            renderer.cleanup()
        }
        check(study.isClosed) { "the renderer's teardown did not close the study" }
        study.close()
        window.cleanup()
        println("OpenDlssNr teardown PASSED: closed by its renderer, before the device (native NR ${if (study.pass != null) "present" else "absent"})")
        exitProcess(0)
    }
}
