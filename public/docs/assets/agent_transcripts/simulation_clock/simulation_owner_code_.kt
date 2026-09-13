import kotlinx.coroutines.*

// Use the ordinary box launch/stop controls. No simulation service is left running.
val runId = System.nanoTime()
var phase = 0.0
var pulse = 0.0
var checksum = 0.0
var childrenStopped = 0
val reuse = true
var points = Array(2048) { vec(0f,0f,0f) }
frames {
    val budget = frameProfile("Playground", targetFps = 60.0, allocationKiB = 128.0)
    try {
        withSimulationClock {
            launch(CoroutineName("Orbit")) {
                try { while (isActive) { phase += 0.045; sleep(stepSeconds) } }
                finally { childrenStopped++ }
            }
            launch(CoroutineName("Pulse")) {
                try { while (isActive) { pulse = 1.0; delay(120); pulse = 0.0; delay(350) } }
                finally { childrenStopped++ }
            }
            runCurrent()
            var last = AnimationClock.nowSeconds()
            while (frame()) {
                budget.beginFrame()
                val now = AnimationClock.nowSeconds()
                budget.measure("Simulation") { advance((now-last).coerceAtLeast(0.0)) }
                last = now
                budget.measure("Vectors") {
                    if (reuse) for (i in points.indices) points[i].set(i.toFloat(),phase.toFloat(),0f)
                    else points = Array(points.size) { vec(it.toFloat(),phase.toFloat(),0f) }
                    repeat(12000) { checksum += kotlin.math.sin(it.toDouble()) }
                }
                val x = 200 + 110*kotlin.math.cos(phase)
                val y = 125 + 70*kotlin.math.sin(phase)
                display("main", """<svg id="simulation-clock-view" data-run="$runId" data-time="$time" width="420" height="270" viewBox="0 0 420 270" style="background:black"><ellipse cx="200" cy="125" rx="110" ry="70" fill="none" stroke="#444"/><circle cx="$x" cy="$y" r="12" fill="#48c9ff"/><circle cx="200" cy="125" r="${8+18*pulse}" fill="#ddd"/><text x="20" y="250" fill="#aaa" font-family="Iosevka,monospace">simulation ${"%.2f".format(time)} s · ${pendingTimers} timers</text></svg>""")
            }
        }
    } finally {
        budget.close()
        display("status", """<div id="simulation-clock-status" data-run="$runId">$childrenStopped children cleaned up</div>""")
        thisBox.setPropertyRaw("simulationCleanup", childrenStopped)
        println("SIMULATION_STOPPED: $childrenStopped children cleaned up")
    }
}
