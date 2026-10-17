package afield.examples.four

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.profiling.live-switch
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.LiveProfilingSwitchSmoke
tags: [smoke, four, gpu, profiling]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "Live profiling switch PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/

/** [Options.profiling] switched on and off on a running renderer: the GPU queries follow it, no restart. */
object LiveProfilingSwitchSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        Options.profiling = false
        val window = Window("Live profiling switch")
        val renderer = Renderer(window)
        fun frames(n: Int) = repeat(n) { window.pollEvents(); renderer.render() }
        try {
            frames(4)
            check(renderer.getProfiler() == null) { "profiling is off, yet there is a profiler" }
            Options.profiling = true
            frames(8)
            check(renderer.getProfiler() != null) { "switching profiling on did not start GPU queries" }
            Options.profiling = false
            frames(8)
            check(renderer.getProfiler() == null) { "switching profiling off did not stop GPU queries" }
            Options.profiling = true
            frames(8)
            check(renderer.getProfiler() != null) { "switching profiling back on did not restart GPU queries" }
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        println("Live profiling switch PASSED: on, off and on again without a restart")
        exitProcess(0)
    }
}
