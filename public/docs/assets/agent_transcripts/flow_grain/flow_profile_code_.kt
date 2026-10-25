// Dependencies are explicit graph exports; resources stay in their parent box.
val resetFlow = requireNotNull(fromContextWithNameOrNull<afield.runtime.lifecycle.SimulationReset>("resetFlow")) {
    "Missing resetFlow; run Resources first"
}
val travel = requireNotNull(fromContextWithNameOrNull<java.util.concurrent.atomic.AtomicBoolean>("travel")) {
    "Missing travel; run Resources first"
}
val settings =
    ["force", "grainCell", "strokeTime", "flowWidth"].map { parameterName ->
        requireNotNull(fromContextWithNameOrNull<afield.ui.controls.EffectiveParameter>(parameterName)) {
                "Run Resources first: missing parameter $parameterName"
            }
            .snapshot()
    }

// Compare a deterministic 180-frame path with identical settings after a reset.
// Stop Box also ends this capture and restores the previous travel setting.
frames {
    val wasTravelling = travel.getAndSet(true)
    var recording = false
    try {
        val ticket = resetFlow.request()
        val resetStarted = System.nanoTime()
        val resetTimeoutNanos = 30_000_000_000L
        while (resetFlow.completed < ticket) {
            // Ignore a prior failure while this new request is still pending.
            if (!resetFlow.pending) check(resetFlow.failure == null) { "Reset failed: ${resetFlow.failure}" }
            check(System.nanoTime() - resetStarted < resetTimeoutNanos) {
                "Timed out waiting for reset $ticket: ${resetFlow.failure ?: "no frame-boundary consumer"}"
            }
            if (!frame()) return@frames
        }
        println("Profile settings: $settings; reset ticket=${resetFlow.completed}")
        val start = renderer.frameCounter
        while (frame() && renderer.frameCounter - start < 60) {} // Warm shaders and caches.
        CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest("start", 30.0, stacks = true))
        recording = true
        val recordingStart = renderer.frameCounter
        while (frame() && renderer.frameCounter - recordingStart < 180) {}
        val result = CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest("stop"))
        recording = false
        thisBox.inBrowser("window.showProfilingTimeline(); document.getElementById('profile-tab-frames').click()")
        println(
            "Flow profile: ${result.frameRecords.size} intervals; ${renderer.frameCounter-recordingStart} rendered frames",
        )
    } finally {
        if (recording) CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest("stop"))
        travel.set(wasTravelling)
    }
}
