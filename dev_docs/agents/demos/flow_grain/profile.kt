// Capture the parent's resources so cleanup still owns this run's state.
val reset = resetFlow
val travelFlag = travel
val settings = [force, grainCell, strokeTime, flowWidth].map { it.snapshot() }

// Compare a deterministic 180-frame path with identical settings after a reset.
// Stop Box also ends this capture and restores the previous travelFlag setting.
frames {
    val wasTravelling = travelFlag.getAndSet(true)
    var recording = false
    try {
        val ticket = reset.request()
        val resetStarted = System.nanoTime()
        val resetTimeoutNanos = 30_000_000_000L
        while (reset.completed < ticket) {
            // Ignore a prior failure while this new request is still pending.
            if (!reset.pending) check(reset.failure == null) { "Reset failed: ${reset.failure}" }
            check(System.nanoTime() - resetStarted < resetTimeoutNanos) {
                "Timed out waiting for reset $ticket: ${reset.failure ?: "no frame-boundary consumer"}"
            }
            if (!frame()) return@frames
        }
        println("Profile settings: $settings; reset ticket=${reset.completed}")
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
        travelFlag.set(wasTravelling)
    }
}
