package afield.examples.four

import afield.graphics.render.neural.OpenDlssNrStudy
import afield.graphics.render.neural.OpenDlssNrPass
import afield.graphics.render.neural.OpenDlssNrInputs
import afield.graphics.render.neural.OpenDlssNrColorEncoding
import afield.graphics.render.neural.OpenDlssNrFrame
import afield.document.graph.Box
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.util.Window
import afield.runtime.lifecycle.RunScope
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
        val box = Box("nr-lifecycle-check")
        val firstRun = RunScope(box, "study", "test")
        val previous = RunScope.withCurrent(firstRun) { OpenDlssNrStudy(renderer, size = 128) }
        if ("--require-native" in args) check(previous.pass != null) { previous.availability.reason }
        repeat(6) { window.pollEvents(); renderer.render() }
        val secondRun = RunScope(box, "study", "test")
        val study = RunScope.withCurrent(secondRun) { OpenDlssNrStudy(renderer, size = 128) }
        firstRun.supersede()
        check(previous.isClosed) { "superseding the box run did not close its study" }
        check(previous.pass?.isClosed != false) { "superseding the box run left its native pass open" }
        check(renderer.renderList.preTargets[study.name]===study && renderer.renderList.modules.containsKey(study.name)) {
            "the older run's cleanup removed the replacement study"
        }
        val standalone = if (study.pass != null) OpenDlssNrPass(renderer, 128, 128) else null
        var frame = 0L
        standalone?.let { pass ->
            renderer.renderList.preTargets["standalone-pass"] = Target { command, _ ->
                pass.evaluate(command, OpenDlssNrInputs(study.source, OpenDlssNrColorEncoding.LINEAR_LDR),
                    OpenDlssNrFrame(frame++))
            }
        }
        try {
            repeat(6) { window.pollEvents(); renderer.render() }
        } finally {
            renderer.cleanup()
        }
        check(study.isClosed) { "the renderer's teardown did not close the study" }
        check(study.pass?.isClosed != false && standalone?.isClosed != false) {
            "the renderer's teardown left a native pass open"
        }
        secondRun.close()
        study.close()
        window.cleanup()
        println("OpenDlssNr teardown PASSED: closed by its renderer, before the device (native NR ${if (study.pass != null) "present" else "absent"})")
        exitProcess(0)
    }
}
