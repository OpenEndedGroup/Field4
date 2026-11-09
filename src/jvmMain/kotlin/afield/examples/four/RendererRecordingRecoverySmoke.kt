package afield.examples.four

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.renderer.recording-recovery
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.RendererRecordingRecoverySmoke
tags: [smoke, four, gpu, lifecycle]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "renderer recording recovery PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/
/** Real failed command recording followed by recovery and desktop/offscreen presentation changes. */
object RendererRecordingRecoverySmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        Options.validate = true
        val window = Window("Renderer recording recovery")
        val renderer = Renderer(window)
        fun frames(n: Int) = repeat(n) {
            window.pollEvents()
            renderer.render()
        }
        try {
            frames(4)
            for ((desktop, computeFailure) in listOf(true to false, false to false, true to true, false to true)) {
                renderer.desktopPresentationEnabled = desktop
                val submitted = renderer.submittedSceneFrames
                val targets = if (computeFailure) renderer.computeList.others else renderer.renderList.preTargets
                targets["intentional-recording-failure"] = Target { _, _ ->
                    error("intentional recording failure")
                }
                var failure: Throwable? = null
                repeat(30) {
                    if (failure == null) {
                        window.pollEvents()
                        failure = runCatching { renderer.render() }.exceptionOrNull()
                    }
                }
                check(failure != null) { "Recording did not fail after 30 acquisition attempts" }
                check(renderer.lastRenderFailure?.contains("intentional recording failure") == true)
                check(renderer.submittedSceneFrames == submitted)
                targets.remove("intentional-recording-failure")
                // Toggle while an acquired desktop image may still be pending.
                renderer.desktopPresentationEnabled = !desktop
                frames(12)
                check(renderer.submittedSceneFrames > submitted)
                check(renderer.lastRenderFailure == null)
            }
            repeat(8) {
                renderer.desktopPresentationEnabled = it % 2 == 0
                frames(4)
            }
            println("renderer recording recovery PASSED: ${renderer.submittedSceneFrames} scene submissions")
        } finally {
            renderer.cleanup()
        }
        exitProcess(0)
    }
}
